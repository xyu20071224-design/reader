/**
 * MiMo 音色控制台 — Client half.
 *
 * Two root-scope occupants sharing one module-level store:
 *   sidebar.footer.action  → the 音色控制台 button beside Settings
 *   shell.overlay          → the frame-wide console panel it opens
 *
 * The API key lives in localStorage and is sent per request; the host half
 * never persists it. Design/clone voices live in the host store.
 *
 * @module @local/dsh-voice-console/client
 */
window.__ModuleLoader__.load({
	id: "@local/dsh-voice-console",
	factory: (require) => {
		var module = { exports: {} }
		var exports = module.exports
		Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" })
		let react = require("react")

		const API = "/voice-console/api"
		const KEY_STORAGE = "dsh.voice-console.mimoApiKey"
		const PREF_STORAGE = "dsh.voice-console.prefs"
		const DEFAULT_SAMPLES = {
			zh: "今天天气很好，我们一起读一本英文小说吧。",
			en: "The lantern flickered once, and the library fell silent.",
		}

		/* ── i18n: Chinese strings are the keys, EN is the translation table ── */
		const EN = {
			"音色控制台": "Voice Console",
			"MiMo TTS 预置 / 设计 / 克隆音色": "MiMo TTS preset / design / clone voices",
			"关闭": "Close",
			"API Key": "API Key",
			"保存": "Save",
			"已保存到浏览器 localStorage（不写入仓库、不落盘）": "Stored in browser localStorage (never written to disk or the repo)",
			"未填写 API Key": "No API key yet",
			"试听样例": "Audition text",
			"中文样例": "Chinese sample",
			"英文样例": "English sample",
			"风格指令（可选，预置音色生效）": "Style instruction (optional; preset voices)",
			"例如：用轻快、亲切的语气朗读": "e.g. read in a lively, warm tone",
			"预置音色": "Preset voices",
			"中文": "Chinese",
			"英文": "English",
			"试听": "Audition",
			"设为中文默认": "Set as zh default",
			"设为英文默认": "Set as en default",
			"默认": "default",
			"女": "female",
			"男": "male",
			"设计音色（voicedesign）": "Design voices (voicedesign)",
			"用自然语言描述生成专属音色。": "Describe a voice in natural language to create it.",
			"名称": "Name",
			"描述": "Description",
			"语言": "Language",
			"性别": "Gender",
			"创建": "Create",
			"删除": "Delete",
			"克隆音色（voiceclone）": "Clone voices (voiceclone)",
			"上传一段 mp3 / wav 样本（≤10 MB）复刻音色。": "Upload an mp3 / wav sample (≤10 MB) to clone a voice.",
			"选择样本文件": "Choose sample file",
			"复制配置": "Copy config",
			"复制": "Copy",
			"已复制": "Copied",
			"合成中…": "Synthesizing…",
			"加载中…": "Loading…",
			"控制台数据": "Console data",
			"存储目录": "Store root",
			"自定义音色": "Custom voices",
			"还没有自定义音色。": "No custom voices yet.",
			"中文默认音色": "Chinese default",
			"英文默认音色": "English default",
			"请求失败": "Request failed",
			"请输入合成文本": "Enter some text first",
			"设计音色需要名称与描述": "A design voice needs a name and a description",
			"请选择克隆样本文件": "Choose a clone sample first",
			"删除失败": "Delete failed",
			"创建失败": "Create failed",
			"合成失败": "Synthesis failed",
			"控制台": "Console",
			"样本": "sample",
			"已保存": "Saved",
			"内录（抓系统输出）": "Internal recording (system output)",
			"直接抓系统输出的数字信号（PipeWire monitor），不经扬声器/麦克风，无环境噪声。": "Captures the system output digitally (PipeWire monitor) — no speakers, no microphone, no room noise.",
			"音频源": "Audio source",
			"系统输出（默认）": "System output (default)",
			"麦克风（默认）": "Microphone (default)",
			"时长（秒）": "Duration (s)",
			"开始录制": "Start recording",
			"停止": "Stop",
			"录音中": "Recording",
			"试听样本": "Audition",
			"存为克隆音色": "Save as clone voice",
			"放弃样本": "Discard",
			"录音失败": "Recording failed",
			"内录不可用": "Recording unavailable",
			"请先录一段样本": "Record a sample first",
			"请填写音色名称": "Enter a voice name",
			"已放弃录音样本": "Recording discarded",
			"已存为克隆音色": "Saved as a clone voice",
			"样本已就绪，可试听或存为克隆音色。": "Sample ready — audition it, or save it as a clone voice.",
		}
		let activeLocale = "zh"
		function tr(text) {
			return activeLocale === "en" ? EN[text] ?? text : text
		}

		/* ── module-level store: open state, prefs, and the host snapshot ── */
		const listeners = new Set()
		let snapshot = {
			open: false,
			locale: "zh",
			apiKey: "",
			style: "",
			samples: { ...DEFAULT_SAMPLES },
			zhVoice: "mimo_default",
			enVoice: "Mia",
			state: null,
			loading: false,
			error: "",
			notice: "",
			busyKey: "",
			recSource: "system",
			recSeconds: 30,
			recSession: null,
			recSample: null,
			recTick: 0,
		}
		function emit() {
			for (const listener of listeners) listener()
		}
		function subscribe(listener) {
			listeners.add(listener)
			return () => listeners.delete(listener)
		}
		function getSnapshot() {
			return snapshot
		}
		function patch(next) {
			snapshot = { ...snapshot, ...next }
			emit()
		}
		function readLocal(key, fallback) {
			try {
				const raw = window.localStorage.getItem(key)
				return raw === null ? fallback : JSON.parse(raw)
			} catch {
				return fallback
			}
		}
		function writeLocal(key, value) {
			try {
				window.localStorage.setItem(key, JSON.stringify(value))
			} catch {
				/* private mode / quota: the console still works for this session */
			}
		}

		/* ── host calls ── */
		async function callApi(path, options) {
			const response = await fetch(API + path, {
				method: (options && options.method) ?? "GET",
				headers: { "x-voice-console": "1", "content-type": "application/json" },
				body: options && options.body !== undefined ? JSON.stringify(options.body) : undefined,
			})
			let payload = null
			try {
				payload = await response.json()
			} catch {
				payload = null
			}
			if (payload === null) throw new Error(`接口无响应（HTTP ${response.status}）`)
			if (payload.ok === false) throw new Error(payload.error || `HTTP ${response.status}`)
			return payload
		}

		/* ── audio: one voice at a time ── */
		let currentAudio = null
		let currentUrl = null
		function stopAudio() {
			if (currentAudio !== null) {
				try {
					currentAudio.pause()
				} catch {
					/* already detached */
				}
				currentAudio = null
			}
			if (currentUrl !== null) {
				try {
					window.URL.revokeObjectURL(currentUrl)
				} catch {
					/* already revoked */
				}
				currentUrl = null
			}
		}
		function base64ToBlob(base64, mime) {
			const binary = window.atob(base64)
			const bytes = new Uint8Array(binary.length)
			for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index)
			return new window.Blob([bytes], { type: mime })
		}
		function playBase64(base64, mime) {
			stopAudio()
			const url = window.URL.createObjectURL(base64ToBlob(base64, mime))
			currentUrl = url
			const audio = new window.Audio(url)
			currentAudio = audio
			audio.onended = () => {
				window.URL.revokeObjectURL(url)
				if (currentAudio === audio) currentAudio = null
				if (currentUrl === url) currentUrl = null
			}
			audio.play().catch(() => {
				window.URL.revokeObjectURL(url)
				if (currentUrl === url) currentUrl = null
			})
		}

		/* ── actions ── */
		async function refresh() {
			patch({ loading: true, error: "" })
			try {
				const payload = await callApi("/state")
				patch({ state: payload, loading: false })
			} catch (error) {
				patch({ loading: false, error: String(error.message ?? error) })
			}
		}

		async function audition(voice, textOverride) {
			const current = snapshot
			if (current.apiKey.trim() === "") {
				patch({ error: tr("未填写 API Key") })
				return
			}
			const text = (textOverride ?? (voice.language === "en" ? current.samples.en : current.samples.zh)).trim()
			if (text === "") {
				patch({ error: tr("请输入合成文本") })
				return
			}
			patch({ busyKey: `voice:${voice.id}`, error: "", notice: tr("合成中…") })
			try {
				const payload = await callApi("/synthesize", {
					method: "POST",
					body: {
						apiKey: current.apiKey,
						text,
						style: current.style,
						voice: { kind: voice.kind ?? "preset", id: voice.id },
					},
				})
				playBase64(payload.audioBase64, payload.mime ?? "audio/wav")
				patch({ busyKey: "", notice: "" })
			} catch (error) {
				patch({ busyKey: "", notice: "", error: `${tr("合成失败")}：${String(error.message ?? error)}` })
			}
		}

		async function createVoice(kind, form) {
			const current = snapshot
			if (kind === "design" && (form.name.trim() === "" || form.description.trim() === "")) {
				patch({ error: tr("设计音色需要名称与描述") })
				return
			}
			if (kind === "clone" && form.fileBase64 === "") {
				patch({ error: tr("请选择克隆样本文件") })
				return
			}
			patch({ busyKey: `create:${kind}`, error: "", notice: "" })
			try {
				const body =
					kind === "clone"
						? {
								kind,
								name: form.name,
								language: form.language,
								gender: form.gender,
								filename: form.filename,
								dataBase64: form.fileBase64,
							}
						: {
								kind,
								name: form.name,
								description: form.description,
								language: form.language,
								gender: form.gender,
							}
				await callApi("/voices", { method: "POST", body })
				await refresh()
				patch({ busyKey: "", notice: "" })
			} catch (error) {
				patch({ busyKey: "", error: `${tr("创建失败")}：${String(error.message ?? error)}` })
			}
		}

		async function deleteVoice(id) {
			patch({ busyKey: `delete:${id}`, error: "" })
			try {
				await callApi(`/voices?id=${encodeURIComponent(id)}`, { method: "DELETE" })
				await refresh()
				patch({ busyKey: "" })
			} catch (error) {
				patch({ busyKey: "", error: `${tr("删除失败")}：${String(error.message ?? error)}` })
			}
		}

		/* ── 内录：Host 持有 parec，浏览器只驱动会话 ── */
		let recTimer = null
		let recTicker = null
		function clearRecTimers() {
			if (recTimer !== null) {
				window.clearTimeout(recTimer)
				recTimer = null
			}
			if (recTicker !== null) {
				window.clearInterval(recTicker)
				recTicker = null
			}
		}
		function defaultCloneName() {
			const now = new Date()
			const pad = (value) => String(value).padStart(2, "0")
			return `录音 ${pad(now.getHours())}:${pad(now.getMinutes())}`
		}
		async function startRecording() {
			const current = snapshot
			const description = current.state?.recording
			if (description && description.available === false) {
				patch({ error: `${tr("内录不可用")}：${description.reason ?? ""}` })
				return
			}
			stopAudio()
			patch({ busyKey: "record:start", error: "", notice: "", recSample: null })
			try {
				const payload = await callApi("/record/start", {
					method: "POST",
					body: { source: current.recSource, seconds: current.recSeconds },
				})
				const session = payload.session
				patch({ busyKey: "", recSession: { ...session, startedAt: session.startedAt ?? Date.now() } })
				recTicker = window.setInterval(() => patch({ recTick: snapshot.recTick + 1 }), 200)
				// 比 Host 的兜底计时器早 400 ms 收尾，保证样本 id 一定回到浏览器
				const seconds = Number(session.seconds ?? current.recSeconds)
				recTimer = window.setTimeout(() => {
					stopRecording().catch(() => {})
				}, Math.max(1000, seconds * 1000 - 400))
			} catch (error) {
				patch({ busyKey: "", error: `${tr("录音失败")}：${String(error.message ?? error)}` })
			}
		}
		async function stopRecording() {
			clearRecTimers()
			if (snapshot.recSession === null) return
			patch({ busyKey: "record:stop", error: "", notice: "" })
			try {
				const payload = await callApi("/record/stop", { method: "POST" })
				patch({ busyKey: "", recSession: null, recSample: payload.sample, notice: tr("样本已就绪，可试听或存为克隆音色。") })
			} catch (error) {
				patch({ busyKey: "", recSession: null, error: `${tr("录音失败")}：${String(error.message ?? error)}` })
			}
		}
		async function auditionSample(sampleId) {
			stopAudio()
			try {
				const response = await fetch(`${API}/samples/${encodeURIComponent(sampleId)}`, { headers: { "x-voice-console": "1" } })
				if (!response.ok) throw new Error(`HTTP ${response.status}`)
				const blob = await response.blob()
				const url = window.URL.createObjectURL(blob)
				currentUrl = url
				const audio = new window.Audio(url)
				currentAudio = audio
				audio.onended = () => {
					window.URL.revokeObjectURL(url)
					if (currentAudio === audio) currentAudio = null
					if (currentUrl === url) currentUrl = null
				}
				await audio.play()
			} catch (error) {
				patch({ error: `${tr("请求失败")}：${String(error.message ?? error)}` })
			}
		}
		async function discardSample() {
			const sample = snapshot.recSample
			if (sample === null) return
			patch({ busyKey: "record:discard", error: "" })
			try {
				await callApi(`/samples/${encodeURIComponent(sample.sampleId)}`, { method: "DELETE" })
				patch({ busyKey: "", recSample: null, notice: tr("已放弃录音样本") })
			} catch (error) {
				patch({ busyKey: "", error: `${tr("删除失败")}：${String(error.message ?? error)}` })
			}
		}
		async function saveSampleAsClone(form) {
			const sample = snapshot.recSample
			if (sample === null) {
				patch({ error: tr("请先录一段样本") })
				return
			}
			if (String(form.name ?? "").trim() === "") {
				patch({ error: tr("请填写音色名称") })
				return
			}
			patch({ busyKey: "record:clone", error: "", notice: "" })
			try {
				await callApi("/voices", {
					method: "POST",
					body: {
						kind: "clone",
						sampleId: sample.sampleId,
						name: form.name.trim(),
						language: form.language,
						gender: form.gender,
					},
				})
				await refresh()
				patch({ busyKey: "", recSample: null, notice: tr("已存为克隆音色") })
			} catch (error) {
				patch({ busyKey: "", error: `${tr("创建失败")}：${String(error.message ?? error)}` })
			}
		}
		/* 面板是模态浮层：关掉它才能操作别的窗口，所以关闭即收尾（样本留在 store 里） */
		function closeConsole() {
			stopAudio()
			if (snapshot.recSession !== null) stopRecording().catch(() => {})
			patch({ open: false })
		}

		function configText() {
			const current = snapshot
			const models = current.state?.models ?? {}
			const lines = [
				"# LinguaReader 听书 · MiMo 云 TTS 配置",
				"引擎: MiMo",
				`Base URL: ${current.state?.baseUrl ?? "https://api.xiaomimimo.com/v1"}`,
				`模型: ${models.preset ?? "mimo-v2.5-tts"}`,
				`中文音色: ${current.zhVoice}`,
				`英文音色: ${current.enVoice}`,
				`风格指令: ${current.style.trim() === "" ? "（留空）" : current.style.trim()}`,
				"API Key: （在控制台里已保存，请自行粘贴）",
			]
			return lines.join("\n")
		}

		async function copyConfig() {
			const text = configText()
			try {
				await window.navigator.clipboard.writeText(text)
				patch({ notice: tr("已复制"), error: "" })
			} catch (error) {
				patch({ error: `复制失败：${String(error.message ?? error)}`, notice: "" })
			}
		}

		/* ── style helpers (theme tokens only) ── */
		const S = {
			btn: {
				border: "1px solid var(--dsw-alias-border-l2)",
				background: "var(--dsw-alias-bg-layer-2)",
				color: "var(--dsw-alias-label-primary)",
				borderRadius: 8,
				padding: "5px 10px",
				fontSize: 12,
				cursor: "pointer",
			},
			btnPrimary: {
				border: "1px solid var(--dsw-alias-brand-primary)",
				background: "var(--dsw-alias-brand-primary)",
				color: "#fff",
				borderRadius: 8,
				padding: "5px 12px",
				fontSize: 12,
				cursor: "pointer",
			},
			input: {
				border: "1px solid var(--dsw-alias-border-l2)",
				background: "var(--dsw-alias-bg-base)",
				color: "var(--dsw-alias-label-primary)",
				borderRadius: 8,
				padding: "6px 9px",
				fontSize: 12,
				outline: "none",
				width: "100%",
				boxSizing: "border-box",
			},
			card: {
				border: "1px solid var(--dsw-alias-border-l1)",
				background: "var(--dsw-alias-bg-layer-2)",
				borderRadius: 10,
				padding: "10px 12px",
			},
			section: {
				border: "1px solid var(--dsw-alias-border-l1)",
				background: "var(--dsw-alias-bg-layer-1)",
				borderRadius: 12,
				padding: 14,
				display: "flex",
				flexDirection: "column",
				gap: 10,
			},
			h: { fontSize: 13, fontWeight: 600, color: "var(--dsw-alias-label-primary)", margin: 0 },
			sub: { fontSize: 11, color: "var(--dsw-alias-label-secondary)" },
			tag: {
				fontSize: 10,
				color: "var(--dsw-alias-label-secondary)",
				border: "1px solid var(--dsw-alias-border-l1)",
				borderRadius: 999,
				padding: "1px 7px",
			},
		}

		/* ── components ── */
		function useStore() {
			return react.useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
		}

		function ConsoleButton(props) {
			const state = useStore()
			const wide = props && props.wide !== false
			const label = tr("音色控制台")
			return react.createElement(
				"button",
				{
					type: "button",
					title: label,
					"aria-label": label,
					onClick: () => {
						const next = !state.open
						if (next) refresh()
						patch({ open: next })
					},
					style: {
						display: "inline-flex",
						alignItems: "center",
						justifyContent: "center",
						gap: 8,
						width: wide ? "auto" : 32,
						height: 32,
						padding: wide ? "0 10px" : 0,
						border: "1px solid var(--dsw-alias-border-l1)",
						background: state.open ? "var(--dsw-alias-bg-layer-2)" : "transparent",
						color: "var(--dsw-alias-label-secondary)",
						borderRadius: 8,
						fontSize: 12,
						cursor: "pointer",
					},
				},
				react.createElement("span", { "aria-hidden": "true", style: { fontSize: 14 } }, "🎙"),
				wide ? react.createElement("span", null, label) : null,
			)
		}

		function Field(props) {
			return react.createElement(
				"label",
				{ style: { display: "flex", flexDirection: "column", gap: 4, flex: props.flex ?? "1 1 0", minWidth: 0 } },
				react.createElement("span", { style: S.sub }, props.label),
				props.children,
			)
		}

		function VoiceCard(props) {
			const state = useStore()
			const voice = props.voice
			const busy = state.busyKey === `voice:${voice.id}`
			const isZh = voice.language !== "en"
			return react.createElement(
				"div",
				{ style: { ...S.card, display: "flex", alignItems: "center", gap: 10 } },
				react.createElement(
					"div",
					{ style: { flex: "1 1 0", minWidth: 0 } },
					react.createElement(
						"div",
						{ style: { fontSize: 12, fontWeight: 600, color: "var(--dsw-alias-label-primary)" } },
						voice.name ?? voice.id,
						voice.kind === "design" ? react.createElement("span", { style: { ...S.tag, marginLeft: 6 } }, "design") : null,
						voice.kind === "clone" ? react.createElement("span", { style: { ...S.tag, marginLeft: 6 } }, "clone") : null,
					),
					react.createElement(
						"div",
						{ style: { ...S.sub, marginTop: 3, display: "flex", gap: 6, flexWrap: "wrap" } },
						react.createElement("span", { style: S.tag }, isZh ? tr("中文") : tr("英文")),
						react.createElement("span", { style: S.tag }, voice.gender === "male" ? tr("男") : tr("女")),
						...(voice.style ?? []).map((tag, index) => react.createElement("span", { key: index, style: S.tag }, tag)),
						voice.description
							? react.createElement("span", { style: { ...S.sub, maxWidth: 320, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" } }, voice.description)
							: null,
					),
				),
				react.createElement(
					"button",
					{ type: "button", style: S.btn, disabled: busy, onClick: () => audition(voice) },
					busy ? tr("合成中…") : tr("试听"),
				),
				voice.kind === undefined
					? react.createElement(
							"button",
							{
								type: "button",
								style: S.btn,
								onClick: () => {
									if (isZh) patch({ zhVoice: voice.id })
									else patch({ enVoice: voice.id })
									writeLocal(PREF_STORAGE, {
										style: snapshot.style,
										samples: snapshot.samples,
										zhVoice: isZh ? voice.id : snapshot.zhVoice,
										enVoice: isZh ? snapshot.enVoice : voice.id,
									})
								},
							},
							isZh ? tr("设为中文默认") : tr("设为英文默认"),
						)
					: react.createElement(
							"button",
							{ type: "button", style: S.btn, disabled: state.busyKey === `delete:${voice.id}`, onClick: () => deleteVoice(voice.id) },
							tr("删除"),
						),
			)
		}

		function RecorderSection() {
			const state = useStore()
			const description = state.state?.recording ?? null
			const sources = description?.sources ?? []
			const available = description === null || description.available !== false
			const session = state.recSession
			const sample = state.recSample
			const [name, setName] = react.useState("")
			const [language, setLanguage] = react.useState("zh")
			const [gender, setGender] = react.useState("female")

			/* 每录完一段，给克隆名一个默认值（默认名可在保存前改） */
			const sampleId = sample === null ? "" : sample.sampleId
			react.useEffect(() => {
				if (sampleId !== "") setName(defaultCloneName())
			}, [sampleId])

			const recording = session !== null
			const elapsed = recording ? Math.max(0, (Date.now() - session.startedAt) / 1000) : 0
			const busy = state.busyKey.startsWith("record:")
			const defaults = description?.defaults ?? {}
			const sourceOptions = [
				{ value: "system", label: `${tr("系统输出（默认）")} · ${defaults.system ?? "—"}` },
				{ value: "mic", label: `${tr("麦克风（默认）")} · ${defaults.mic ?? "—"}` },
				...sources
					.filter((entry) => entry.name !== defaults.system && entry.name !== defaults.mic)
					.map((entry) => ({ value: entry.name, label: entry.label })),
			]

			return react.createElement(
				"div",
				{ style: S.section },
				react.createElement("h3", { style: S.h }, tr("内录（抓系统输出）")),
				react.createElement(
					"p",
					{ style: { ...S.sub, margin: 0 } },
					tr("直接抓系统输出的数字信号（PipeWire monitor），不经扬声器/麦克风，无环境噪声。"),
				),
				available
					? null
					: react.createElement(
							"p",
							{ style: { ...S.sub, margin: 0, color: "var(--dsw-alias-state-error-primary)" } },
							`${tr("内录不可用")}：${description?.reason ?? ""}`,
						),
				react.createElement(
					"div",
					{ style: { display: "flex", gap: 8, flexWrap: "wrap", alignItems: "flex-end" } },
					react.createElement(
						Field,
						{ label: tr("音频源"), flex: "2 1 260px" },
						react.createElement(
							"select",
							{
								style: S.input,
								value: state.recSource,
								disabled: recording,
								onChange: (event) => patch({ recSource: event.target.value }),
							},
							...sourceOptions.map((option) => react.createElement("option", { key: option.value, value: option.value }, option.label)),
						),
					),
					react.createElement(
						Field,
						{ label: tr("时长（秒）"), flex: "0 1 110px" },
						react.createElement("input", {
							type: "number",
							min: 3,
							max: 300,
							style: S.input,
							value: state.recSeconds,
							disabled: recording,
							onChange: (event) => patch({ recSeconds: Number(event.target.value) || 30 }),
						}),
					),
					recording
						? react.createElement("button", { type: "button", style: S.btnPrimary, disabled: busy, onClick: () => stopRecording() }, tr("停止"))
						: react.createElement(
								"button",
								{ type: "button", style: S.btnPrimary, disabled: busy || !available, onClick: () => startRecording() },
								tr("开始录制"),
							),
				),
				recording
					? react.createElement(
							"div",
							{ style: { ...S.card, display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" } },
							react.createElement(
								"span",
								{ style: { fontSize: 12, fontWeight: 600, color: "var(--dsw-alias-state-error-primary)" } },
								`● ${tr("录音中")}`,
							),
							react.createElement("span", { style: S.sub }, `${elapsed.toFixed(1)}s / ${session.seconds}s`),
							react.createElement(
								"span",
								{ style: { ...S.sub, flex: "1 1 0", minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" } },
								session.source,
							),
						)
					: null,
				sample !== null
					? react.createElement(
							"div",
							{ style: { ...S.card, display: "flex", flexDirection: "column", gap: 8 } },
							react.createElement(
								"div",
								{ style: { display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" } },
								react.createElement("span", { style: { fontSize: 12, fontWeight: 600, color: "var(--dsw-alias-label-primary)" } }, tr("样本")),
								react.createElement("span", { style: S.tag }, `${Number(sample.duration ?? 0).toFixed(1)}s`),
								react.createElement("span", { style: S.tag }, `${Math.round(Number(sample.bytes ?? 0) / 1024)} KB`),
								react.createElement(
									"span",
									{ style: { ...S.sub, flex: "1 1 0", minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" } },
									sample.source,
								),
								react.createElement("button", { type: "button", style: S.btn, onClick: () => auditionSample(sample.sampleId) }, tr("试听样本")),
								react.createElement("button", { type: "button", style: S.btn, disabled: busy, onClick: () => discardSample() }, tr("放弃样本")),
							),
							react.createElement(
								"div",
								{ style: { display: "flex", gap: 8, flexWrap: "wrap", alignItems: "flex-end" } },
								react.createElement(
									Field,
									{ label: tr("名称") },
									react.createElement("input", { style: S.input, value: name, onChange: (event) => setName(event.target.value) }),
								),
								react.createElement(
									Field,
									{ label: tr("语言"), flex: "0 1 110px" },
									react.createElement(
										"select",
										{ style: S.input, value: language, onChange: (event) => setLanguage(event.target.value) },
										react.createElement("option", { value: "zh" }, tr("中文")),
										react.createElement("option", { value: "en" }, tr("英文")),
									),
								),
								react.createElement(
									Field,
									{ label: tr("性别"), flex: "0 1 110px" },
									react.createElement(
										"select",
										{ style: S.input, value: gender, onChange: (event) => setGender(event.target.value) },
										react.createElement("option", { value: "female" }, tr("女")),
										react.createElement("option", { value: "male" }, tr("男")),
									),
								),
								react.createElement(
									"button",
									{ type: "button", style: S.btnPrimary, disabled: busy, onClick: () => saveSampleAsClone({ name, language, gender }) },
									tr("存为克隆音色"),
								),
							),
						)
					: null,
			)
		}

		function ConsolePanel() {
			const state = useStore()
			const [design, setDesign] = react.useState({ name: "", description: "", language: "zh", gender: "female" })
			const [clone, setClone] = react.useState({ name: "", language: "zh", gender: "female", filename: "", fileBase64: "" })
			const presets = state.state?.presets ?? []
			const custom = state.state?.voices ?? []
			/* 两个区必须互补，且与 VoiceCard 的 isZh 口径一致：非 en 一律算中文区 */
			const zhPresets = presets.filter((voice) => voice.language !== "en")
			const enPresets = presets.filter((voice) => voice.language === "en")
			const designVoices = custom.filter((voice) => voice.kind === "design")
			const cloneVoices = custom.filter((voice) => voice.kind === "clone")

			function onPickFile(event) {
				const file = event.target.files && event.target.files[0]
				if (!file) return
				const reader = new window.FileReader()
				reader.onload = () => {
					const result = String(reader.result ?? "")
					const comma = result.indexOf(",")
					setClone((prev) => ({ ...prev, filename: file.name, fileBase64: comma >= 0 ? result.slice(comma + 1) : "" }))
				}
				reader.readAsDataURL(file)
			}

			function saveKey() {
				writeLocal(KEY_STORAGE, state.apiKey)
				patch({ notice: tr("已保存"), error: "" })
			}
			function savePrefs(next) {
				patch(next)
				writeLocal(PREF_STORAGE, {
					style: next.style ?? snapshot.style,
					samples: next.samples ?? snapshot.samples,
					zhVoice: next.zhVoice ?? snapshot.zhVoice,
					enVoice: next.enVoice ?? snapshot.enVoice,
				})
			}

			const body = [
				react.createElement(
					"div",
					{ key: "key", style: S.section },
					react.createElement("h3", { style: S.h }, tr("API Key")),
					react.createElement(
						"div",
						{ style: { display: "flex", gap: 8, alignItems: "flex-end" } },
						react.createElement(
							Field,
							{ label: tr("已保存到浏览器 localStorage（不写入仓库、不落盘）") },
							react.createElement("input", {
								type: "password",
								value: state.apiKey,
								placeholder: "sk-…",
								style: S.input,
								onChange: (event) => patch({ apiKey: event.target.value }),
							}),
						),
						react.createElement("button", { type: "button", style: S.btnPrimary, onClick: saveKey }, tr("保存")),
					),
				),
				react.createElement(
					"div",
					{ key: "samples", style: S.section },
					react.createElement("h3", { style: S.h }, tr("试听样例")),
					react.createElement(
						"div",
						{ style: { display: "flex", gap: 10 } },
						react.createElement(
							Field,
							{ label: tr("中文样例") },
							react.createElement("input", {
								style: S.input,
								value: state.samples.zh,
								onChange: (event) => savePrefs({ samples: { ...state.samples, zh: event.target.value } }),
							}),
						),
						react.createElement(
							Field,
							{ label: tr("英文样例") },
							react.createElement("input", {
								style: S.input,
								value: state.samples.en,
								onChange: (event) => savePrefs({ samples: { ...state.samples, en: event.target.value } }),
							}),
						),
					),
					react.createElement(Field, { label: tr("风格指令（可选，预置音色生效）") }, react.createElement("textarea", {
						style: { ...S.input, minHeight: 54, resize: "vertical", fontFamily: "inherit" },
						value: state.style,
						placeholder: tr("例如：用轻快、亲切的语气朗读"),
						onChange: (event) => savePrefs({ style: event.target.value }),
					})),
				),
				react.createElement(
					"div",
					{ key: "presets", style: S.section },
					react.createElement("h3", { style: S.h }, `${tr("预置音色")} · ${tr("中文")}`),
					...zhPresets.map((voice) => react.createElement(VoiceCard, { key: voice.id, voice })),
					react.createElement("h3", { style: { ...S.h, marginTop: 6 } }, `${tr("预置音色")} · ${tr("英文")}`),
					...enPresets.map((voice) => react.createElement(VoiceCard, { key: voice.id, voice })),
				),
				react.createElement(
					"div",
					{ key: "design", style: S.section },
					react.createElement("h3", { style: S.h }, tr("设计音色（voicedesign）")),
					react.createElement("p", { style: { ...S.sub, margin: 0 } }, tr("用自然语言描述生成专属音色。")),
					react.createElement(
						"div",
						{ style: { display: "flex", gap: 8, flexWrap: "wrap", alignItems: "flex-end" } },
						react.createElement(Field, { label: tr("名称") }, react.createElement("input", {
							style: S.input,
							value: design.name,
							onChange: (event) => setDesign({ ...design, name: event.target.value }),
						})),
						react.createElement(Field, { label: tr("语言") }, react.createElement(
							"select",
							{ style: S.input, value: design.language, onChange: (event) => setDesign({ ...design, language: event.target.value }) },
							react.createElement("option", { value: "zh" }, tr("中文")),
							react.createElement("option", { value: "en" }, tr("英文")),
						)),
						react.createElement(Field, { label: tr("性别") }, react.createElement(
							"select",
							{ style: S.input, value: design.gender, onChange: (event) => setDesign({ ...design, gender: event.target.value }) },
							react.createElement("option", { value: "female" }, tr("女")),
							react.createElement("option", { value: "male" }, tr("男")),
						)),
					),
					react.createElement(Field, { label: tr("描述") }, react.createElement("textarea", {
						style: { ...S.input, minHeight: 54, resize: "vertical", fontFamily: "inherit" },
						value: design.description,
						onChange: (event) => setDesign({ ...design, description: event.target.value }),
					})),
					react.createElement(
						"div",
						{ style: { display: "flex", gap: 8 } },
						react.createElement(
							"button",
							{ type: "button", style: S.btnPrimary, disabled: state.busyKey === "create:design", onClick: () => createVoice("design", design) },
							tr("创建"),
						),
					),
					designVoices.length === 0
						? react.createElement("p", { style: { ...S.sub, margin: 0 } }, tr("还没有自定义音色。"))
						: null,
					...designVoices.map((voice) => react.createElement(VoiceCard, { key: voice.id, voice })),
				),
				react.createElement(RecorderSection, { key: "record" }),
				react.createElement(
					"div",
					{ key: "clone", style: S.section },
					react.createElement("h3", { style: S.h }, tr("克隆音色（voiceclone）")),
					react.createElement("p", { style: { ...S.sub, margin: 0 } }, tr("上传一段 mp3 / wav 样本（≤10 MB）复刻音色。")),
					react.createElement(
						"div",
						{ style: { display: "flex", gap: 8, flexWrap: "wrap", alignItems: "flex-end" } },
						react.createElement(Field, { label: tr("名称") }, react.createElement("input", {
							style: S.input,
							value: clone.name,
							onChange: (event) => setClone({ ...clone, name: event.target.value }),
						})),
						react.createElement(Field, { label: tr("语言") }, react.createElement(
							"select",
							{ style: S.input, value: clone.language, onChange: (event) => setClone({ ...clone, language: event.target.value }) },
							react.createElement("option", { value: "zh" }, tr("中文")),
							react.createElement("option", { value: "en" }, tr("英文")),
						)),
						react.createElement(Field, { label: tr("性别") }, react.createElement(
							"select",
							{ style: S.input, value: clone.gender, onChange: (event) => setClone({ ...clone, gender: event.target.value }) },
							react.createElement("option", { value: "female" }, tr("女")),
							react.createElement("option", { value: "male" }, tr("男")),
						)),
					),
					react.createElement(
						"div",
						{ style: { display: "flex", gap: 8, alignItems: "center" } },
						react.createElement(
							"label",
							{ style: { ...S.btn, display: "inline-flex", alignItems: "center", gap: 6 } },
							tr("选择样本文件"),
							react.createElement("input", { type: "file", accept: "audio/*", style: { display: "none" }, onChange: onPickFile }),
						),
						react.createElement("span", { style: S.sub }, clone.filename === "" ? "" : clone.filename),
						react.createElement(
							"button",
							{ type: "button", style: S.btnPrimary, disabled: state.busyKey === "create:clone", onClick: () => createVoice("clone", clone) },
							tr("创建"),
						),
					),
					cloneVoices.map((voice) => react.createElement(VoiceCard, { key: voice.id, voice })),
				),
				react.createElement(
					"div",
					{ key: "config", style: S.section },
					react.createElement("h3", { style: S.h }, tr("复制配置")),
					react.createElement("pre", {
						style: {
							...S.input,
							margin: 0,
							whiteSpace: "pre-wrap",
							fontSize: 11,
							lineHeight: 1.5,
							fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
						},
					}, configText()),
					react.createElement(
						"div",
						{ style: { display: "flex", gap: 8, alignItems: "center" } },
						react.createElement("button", { type: "button", style: S.btnPrimary, onClick: copyConfig }, tr("复制")),
						react.createElement("span", { style: S.sub }, `${tr("中文默认音色")}: ${state.zhVoice} · ${tr("英文默认音色")}: ${state.enVoice}`),
					),
				),
			]

			return react.createElement(
				"div",
				{
					style: {
						position: "fixed",
						inset: 0,
						zIndex: 60,
						display: "flex",
						alignItems: "center",
						justifyContent: "center",
						background: "rgba(0,0,0,.45)",
						pointerEvents: "auto",
					},
					onClick: (event) => {
						if (event.target === event.currentTarget) closeConsole()
					},
				},
				react.createElement(
					"div",
					{
						style: {
							width: "min(1040px, 94vw)",
							height: "min(88vh, 920px)",
							display: "flex",
							flexDirection: "column",
							background: "var(--dsw-alias-bg-overlay)",
							border: "1px solid var(--dsw-alias-border-l2)",
							borderRadius: 14,
							overflow: "hidden",
							boxShadow: "0 18px 48px rgba(0,0,0,.35)",
						},
					},
					react.createElement(
						"div",
						{
							style: {
								display: "flex",
								alignItems: "center",
								gap: 12,
								padding: "12px 16px",
								borderBottom: "1px solid var(--dsw-alias-border-l1)",
								background: "var(--dsw-alias-bg-layer-1)",
							},
						},
						react.createElement("span", { "aria-hidden": "true", style: { fontSize: 18 } }, "🎙"),
						react.createElement(
							"div",
							{ style: { flex: "1 1 0", minWidth: 0 } },
							react.createElement("div", { style: { fontSize: 14, fontWeight: 600, color: "var(--dsw-alias-label-primary)" } }, tr("音色控制台")),
							react.createElement("div", { style: S.sub }, tr("MiMo TTS 预置 / 设计 / 克隆音色")),
						),
						react.createElement(
							"button",
							{ type: "button", style: S.btn, onClick: () => closeConsole() },
							tr("关闭"),
						),
					),
					react.createElement(
						"div",
						{ style: { flex: "1 1 0", overflowY: "auto", padding: 16, display: "flex", flexDirection: "column", gap: 14 } },
						state.loading ? react.createElement("p", { style: S.sub }, tr("加载中…")) : null,
						...body,
					),
					state.error || state.notice
						? react.createElement(
								"div",
								{
									style: {
										padding: "8px 16px",
										fontSize: 12,
										borderTop: "1px solid var(--dsw-alias-border-l1)",
										color: state.error ? "var(--dsw-alias-state-error-primary)" : "var(--dsw-alias-label-secondary)",
									},
								},
								state.error || state.notice,
							)
						: null,
					react.createElement(
						"div",
						{ style: { ...S.sub, padding: "6px 16px", borderTop: "1px solid var(--dsw-alias-border-l1)" } },
						`${tr("存储目录")}: ${state.state?.storeRoot ?? "—"}`,
					),
				),
			)
		}

		function ConsoleOverlay() {
			const state = useStore()
			if (!state.open) return null
			return react.createElement(ConsolePanel, null)
		}

		/* ── plugin body ── */
		function apply(ctx) {
			activeLocale = ctx.locale.getSnapshot().active
			patch({
				locale: activeLocale,
				apiKey: readLocal(KEY_STORAGE, ""),
				style: readLocal(PREF_STORAGE, {}).style ?? "",
				samples: { ...DEFAULT_SAMPLES, ...(readLocal(PREF_STORAGE, {}).samples ?? {}) },
				zhVoice: readLocal(PREF_STORAGE, {}).zhVoice ?? "mimo_default",
				enVoice: readLocal(PREF_STORAGE, {}).enVoice ?? "Mia",
			})
			ctx.effect(
				() => ctx.locale.subscribe(() => {
					activeLocale = ctx.locale.getSnapshot().active
					patch({ locale: activeLocale })
				}),
				"voice-console: locale",
			)
			ctx.slots.inject("sidebar.footer.action", () =>
				ctx.slots.register(
					{
						name: "sidebar.footer.action",
						id: "voice-console",
						order: 5,
						label: () => tr("音色控制台"),
					},
					ConsoleButton,
				),
			)
			ctx.slots.inject("shell.overlay", () =>
				ctx.slots.register(
					{
						name: "shell.overlay",
						id: "voice-console-panel",
						order: 100,
					},
					ConsoleOverlay,
				),
			)
		}

		exports.apply = apply
		exports.inject = ["slots", "locale"]
		return module.exports
	},
})
