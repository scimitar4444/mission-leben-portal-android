// Executes the exact Kotlin-embedded script against a synthetic document. No browser/network/authentication.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/java/de/missionleben/portal/web/HelpdeskContainerLayout.kt', 'utf8');
// The production Kotlin entry point replaces this JSON label before evaluation.
const script = source.match(/const val SCRIPT = """([\s\S]*?)"""/)[1]
  .replace('__ML_UPLOAD_LABEL_JSON__', () => JSON.stringify('Datei hochladen / Foto machen'));
function page(origin = 'https://it-service.mission-leben.de:8443', pathname = '/otobo/customer.pl', embedded = false) {
  const nodes = [], events = [];
  const root = { appendChild(node) { nodes.push(node); } };
  const document = {
    documentElement: root, readyState: 'complete',
    heading: {textContent:'IT-Service – Mission Leben', setAttribute(k, v) {this[k] = v;}},
    querySelector(selector) { assert.equal(selector, '#oooHeader .oooWithSub h1'); return this.heading; },
    querySelectorAll() { return []; },
    getElementById(id) { return nodes.find(n => n.id === id); },
    createElement(tag) { assert.equal(tag, 'style'); return {}; },
    addEventListener(name, action, options) { events.push({name, action, options}); },
  };
  const window = {}; window.top = embedded ? {} : window;
  const context = vm.createContext({ window, document, location: {origin, pathname},
    MutationObserver: class { observe() {} },
  });
  return {nodes, document, events, run() { vm.runInContext(script, context); }, root};
}
for (const origin of ['https://it-service.mission-leben.de', 'https://it-service.mission-leben.de:8443']) {
  const p = page(origin); p.run(); p.run();
  assert.equal(p.document.heading.textContent, 'IT Hilfe'); assert.equal(p.document.heading.title, 'IT Hilfe');
  assert.equal(p.nodes.length, 1); assert.equal(p.nodes[0].id, 'ml-helpdesk-container-layout');
  assert.match(p.nodes[0].textContent, /#oooNavigation a\[href\*="Action=Logout"\]/);
  assert.match(p.nodes[0].textContent, /#oooNavigation a\[href\*="Action=CustomerPreferences"\]/);
  assert.match(p.nodes[0].textContent, /grid-template-rows: auto 24px/);
  assert.match(p.nodes[0].textContent, /max-width: 767px/);
  assert.match(p.nodes[0].textContent, /#oooHeader \.oooWithSub h3:not\(\.ooog\) \{ font-size: 13px/);
  assert.doesNotMatch(p.nodes[0].textContent, /#oooHeader h3\s*\{/);
  assert.match(p.nodes[0].textContent, /#oooSearchBox h3\.ooog \{ position: relative/);
  assert.match(p.nodes[0].textContent, /#oooSearchBox i\.ooofo-search[\s\S]*font-size: 20px/);
  assert.match(p.nodes[0].textContent, /top: 50% !important; left: 10px !important; transform: translateY\(-50%\)/);
  assert.match(p.nodes[0].textContent, /\.oooTile_TicketList[\s\S]*display: block !important/);
  assert.doesNotMatch(p.nodes[0].textContent, /(?:background-)?color\s*:/);
}
for (const p of [page('http://it-service.mission-leben.de'), page('https://it-service.mission-leben.de:9443'),
  page('https://id.mission-leben.de'), page('https://it-service.mission-leben.de.evil.test'),
  page(undefined, '/otobo/index.pl'), page(undefined, undefined, true)]) {
  p.run(); assert.equal(p.nodes.length, 0); assert.equal(p.events.length, 0);
  assert.equal(p.document.heading.textContent, 'IT-Service – Mission Leben');
}
const ticket = page(); ticket.document.heading.textContent = 'IT-Service – Mission Leben: ticket subject'; ticket.run();
assert.equal(ticket.document.heading.textContent, 'IT-Service – Mission Leben: ticket subject');
const early = page(); early.document.documentElement = null; early.document.readyState = 'loading'; early.run();
assert.equal(early.nodes.length, 0); assert.equal(early.events[0].name, 'DOMContentLoaded');
assert.equal(early.events[0].options.once, true);
early.document.documentElement = early.root; early.events[0].action(); early.run();
assert.equal(early.nodes.length, 1);
console.log('Helpdesk container DOM fixture passed: exact origin/path/top frame, idempotency, early document, visibility and exact dashboard label only.');
