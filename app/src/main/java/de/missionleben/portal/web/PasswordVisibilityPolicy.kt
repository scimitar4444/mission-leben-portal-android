package de.missionleben.portal.web

import java.net.URI
import java.util.Locale

/** Presentation only: never reads the password or changes authentication decisions. */
internal object PasswordVisibilityPolicy {
    fun script(baseUrl: String, showLabel: String, hideLabel: String): String? {
        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return null
        if (!base.scheme.equals("https", ignoreCase = true) || base.host == null ||
            base.rawUserInfo != null || base.port !in -1..65535
        ) return null
        val origin = URI("https", null, base.host.lowercase(Locale.ROOT),
            if (base.port == 443) -1 else base.port, null, null, null).toASCIIString()
        val replacements = mapOf(
            "ORIGIN" to quote(origin),
            "SHOW" to quote(showLabel),
            "HIDE" to quote(hideLabel),
        )
        // One pass: labels containing placeholder-like text must remain literal labels.
        return Regex("__ML_(ORIGIN|SHOW|HIDE)_JSON__").replace(SCRIPT) {
            replacements.getValue(it.groupValues[1])
        }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                in '\u0000'..'\u001f', '\u2028', '\u2029' ->
                    append("\\u" + character.code.toString(16).padStart(4, '0'))
                else -> append(character)
            }
        }
        append('"')
    }

    internal const val HIDE_SCRIPT = "window.__mlPasswordVisibility?.hideAll();"

    internal const val SCRIPT = """
        (() => {
          if (window.top !== window || location.origin !== __ML_ORIGIN_JSON__ ||
              !location.pathname.startsWith('/if/flow/')) return;
          if (window.__mlPasswordVisibility) {
            window.__mlPasswordVisibility.refresh();
            return;
          }
          const showLabel = __ML_SHOW_JSON__;
          const hideLabel = __ML_HIDE_JSON__;
          const watched = new WeakSet();
          const byInput = new WeakMap();
          const records = new Set();
          const eye = '<svg aria-hidden="true" focusable="false" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/></svg>';
          const eyeOff = '<svg aria-hidden="true" focusable="false" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/><path d="m3 3 18 18"/></svg>';
          const update = record => {
            const visible = record.input.type === 'text';
            record.button.setAttribute('aria-label', visible ? hideLabel : showLabel);
            record.button.title = visible ? hideLabel : showLabel;
            record.button.setAttribute('aria-pressed', String(visible));
            if (record.button.disabled !== record.input.disabled) {
              record.button.disabled = record.input.disabled;
            }
            const icon = visible ? eyeOff : eye;
            if (record.icon !== icon) {
              record.button.innerHTML = icon;
              record.icon = icon;
            }
          };
          const hideAll = () => {
            for (const record of records) {
              record.input.type = 'password';
              update(record);
            }
          };
          const forget = record => {
            record.input.type = 'password';
            record.button.remove();
            records.delete(record);
            byInput.delete(record.input);
          };
          const enhance = input => {
            const group = input.parentElement;
            if (!group || !group.classList.contains('pf-c-input-group')) return;
            let record = byInput.get(input);
            // Keep Authentik's own control if a future version already supplies an eye.
            if (Array.from(group.querySelectorAll('button')).some(button => button !== record?.button)) {
              if (record) forget(record);
              return;
            }
            if (record) {
              if (!record.button.isConnected) group.appendChild(record.button);
              update(record);
              return;
            }
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'pf-c-button pf-m-control';
            button.style.display = 'inline-flex';
            button.style.alignItems = 'center';
            button.style.justifyContent = 'center';
            button.style.minWidth = '44px';
            if (input.id) button.setAttribute('aria-controls', input.id);
            record = { input, button, icon: null };
            byInput.set(input, record);
            records.add(record);
            button.addEventListener('mousedown', event => event.preventDefault());
            button.addEventListener('click', event => {
              event.preventDefault();
              if (!input.isConnected || input.disabled) return;
              input.type = input.type === 'password' ? 'text' : 'password';
              update(record);
            });
            update(record);
            group.appendChild(button);
          };
          let queued = false;
          const schedule = () => {
            if (queued) return;
            queued = true;
            queueMicrotask(() => {
              queued = false;
              refresh();
            });
          };
          const visit = root => {
            if (!watched.has(root)) {
              watched.add(root);
              new MutationObserver(schedule).observe(root, {
                childList: true, subtree: true, attributes: true,
                attributeFilter: ['type', 'disabled'],
              });
              root.addEventListener('submit', hideAll, true);
              root.addEventListener('reset', hideAll, true);
            }
            for (const element of root.querySelectorAll('*')) {
              if (element.tagName === 'INPUT' &&
                  (element.type === 'password' || byInput.has(element))) enhance(element);
              if (element.shadowRoot) visit(element.shadowRoot);
            }
          };
          const refresh = () => {
            if (!location.pathname.startsWith('/if/flow/')) {
              for (const record of records) forget(record);
              return;
            }
            for (const record of records) {
              if (!record.input.isConnected) forget(record);
            }
            visit(document);
          };
          window.__mlPasswordVisibility = { refresh, hideAll };
          window.addEventListener('blur', hideAll);
          window.addEventListener('pagehide', hideAll);
          document.addEventListener('visibilitychange', () => {
            if (document.hidden) hideAll();
          });
          document.addEventListener('DOMContentLoaded', schedule);
          window.addEventListener('load', schedule);
          refresh();
        })();
    """
}
