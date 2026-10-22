package de.missionleben.portal.web

import java.net.URI
import java.util.Locale

/** Input assistance only. Does not submit, authenticate, store identifiers or inspect secrets. */
internal object LoginIdentifierPolicy {
    fun script(baseUrl: String, hint: String, invalid: String, useName: String, deniedHint: String,
        deniedSummary: String, technicalLabel: String): String? {
        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return null
        if (!base.scheme.equals("https", true) || base.host == null || base.rawUserInfo != null ||
            base.port !in -1..65535) return null
        val origin = URI("https", null, base.host.lowercase(Locale.ROOT),
            if (base.port == 443) -1 else base.port, null, null, null).toASCIIString()
        val values = mapOf("ORIGIN" to origin, "HINT" to hint, "INVALID" to invalid,
            "USE" to useName, "DENIED" to deniedHint, "SUMMARY" to deniedSummary, "TECHNICAL" to technicalLabel)
        return Regex("__ML_LOGIN_(ORIGIN|HINT|INVALID|USE|DENIED|SUMMARY|TECHNICAL)__").replace(SCRIPT) {
            quote(values.getValue(it.groupValues[1]))
        }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                in '\u0000'..'\u001f', '\u2028', '\u2029' ->
                    append("\\u" + c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }

    internal const val SCRIPT = """
        (() => {
          const allowed = () => window.top === window && location.origin === __ML_LOGIN_ORIGIN__ &&
            /^\/if\/flow\/mission-leben-android-(?:authentication|authorization)\/${'$'}/.test(location.pathname);
          if (!allowed()) return;
          if (window.__mlLoginIdentifier) { window.__mlLoginIdentifier.refresh(); return; }
          const hintText = __ML_LOGIN_HINT__;
          const invalidText = __ML_LOGIN_INVALID__;
          const useText = __ML_LOGIN_USE__;
          const deniedText = __ML_LOGIN_DENIED__;
          const deniedSummary = __ML_LOGIN_SUMMARY__;
          const technicalLabel = __ML_LOGIN_TECHNICAL__;
          const genericRefusal = 'Dieses Gerät oder dieser Benutzer ist für Mission Leben Zentral nicht freigegeben.';
          const records = new Map();
          const deniedHints = new Map();
          const watched = new WeakSet();
          let number = 0;
          const text = (node, value) => { if (node.textContent !== value) node.textContent = value; };
          const stageOf = root => {
            for (let r = root; r; r = r.host?.getRootNode()) {
              if (r.host?.tagName === 'AK-STAGE-IDENTIFICATION') return r.host;
            }
            return null;
          };
          const isIdentifier = (input, root) => input.tagName === 'INPUT' && input.type === 'text' &&
            input.getAttribute('name') === 'uidField' && !!stageOf(root);
          const candidate = value => {
            const v = value.trim();
            const leading = v.match(/^@([a-z0-9][a-z0-9._-]{0,149})${'$'}/i);
            if (leading) return leading[1];
            // Offer a visible, user-confirmed spelling suggestion, never an email-to-user mapping.
            const email = v.match(/^([a-z0-9][a-z0-9._-]{0,149})@(mission-leben\.de|akademie-mission-leben\.de)${'$'}/i);
            return email ? email[1] : null;
          };
          const clearOwnError = record => {
            if (record.ownsError && record.input.validationMessage === invalidText) record.input.setCustomValidity('');
            record.ownsError = false;
          };
          const update = record => {
            const input = record.input;
            const value = input.value;
            const invalid = value.includes('@');
            text(record.hint, invalid ? invalidText : hintText);
            record.suggestion = invalid ? candidate(value) : null;
            record.button.hidden = !record.suggestion;
            record.button.disabled = input.disabled;
            text(record.button, record.suggestion ? useText + ' ' + record.suggestion : useText);
            if (invalid && !input.disabled) {
              // Do not overwrite an unrelated server/browser custom error.
              if (!input.validity.customError || (record.ownsError && input.validationMessage === invalidText)) {
                input.setCustomValidity(invalidText); record.ownsError = true;
              }
            } else clearOwnError(record);
            return !invalid;
          };
          const forget = record => {
            clearOwnError(record);
            record.input.removeEventListener('input', record.changed);
            record.input.removeEventListener('change', record.changed);
            const ids = (record.input.getAttribute('aria-describedby') || '').split(/\s+/)
              .filter(id => id && id !== record.hint.id);
            if (ids.length) record.input.setAttribute('aria-describedby', ids.join(' '));
            else record.input.removeAttribute('aria-describedby');
            record.box.remove(); records.delete(record.input);
          };
          const enhance = input => {
            let record = records.get(input);
            if (!record) {
              const box = document.createElement('div');
              box.style.marginTop = '0.5rem';
              box.style.fontSize = '0.9rem';
              const hint = box.appendChild(document.createElement('p'));
              hint.id = 'ml-login-identifier-' + (++number);
              hint.className = 'pf-c-form__helper-text';
              hint.setAttribute('role', 'status');
              const button = box.appendChild(document.createElement('button'));
              button.type = 'button';
              button.className = 'pf-c-button pf-m-secondary';
              button.style.marginTop = '0.5rem';
              button.style.whiteSpace = 'normal';
              record = { input, box, hint, button, ownsError: false, suggestion: null };
              record.changed = () => { if (allowed() && isIdentifier(input, input.getRootNode())) update(record); };
              input.addEventListener('input', record.changed);
              input.addEventListener('change', record.changed);
              button.addEventListener('click', event => {
                event.preventDefault();
                if (!allowed() || !input.isConnected || input.disabled || !isIdentifier(input, input.getRootNode())) return;
                const next = candidate(input.value);
                if (!next) return;
                input.value = next;
                input.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
                input.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
                update(record); input.focus(); // The user still submits Authentik's form themselves.
              });
              records.set(input, record);
            }
            if (!record.box.isConnected) input.insertAdjacentElement('afterend', record.box);
            const ids = (input.getAttribute('aria-describedby') || '').split(/\s+/).filter(Boolean);
            if (!ids.includes(record.hint.id)) input.setAttribute('aria-describedby', [...ids, record.hint.id].join(' '));
            update(record);
          };
          const guardSubmit = (root, event) => {
            if (!allowed() || !stageOf(root)) return;
            for (const record of records.values()) {
              if (!record.input.isConnected || record.input.getRootNode() !== root || record.input.disabled ||
                  !isIdentifier(record.input, root)) continue;
              if (!update(record)) {
                event.preventDefault(); event.stopImmediatePropagation();
                record.input.reportValidity(); record.input.focus(); return;
              }
              // Whitespace is the only automatic correction; account spelling is never guessed.
              const trimmed = record.input.value.trim();
              if (record.input.value !== trimmed) {
                record.input.value = trimmed;
                record.input.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
              }
            }
          };
          let queued = false;
          const schedule = () => {
            if (queued) return;
            queued = true;
            queueMicrotask(() => { queued = false; refresh(); });
          };
          const visit = root => {
            if (!watched.has(root)) {
              watched.add(root);
              new MutationObserver(schedule).observe(root, { childList: true, subtree: true,
                attributes: true, attributeFilter: ['type', 'name', 'disabled'] });
              root.addEventListener('submit', event => guardSubmit(root, event), true);
              root.addEventListener('keydown', event => {
                // Enter on the non-submitting correction button must remain usable with a keyboard.
                if (event.key === 'Enter' && event.target?.closest?.('button')?.type !== 'button') guardSubmit(root, event);
              }, true);
              root.addEventListener('click', event => {
                if (event.target?.closest?.('button')?.type === 'submit') guardSubmit(root, event);
              }, true);
            }
            for (const el of root.querySelectorAll('*')) {
              if (isIdentifier(el, root)) enhance(el);
              if (el.tagName === 'AK-STAGE-ACCESS-DENIED' && el.shadowRoot) {
                const form = el.shadowRoot.querySelector('form');
                if (form) {
                  let record = deniedHints.get(el);
                  if (!record) {
                    const box = document.createElement('div');
                    const hint = box.appendChild(document.createElement('p'));
                    hint.className = 'pf-c-form__helper-text'; hint.style.fontSize = '0.9rem'; text(hint, deniedText);
                    record = { box, originals: new Set(), details: null }; deniedHints.set(el, record);
                  }
                  // Reword only our known generic refusal, never a specific server error or access decision.
                  for (const p of el.shadowRoot.querySelectorAll('p')) {
                    if (p.parentElement?.getAttribute('slot') !== 'body' ||
                        p.parentElement?.parentElement?.tagName !== 'AK-EMPTY-STATE' || p.textContent !== genericRefusal) continue;
                    record.originals.add(p); text(p, deniedSummary);
                    if (!record.details) {
                      record.details = record.box.appendChild(document.createElement('details'));
                      text(record.details.appendChild(document.createElement('summary')), technicalLabel);
                      text(record.details.appendChild(document.createElement('p')), genericRefusal);
                    }
                  }
                  if (!record.box.isConnected) form.appendChild(record.box);
                }
              }
              if (el.shadowRoot) visit(el.shadowRoot);
            }
          };
          const refresh = () => {
            for (const record of records.values()) {
              if (!allowed() || !record.input.isConnected || !isIdentifier(record.input, record.input.getRootNode())) forget(record);
            }
            for (const [stage, record] of deniedHints) {
              if (!allowed() || !stage.isConnected) {
                for (const p of record.originals) { if (p.isConnected && p.textContent === deniedSummary) text(p, genericRefusal); }
                record.box.remove(); deniedHints.delete(stage);
              }
            }
            if (allowed()) visit(document);
          };
          window.__mlLoginIdentifier = { refresh };
          document.addEventListener('DOMContentLoaded', schedule);
          window.addEventListener('load', schedule);
          window.addEventListener('popstate', schedule);
          refresh();
        })();
    """
}
