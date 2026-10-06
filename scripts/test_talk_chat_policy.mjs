import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../app/src/main/java/de/missionleben/portal/web/TalkChatPolicy.kt', import.meta.url), 'utf8');
const script = source.match(/const val CHAT_ONLY_SCRIPT = """([\s\S]*?)"""/)[1]
  .replaceAll("${'$'}", '$');
const origin = 'https://nextcloud.mission-leben.de';
const legacyKey = 'ml-talk-last-room-path';

function fixture({ path = '/apps/spreed/', storedRoom = '/call/old_room', rooms = ['room_123', 'old_room'], failFilter = false, failStorage = false, visibleNavigation = false, navigationReady = true, startAtLastChat = false } = {}) {
  const storage = new Map([[legacyKey, storedRoom]]);
  const listeners = new Map();
  const observers = [];
  const state = { redirects: [], navigationClicks: 0, storageWrites: 0, requests: [], styles: [], navigationReady };
  const attributes = new Map();
  const navigation = { getBoundingClientRect: () => ({ right: visibleNavigation ? 300 : 0 }) };
  const toggle = { click: () => { state.navigationClicks++; } };
  const document = {
    documentElement: { setAttribute: (key, value) => attributes.set(key, value), removeAttribute: key => attributes.delete(key) },
    head: { appendChild: style => state.styles.push(style) },
    getElementById: id => state.styles.find(style => style.id === id),
    createElement: () => ({}),
    querySelector: selector => !state.navigationReady ? null : selector === '.app-navigation' ? navigation : selector === '.app-navigation-toggle' ? toggle : null,
    querySelectorAll: () => [],
    addEventListener: () => {},
  };
  const window = {
    location: { origin, pathname: path, replace: url => state.redirects.push(url) },
    localStorage: {
      getItem: key => { if (failStorage) throw new Error('storage disabled'); return storage.get(key); },
      removeItem: key => { if (failStorage) throw new Error('storage disabled'); storage.delete(key); },
      setItem: (key, value) => { if (failStorage) throw new Error('storage disabled'); state.storageWrites++; storage.set(key, value); },
    },
    addEventListener: (name, listener) => listeners.set(name, listener),
  };
  const context = vm.createContext({
    window, document, Element: class {},
    MutationObserver: class { constructor(callback) { observers.push(callback); } observe() {} disconnect() {} },
    fetch: async url => {
      state.requests.push(url);
      if (failFilter) throw new Error('room filter unavailable');
      return { ok: true, json: async () => ({ room_tokens: rooms }) };
    },
  });
  return {
    state, storage, window,
    run: () => vm.runInContext(script.replace('const restoreLastRoomOnOpen = false;', `const restoreLastRoomOnOpen = ${startAtLastChat};`), context),
    mutate: () => observers.forEach(callback => callback()),
    navigate: path => { window.location.pathname = path; listeners.get('popstate')?.(); },
    settle: async () => { for (let turn = 0; turn < 6; turn++) await Promise.resolve(); },
  };
}

for (const path of ['/apps/spreed/', '/index.php/apps/spreed/', '/apps/spreed']) {
  for (const failFilter of [false, true]) {
    const f = fixture({ path, failFilter });
    f.run();
    await f.settle();
    f.mutate();
    assert.deepEqual(f.state.redirects, [], 'opening the tile must not restore an old room');
    assert.equal(f.state.navigationClicks, 1, 'mobile conversation list opens exactly once');
    assert.equal(f.storage.get(legacyKey), '/call/old_room', 'overview mode ignores even an allowed remembered chat');
    assert.equal(f.state.storageWrites, 0);
    assert.equal(f.state.requests.length, 1);
    assert.equal(f.state.styles.length, 1);
    f.run();
    assert.equal(f.state.navigationClicks, 1, 'document-start and page-finished guards are idempotent');
  }
}

const noStorage = fixture({ failStorage: true, startAtLastChat: true });
noStorage.run();
await noStorage.settle();
assert.deepEqual(noStorage.state.redirects, []);
assert.equal(noStorage.state.navigationClicks, 1, 'disabled storage must not break the overview');

const desktop = fixture({ visibleNavigation: true });
desktop.run();
await desktop.settle();
assert.equal(desktop.state.navigationClicks, 0, 'a visible desktop list must not be closed');

const delayedNavigation = fixture({ navigationReady: false });
delayedNavigation.run();
await delayedNavigation.settle();
assert.equal(delayedNavigation.state.navigationClicks, 0);
delayedNavigation.state.navigationReady = true;
delayedNavigation.mutate();
assert.equal(delayedNavigation.state.navigationClicks, 1, 'show the list once Nextcloud finishes rendering it');
assert.deepEqual(delayedNavigation.state.redirects, []);

for (const startAtLastChat of [false, true]) {
for (const path of ['/call/room_123', '/index.php/call/room_123']) {
  const f = fixture({ path, startAtLastChat });
  f.run();
  await f.settle();
  assert.deepEqual(f.state.redirects, [], 'notification target stays in the requested allowed chat');
  assert.equal(f.state.navigationClicks, 0);
  assert.ok(f.state.storageWrites > 0, 'visited chats remain available for the optional last-chat start');
  assert.equal(f.storage.get(legacyKey), path);
  f.navigate(path.startsWith('/index.php/') ? '/index.php/apps/spreed/' : '/apps/spreed/');
  assert.equal(f.state.navigationClicks, 1);
  assert.deepEqual(f.state.redirects, [], 'returning to the list must not reopen the chat');
}
}

for (const prefix of ['', '/index.php']) {
  const lastChat = fixture({ path: `${prefix}/apps/spreed/`, storedRoom: `${prefix}/call/old_room`, startAtLastChat: true });
  lastChat.run();
  await lastChat.settle();
  lastChat.mutate();
  assert.deepEqual(lastChat.state.redirects, [`${origin}${prefix}/call/old_room`], 'last-chat setting restores one allowed room exactly once');
}

for (const storedRoom of [null, '/call/blocked_room', '/apps/files/', 'https://evil.example/call/old_room', '/call/../../private']) {
  const fallback = fixture({ storedRoom, startAtLastChat: true });
  fallback.run();
  await fallback.settle();
  assert.deepEqual(fallback.state.redirects, [], 'missing, forbidden or invalid remembered rooms fall back to the overview');
}

for (const prefix of ['', '/index.php']) {
  const forbidden = fixture({ path: `${prefix}/call/blocked_room` });
  forbidden.run();
  await forbidden.settle();
  assert.deepEqual(forbidden.state.redirects, [`${origin}${prefix}/apps/spreed/`], 'restricted rooms still return to the list');
  for (const suffix of ['not-found', 'forbidden']) {
    const unavailable = fixture({ path: `${prefix}/apps/spreed/${suffix}`, storedRoom: null });
    unavailable.run();
    assert.equal(unavailable.state.redirects[0], `${origin}${prefix}/apps/spreed/`);
  }
}

const unrelated = fixture({ path: '/apps/files/' });
unrelated.run();
await unrelated.settle();
assert.equal(unrelated.state.requests.length, 0);
assert.equal(unrelated.state.navigationClicks, 0);
assert.deepEqual(unrelated.state.redirects, []);

console.log('Talk: both start settings, overview default, mobile navigation, direct notifications, safe fallback and idempotence passed');
