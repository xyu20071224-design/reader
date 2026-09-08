/**
 * MiMo 音色控制台 — Host half.
 *
 * One prefix route on the web server (`/voice-console/api`) that proxies the
 * MiMo TTS HTTP API, records local audio for clone samples, and owns the
 * on-disk store for design/clone voices:
 *
 *   ~/.dsh/voice-console/voices.json        design + clone metadata
 *   ~/.dsh/voice-console/clones/<id>.<ext>  clone reference samples
 *   ~/.dsh/voice-console/recordings/<id>.wav  finished internal recordings
 *
 * The MiMo API key never reaches this half on disk: the browser keeps it in
 * localStorage and sends it per request, so the key is only in transit over
 * loopback. Every route requires the `x-voice-console: 1` request header,
 * which is not a CORS-safelisted header — a cross-origin page therefore
 * cannot reach these routes without a preflight this server never grants.
 *
 * @module @local/dsh-voice-console
 */

import { execFile, spawn } from 'node:child_process'
import { closeSync, existsSync, openSync } from 'node:fs'
import { mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { homedir } from 'node:os'
import { randomUUID } from 'node:crypto'

/** Loader row name. */
export const name = 'voice-console'

/** The route needs the browser HTTP carrier. */
export const inject = ['webServer']

const ROUTE_PREFIX = '/voice-console/api'
const GUARD_HEADER = 'x-voice-console'
const MAX_BODY_BYTES = 14 * 1024 * 1024 // clone samples are ≤10 MB before base64
const MIMO_BASE_URL = 'https://api.xiaomimimo.com/v1'
const MODELS = {
  preset: 'mimo-v2.5-tts',
  design: 'mimo-v2.5-tts-voicedesign',
  clone: 'mimo-v2.5-tts-voiceclone',
}
const DEFAULT_PRESET = { zh: 'mimo_default', en: 'Mia' }
const SAMPLE_TEXTS = {
  zh: '今天天气很好，我们一起读一本英文小说吧。',
  en: 'The lantern flickered once, and the library fell silent.',
}

/** 内录：采集参数与后处理链（与 scripts/record_voice_sample.sh 保持一致）。 */
const RECORD_RATE = 48000
const RECORD_CHANNELS = 2
const RECORD_MIN_SECONDS = 3
const RECORD_MAX_SECONDS = 300
const RECORD_SAMPLE_BYTES = 24000 * 2 // 24 kHz 单声道 s16：每秒字节数
const RECORD_FILTER = [
  'highpass=f=80',
  'lowpass=f=12000',
  'silenceremove=start_periods=1:start_silence=0.3:start_threshold=-45dB',
  'areverse',
  'silenceremove=start_periods=1:start_silence=0.3:start_threshold=-45dB',
  'areverse',
  'loudnorm=I=-16:TP=-1.5:LRA=11',
].join(',')

/**
 * MiMo-V2.5-TTS 预置音色（镜像 App 的 MiMoVoiceCatalog）。
 * `id` 就是请求体 `audio.voice` 直传值：中文音色必须发中文名。
 */
const PRESET_VOICES = [
  { id: 'mimo_default', name: '默认（冰糖）', language: 'zh', gender: 'female', style: ['中性', '默认'], ageGroup: '' },
  { id: '冰糖', name: '冰糖', language: 'zh', gender: 'female', style: ['活泼', '甜美'], ageGroup: 'young' },
  { id: '茉莉', name: '茉莉', language: 'zh', gender: 'female', style: ['知性', '温柔'], ageGroup: 'adult' },
  { id: '苏打', name: '苏打', language: 'zh', gender: 'male', style: ['阳光', '开朗'], ageGroup: 'young' },
  { id: '白桦', name: '白桦', language: 'zh', gender: 'male', style: ['沉稳', '成熟'], ageGroup: 'adult' },
  { id: 'Mia', name: 'Mia', language: 'en', gender: 'female', style: ['lively', 'bright'], ageGroup: 'young' },
  { id: 'Chloe', name: 'Chloe', language: 'en', gender: 'female', style: ['sweet', 'dreamy'], ageGroup: 'young' },
  { id: 'Milo', name: 'Milo', language: 'en', gender: 'male', style: ['sunny', 'upbeat'], ageGroup: 'young' },
  { id: 'Dean', name: 'Dean', language: 'en', gender: 'male', style: ['warm', 'deep'], ageGroup: 'adult' },
]

/** Harness home, the same anchor the rest of DSH uses. */
function dshHome() {
  const fromEnv = process.env.DSH_HOME
  return fromEnv && fromEnv.trim() !== '' ? fromEnv : join(homedir(), '.dsh')
}

/** MIME type for a clone sample filename. */
function mimeFor(filename) {
  const lower = String(filename ?? '').toLowerCase()
  if (lower.endsWith('.wav')) return 'audio/wav'
  if (lower.endsWith('.mp3')) return 'audio/mpeg'
  if (lower.endsWith('.m4a')) return 'audio/mp4'
  if (lower.endsWith('.ogg')) return 'audio/ogg'
  if (lower.endsWith('.flac')) return 'audio/flac'
  return 'application/octet-stream'
}

function extensionFor(filename) {
  const lower = String(filename ?? '').toLowerCase()
  const match = /\.(wav|mp3|m4a|ogg|flac)$/.exec(lower)
  return match ? match[1] : 'wav'
}

/** Run a command and resolve stdout; rejects with a readable message. */
function runCommand(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    execFile(command, args, { timeout: 20000, ...options }, (error, stdout, stderr) => {
      if (error !== null) {
        const detail = String(stderr || error.message || '').trim().slice(0, 200)
        reject(new Error(`${command} 失败：${detail}`))
        return
      }
      resolve(String(stdout))
    })
  })
}

