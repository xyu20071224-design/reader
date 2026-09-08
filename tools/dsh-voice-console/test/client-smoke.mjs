/**
 * Headless smoke test for the voice-console client half.
 *
 * The browser bundle is plain CommonJS-in-a-factory, so Node can load it with a
 * captured `window.__ModuleLoader__` and a minimal React stub. This checks the
 * two Slot registrations, the sidebar button, and a full open-panel render
 * against a fake catalog — no browser and no network required.
 *
 * Run: node tools/dsh-voice-console/test/client-smoke.mjs
 */
import assert from 'node:assert/strict'

/* ── fake browser globals ── */
const storage = new Map()
globalThis.window = {
	localStorage: {
		getItem: (key) => (storage.has(key) ? storage.get(key) : null),
		setItem: (key, value) => storage.set(key, String(value)),
	},
	__ModuleLoader__: { load: (entry) => { captured = entry } },
}
let captured = null

/* ── minimal React stub ── */
const React = {
	createElement: (type, props, ...children) => ({ type, props: { ...(props ?? {}), children: children.flat() } }),
	useState: (initial) => [typeof initial === 'function' ? initial() : initial, () => {}],
	useSyncExternalStore: (_subscribe, getSnapshot) => getSnapshot(),
	useEffect: () => {},
	useRef: (value) => ({ current: value }),
}
const requireStub = (name) => {
	if (name === 'react') return React
	throw new Error(`unexpected require: ${name}`)
}

/* ── host stub: /state answers with one preset per language ── */
globalThis.fetch = async (url) => {
	assert.equal(url, '/voice-console/api/state')
	return {
		status: 200,
		json: async () => ({
			ok: true,
			baseUrl: 'https://api.xiaomimimo.com/v1',
			models: { preset: 'mimo-v2.5-tts', design: 'mimo-v2.5-tts-voicedesign', clone: 'mimo-v2.5-tts-voiceclone' },
			defaults: { zh: 'mimo_default', en: 'Mia' },
			samples: { zh: '中文样例', en: 'English sample' },
			presets: [
				{ id: 'mimo_default', name: '默认（冰糖）', language: 'zh', gender: 'female', style: ['中性'] },
				{ id: 'Mia', name: 'Mia', language: 'en', gender: 'female', style: ['lively'] },
			],
			voices: [
				{ id: 'design-1', kind: 'design', name: '测试设计', language: 'zh', gender: 'female', description: '温柔' },
				{ id: 'clone-1', kind: 'clone', name: '测试克隆', language: 'en', gender: 'male', sampleFile: 'clone-1.wav' },
			],
			storeRoot: '/tmp/voice-console',
			recording: {
				available: true,
				reason: '',
				sources: [{ name: 'vctest.monitor', kind: 'monitor', label: '内录 · vctest' }],
				defaults: { system: 'vctest.monitor', mic: 'alsa_input.test' },
			},
			session: { active: false },
		}),
	}
}

await import('../lib/client.js')

assert.ok(captured, 'client.js must call window.__ModuleLoader__.load')
assert.equal(captured.id, '@local/dsh-voice-console')

const mod = captured.factory(requireStub)
assert.equal(typeof mod.apply, 'function')
assert.deepEqual(mod.inject, ['slots', 'locale'])

/* ── mount with a stub context ── */
const registrations = []
const ctx = {
	locale: {
		getSnapshot: () => ({ active: 'zh' }),
		subscribe: () => () => {},
	},
	effect: (fn) => {
		const disposer = fn()
		return disposer
	},
	slots: {
		inject: (_key, callback) => callback(),
		register: (options, Component) => {
			registrations.push({ options, Component })
			return () => {}
		},
	},
}
mod.apply(ctx)

assert.equal(registrations.length, 2, 'two slot registrations')
const button = registrations.find((entry) => entry.options.name === 'sidebar.footer.action')
const overlay = registrations.find((entry) => entry.options.name === 'shell.overlay')
assert.ok(button && overlay, 'both target slots registered')
assert.equal(button.options.id, 'voice-console')
assert.equal(overlay.options.id, 'voice-console-panel')

