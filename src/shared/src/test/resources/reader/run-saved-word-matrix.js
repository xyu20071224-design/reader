// Execute the current Kotlin-generated bootstrap against a small text DOM.
// This deliberately does not emulate layout; the Android WebView matrix covers that layer.
const fs = require('fs');
const vm = require('vm');
const input = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
class DomNode {
  constructor(type, tag = '', value = '') {
    this.nodeType = type; this.tagName = tag.toUpperCase(); this.nodeValue = value;
    this.childNodes = []; this.parentNode = null; this.id = ''; this.className = '';
    this.style = {setProperty() {}, removeProperty() {}}; this.dataset = {};
    this.classList = {add() {}, remove() {}, contains: c => this.className.split(/\s+/).includes(c)};
  }
  get parentElement() { return this.parentNode && this.parentNode.nodeType === 1 ? this.parentNode : null; }
  get children() { return this.childNodes.filter(n => n.nodeType === 1); }
  get firstChild() { return this.childNodes[0] || null; }
  get textContent() { return this.nodeType === 3 ? this.nodeValue : this.childNodes.map(n => n.textContent).join(''); }
  set textContent(value) {
    if (this.nodeType === 3) this.nodeValue = String(value);
    else { this.childNodes.forEach(n => n.parentNode = null); this.childNodes = []; if (value !== '') this.appendChild(new DomNode(3, '', String(value))); }
  }
  appendChild(node) {
    if (node.nodeType === 11) { while (node.firstChild) this.appendChild(node.firstChild); return node; }
    if (node.parentNode) node.parentNode.removeChild(node);
    this.childNodes.push(node); node.parentNode = this; return node;
  }
  removeChild(node) {
    const at = this.childNodes.indexOf(node); if (at < 0) throw Error('removeChild missing node');
    this.childNodes.splice(at, 1); node.parentNode = null; return node;
  }
  insertBefore(node, before) {
    if (node.parentNode) node.parentNode.removeChild(node);
    const at = this.childNodes.indexOf(before); if (at < 0) throw Error('insertBefore missing node');
    this.childNodes.splice(at, 0, node); node.parentNode = this; return node;
  }
  replaceChild(node, old) { this.insertBefore(node, old); this.removeChild(old); if (node.nodeType === 11) {
    // DocumentFragment itself is never retained in a browser tree.
    while (node.firstChild) this.insertBefore(node.firstChild, node); this.removeChild(node);
  } return old; }
  matches(selector) { return selector.split(',').some(s => {
    s = s.trim(); return s.startsWith('.') ? this.className.split(/\s+/).includes(s.slice(1)) :
      s.startsWith('#') ? this.id === s.slice(1) : this.tagName.toLowerCase() === s.toLowerCase();
  }); }
  closest(selector) { for (let n = this; n; n = n.parentElement) if (n.matches(selector)) return n; return null; }
  querySelectorAll(selector) { const out = []; const visit = n => n.childNodes.forEach(c => {
    if (c.nodeType === 1 && c.matches(selector)) out.push(c); visit(c);
  }); visit(this); return out; }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  normalize() {
    for (let i = 0; i < this.childNodes.length; i++) {
      const n = this.childNodes[i]; n.normalize();
      while (n.nodeType === 3 && this.childNodes[i + 1]?.nodeType === 3) {
        n.nodeValue += this.childNodes[i + 1].nodeValue; this.removeChild(this.childNodes[i + 1]);
      }
      if (n.nodeType === 3 && !n.nodeValue) { this.removeChild(n); i--; }
    }
  }
  addEventListener() {} removeEventListener() {} setAttribute(k, v) { this[k] = String(v); }
  getAttribute(k) { return this[k] ?? null; } removeAttribute(k) { delete this[k]; }
  getBoundingClientRect() { return {left: 0, top: 0, right: 400, bottom: 800, width: 400, height: 800}; }
  getClientRects() { return []; }
}
const body = new DomNode(1, 'body'), head = new DomNode(1, 'head'), root = new DomNode(1, 'html');
root.appendChild(head); root.appendChild(body);
function makePart(part) {
  if (typeof part === 'string') return new DomNode(3, '', part);
  const node = new DomNode(1, part.tag || 'em'); node.id = part.id || '';
  (part.parts || [part.text || '']).forEach(p => node.appendChild(makePart(p))); return node;
}
for (const row of input.rows) {
  const node = new DomNode(1, row.tag || 'p'); node.id = row.id;
  row.parts.forEach(p => node.appendChild(makePart(p))); body.appendChild(node);
}
const document = {
  body, head, documentElement: root, hidden: true,
  createElement: tag => new DomNode(1, tag), createTextNode: s => new DomNode(3, '', s),
  createDocumentFragment: () => new DomNode(11),
  getElementById: id => root.querySelector('#' + id),
  querySelector: s => s === ':root' ? root : root.querySelector(s),
  querySelectorAll: s => root.querySelectorAll(s), addEventListener() {},
  createTreeWalker(node) {
    const nodes = []; const visit = n => n.childNodes.forEach(c => { if (c.nodeType === 3) nodes.push(c); else visit(c); });
    visit(node); let index = 0; return {currentNode: node, nextNode() { return this.currentNode = nodes[index++] || null; }};
  }
};
const context = vm.createContext({document, console, Node: {TEXT_NODE: 3, ELEMENT_NODE: 1}, NodeFilter: {SHOW_TEXT: 4},
  innerWidth: 400, innerHeight: 800, requestAnimationFrame() {}, cancelAnimationFrame() {},
  setTimeout() {}, clearTimeout() {}, addEventListener() {}, ReaderBridge: new Proxy({}, {get: () => () => {}})});
context.window = context;
vm.runInContext(fs.readFileSync(process.argv[2], 'utf8'), context, {timeout: 10000});
const before = Object.fromEntries(input.rows.map(row => [row.id, document.getElementById(row.id).textContent]));
const results = [];
for (const action of input.actions) {
  vm.runInContext(action.script, context, {timeout: 10000});
  const rows = {};
  for (const row of input.rows) {
    const node = document.getElementById(row.id), marks = node.querySelectorAll('.lr-saved-word, .lr-due-word');
    if (node.textContent !== before[row.id]) throw Error('Source text changed: ' + row.id);
    if (marks.some(mark => mark.querySelectorAll('.lr-saved-word, .lr-due-word').length)) throw Error('Nested marks: ' + row.id);
    rows[row.id] = {saved: marks.filter(m => m.className === 'lr-saved-word').map(m => m.textContent),
      due: marks.filter(m => m.className === 'lr-due-word').map(m => m.textContent)};
  }
  results.push(rows);
}
console.log(JSON.stringify(results));