/** Whether a CLI exists on PATH (ENOENT is the only "absent" answer). */
function commandExists(command) {
  return new Promise((resolve) => {
    execFile(command, ['--version'], { timeout: 5000 }, (error) => {
      if (error === null) return resolve(true)
      resolve(error.code !== 'ENOENT')
    })
  })
}

/** JSON response helper. */
function sendJson(res, status, payload) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8')
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': String(body.byteLength),
    'cache-control': 'no-store',
  })
  res.end(body)
}

/** Read a JSON request body under the size cap; throws a user-facing Error. */
function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    req.on('data', (chunk) => {
      size += chunk.length
      if (size > MAX_BODY_BYTES) {
        reject(new Error(`请求体过大（>${Math.round(MAX_BODY_BYTES / 1024 / 1024)} MB）`))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => {
      const text = Buffer.concat(chunks).toString('utf8')
      if (text.trim() === '') {
        resolve({})
        return
      }
      try {
        resolve(JSON.parse(text))
      } catch (error) {
        reject(new Error(`请求体不是合法 JSON：${String(error && error.message ? error.message : error)}`))
      }
    })
    req.on('error', (error) => reject(error))
  })
}

/** On-disk design/clone voice store. */
class VoiceStore {
  constructor() {
    this.root = join(dshHome(), 'voice-console')
    this.clonesDir = join(this.root, 'clones')
    this.recordingsDir = join(this.root, 'recordings')
    this.indexFile = join(this.root, 'voices.json')
    this.cache = undefined
  }

  async load() {
    if (this.cache !== undefined) return this.cache
    let parsed = { voices: [] }
    if (existsSync(this.indexFile)) {
      try {
        parsed = JSON.parse(await readFile(this.indexFile, 'utf8'))
      } catch {
        parsed = { voices: [] }
      }
    }
    const voices = Array.isArray(parsed.voices) ? parsed.voices : []
    this.cache = voices.filter((voice) => voice && typeof voice.id === 'string')
    return this.cache
  }