/* ── closed panel renders nothing; button renders a button ── */
assert.equal(overlay.Component({}), null)
const buttonTree = button.Component({ wide: true })
assert.equal(buttonTree.type, 'button')
assert.ok(buttonTree.props.children.some((child) => child && child.type === 'span'))

/* ── open it through the button, then render the whole panel ── */
buttonTree.props.onClick()
await new Promise((resolve) => setTimeout(resolve, 0))

const panelElement = overlay.Component({})
assert.ok(panelElement, 'open panel renders')
assert.equal(typeof panelElement.type, 'function', 'open panel renders the ConsolePanel component')
const panelTree = panelElement.type(panelElement.props)
assert.equal(panelTree.type, 'div')
const panel = panelTree.props.children.find((child) => child && child.type === 'div')
const panelChildren = panel.props.children.flat().filter(Boolean)
const body = panelChildren.find((child) => child && child.props && child.props.style && child.props.style.overflowY === 'auto')
assert.ok(body, 'panel body present')
const sections = body.props.children.flat().filter((child) => child && child.type === 'div' && child.props && child.props.style && child.props.style.flexDirection === 'column')
assert.ok(sections.length >= 5, `expected several console sections, got ${sections.length}`)

/* the catalog sections must have rendered one VoiceCard element per voice */
const voiceCards = sections
	.flatMap((section) => section.props.children.flat())
	.filter((child) => child && typeof child.type === 'function' && child.props && child.props.voice)
assert.ok(voiceCards.length >= 4, `expected preset+design+clone cards, got ${voiceCards.length}`)

/* one card renders to a row with 试听 plus either 设为默认 or 删除 */
const cardTree = voiceCards[0].type(voiceCards[0].props)
assert.equal(cardTree.type, 'div')
const cardButtons = cardTree.props.children.flat().filter((child) => child && child.type === 'button')
assert.ok(cardButtons.length >= 2, `expected audition + action buttons, got ${cardButtons.length}`)

/* 英文区只能列 en 音色（曾因写成 !== "en" 而变成中文区的镜像） */
const presetsSection = sections.find((section) =>
	section.props.children.flat().some((child) => child && child.type === 'h3' && [child.props.children].flat().join('').includes('预置音色')),
)
assert.ok(presetsSection, 'presets section present')
const presetChildren = presetsSection.props.children.flat().filter(Boolean)
const enHeaderIndex = presetChildren.findIndex(
	(child) => child && child.type === 'h3' && [child.props.children].flat().join('').includes('英文'),
)
assert.ok(enHeaderIndex >= 0, '英文 presets header present')
const enCards = presetChildren.slice(enHeaderIndex + 1).filter((child) => child && child.props && child.props.voice)
assert.ok(enCards.length > 0, 'english presets rendered')
assert.ok(enCards.every((card) => card.props.voice.language === 'en'), 'english section must only list en voices')

/* the 内录 section must offer a source picker and a start-recording button */
const recorder = body.props.children
	.flat()
	.find((child) => child && typeof child.type === 'function' && child.type.name === 'RecorderSection')
assert.ok(recorder, '内录 section present')
const recorderChildren = recorder.type(recorder.props).props.children.flat().filter(Boolean)
const controls = recorderChildren.find(
	(child) => child && child.type === 'div' && child.props.style && child.props.style.flexWrap === 'wrap',
)
assert.ok(controls, '内录 controls row present')
const controlChildren = controls.props.children.flat().filter(Boolean)
const sourceField = controlChildren.find((child) => child && child.props && child.props.label === '音频源')
assert.ok(sourceField, '音频源 picker present')
assert.equal([sourceField.props.children].flat()[0].type, 'select')
const startButton = controlChildren.find((child) => child && child.type === 'button')
assert.ok(startButton, 'start-recording button present')
assert.equal([startButton.props.children].flat()[0], '开始录制')

console.log(JSON.stringify({
	ok: true,
	registrations: registrations.map((entry) => `${entry.options.name}#${entry.options.id}`),
	sections: sections.length,
	voiceCards: voiceCards.length,
	recorder: 'ok',
}))
