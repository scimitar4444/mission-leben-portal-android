import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const root = new URL('../', import.meta.url);
const source = readFileSync(new URL('app/src/main/java/de/missionleben/portal/web/TalkVoicePolicy.kt', root), 'utf8');
const script = source.match(/const val CAPTURE_GUARD_SCRIPT = """([\s\S]*?)"""/)[1].replaceAll("${'$'}", '$');

function fixture({path = '/call/abcd_123', hidden = false, iframe = false, delayed = false} = {}) {
  const events = new Map();
  const order = [];
  const state = { requests: 0, stopped: 0, aborts: 0, resolve: null };
  const track = { stop() { state.stopped++; order.push('stop'); }, addEventListener() {} };
  const stream = { getTracks: () => [track] };
  const media = { getUserMedia: () => {
    state.requests++;
    return delayed ? new Promise(resolve => { state.resolve = () => resolve(stream); }) : Promise.resolve(stream);
  } };
  const document = {
    hidden,
    addEventListener: (name, fn) => events.set(name, fn),
    querySelectorAll: selector => {
      assert.equal(selector, '.audio-recorder button:has(.close-icon)');
      return [{ click() { state.aborts++; order.push('abort'); } }];
    },
  };
  const window = { location: { pathname: path }, addEventListener: (name, fn) => events.set(name, fn) };
  window.top = iframe ? {} : window;
  const context = vm.createContext({window, document, navigator: {mediaDevices: media}, DOMException});
  return { state, order, stream, media, document, window, events,
    run: () => vm.runInContext(script, context) };
}

for (const path of ['/call/abcd_123', '/index.php/call/abcd_123', '/apps/spreed/', '/index.php/apps/spreed/']) {
  const f = fixture({path}); assert.equal(f.run(), true);
  assert.equal(f.run(), true, 'guard must not double-wrap getUserMedia');
  assert.equal(await f.media.getUserMedia({audio: true, video: false}), f.stream);
  assert.equal(f.state.requests, 1);
  f.window.__mlStopTalkVoiceCapture();
  assert.equal(f.state.stopped, 1);
  assert.deepEqual(f.order.slice(0, 2), ['abort', 'stop'], 'cancel recording before stopping tracks; never click send');
  await assert.rejects(f.media.getUserMedia({audio: true}), {name: 'NotAllowedError'});
  f.window.__mlResumeTalkVoiceCapture();
  assert.equal(await f.media.getUserMedia({audio: true}), f.stream, 'native foreground resume re-enables capture');
}

for (const constraints of [{audio: true, video: true}, {audio: true, video: {}}, {video: true}, {}, null]) {
  const f = fixture(); f.run();
  await assert.rejects(f.media.getUserMedia(constraints), { name: 'NotAllowedError' });
  assert.equal(f.state.requests, 0);
}
for (const options of [{hidden: true}, {iframe: true}, {path: '/apps/files/'}, {path: '/apps/spreed-fake/'}]) {
  const f = fixture(options); f.run();
  await assert.rejects(f.media.getUserMedia({audio: true}), {name: 'NotAllowedError'});
  assert.equal(f.state.requests, 0);
}
for (const event of ['visibilitychange', 'pagehide', 'popstate', 'hashchange']) {
  const f = fixture(); f.run(); await f.media.getUserMedia({audio: true});
  if (event === 'visibilitychange') f.document.hidden = true;
  if (event === 'popstate' || event === 'hashchange') f.window.location.pathname = '/apps/files/';
  f.events.get(event)();
  assert.equal(f.state.stopped, 1, event);
  assert.equal(f.state.aborts, 1, event);
}
for (const hideAfterStart of [false, true]) {
  const f = fixture({delayed: true}); f.run();
  const pending = f.media.getUserMedia({audio: true});
  if (hideAfterStart) f.document.hidden = true; else f.window.__mlStopTalkVoiceCapture();
  f.state.resolve();
  await assert.rejects(pending, {name: 'NotAllowedError'});
  assert.equal(f.state.stopped, 1, 'late microphone access must be stopped without being returned to Talk');
}

const activity = readFileSync(new URL('app/src/main/java/de/missionleben/portal/web/PortalBrowserActivity.kt', root), 'utf8');
const manifest = readFileSync(new URL('app/src/main/AndroidManifest.xml', root), 'utf8');
const chatPolicy = readFileSync(new URL('app/src/main/java/de/missionleben/portal/web/TalkChatPolicy.kt', root), 'utf8');
assert.match(activity, /request\.grant\(arrayOf\(PermissionRequest\.RESOURCE_AUDIO_CAPTURE\)\)/);
assert.doesNotMatch(activity, /request\.grant\(request\.resources\)/);
assert.match(activity, /onPermissionRequestCanceled/);
assert.match(activity, /voiceDocumentStartInstalled && voiceCaptureGuardReady/);
assert.match(manifest, /android\.permission\.RECORD_AUDIO/);
assert.match(manifest, /android\.permission\.MODIFY_AUDIO_SETTINGS/,
  'Chromium requires both audio permissions before it exposes an input device');
assert.doesNotMatch(manifest, /android\.permission\.CAMERA|foregroundServiceType="microphone"/);
assert.ok(!chatPolicy.includes("'button:has(.microphone-outline-icon)'"));
for (const selector of ['.join-call', '.leave-call', '.call-button', '.talk-dashboard__actions button:has(.microphone-outline-icon)']) {
  assert.ok(chatPolicy.includes(selector));
}
console.log('Talk voice: foreground audio only, camera denied, cancel before stop, stale requests stopped, call controls retained');