  async persist() {
    await mkdir(this.root, { recursive: true })
    await writeFile(this.indexFile, JSON.stringify({ voices: this.cache ?? [] }, null, 2), 'utf8')
  }

  async list() {
    return await this.load()
  }

  async find(id) {
    const voices = await this.load()
    return voices.find((voice) => voice.id === id)
  }

  /** Create a design voice: the description is the whole voice. */
  async addDesign(input) {
    const designName = String(input.name ?? '').trim()
    const description = String(input.description ?? '').trim()
    if (designName === '') throw new Error('音色名称不能为空')
    if (description === '') throw new Error('音色描述不能为空')
    const voice = {
      id: `design-${randomUUID()}`,
      kind: 'design',
      name: designName,
      language: input.language === 'en' ? 'en' : 'zh',
      gender: input.gender === 'male' ? 'male' : 'female',
      description,
      createdAt: new Date().toISOString(),
    }
    this.cache = [...(await this.load()), voice]
    await this.persist()
    return voice
  }

  /** Create a clone voice from an uploaded sample. */
  async addClone(input) {
    const cloneName = String(input.name ?? '').trim()
    if (cloneName === '') throw new Error('音色名称不能为空')
    const dataBase64 = String(input.dataBase64 ?? '')
    if (dataBase64 === '') throw new Error('缺少克隆样本数据')
    const bytes = Buffer.from(dataBase64, 'base64')
    if (bytes.byteLength === 0) throw new Error('克隆样本为空')
    if (bytes.byteLength > 10 * 1024 * 1024) throw new Error('克隆样本超过 MiMo 的 10 MB 上限')
    const extension = extensionFor(input.filename)
    const id = `clone-${randomUUID()}`
    await mkdir(this.clonesDir, { recursive: true })
    await writeFile(join(this.clonesDir, `${id}.${extension}`), bytes)
    const voice = {
      id,
      kind: 'clone',
      name: cloneName,
      language: input.language === 'en' ? 'en' : 'zh',
      gender: input.gender === 'male' ? 'male' : 'female',
      sampleFile: `${id}.${extension}`,
      sampleBytes: bytes.byteLength,
      createdAt: new Date().toISOString(),
    }
    this.cache = [...(await this.load()), voice]
    await this.persist()
    return voice
  }

  /** Remove one voice and its sample file. */
  async remove(id) {
    const voices = await this.load()
    const voice = voices.find((entry) => entry.id === id)
    if (voice === undefined) throw new Error(`音色不存在：${id}`)
    this.cache = voices.filter((entry) => entry.id !== id)
    await this.persist()
    if (voice.kind === 'clone' && typeof voice.sampleFile === 'string') {
      const file = join(this.clonesDir, voice.sampleFile)
      if (existsSync(file)) await rm(file, { force: true })
    }
    return voice
  }

  /** `data:<mime>;base64,…` for a clone voice, as MiMo's `audio.voice` requires. */
  async sampleDataUri(id) {
    const voice = await this.find(id)
    if (voice === undefined || voice.kind !== 'clone') throw new Error(`克隆音色不存在：${id}`)
    const file = join(this.clonesDir, voice.sampleFile)
    if (!existsSync(file)) throw new Error(`克隆样本文件缺失：${voice.sampleFile}`)
    const bytes = await readFile(file)
    return `data:${mimeFor(voice.sampleFile)};base64,${bytes.toString('base64')}`
  }

  /** Absolute path of a finished internal recording. */
  recordingPath(sampleId) {
    if (!/^[A-Za-z0-9-]+$/.test(String(sampleId ?? ''))) throw new Error('录音样本 id 非法')
    return join(this.recordingsDir, `${sampleId}.wav`)
  }

