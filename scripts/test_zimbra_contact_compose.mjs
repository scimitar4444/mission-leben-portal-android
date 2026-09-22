import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../app/src/main/java/de/missionleben/portal/web/ZimbraContactCompose.kt', import.meta.url), 'utf8');
const script = source.match(/internal const val SCRIPT = """([\s\S]*?)"""/)[1]
  .replaceAll('__ORIGIN__', 'https://mail.example.org')
  .replaceAll('__RECIPIENT__', 'maria+team@example.org');

function fixture(origin = 'https://mail.example.org', pathname = '/modern/email/Inbox') {
  const listeners = [];
  const links = new Set();
  const state = { opened: [], externalNavigations: 0 };
  const window = {
    addEventListener: (_, listener) => listeners.push(listener),
    removeEventListener: (_, listener) => listeners.splice(listeners.indexOf(listener), 1),
  };
  const context = {
    window, location: { origin, pathname }, encodeURIComponent,
    MouseEvent: class {
      constructor(type, options) { Object.assign(this, { type, defaultPrevented: false }, options); }
      preventDefault() { if (this.cancelable) this.defaultPrevented = true; }
    },
    document: {
      body: { appendChild: link => links.add(link) },
      createElement: () => {
        const link = {
          remove: () => links.delete(link),
          dispatchEvent: event => {
            event.target = link;
            for (const listener of [...listeners]) listener(event);
            if (!event.defaultPrevented) state.externalNavigations++;
          },
        };
        return link;
      },
    },
  };
  const ready = () => window.addEventListener('click', event => {
    // Contract of the deployed Modern UI's handleGlobalClick/openComposer.
    const address = decodeURIComponent(event.target.href.replace(/^mailto:/, ''));
    state.opened.push(address);
    event.preventDefault();
  });
  return { run: () => vm.runInNewContext(script, context), ready, links, listeners, state };
}

const loading = fixture();
assert.equal(loading.run(), 'waiting');
assert.equal(loading.state.externalNavigations, 0);
assert.equal(loading.links.size, 0);
assert.equal(loading.listeners.length, 0);
loading.ready();
assert.equal(loading.run(), 'opened');
assert.deepEqual(loading.state.opened, ['maria+team@example.org']);
assert.equal(loading.links.size, 0);
assert.equal(loading.listeners.length, 1);
assert.equal(loading.run(), 'opened');
assert.equal(loading.state.opened.length, 1, 'must not create duplicate drafts');
assert.equal(loading.state.externalNavigations, 0);

for (const f of [fixture('https://other.example'), fixture('https://mail.example.org', '/login')]) {
  f.ready();
  assert.equal(f.run(), 'wrong-origin');
  assert.equal(f.state.opened.length, 0);
  assert.equal(f.links.size, 0);
}
console.log('Zimbra contact compose: loading/retry, recipient, no duplicate, origin and no external navigation checks passed');
