import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../app/src/main/java/de/missionleben/portal/web/LoginIdentifierPolicy.kt', import.meta.url), 'utf8');
const labels = { ORIGIN: 'https://id.example.org', HINT: 'Nur Anmeldename, keine E-Mail-Adresse.',
  INVALID: 'Bitte ohne @ eingeben.', USE: 'Diesen Anmeldenamen verwenden:',
  DENIED: 'Anmeldenamen prüfen; sonst IT fragen. Keine App-Daten löschen.',
  SUMMARY: 'Anmeldung nicht bestätigt. Namen oder Freigabe prüfen.', TECHNICAL: 'Technische Meldung anzeigen' };
const script = source.match(/internal const val SCRIPT = """([\s\S]*?)"""/)[1]
  .replaceAll("${'$'}", '$').replace(/__ML_LOGIN_(\w+)__/g, (_, key) => JSON.stringify(labels[key]));
const originalRefusal = 'Dieses Gerät oder dieser Benutzer ist für Mission Leben Zentral nicht freigegeben.';

function fixture({ origin = labels.ORIGIN, path = '/if/flow/mission-leben-android-authentication/', frame = false } = {}) {
  const tasks = [], observers = [];
  const mutate = (target, kind, attr) => {
    for (const ob of observers) {
      if (ob.root !== target && !ob.root.querySelectorAll('*').includes(target)) continue;
      if (kind === 'attributes' && !ob.options.attributeFilter.includes(attr)) continue;
      tasks.push(ob.callback);
    }
  };
  class Evt {
    constructor(type, options = {}) { Object.assign(this, options, { type, defaultPrevented: false }); }
    preventDefault() { this.defaultPrevented = true; }
    stopImmediatePropagation() { this.stopped = true; }
  }
  class Target {
    listeners = new Map();
    addEventListener(type, fn) { if (!this.listeners.has(type)) this.listeners.set(type, []); this.listeners.get(type).push(fn); }
    removeEventListener(type, fn) { this.listeners.set(type, (this.listeners.get(type) ?? []).filter(x => x !== fn)); }
    dispatchEvent(event) { event.target ??= this; for (const fn of this.listeners.get(event.type) ?? []) { fn(event); if (event.stopped) break; } return !event.defaultPrevented; }
    dispatch(type, values = {}) { const e = new Evt(type, values); this.dispatchEvent(e); return e; }
  }
  class Node extends Target {
    constructor(tagName = '') { super(); Object.assign(this, { tagName, children: [], attrs: new Map(), style: {}, _text: '', _value: '', type: '', disabled: false, validationMessage: '', focused: 0, reports: 0 }); }
    get parentElement() { return this.parent; }
    get isConnected() { return this === document || !!(this.parent?.isConnected || this.host?.isConnected); }
    getRootNode() { return this.parent ? this.parent.getRootNode() : this; }
    get textContent() { return this._text; }
    set textContent(v) { this._text = v; mutate(this, 'childList'); }
    get value() { if (this.tagName === 'INPUT' && (this.getAttribute('name') !== 'uidField' || this.type !== 'text')) throw Error('Must not read password/TOTP/helper inputs'); return this._value; }
    set value(v) { this._value = v; }
    get validity() { return { customError: !!this.validationMessage }; }
    setCustomValidity(message) { this.validationMessage = message; }
    reportValidity() { this.reports++; return !this.validationMessage; }
    focus() { this.focused++; }
    setAttribute(k, v) { this.attrs.set(k, v); mutate(this, 'attributes', k); }
    getAttribute(k) { return this.attrs.get(k) ?? null; }
    removeAttribute(k) { this.attrs.delete(k); }
    appendChild(n) { n.remove(); n.parent = this; this.children.push(n); mutate(this, 'childList'); return n; }
    remove() { if (!this.parent) return; const p = this.parent; p.children = p.children.filter(n => n !== this); this.parent = null; mutate(p, 'childList'); }
    insertAdjacentElement(where, n) { assert.equal(where, 'afterend'); n.remove(); n.parent = this.parent; this.parent.children.splice(this.parent.children.indexOf(this) + 1, 0, n); mutate(this.parent, 'childList'); }
    querySelectorAll(s) { const nodes = this.children.flatMap(n => [n, ...n.querySelectorAll('*')]); return s === '*' ? nodes : nodes.filter(n => n.tagName === s.toUpperCase()); }
    querySelector(s) { return this.querySelectorAll(s)[0] ?? null; }
    closest(s) { return this.tagName === s.toUpperCase() ? this : this.parent?.closest(s); }
  }
  const document = new Node(); document.createElement = tag => new Node(tag.toUpperCase());
  const window = new Target(); window.top = frame ? {} : window;
  const location = { origin, pathname: path };
  const context = vm.createContext({ document, window, location, Event: Evt,
    queueMicrotask: task => tasks.push(task),
    MutationObserver: class { constructor(callback) { this.callback = callback; } observe(root, options) { Object.assign(this, { root, options }); observers.push(this); } },
  });
  const flush = () => { let n = 0; while (tasks.length) { assert.ok(++n < 250, 'observer must settle'); tasks.shift()(); } };
  const shadow = (tag = 'AK-STAGE-IDENTIFICATION', parent = document) => {
    const host = parent.appendChild(new Node(tag)); host.shadowRoot = new Node(); host.shadowRoot.host = host;
    return { host, root: host.shadowRoot };
  };
  const field = (root, name = 'uidField', type = 'text') => {
    const group = root.appendChild(new Node('DIV')); const input = group.appendChild(new Node('INPUT'));
    input.setAttribute('name', name); input.type = type; return { group, input, button: () => group.querySelector('button'), hint: () => group.querySelector('p') };
  };
  const run = () => { vm.runInContext(script, context); flush(); };
  return { document, window, location, shadow, field, run, flush, observers };
}