  /** Create a clone voice from a finished internal recording (no upload). */
  async addCloneFromSample(input) {
    const cloneName = String(input.name ?? '').trim()
    if (cloneName === '') throw new Error('音色名称不能为空')
    const source = this.recordingPath(input.sampleId)
    if (!existsSync(source)) throw new Error(`录音样本不存在：${input.sampleId}`)
    const bytes = await readFile(source)
    if (bytes.byteLength === 0) throw new Error('录音样本为空')
    if (bytes.byteLength > 10 * 1024 * 1024) throw new Error('录音样本超过 MiMo 的 10 MB 上限')
    const id = `clone-${randomUUID()}`
    await mkdir(this.clonesDir, { recursive: true })
    await writeFile(join(this.clonesDir, `${id}.wav`), bytes)
    const voice = {
      id,
      kind: 'clone',
      name: cloneName,
      language: input.language === 'en' ? 'en' : 'zh',
      gender: input.gender === 'male' ? 'male' : 'female',
      sampleFile: `${id}.wav`,
      sampleBytes: bytes.byteLength,
      sampleSource: `录音 ${input.sampleId}`,
      createdAt: new Date().toISOString(),
    }
    this.cache = [...(await this.load()), voice]
    await this.persist()
    return voice
  }
}

/**
 * Internal recording (内录) over PipeWire/PulseAudio.
 *
 * `parec` captures the resolved source to raw PCM while a browser-driven
 * session is active; `ffmpeg` then trims silence, denoises, normalises and
 * downsamples to the 24 kHz mono WAV a MiMo clone sample wants. Only one
 * recording runs at a time, and the host owns the auto-stop timer so a
 * closed panel cannot leave `parec` running.
 */
class Recorder {
  constructor(store) {
    this.store = store
    this.active = null
    this.tools = undefined
    // The last finished sample, so a stop() that loses the race against the
    // auto-stop timer (slow tab, delayed request) still returns the sample
    // instead of an error the browser cannot recover from.
    this.lastSample = null
  }

  /** Cached CLI availability, checked once per plugin lifetime. */
  async describe() {
    if (this.tools === undefined) {
      const [parec, ffmpeg, pactl] = await Promise.all([
        commandExists('parec'),
        commandExists('ffmpeg'),
        commandExists('pactl'),
      ])
      this.tools = { parec, ffmpeg, pactl }
    }
    const missing = Object.entries(this.tools)
      .filter(([, present]) => !present)
      .map(([name]) => name)
    if (missing.length > 0) {
      return { available: false, reason: `缺少命令：${missing.join(' / ')}`, sources: [], defaults: {} }
    }
    let defaults = {}
    let sources = []
    try {
      const [list, defaultSink, defaultSource] = await Promise.all([
        runCommand('pactl', ['list', 'short', 'sources']),
        runCommand('pactl', ['get-default-sink']),
        runCommand('pactl', ['get-default-source']),
      ])
      defaults = {
        system: `${defaultSink.trim()}.monitor`,
        mic: defaultSource.trim(),
      }
      sources = list
        .split('\n')
        .map((line) => line.trim())
        .filter((line) => line !== '')
        .map((line) => {
          const fields = line.split(/\s+/)
          const name = fields[1] ?? ''
          const isMonitor = name.endsWith('.monitor')
          return {
            name,
            kind: isMonitor ? 'monitor' : 'input',
            label: isMonitor
              ? `内录 · ${name.replace(/\.monitor$/, '')}`
              : `输入 · ${name}`,
          }
        })
        .filter((entry) => entry.name !== '')
    } catch (error) {
      return {
        available: false,
        reason: String(error && error.message ? error.message : error),
        sources: [],
        defaults: {},
      }
    }
    return { available: true, reason: '', sources, defaults }
  }

  /** Resolve a requested source into a real pactl source name. */
  async resolveSource(requested) {
    const description = await this.describe()
    if (!description.available) throw new Error(description.reason)
    const wanted = String(requested ?? 'system')
    if (wanted === 'system') return description.defaults.system
    if (wanted === 'mic') return description.defaults.mic
    if (!description.sources.some((entry) => entry.name === wanted)) {
      throw new Error(`找不到音频源：${wanted}`)
    }
    return wanted
  }

