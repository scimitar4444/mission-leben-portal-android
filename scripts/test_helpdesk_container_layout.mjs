import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../app/src/main/java/de/missionleben/portal/web/HelpdeskContainerLayout.kt', import.meta.url), 'utf8');
const template = source.match(/const val SCRIPT = """([\s\S]*?)"""/)[1];
const defaultLabel = 'Datei hochladen / Foto machen';

function fixture({ origin = 'https://it-service.mission-leben.de:8443',
  path = '/otobo/customer.pl', frame = false, early = false, label = defaultLabel } = {}) {
  const styles = [], observers = [], labels = [], listeners = new Map();
  let writes = 0;
  const heading = { textContent: 'IT-Service – Mission Leben', setAttribute() {} };
  const root = { appendChild: style => { styles.push(style); } };
  const untouched = { input: { type: 'file', multiple: true, value: '' },
    icons: ['spinner', 'save'], progress: 'Uploading', error: 'Upload failed', ticketTitle: 'My ticket' };
  const document = {
    documentElement: early ? null : root,
    readyState: early ? 'loading' : 'complete',
    querySelector: selector => selector === '#oooHeader .oooWithSub h1' ? heading : null,
    querySelectorAll: selector => selector === '.DnDUpload .ReadyForUpload' ? labels : [],
    createElement: () => ({}),
    getElementById: id => styles.find(style => style.id === id),
    addEventListener: (key, handler) => listeners.set(key, handler),
  };
  const window = {};
  window.top = frame ? {} : window;
  const context = vm.createContext({ document, window, location: { origin, pathname: path },
    MutationObserver: class {
      constructor(handler) { this.handler = handler; }
      observe(target, options) { observers.push({ handler: this.handler, target, options }); }
    },
  });
  return {
    styles, observers, labels, untouched, heading, get writes() { return writes; },
    addUpload() {
      let text = 'Klicken und Dateien auswählen oder hier ablegen.';
      const attributes = new Map([['aria-label', 'Klicken Sie, um eine Datei auszuwählen.']]);
      const button = { getAttribute: name => attributes.get(name),
        setAttribute: (name, value) => { writes++; attributes.set(name, value); } };
      const ready = { get textContent() { return text; },
        set textContent(value) { writes++; text = value; },
        closest: selector => selector === '.DnDUpload' ? button : null };
      labels.push(ready);
      return { ready, button };
    },
    run: () => vm.runInContext(template.replace('__ML_UPLOAD_LABEL_JSON__', () => JSON.stringify(label)), context),
    async mutate() { observers.forEach(({ handler }) => { handler(); handler(); }); await Promise.resolve(); },
    createRoot() { document.documentElement = root; listeners.get('DOMContentLoaded')?.(); },
  };
}

for (const origin of ['https://it-service.mission-leben.de', 'https://it-service.mission-leben.de:8443']) {
  const f = fixture({ origin });
  const first = f.addUpload();
  const unchanged = structuredClone(f.untouched);
  f.run();
  assert.equal(first.ready.textContent, defaultLabel);
  assert.equal(first.button.getAttribute('aria-label'), defaultLabel);
  assert.equal(f.heading.textContent, 'IT Hilfe');
  assert.equal(f.styles.length, 1);
  const initialWrites = f.writes;
  f.run(); await f.mutate();
  assert.equal(f.writes, initialWrites, 'Idempotent writes do not create mutation loops');
  assert.equal(f.observers.length, 1, 'Page-finished and document-start share one observer');
  assert.equal(f.observers[0].options.attributes, undefined, 'Does not observe its own aria-label changes');
  const second = f.addUpload(); await f.mutate();
  assert.equal(second.ready.textContent, defaultLabel, 'Ajax-created control gets the same label');
  assert.deepEqual(f.untouched, unchanged, 'Input, icons, progress, errors and ticket content are untouched');
  assert.equal(f.styles.length, 1);
}

const early = fixture({ early: true }); early.run();
assert.equal(early.styles.length, 0);
early.createRoot(); const late = early.addUpload(); await early.mutate();
assert.equal(late.ready.textContent, defaultLabel, 'Injection before DOM creation works');
assert.equal(early.styles.length, 1);
for (const options of [
  { origin: 'https://it-service.mission-leben.de.evil.test' },
  { origin: 'http://it-service.mission-leben.de' },
  { origin: 'https://it-service.mission-leben.de:8444' },
  { origin: 'https://id.mission-leben.de' },
  { path: '/otobo/index.pl' }, { path: '/otobo/customer.pl/other' }, { frame: true },
]) {
  const f = fixture(options); const upload = f.addUpload(); f.run(); await f.mutate();
  assert.notEqual(upload.ready.textContent, defaultLabel);
  assert.equal(f.styles.length, 0); assert.equal(f.observers.length, 0);
}
const hostileLabel = 'Upload "file" \\ /\nphoto\u2028__ML_UPLOAD_LABEL_JSON__ <script> $&';
const escaped = fixture({ label: hostileLabel }); const upload = escaped.addUpload(); escaped.run();
assert.equal(upload.ready.textContent, hostileLabel, 'Label stays literal, not executable HTML or script');
console.log('Helpdesk layout: localized upload label, Ajax, early injection, idempotence, guards and unchanged ticket controls passed.');
