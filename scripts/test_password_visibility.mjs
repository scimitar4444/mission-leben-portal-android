import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../app/src/main/java/de/missionleben/portal/web/PasswordVisibilityPolicy.kt', import.meta.url), 'utf8');
const template = source.match(/internal const val SCRIPT = """([\s\S]*?)"""/)[1];
const script = template.replace(/__ML_(ORIGIN|SHOW|HIDE)_JSON__/g, (_, key) => JSON.stringify({
  ORIGIN: 'https://id.example.org', SHOW: 'Passwort anzeigen', HIDE: 'Passwort verbergen',
}[key]));

function fixture({ origin = 'https://id.example.org', path = '/if/flow/login/', frame = false } = {}) {
  const observers = [];
  const tasks = [];
  const mutate = (target, kind, attribute) => {
    for (const observer of observers) {
      if (observer.root !== target && !observer.root.querySelectorAll('*').includes(target)) continue;
      if (kind === 'attributes' && !observer.options.attributeFilter.includes(attribute)) continue;
      tasks.push(observer.callback);
    }
  };
  class Target {
    listeners = new Map();
    addEventListener(type, listener) {
      if (!this.listeners.has(type)) this.listeners.set(type, []);
      this.listeners.get(type).push(listener);
    }
    dispatch(type) {
      const event = { defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
      for (const listener of this.listeners.get(type) ?? []) listener(event);
      return event;
    }
  }
  class Node extends Target {
    constructor(tagName = '') {
      super();
      this.tagName = tagName;
      this.children = [];
      this.attributes = new Map();
      this.style = {};
      this.className = '';
      this.id = '';
      this._type = '';
      this._disabled = false;
      this.classList = { contains: name => this.className.split(' ').includes(name) };
    }
    get value() { throw new Error('The enhancement must never read the password'); }
    get type() { return this._type; }
    set type(type) { this._type = type; mutate(this, 'attributes', 'type'); }
    get disabled() { return this._disabled; }
    set disabled(disabled) { this._disabled = disabled; mutate(this, 'attributes', 'disabled'); }
    get parentElement() { return this.parent; }
    get isConnected() { return this === document || Boolean(this.parent?.isConnected || this.host?.isConnected); }
    setAttribute(name, value) { this.attributes.set(name, value); mutate(this, 'attributes', name); }
    getAttribute(name) { return this.attributes.get(name); }
    appendChild(node) {
      node.remove();
      node.parent = this;
      this.children.push(node);
      mutate(this, 'childList');
      return node;
    }
    remove() {
      if (!this.parent) return;
      const parent = this.parent;
      parent.children = parent.children.filter(node => node !== this);
      this.parent = undefined;
      mutate(parent, 'childList');
    }
    querySelectorAll(selector) {
      const nodes = this.children.flatMap(node => [node, ...node.querySelectorAll('*')]);
      return selector === '*' ? nodes : nodes.filter(node => node.tagName === selector.toUpperCase());
    }
  }
  const document = new Node();
  document.createElement = tag => new Node(tag.toUpperCase());
  const window = new Target();
  window.top = frame ? {} : window;
  const location = { origin, pathname: path };
  const context = vm.createContext({
    document, window, location,
    queueMicrotask: task => tasks.push(task),
    MutationObserver: class {
      constructor(callback) { this.callback = callback; }
      observe(root, options) { Object.assign(this, { root, options }); observers.push(this); }
    },
  });
  const flush = () => {
    let count = 0;
    while (tasks.length) {
      assert.ok(++count < 200, 'Mutation observation must settle, not loop');
      tasks.shift()();
    }
  };
  const field = (root = document, type = 'password', nativeEye = false) => {
    const group = root.appendChild(new Node('DIV'));
    group.className = 'pf-c-input-group';
    const input = group.appendChild(new Node('INPUT'));
    input.type = type;
    input.id = 'password-input';
    if (nativeEye) group.appendChild(new Node('BUTTON'));
    return { group, input, button: () => group.querySelectorAll('button')[0] };
  };
  const shadow = (root = document) => {
    const host = root.appendChild(new Node('AK-STAGE-PASSWORD'));
    host.shadowRoot = new Node();
    host.shadowRoot.host = host;
    return { host, root: host.shadowRoot };
  };
  const run = () => { vm.runInContext(script, context); flush(); };
  return { document, window, location, field, shadow, run, flush, observers };
}

const f = fixture();
const password = f.field();
const otp = f.field(f.document, 'text');
f.run();
assert.equal(password.button().type, 'button', 'eye must never submit the form');
assert.equal(password.button().getAttribute('aria-label'), 'Passwort anzeigen');
assert.equal(password.button().getAttribute('aria-controls'), 'password-input');
assert.equal(password.input.type, 'password');
assert.equal(otp.button(), undefined, 'TOTP is not a password field');
assert.equal(password.button().dispatch('click').defaultPrevented, true);
f.flush();
assert.equal(password.input.type, 'text');
assert.equal(password.button().getAttribute('aria-label'), 'Passwort verbergen');
assert.equal(password.button().getAttribute('aria-pressed'), 'true');
password.button().dispatch('click');
f.flush();
assert.equal(password.input.type, 'password');
f.run();
assert.equal(password.group.querySelectorAll('button').length, 1, 'page-finished fallback must be idempotent');

for (const [target, type] of [[f.window, 'blur'], [f.window, 'pagehide'],
  [f.document, 'submit'], [f.document, 'reset'], [f.document, 'visibilitychange']]) {
  password.button().dispatch('click');
  f.document.hidden = true;
  target.dispatch(type);
  f.flush();
  assert.equal(password.input.type, 'password', `${type} must re-mask`);
}
password.button().dispatch('click');
f.window.__mlPasswordVisibility.hideAll();
f.flush();
assert.equal(password.input.type, 'password', 'Android onPause re-masks');

const shadow = f.shadow();
const nestedShadow = f.shadow(shadow.root);
const dynamic = f.field(nestedShadow.root);
f.flush();
assert.ok(dynamic.button(), 'late Authentik stages and nested shadow roots are enhanced');
dynamic.button().dispatch('click');
f.flush();
nestedShadow.root.dispatch('submit');
f.flush();
assert.equal(dynamic.input.type, 'password', 'shadow form submission re-masks');
dynamic.input.disabled = true;
f.flush();
assert.equal(dynamic.button().disabled, true);
dynamic.button().dispatch('click');
assert.equal(dynamic.input.type, 'password', 'disabled fields cannot be revealed');
dynamic.input.disabled = false;
f.flush();
assert.equal(dynamic.button().disabled, false);

password.button().remove();
f.flush();
assert.equal(password.group.querySelectorAll('button').length, 1, 'restore a control removed by a SPA re-render');
const lateNative = f.field();
f.flush();
lateNative.button().dispatch('click');
lateNative.group.appendChild(f.document.createElement('button'));
f.flush();
assert.equal(lateNative.group.querySelectorAll('button').length, 1, 'yield to a native eye added later');
assert.equal(lateNative.input.type, 'password');

const native = f.field(f.document, 'password', true);
f.flush();
assert.equal(native.group.querySelectorAll('button').length, 1, 'keep native Authentik eye');
password.button().dispatch('click');
shadow.host.remove();
f.flush();
f.window.__mlPasswordVisibility.hideAll();
assert.equal(password.input.type, 'password');
assert.equal(f.observers.length, 3, 'one observer per root, not per refresh');

f.location.pathname = '/if/user/';
f.window.__mlPasswordVisibility.refresh();
f.flush();
assert.equal(password.button(), undefined, 'remove enhancement outside a flow');

for (const options of [{ origin: 'https://evil.example' }, { origin: 'http://id.example.org' },
  { path: '/if/admin/' }, { path: '/if/flow-lookalike/' }, { frame: true }]) {
  const denied = fixture(options);
  const field = denied.field();
  denied.run();
  assert.equal(field.button(), undefined, 'wrong origin, path or subframe must not be enhanced');
  assert.equal(denied.observers.length, 0);
}
console.log('Password visibility: toggle, no secret access/submission, masking, shadow DOM, native eye, disabled fields, idempotence and origin/frame guards passed');
