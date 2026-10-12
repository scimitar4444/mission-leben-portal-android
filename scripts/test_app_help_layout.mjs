import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../app/src/main/java/de/missionleben/portal/web/AppHelpLayout.kt', import.meta.url), 'utf8');
const script = source.match(/const val SCRIPT = """([\s\S]*?)"""/)[1].replaceAll("${'$'}", '$');
const helpPath = '/apps/collectives/User-Guides-7/Android-App-95685099';

function fixture({ origin = 'https://nextcloud.mission-leben.de', path = helpPath, frame = false, rootReady = true } = {}) {
  const styles = [], observers = [], attributes = new Map(), listeners = new Map();
  const root = {
    setAttribute: (key, value) => attributes.set(key, value),
    removeAttribute: key => attributes.delete(key),
    appendChild: style => styles.push(style),
  };
  const document = {
    documentElement: rootReady ? root : null,
    head: rootReady ? root : null,
    getElementById: id => styles.find(style => style.id === id),
    createElement: () => ({}),
  };
  const window = { addEventListener: (key, handler) => listeners.set(key, handler) };
  window.top = frame ? {} : window;
  const location = { origin, pathname: path };
  const context = vm.createContext({
    window, location, document,
    MutationObserver: class {
      constructor(handler) { this.handler = handler; }
      observe(target, options) { observers.push({ handler: this.handler, target, options }); }
    },
  });
  return {
    styles, attributes, observers,
    run: () => vm.runInContext(script, context),
    mutate: () => observers.forEach(({ handler }) => handler()),
    navigate: (path, event = 'popstate') => { location.pathname = path; listeners.get(event)?.(); },
    createRoot: () => { document.documentElement = root; document.head = root; },
  };
}

for (const path of [helpPath, '/apps/collectives/User-Guides-7', '/index.php' + helpPath,
  '/apps/collectives/User-Guides-7/Personliches-Handy-95685102']) {
  const f = fixture({ path });
  f.run(); f.mutate(); f.run();
  assert.ok(f.attributes.has('data-ml-app-help-embedded'), path);
  assert.equal(f.styles.length, 1, 'Fallback and SPA updates do not duplicate the style');
  assert.equal(f.observers.length, 1, 'Document-start/page-finished are idempotent');
  assert.equal(f.observers[0].options.attributes, undefined, 'Our attribute writes do not trigger observer loops');
  assert.match(f.styles[0].textContent, /#header \{ display: none !important;/);
  assert.match(f.styles[0].textContent, /--header-height: 0px/);
  assert.match(f.styles[0].textContent, /margin-top: 0 !important/);
  f.navigate('/apps/files/');
  assert.equal(f.attributes.has('data-ml-app-help-embedded'), false, 'Leaving help restores Nextcloud chrome');
  f.navigate(path, 'hashchange');
  assert.ok(f.attributes.has('data-ml-app-help-embedded'), 'Returning restores the embedded layout');
  assert.equal(f.styles.length, 1);
}

for (const path of ['/apps/collectives/User-Guides-70/Readme', '/apps/collectives/Other-8/Readme',
  '/login', '/apps/spreed/', '/not-apps/collectives/User-Guides-7/']) {
  const f = fixture({ path }); f.run(); f.mutate();
  assert.equal(f.attributes.has('data-ml-app-help-embedded'), false, path);
}
for (const origin of ['http://nextcloud.mission-leben.de', 'https://nextcloud.mission-leben.de:8443',
  'https://nextcloud.mission-leben.de.evil.test', 'https://id.mission-leben.de']) {
  const f = fixture({ origin }); f.run();
  assert.equal(f.styles.length, 0, origin);
  assert.equal(f.observers.length, 0, origin);
}
const iframe = fixture({ frame: true }); iframe.run();
assert.equal(iframe.styles.length, 0, 'Only the main document is affected');
const early = fixture({ rootReady: false }); early.run();
assert.equal(early.styles.length, 0);
early.createRoot(); early.mutate();
assert.ok(early.attributes.has('data-ml-app-help-embedded'), 'Document-start works before the root exists');
assert.equal(early.styles.length, 1);
console.log('App-help layout JavaScript: origin/path guards, early injection, idempotence and SPA restoration passed.');
