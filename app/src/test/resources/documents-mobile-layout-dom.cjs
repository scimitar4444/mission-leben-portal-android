// Runs the exact injected script against a small DOM model. No network or browser automation.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname,
  '../../main/java/de/missionleben/portal/web/DocumentsMobileLayout.kt'), 'utf8');
const script = source.match(/const val SCRIPT = """([\s\S]*?)"""/)[1];
class Element {
  constructor() { this.attributes = new Map(); this.children = []; this.textContent = ''; }
  getAttribute(name) { return this.attributes.has(name) ? this.attributes.get(name) : null; }
  setAttribute(name, value) { this.attributes.set(name, value); }
  removeAttribute(name) { this.attributes.delete(name); }
  appendChild(child) { this.children.push(child); child.parent = this; }
  get firstChild() { return this.children[0] || null; }
  insertBefore(child, before) {
    const index = this.children.indexOf(before);
    if (index < 0) this.appendChild(child); else { this.children.splice(index, 0, child); child.parent = this; }
  }
  replaceChildren(...children) { this.children = []; children.forEach(child => this.appendChild(child)); }
  remove() { this.parent.children = this.parent.children.filter(child => child !== this); }
  querySelector(selector) {
    if (selector === '[data-ml-file-type]') return this.children.find(child => child.getAttribute('data-ml-file-type') !== null) || null;
    return null;
  }
}
class SidebarTrigger extends Element {
  constructor() {
    super();
    this.icon = new Element();
    this.icon.setAttribute('class', 'lucide lucide-panel-left');
    this.icon.setAttribute('viewBox', '0 0 24 24');
    this.icon.appendChild(new Element());
    this.text = Object.assign(new Element(), {textContent:'Toggle Sidebar'});
    this.onclick = () => 'framework sidebar handler';
  }
  querySelector(selector) {
    if (selector === 'svg.lucide-panel-left') return this.icon.getAttribute('class') === 'lucide lucide-panel-left' ? this.icon : null;
    if (selector === '.sr-only') return this.text;
    return super.querySelector(selector);
  }
}
class Button extends Element {
  constructor(label, icon = '') {
    super();
    if (label) this.setAttribute('aria-label', label);
    this.disabled = false;
    this.icon = icon;
  }
  getAttribute(name) { return this.attributes.has(name) ? this.attributes.get(name) : null; }
  setAttribute(name, value) { this.attributes.set(name, value); }
  removeAttribute(name) { this.attributes.delete(name); }
  querySelector(selector) { return selector === 'svg.lucide-eye' && this.icon === 'eye' ? {} : null; }
}
class Row {
  constructor(label, id = null, prefix = '') {
    this.thumbnail = new Button(label);
    this.eye = new Button(null, 'eye');
    this.download = new Button(null, 'download');
    this.comments = new Button(null, 'message-circle');
    this.buttons = [this.thumbnail, this.eye, this.download, this.comments];
    this.title = new Element();
    this.links = [];
    if (id !== null) {
      const link = new Element(); link.setAttribute('href', `${prefix}/files/${id}/download`); this.links.push(link);
    }
  }
  querySelector(selector) { return selector === 'p.font-medium' ? this.title : null; }
  querySelectorAll(selector) {
    if (selector === 'a[href]') return this.links;
    if (selector === 'button') return this.buttons;
    const attribute = selector.match(/^button\[(.+)\]$/)?.[1];
    return attribute ? this.buttons.filter(button => button.getAttribute(attribute) !== null) : [];
  }
}
function page(rows, origin = 'https://dokumente.mission-leben.de', iframe = false, metadata = undefined, bootstrapTag = 'DIV', metadataKey = 'files') {
  const styles = new Map(), callbacks = [], queued = [], listeners = new Map();
  const triggers = [new SidebarTrigger()];
  const root = metadata === undefined ? null : new Element();
  if (root) {
    root.tagName = bootstrapTag;
    const json = JSON.stringify({ component: metadataKey === 'latest_files' ? 'portal/dashboard' : 'portal/my-files', props: { [metadataKey]: metadata } });
    if (bootstrapTag === 'SCRIPT') root.textContent = json; else root.setAttribute('data-page', json);
  }
  const document = {
    documentElement: Object.assign(new Element(), { lang: 'de' }),
    greetingHeading: Object.assign(new Element(), { textContent: 'Hallo, Thiele, Christian' }),
    getElementById: id => styles.get(id),
    createElement: () => new Element(),
    createElementNS: (ns, tag) => {
      assert.equal(ns, 'http://www.w3.org/2000/svg');
      assert.ok(['svg', 'path', 'text'].includes(tag));
      const element = new Element(); element.tagName = tag; return element;
    },
    head: { appendChild: style => styles.set(style.id, style) },
    querySelector: selector => {
      if (selector === 'main .mb-8[class~="space-y-0.5"] > h1') return document.greetingHeading;
      assert.equal(selector, '[data-page]'); return root;
    },
    addEventListener: (name, callback) => listeners.set(name, callback),
    querySelectorAll: selector => {
      if (selector === 'button[data-sidebar="trigger"]') return triggers;
      assert.match(selector, /^main /); return rows;
    }
  };
  const window = {}; window.top = iframe ? {} : window;
  const context = vm.createContext({ document, window, location: { origin }, URL,
    MutationObserver: class { constructor(callback) { callbacks.push(callback); } observe() {} },
    queueMicrotask: callback => queued.push(callback) });
  const run = () => vm.runInContext(script, context);
  const mutate = () => { callbacks.forEach(callback => callback()); while (queued.length) queued.shift()(); };
  run();
  const navigate = (files, key = 'files') => { listeners.get('inertia:navigate')({detail: {page: {
    component: key === 'latest_files' ? 'portal/dashboard' : 'portal/my-files', props: {[key]:files}
  }}}); mutate(); };
  return { styles, callbacks, run, mutate, navigate, document, triggers };
}
function isHidden(button) { return button.getAttribute('data-ml-pdf-preview') !== null; }
const pdf = new Row('304-5653332-2397948.pdf in der Vorschau öffnen');
const image = new Row('report.pdf.jpg in der Vorschau öffnen');
const audio = new Row('Preview meeting.mp3');
const rows = [pdf, image, audio];
const p = page(rows);
const menu = p.triggers[0], handler = menu.onclick;
assert.deepEqual(menu.icon.children.map(path => path.getAttribute('d')), ['M4 6h16', 'M4 12h16', 'M4 18h16']);
assert.equal(menu.icon.getAttribute('viewBox'), '0 0 24 24');
assert.equal(menu.icon.getAttribute('data-ml-hamburger'), '');
assert.equal(menu.icon.getAttribute('aria-hidden'), 'true');
assert.equal(menu.getAttribute('aria-label'), 'Menü öffnen');
assert.equal(menu.text.textContent, 'Menü öffnen');
assert.match(p.styles.get('ml-documents-mobile-layout').textContent,
  /\[data-sidebar="footer"\] > \[data-sidebar="menu"\] \{ display: none !important/);
assert.doesNotMatch(p.styles.get('ml-documents-mobile-layout').textContent,
  /\[data-sidebar="footer"\] \{ display: none/, 'vendor attribution outside account menu stays visible');
p.mutate(); p.run();
assert.equal(menu.onclick, handler, 'the existing sidebar click handler is untouched');
assert.equal(menu.icon.children.length, 3, 'repeated observer updates never duplicate hamburger paths');
const replacementMenu = new SidebarTrigger(); p.triggers.push(replacementMenu); p.mutate();
assert.equal(replacementMenu.icon.getAttribute('data-ml-hamburger'), '', 'SPA/remounted sidebar gets the same icon');
p.document.documentElement.lang = 'en'; p.mutate();
assert.equal(menu.getAttribute('aria-label'), 'Open menu');
p.document.documentElement.lang = 'de'; p.mutate();
assert.equal(p.document.documentElement.getAttribute('data-ml-client-documents-dashboard'), null);
const greeting = page([], undefined, false, [], 'DIV', 'latest_files');
assert.equal(greeting.document.documentElement.getAttribute('data-ml-client-documents-dashboard'), '');
assert.equal(greeting.document.greetingHeading.textContent, 'Meine Dokumente');
assert.equal(p.document.greetingHeading.textContent, 'Hallo, Thiele, Christian', 'other page headings are untouched');
assert.match(greeting.styles.get('ml-documents-mobile-layout').textContent,
  /\[data-ml-client-documents-dashboard\] main \.mb-8\[class~="space-y-0\.5"\]:has\(> h1\)/);
greeting.navigate([]);
assert.equal(greeting.document.documentElement.getAttribute('data-ml-client-documents-dashboard'), null,
  'my-files title and forms must remain visible after SPA navigation');
greeting.navigate([], 'latest_files');
assert.equal(greeting.document.documentElement.getAttribute('data-ml-client-documents-dashboard'), '',
  'returning to the client overview hides only its repeated greeting');
assert.equal(isHidden(pdf.eye), true);
assert.equal(pdf.thumbnail.disabled, true);
assert.equal(pdf.thumbnail.getAttribute('tabindex'), '-1');
assert.equal(pdf.thumbnail.getAttribute('aria-hidden'), 'true');
assert.equal(isHidden(pdf.download), false);
assert.equal(pdf.download.disabled, false);
assert.equal(isHidden(pdf.comments), false);
assert.equal(isHidden(image.eye), false);
assert.equal(image.thumbnail.disabled, false);
assert.equal(isHidden(audio.eye), false);
assert.equal(audio.thumbnail.disabled, false);
assert.match(p.styles.get('ml-documents-mobile-layout').textContent, /button\[data-ml-pdf-preview\].*display: none/);
p.run();
assert.equal(p.callbacks.length, 1, 'page-finished fallback must not duplicate the observer');
const added = new Row('Preview Quarterly.PDF'); rows.push(added); p.mutate();
assert.equal(isHidden(added.eye), true, 'SPA insertion gets the same policy');
pdf.thumbnail.setAttribute('aria-label', 'picture.png in der Vorschau öffnen'); p.mutate();
assert.equal(isHidden(pdf.eye), false, 'reused PDF row restores image preview');
assert.equal(pdf.thumbnail.disabled, false);
assert.equal(pdf.thumbnail.getAttribute('tabindex'), null);
assert.equal(pdf.thumbnail.getAttribute('aria-hidden'), null);
pdf.thumbnail.setAttribute('tabindex', '2');
pdf.thumbnail.setAttribute('aria-hidden', 'false');
pdf.thumbnail.disabled = true;
pdf.thumbnail.setAttribute('aria-label', 'Preview next.pdf'); p.mutate();
pdf.thumbnail.setAttribute('aria-label', 'Preview next.png'); p.mutate();
assert.equal(pdf.thumbnail.disabled, true, 'preserve originally disabled buttons');
assert.equal(pdf.thumbnail.getAttribute('tabindex'), '2');
assert.equal(pdf.thumbnail.getAttribute('aria-hidden'), 'false');
for (const label of ['Preview a.pdf', 'a.PDF in der Vorschau öffnen', 'Podgląd a.pdf',
  'a.pdf dosyasını önizle', 'Переглянути a.pdf']) {
  const row = new Row(label); page([row]); assert.equal(isHidden(row.eye), true, label);
}
for (const label of ['Preview a.pdf.png', 'a.pdf.jpg in der Vorschau öffnen',
  'Preview a.pdf.txt', 'a.pdf.jpg dosyasını önizle', 'file.pdf', 'Preview pdf']) {
  const row = new Row(label); page([row]); assert.equal(isHidden(row.eye), false, label);
}
for (const [origin, iframe] of [['https://id.mission-leben.de', false],
  ['https://dokumente.mission-leben.de.evil.invalid', false],
  ['https://dokumente.mission-leben.de', true]]) {
  const row = new Row('Preview a.pdf'); const rejected = page([row], origin, iframe);
  assert.equal(isHidden(row.eye), false); assert.equal(rejected.styles.size, 0);
  assert.equal(rejected.triggers[0].icon.getAttribute('class'), 'lucide lucide-panel-left');
  assert.equal(rejected.triggers[0].getAttribute('aria-label'), null);
}
console.log('Documents mobile DOM checks passed: PDF only, images/downloads preserved, SPA/locale/restoration/origin guards.');
const typedPdf = new Row('Preview safely-renamed.dat', 11);
const typedText = new Row(null, 12);
const typedImage = new Row('Preview misleading.pdf', 13);
const foreign = new Row('Preview example.jpg', 14, 'https://evil.invalid');
const typedRows = [typedPdf, typedText, typedImage, foreign];
// A file-selection checkbox must never be disabled as though it were a thumbnail.
const checkbox = new Button('Select safely-renamed'); typedPdf.buttons.unshift(checkbox);
const data = [
  {id: 11, mime_type: 'application/pdf', original_name: 'safely-renamed.dat'},
  {id: 12, mime_type: 'text/plain', original_name: 'example.txt'},
  {id: 13, mime_type: 'image/jpeg', original_name: 'misleading.pdf'},
  {id: 14, mime_type: 'image/jpeg', original_name: 'example.jpg'}
];
const typed = page(typedRows, 'https://dokumente.mission-leben.de', false, data);
assert.equal(typedPdf.title.children[0].getAttribute('aria-label'), 'PDF');
assert.equal(typedPdf.title.children[0].getAttribute('data-ml-file-kind'), 'pdf');
assert.equal(typedText.title.children[0].getAttribute('aria-label'), 'Text');
assert.equal(typedImage.title.children[0].getAttribute('aria-label'), 'Bild');
assert.equal(typedImage.title.children[0].getAttribute('data-ml-file-kind'), 'image');
for (const row of [typedPdf, typedText, typedImage]) {
  const icon = row.title.children[0];
  assert.equal(icon.getAttribute('role'), 'img', 'a visible icon also has an accessible type name');
  assert.equal(icon.textContent, '', 'no text badge beside the filename');
  assert.equal(icon.children[0].tagName, 'svg');
  assert.equal(icon.children[0].getAttribute('aria-hidden'), 'true', 'one accessible label, not duplicate SVG content');
}
assert.equal(foreign.title.children.length, 0, 'never bind metadata to a foreign link');
assert.equal(isHidden(typedPdf.eye), true, 'MIME type wins over renamed extension');
assert.equal(typedPdf.thumbnail.disabled, true);
assert.match(typed.styles.get('ml-documents-mobile-layout').textContent,
  /\.bg-card\.flex:has\(\[data-ml-file-type\]\) button\[data-ml-pdf-thumbnail\]/,
  'a confirmed PDF thumbnail is hidden only when its new type icon is present');
assert.match(typed.styles.get('ml-documents-mobile-layout').textContent,
  /\.grid > \.group:has\(\[data-ml-file-type\]\) > button\[data-ml-pdf-thumbnail\]/,
  'PDF grid placeholder is removed, not left as a second blank preview');
assert.equal(checkbox.disabled, false);
assert.equal(isHidden(typedImage.eye), false, 'image MIME wins over misleading PDF extension');
typed.mutate(); typed.run();
assert.equal(typedPdf.title.children.length, 1, 'only one badge after repeated application');
typed.navigate([{id: 11, mime_type: 'image/png', original_name: 'image.png'}]);
assert.equal(typedPdf.title.children[0].getAttribute('aria-label'), 'Bild');
assert.equal(typedPdf.title.children[0].getAttribute('data-ml-file-kind'), 'image');
assert.equal(typedPdf.thumbnail.disabled, false);
assert.equal(isHidden(typedPdf.eye), false);
assert.equal(typedText.title.children.length, 0, 'old page metadata is removed on navigation');
const scriptBootstrap = new Row(null, 12);
page([scriptBootstrap], 'https://dokumente.mission-leben.de', false, data, 'SCRIPT');
assert.equal(scriptBootstrap.title.children[0].getAttribute('aria-label'), 'Text');
const overviewPdf = new Row(null, 11), overviewText = new Row(null, 12);
const overview = page([overviewPdf, overviewText], 'https://dokumente.mission-leben.de', false, data, 'DIV', 'latest_files');
assert.equal(overviewPdf.title.children[0].getAttribute('data-ml-file-kind'), 'pdf', 'dashboard latest_files use server MIME too');
assert.equal(overviewText.title.children[0].getAttribute('data-ml-file-kind'), 'text');
const textPaths = overviewText.title.children[0].children[0].children.map(child=>child.getAttribute('d'));
assert.ok(textPaths.includes('M8 12h8'), 'text has lines, not the PDF icon');
overview.navigate([], 'latest_files');
assert.equal(overviewPdf.title.children.length, 0, 'dashboard navigation clears prior type metadata');
assert.equal(overviewText.title.children.length, 0);
for (const [mime, kind] of [['audio/mpeg','audio'],['video/mp4','video'],['application/zip','archive'],
  ['application/vnd.ms-excel','sheet'],['application/vnd.ms-powerpoint','presentation'],['application/msword','document']]) {
  const row = new Row(null, 21); page([row], 'https://dokumente.mission-leben.de', false, [{id:21,mime_type:mime,original_name:'document.dat'}]);
  assert.equal(row.title.children[0].getAttribute('data-ml-file-kind'),kind);
}
console.log('File-type icon checks passed: accessible SVG PDF/text/image and other types, dashboard/my-files, MIME precedence, SPA/history, no stale or duplicate icons, both bootstrap formats.');