  /** Begin a recording; resolves once `parec` is spawned. */
  async start({ source, seconds }) {
    if (this.active !== null) throw new Error('已有录音在进行，请先停止')
    const description = await this.describe()
    if (!description.available) throw new Error(description.reason)
    const sourceName = await this.resolveSource(source)
    this.lastSample = null
    const wanted = Number(seconds)
    const duration = Number.isFinite(wanted)
      ? Math.min(RECORD_MAX_SECONDS, Math.max(RECORD_MIN_SECONDS, Math.round(wanted)))
      : 30
    await mkdir(this.store.recordingsDir, { recursive: true })
    const id = `rec-${randomUUID()}`
    const rawPath = join(this.store.recordingsDir, `${id}.raw`)
    const descriptor = openSync(rawPath, 'w')
    const proc = spawn(
      'parec',
      [
        `--device=${sourceName}`,
        '--latency-msec=50',
        `--rate=${RECORD_RATE}`,
        `--channels=${RECORD_CHANNELS}`,
        '--format=s16le',
        '--raw',
      ],
      { stdio: ['ignore', descriptor, 'ignore'] },
    )
    const session = { id, sourceName, seconds: duration, rawPath, proc, descriptor, timer: undefined, startedAt: Date.now() }
    this.active = session
    proc.on('error', () => {
      /* surfaced by the next stop()/status() call */
    })
    session.timer = setTimeout(() => {
      this.stop().catch(() => {})
    }, duration * 1000)
    if (typeof session.timer.unref === 'function') session.timer.unref()
    return { id, source: sourceName, seconds: duration, startedAt: session.startedAt }
  }

  /** Stop the active recording, post-process it, and return the sample. */
  async stop() {
    const session = this.active
    if (session === null) {
      // Idempotent: hand back the sample the auto-stop timer just produced.
      if (this.lastSample !== null) return { ...this.lastSample, alreadyStopped: true }
      throw new Error('当前没有录音在进行')
    }
    this.active = null
    if (session.timer !== undefined) clearTimeout(session.timer)

    await new Promise((resolve) => {
      let settled = false
      const done = () => {
        if (settled) return
        settled = true
        resolve()
      }
      session.proc.once('close', done)
      session.proc.kill('SIGINT')
      setTimeout(done, 3000)
    })
    try {
      closeSync(session.descriptor)
    } catch {
      /* already closed by the child */
    }

    const rawInfo = await stat(session.rawPath).catch(() => undefined)
    if (rawInfo === undefined || rawInfo.size < RECORD_RATE * RECORD_CHANNELS * 2 * 0.3) {
      await rm(session.rawPath, { force: true })
      throw new Error('没录到有效数据（设备被占用或音频源不对）')
    }

    const outPath = this.store.recordingPath(session.id)
    try {
      await runCommand('ffmpeg', [
        '-hide_banner',
        '-loglevel',
        'error',
        '-y',
        '-f',
        's16le',
        '-ar',
        String(RECORD_RATE),
        '-ac',
        String(RECORD_CHANNELS),
        '-i',
        session.rawPath,
        '-af',
        RECORD_FILTER,
        '-ac',
        '1',
        '-ar',
        '24000',
        '-c:a',
        'pcm_s16le',
        outPath,
      ], { timeout: 120000 })
    } finally {
      await rm(session.rawPath, { force: true })
    }

    const info = await stat(outPath).catch(() => undefined)
    if (info === undefined || info.size <= 44) {
      await rm(outPath, { force: true })
      throw new Error('后处理结果为空（整段都在静音阈值以下，把音量调大再录一次）')
    }
    const duration = Math.max(0, (info.size - 44) / RECORD_SAMPLE_BYTES)
    const sample = {
      sampleId: session.id,
      source: session.sourceName,
      bytes: info.size,
      duration,
      requestedSeconds: session.seconds,
      recordedSeconds: Math.round((Date.now() - session.startedAt) / 100) / 10,
    }
    this.lastSample = sample
    return sample
  }

