// 用最小 DOM 桩在 Node 里**真正执行** ReaderScripts 产出的 JS。
//
// 为什么：WebView 在 Robolectric 里不跑 JS，所以「拆完脚本还能不能用」一直只能靠真机。
// 但脚本的第一件事就是把 16 个 window.lr* 挂在 window 上——这一步不依赖真实布局。
// 只要能执行完且 16 个 API 都在，就证明语法、作用域、IIFE 结构都没被拆分破坏。
//
// 覆盖不到的：布局测量、分页计算、触摸事件。那些仍然必须真机。

const fs = require("fs");
const path = process.argv[2];
if (!path) { console.log("USAGE: node run-reader-js.js <bootstrap.js>"); process.exit(2); }

// ── 最小 DOM/browser 桩 ─────────────────────────────────────────
function makeClassList() {
  const set = new Set();
  return {
    add: (...c) => c.forEach(x => set.add(x)),
    remove: (...c) => c.forEach(x => set.delete(x)),
    contains: c => set.has(c),
    toggle: c => (set.has(c) ? set.delete(c) : set.add(c)),
  };
}
function makeElement(tag) {
  const el = {
    tagName: String(tag).toUpperCase(),
    children: [], childNodes: [], parentElement: null, parentNode: null,
    style: { setProperty() {}, removeProperty() {} },
    dataset: {}, classList: makeClassList(),
    _id: "", _text: "",
    get id() { return this._id; },
    set id(v) { this._id = v; },
    get textContent() { return this._text; },
    set textContent(v) { this._text = String(v); },
    get innerHTML() { return this._text; },
    set innerHTML(v) { this._text = String(v); },
    get firstChild() { return this.childNodes[0] || null; },
    appendChild(c) { this.childNodes.push(c); this.children.push(c); c.parentElement = this; c.parentNode = this; return c; },
    removeChild(c) { const i = this.childNodes.indexOf(c); if (i >= 0) { this.childNodes.splice(i, 1); this.children.splice(i, 1); } return c; },
    insertBefore(c) { return this.appendChild(c); },
    addEventListener() {}, removeEventListener() {},
    getBoundingClientRect() { return { x: 0, y: 0, top: 0, left: 0, right: 390, bottom: 844, width: 390, height: 844 }; },
    getClientRects() { return []; },
    setAttribute(k, v) { if (k === "id") this._id = v; },
    getAttribute() { return null; },
    removeAttribute() {},
    closest() { return null; },
    querySelector() { return null; },
    querySelectorAll() { return []; },
    scrollIntoView() {},
    focus() {}, blur() {},
    cloneNode() { return makeElement(tag); },
    contains() { return false; },
  };
  return el;
}

const body = makeElement("body");
const head = makeElement("head");
const documentElement = makeElement("html");

const document = {
  body, head, documentElement,
  readyState: "complete",
  createElement: makeElement,
  createTextNode: t => ({ nodeType: 3, textContent: String(t), parentElement: null, parentNode: null }),
  getElementById(id) {
    const find = n => {
      if (n._id === id) return n;
      for (const c of n.children) { const r = find(c); if (r) return r; }
      return null;
    };
    return find(body) || find(head);
  },
  querySelector() { return null; },
  querySelectorAll() { return []; },
  addEventListener() {}, removeEventListener() {},
  createRange() {
    return {
      setStart() {}, setEnd() {}, collapse() {},
      getBoundingClientRect() { return { x: 0, y: 0, top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0 }; },
      getClientRects() { return []; },
      commonAncestorContainer: body,
      startContainer: body, endContainer: body, startOffset: 0, endOffset: 0,
      cloneRange() { return this; },
    };
  },
  createTreeWalker() {
    return { currentNode: body, root: body, nextNode() { return null; }, previousNode() { return null; }, firstChild() { return null; }, lastChild() { return null; }, parentNode() { return null; }, nextSibling() { return null; }, previousSibling() { return null; } };
  },
  elementFromPoint() { return body; },
  caretRangeFromPoint() { return null; },
  getSelection() { return { removeAllRanges() {}, addRange() {}, rangeCount: 0, getRangeAt() { return null; }, toString() { return ""; } }; },
};

const window = {
  document,
  innerWidth: 390, innerHeight: 844,
  devicePixelRatio: 3,
  scrollX: 0, scrollY: 0,
  getComputedStyle: () => ({ getPropertyValue: () => "", lineHeight: "28px", fontSize: "16px" }),
  requestAnimationFrame: cb => { /* 不真的排队，避免无限循环 */ return 0; },
  cancelAnimationFrame() {},
  setTimeout: (cb) => 0,
  clearTimeout() {},
  setInterval: () => 0,
  clearInterval() {},
  addEventListener() {}, removeEventListener() {},
  matchMedia: () => ({ matches: false, addEventListener() {}, removeEventListener() {} }),
  performance: { now: () => Date.now() },
  // ReaderBridge 由 Android 侧注入；这里给个记录器
  ReaderBridge: new Proxy({}, { get: () => () => {} }),
};


// 关键：脚本用 window.xxx 访问，同时也会读全局 document / Node / Range 等
const vm = require("vm");
const context = vm.createContext(Object.assign({}, window, {
  window, document, console,
  Node: { ELEMENT_NODE: 1, TEXT_NODE: 3 },
  Range: function () {},
  HTMLElement: function () {},
  getComputedStyle: window.getComputedStyle,
  requestAnimationFrame: window.requestAnimationFrame,
  setTimeout: window.setTimeout, clearTimeout: window.clearTimeout,
  innerWidth: window.innerWidth, innerHeight: window.innerHeight,
  matchMedia: window.matchMedia,
}));
// 让 context.window 指向自己，脚本里的 window.x = ... 写回同一对象
context.window = context;

const src = fs.readFileSync(path, "utf8");
try {
  vm.runInContext(src, context, { timeout: 10000, filename: path });
} catch (e) {
  console.log("EXEC_FAIL: " + e.message);
  process.exit(1);
}

const apis = [
  "lrTurn", "lrSetPage", "lrGetPage", "lrSyncPage",
  "lrEnterScrollMode", "lrExitScrollMode",
  "lrLookupAt", "lrHighlightBlock", "lrHighlightSentence", "lrClearHighlight",
  "lrRefreshSavedWords", "lrSetChromeInsets", "lrSetChoosingStart",
  "lrLocusHere", "lrScrollToLocus", "lrBackToSpeaking",
];
const missing = apis.filter(a => typeof context["window"][a] !== "function");
if (missing.length) {
  console.log("MISSING_APIS: " + missing.join(", "));
  process.exit(1);
}
console.log("EXEC_OK " + path + " — 16/16 APIs installed (" + src.length + " chars)");