const f = fixture(), stage = f.shadow(), name = f.field(stage.root);
f.field(stage.root, 'password', 'password'); f.field(stage.root, 'code'); f.field(f.document, 'username');
name.input.setAttribute('aria-describedby', 'server-help');
f.run(); assert.equal(name.hint().textContent, labels.HINT); assert.equal(name.button().hidden, true);
assert.ok(name.input.getAttribute('aria-describedby').startsWith('server-help '));
for (const [entered, expected] of [['@person.example', 'person.example'], ['Person.Example@MISSION-LEBEN.DE', 'Person.Example'],
  ['  person.example@akademie-mission-leben.de  ', 'person.example']]) {
  name.input.value = entered; name.input.dispatch('input');
  assert.equal(name.input.value, entered, 'never silently change account spelling');
  assert.equal(name.input.validationMessage, labels.INVALID);
  assert.equal(name.button().hidden, false);
  assert.equal(name.button().type, 'button');
  assert.equal(name.button().textContent, `${labels.USE} ${expected}`);
  assert.equal(stage.root.dispatch('keydown', {key:'Enter',target:name.button()}).defaultPrevented, false,
    'keyboard users can activate the correction button');
  for (const type of ['submit', 'keydown', 'click']) {
    const e = stage.root.dispatch(type, { key: 'Enter', target: { closest: () => ({ type: 'submit' }) } });
    assert.equal(e.defaultPrevented, true, `${type} must block malformed identifiers`);
  }
  name.button().dispatch('click'); f.flush();
  assert.equal(name.input.value, expected); assert.equal(name.input.validationMessage, '');
  assert.equal(name.button().hidden, true);
  assert.equal(stage.root.dispatch('submit').defaultPrevented, false, 'normal server submission remains in Authentik');
}
for (const bad of ['x@other.org', 'x@mission-leben.de.evil.org', '@@person.example', '@person@example.org', 'x@', '<img>@mission-leben.de']) {
  name.input.value = bad; name.input.dispatch('change');
  assert.equal(name.button().hidden, true, `do not guess ${bad}`);
  assert.equal(stage.root.dispatch('submit').defaultPrevented, true);
}
for (const valid of ['quarkholz', '123456', 'person.example', 'team.ward-2', 'person_with_underscore', 'Müller']) {
  name.input.value = valid; name.input.dispatch('input');
  assert.equal(name.input.validationMessage, '', 'syntax cannot establish whether a user exists');
  assert.equal(stage.root.dispatch('submit').defaultPrevented, false);
  assert.equal(name.input.value, valid);
}
name.input.value = '  person.example  '; name.input.dispatch('input');
stage.root.dispatch('submit'); assert.equal(name.input.value, 'person.example', 'only edge whitespace is automatic');
name.input.setCustomValidity('Server-owned validation'); name.input.dispatch('input');
assert.equal(name.input.validationMessage, 'Server-owned validation', 'keep server errors');
name.input.setCustomValidity('');
name.input.value = '@person.example'; name.input.dispatch('input'); name.input.disabled = true;
name.input.dispatch('change'); name.button().dispatch('click');
assert.equal(name.input.value, '@person.example', 'disabled inputs cannot change'); name.input.disabled = false;
name.input.dispatch('change');
f.run(); assert.equal(name.group.querySelectorAll('button').length, 1, 'fallback idempotence');
const lateStage = f.shadow(), late = f.field(lateStage.root); f.flush();
assert.ok(late.hint(), 'late shadow stages work');
late.input.type = 'password'; late.input.dispatch('input'); lateStage.root.dispatch('submit');
late.button().dispatch('click'); f.window.__mlLoginIdentifier.refresh(); f.flush();
assert.equal(late.hint(), null, 'a retyped field must never be read, even before observer cleanup');
const otpStage = f.shadow('AK-STAGE-AUTHENTICATOR-VALIDATE'); const otp = f.field(otpStage.root);
f.flush(); assert.equal(otp.hint(), null, 'do not enhance MFA, even with a similar name');
const denied = f.shadow('AK-STAGE-ACCESS-DENIED'); const form = denied.root.appendChild(f.document.createElement('form'));
const empty = form.appendChild(f.document.createElement('ak-empty-state'));
const body = empty.appendChild(f.document.createElement('div')); body.setAttribute('slot', 'body');
const refusal = body.appendChild(f.document.createElement('p')); refusal.textContent = originalRefusal;
const back = form.appendChild(f.document.createElement('a')); back.setAttribute('href', '/cancel');
f.flush(); assert.equal(refusal.textContent, labels.SUMMARY);
assert.equal(form.querySelector('details').querySelector('p').textContent, originalRefusal, 'original refusal stays available');
assert.equal(back.getAttribute('href'), '/cancel', 'preserve server navigation');
f.run(); assert.equal(form.querySelectorAll('details').length, 1);
const specific = f.shadow('AK-STAGE-ACCESS-DENIED'); const specificForm = specific.root.appendChild(f.document.createElement('form'));
const specificP = specificForm.appendChild(f.document.createElement('p')); specificP.textContent = 'Specific device revocation';
f.flush(); assert.equal(specificP.textContent, 'Specific device revocation', 'never relabel a specific block');
f.location.pathname = '/if/flow/another-flow/'; f.window.__mlLoginIdentifier.refresh(); f.flush();
assert.equal(name.hint(), null); assert.equal(name.input.validationMessage, '');
assert.equal(name.input.getAttribute('aria-describedby'), 'server-help');
assert.equal(refusal.textContent, originalRefusal, 'restore server text outside scope');
assert.equal(form.querySelector('details'), null);
for (const options of [{origin:'https://evil.example'}, {origin:'http://id.example.org'}, {frame:true},
  {path:'/if/flow/mission-leben-browser-authentication/'}, {path:'/if/admin/'},
  {path:'/if/flow/mission-leben-android-authentication/extra'}]) {
  const x = fixture(options), s = x.shadow(), n = x.field(s.root); x.run();
  assert.equal(n.hint(), null); assert.equal(x.observers.length, 0);
}
for (const forbidden of ['fetch(', 'XMLHttpRequest', 'localStorage', 'sessionStorage', 'postMessage', 'console.', 'requestSubmit(', '.submit(']) {
  assert.equal(script.includes(forbidden), false, `no ${forbidden}`);
}
console.log('Login identifier: hints, explicit suggestions, malformed submit blocking, no guessed identities, secret isolation, generic refusal wording, preserved server decisions, cleanup and origin/flow/frame guards passed');