  /** Current session summary, or null. */
  status() {
    if (this.active === null) return { active: false }
    return {
      active: true,
      id: this.active.id,
      source: this.active.sourceName,
      seconds: this.active.seconds,
      elapsed: Math.round((Date.now() - this.active.startedAt) / 100) / 10,
    }
  }

  /** Kill any in-flight capture (plugin dispose). */
  dispose() {
    const session = this.active
    this.active = null
    if (session === null) return
    if (session.timer !== undefined) clearTimeout(session.timer)
    try {
      session.proc.kill('SIGINT')
    } catch {
      /* already gone */
    }
    try {
      closeSync(session.descriptor)
    } catch {
      /* already closed */
    }
    rm(session.rawPath, { force: true }).catch(() => {})
  }
}

/** Build the MiMo chat/completions request body (mirrors MiMoTtsBackend). */
function buildRequestBody({ text, model, style, voiceId, sampleDataUri }) {
  const messages = []
  if (typeof style === 'string' && style.trim() !== '') {
    messages.push({ role: 'user', content: style.trim() })
  }
  messages.push({ role: 'assistant', content: text })
  const audio = { format: 'wav' }
  if (typeof sampleDataUri === 'string' && sampleDataUri !== '') audio.voice = sampleDataUri
  else if (typeof voiceId === 'string' && voiceId !== '') audio.voice = voiceId
  return { model, messages, audio }
}

/** Call MiMo and return the decoded audio bytes. */
async function synthesizeToBase64({ apiKey, body }) {
  if (typeof apiKey !== 'string' || apiKey.trim() === '') throw new Error('未填写 MiMo API Key')
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 150_000)
  let response
  try {
    response = await fetch(`${MIMO_BASE_URL}/chat/completions`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'api-key': apiKey.trim(),
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    })
  } catch (error) {
    throw new Error(`无法连接 MiMo（${String(error && error.message ? error.message : error)}）`)
  } finally {
    clearTimeout(timer)
  }
  const raw = await response.text()
  if (!response.ok) {
    throw new Error(`MiMo 返回 HTTP ${response.status}：${raw.slice(0, 300)}`)
  }
  let parsed
  try {
    parsed = JSON.parse(raw)
  } catch {
    throw new Error(`MiMo 响应不是 JSON：${raw.slice(0, 200)}`)
  }
  const data = parsed?.choices?.[0]?.message?.audio?.data
  if (typeof data !== 'string' || data === '') {
    throw new Error(`MiMo 响应缺少音频数据：${raw.slice(0, 300)}`)
  }
  return data
}

/** Resolve one synthesize request into a MiMo call. */
async function handleSynthesize(store, input) {
  const text = String(input.text ?? '').trim()
  if (text === '') throw new Error('合成文本为空')
  const voice = input.voice ?? {}
  const style = typeof input.style === 'string' ? input.style : ''
  if (voice.kind === 'design') {
    const record = await store.find(voice.id)
    if (record === undefined || record.kind !== 'design') throw new Error(`设计音色不存在：${voice.id}`)
    return {
      model: MODELS.design,
      body: buildRequestBody({ text, model: MODELS.design, style: record.description, voiceId: '', sampleDataUri: '' }),
    }
  }
  if (voice.kind === 'clone') {
    const sampleDataUri = await store.sampleDataUri(voice.id)
    return {
      model: MODELS.clone,
      body: buildRequestBody({ text, model: MODELS.clone, style, voiceId: '', sampleDataUri }),
    }
  }
  const presetId = String(voice.id ?? '').trim() || DEFAULT_PRESET.zh
  return {
    model: MODELS.preset,
    body: buildRequestBody({ text, model: MODELS.preset, style, voiceId: presetId, sampleDataUri: '' }),
  }
}

/** Route table. */
async function handleRequest(ctx, store, recorder, req, res, url) {
  const route = url.pathname.slice(ROUTE_PREFIX.length) || '/'
  if (req.method === 'GET' && (route === '/' || route === '/state')) {
    sendJson(res, 200, {
      ok: true,
      baseUrl: MIMO_BASE_URL,
      models: MODELS,
      defaults: DEFAULT_PRESET,
      samples: SAMPLE_TEXTS,
      presets: PRESET_VOICES,
      voices: await store.list(),
      storeRoot: store.root,
      recording: await recorder.describe(),
      session: recorder.status(),
    })
    return
  }
  if (req.method === 'POST' && route === '/synthesize') {
    const input = await readJsonBody(req)
    const request = await handleSynthesize(store, input)
    const audioBase64 = await synthesizeToBase64({ apiKey: input.apiKey, body: request.body })
    sendJson(res, 200, { ok: true, model: request.model, mime: 'audio/wav', audioBase64 })
    return
  }
  if (req.method === 'POST' && route === '/record/start') {
    const input = await readJsonBody(req)
    const session = await recorder.start({ source: input.source, seconds: input.seconds })
    sendJson(res, 200, { ok: true, session })
    return
  }
  if (req.method === 'POST' && route === '/record/stop') {
    const sample = await recorder.stop()
    sendJson(res, 200, { ok: true, sample })
    return
  }
  if (req.method === 'GET' && route === '/record/status') {
    sendJson(res, 200, { ok: true, session: recorder.status() })
    return
  }
  if (route.startsWith('/samples/')) {
    const sampleId = route.slice('/samples/'.length)
    const file = store.recordingPath(sampleId)
    if (req.method === 'GET') {
      if (!existsSync(file)) throw new Error(`录音样本不存在：${sampleId}`)
      const bytes = await readFile(file)
      res.writeHead(200, {
        'content-type': 'audio/wav',
        'content-length': String(bytes.byteLength),
        'cache-control': 'no-store',
      })
      res.end(bytes)
      return
    }
    if (req.method === 'DELETE') {
      await rm(file, { force: true })
      sendJson(res, 200, { ok: true, sampleId })
      return
    }
  }
  if (req.method === 'POST' && route === '/voices') {
    const input = await readJsonBody(req)
    let voice
    if (input.kind === 'clone') {
      voice = String(input.sampleId ?? '') !== ''
        ? await store.addCloneFromSample(input)
        : await store.addClone(input)
    } else {
      voice = await store.addDesign(input)
    }
    sendJson(res, 200, { ok: true, voice })
    return
  }
  if (req.method === 'DELETE' && route === '/voices') {
    const id = url.searchParams.get('id') ?? ''
    const voice = await store.remove(id)
    sendJson(res, 200, { ok: true, voice })
    return
  }
  sendJson(res, 404, { ok: false, error: `未知接口：${req.method} ${url.pathname}` })
}

/** Plugin body. */
export function apply(ctx) {
  const store = new VoiceStore()
  const recorder = new Recorder(store)
  const handler = async (req, res) => {
    try {
      if (req.headers[GUARD_HEADER] === undefined) {
        sendJson(res, 403, { ok: false, error: '缺少控制台请求头（跨站请求已拒绝）' })
        return
      }
      const url = new URL(req.url ?? '/', 'http://127.0.0.1')
      await handleRequest(ctx, store, recorder, req, res, url)
    } catch (error) {
      if (!res.headersSent) {
        sendJson(res, 500, { ok: false, error: String(error && error.message ? error.message : error) })
      } else {
        res.destroy()
      }
    }
  }
  ctx.effect(() => {
    const disposeRoute = ctx.webServer.register({ kind: 'prefix', path: ROUTE_PREFIX, handler })
    return () => {
      disposeRoute()
      recorder.dispose()
    }
  }, 'voice-console: /voice-console/api')
}
