package de.missionleben.portal.web

import java.net.URI

/** Presentation only: no new navigation, permissions, API calls or document exports. */
internal object DocumentsMobileLayout {
    const val ORIGIN = "https://dokumente.mission-leben.de"

    fun isDocumentOrigin(url: String): Boolean {
        val parsed = runCatching { URI(url) }.getOrNull() ?: return false
        return parsed.scheme.equals("https", true) && parsed.host.equals("dokumente.mission-leben.de", true) &&
            parsed.port in setOf(-1, 443) && parsed.rawUserInfo == null
    }

    const val SCRIPT = """
        (() => {
          if (location.origin !== 'https://dokumente.mission-leben.de' || window.top !== window) return;
          const installPdfPreviewPolicy = () => {
            if (window.__mlDocumentsPdfPreviewPolicy) return;
            window.__mlDocumentsPdfPreviewPolicy = true;
            // ProjectSend exposes the original filename on its thumbnail, not its MIME type.
            // Match its shipped translations exactly; never infer PDF from a display title.
            const templates = [
              ['Preview ', ''], ['', ' in der Vorschau öffnen'], ['Podgląd ', ''],
              ['', ' dosyasını önizle'], ['Переглянути ', '']
            ];
            const previewName = label => {
              for (const [prefix, suffix] of templates) {
                if (label.startsWith(prefix) && label.endsWith(suffix)) {
                  return label.slice(prefix.length, suffix ? -suffix.length : undefined);
                }
              }
              return null;
            };
            const isPdfLabel = label => {
              const name = previewName(label);
              return name !== null && name.toLowerCase().endsWith('.pdf');
            };
            const originals = new WeakMap();
            let files = new Map();
            let pageReceived = false;
            let clientDashboard = false;
            const receivePage = page => {
              files = new Map();
              clientDashboard = !!(page && page.component === 'portal/dashboard');
              // Only the client overview repeats the greeting from the Android home screen.
              if (clientDashboard) {
                document.documentElement.setAttribute('data-ml-client-documents-dashboard', '');
              } else {
                document.documentElement.removeAttribute('data-ml-client-documents-dashboard');
              }
              const props = page && page.props;
              const list = props && (Array.isArray(props.files) ? props.files : props.latest_files);
              if (!Array.isArray(list)) return;
              for (const file of list) {
                if (file && Number.isSafeInteger(file.id) && typeof file.mime_type === 'string') {
                  files.set(String(file.id), { mime: file.mime_type.split(';')[0].trim().toLowerCase(), name: file.original_name || '' });
                }
              }
            };
            const readInitialPage = () => {
              if (pageReceived) return;
              // Both Inertia bootstrap formats; read only the current authorized page's file metadata.
              const root = document.querySelector('[data-page]');
              if (!root) return;
              try {
                const json = root.tagName === 'SCRIPT' ? root.textContent : root.getAttribute('data-page');
                receivePage(JSON.parse(json)); pageReceived = true;
              } catch (_) { /* Unknown bootstrap format: leave the page unchanged. */ }
            };
            const rowFile = row => {
              for (const link of row.querySelectorAll('a[href]')) {
                try {
                  const url = new URL(link.getAttribute('href'), location.origin);
                  const match = url.pathname.match(/^\/files\/([0-9]+)\/download$/);
                  if (url.origin === location.origin && match) return files.get(match[1]);
                } catch (_) { /* Not a file download link. */ }
              }
              return undefined;
            };
            const typeLabel = file => {
              const lang = (document.documentElement.lang || 'en').split('-')[0];
              const labels = {
                de: ['Text', 'Bild', 'Audio', 'Video'], en: ['Text', 'Image', 'Audio', 'Video'],
                pl: ['Tekst', 'Obraz', 'Audio', 'Wideo'], tr: ['Metin', 'Resim', 'Ses', 'Video'],
                uk: ['Текст', 'Зображення', 'Аудіо', 'Відео']
              }[lang] || ['Text', 'Image', 'Audio', 'Video'];
              if (file.mime === 'application/pdf') return 'PDF';
              if (file.mime === 'text/plain') return labels[0];
              if (file.mime.startsWith('image/')) return labels[1];
              if (file.mime.startsWith('audio/')) return labels[2];
              if (file.mime.startsWith('video/')) return labels[3];
              const extension = String(file.name).match(/\.([a-zA-Z0-9]{1,6})$/);
              return extension ? extension[1].toUpperCase() : null;
            };
            const typeKind = file => {
              if (file.mime === 'application/pdf') return 'pdf';
              if (file.mime.startsWith('text/')) return 'text';
              if (file.mime.startsWith('image/')) return 'image';
              if (file.mime.startsWith('audio/')) return 'audio';
              if (file.mime.startsWith('video/')) return 'video';
              if (/spreadsheet|excel|csv/.test(file.mime)) return 'sheet';
              if (/presentation|powerpoint/.test(file.mime)) return 'presentation';
              if (/word|opendocument.text/.test(file.mime)) return 'document';
              if (/zip|compressed|archive|tar/.test(file.mime)) return 'archive';
              return 'file';
            };
            const fileIcon = kind => {
              const ns = 'http://www.w3.org/2000/svg';
              const svg = document.createElementNS(ns, 'svg');
              for (const [name, value] of Object.entries({viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor',
                'stroke-width': '1.6', 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true'})) {
                svg.setAttribute(name, value);
              }
              const paths = ['M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z', 'M14 2v6h6'];
              const details = {
                text: ['M8 12h8', 'M8 16h8'], image: ['M7 18l4-5 3 3 2-2 2 4', 'M8 10h.01'],
                audio: ['M13 11v6', 'M13 11l4-1v5', 'M13 17c-2-2-4 1-2 2 1 1 2 0 2-2', 'M17 15c-2-2-4 1-2 2 1 1 2 0 2-2'],
                video: ['M10 11l6 4-6 4Z'], sheet: ['M7 11h10v8H7Z', 'M7 15h10', 'M12 11v8'],
                presentation: ['M7 11h10v6H7Z', 'M10 20l2-3 2 3'],
                document: ['M7 12l2 6 3-5 3 5 2-6'], archive: ['M12 10v1', 'M12 13v1', 'M11 17h2v2h-2Z']
              }[kind] || [];
              for (const d of paths.concat(details)) {
                const path = document.createElementNS(ns, 'path'); path.setAttribute('d', d); svg.appendChild(path);
              }
              if (kind === 'pdf') {
                const text = document.createElementNS(ns, 'text');
                for (const [name, value] of Object.entries({x: '12', y: '17.5', 'text-anchor': 'middle',
                  'font-size': '5.5', 'font-family': 'sans-serif', 'font-weight': '700', fill: 'currentColor', stroke: 'none'})) {
                  text.setAttribute(name, value);
                }
                text.textContent = 'PDF'; svg.appendChild(text);
              }
              return svg;
            };
            const markType = (row, file) => {
              const title = row.querySelector('p.font-medium');
              if (!title) return;
              let badge = title.querySelector('[data-ml-file-type]');
              const label = file && typeLabel(file);
              if (!label) { if (badge) badge.remove(); return; }
              if (!badge) {
                badge = document.createElement('span');
                badge.setAttribute('data-ml-file-type', '');
                badge.setAttribute('role', 'img');
                title.insertBefore(badge, title.firstChild);
              }
              const kind = typeKind(file);
              if (badge.getAttribute('data-ml-file-kind') !== kind) {
                badge.setAttribute('data-ml-file-kind', kind);
                badge.replaceChildren(fileIcon(kind));
              }
              if (badge.getAttribute('aria-label') !== label) badge.setAttribute('aria-label', label);
              if (badge.getAttribute('title') !== label) badge.setAttribute('title', label);
            };
            const disableThumbnail = button => {
              if (!originals.has(button)) originals.set(button, {
                disabled: button.disabled,
                tabindex: button.getAttribute('tabindex'),
                ariaHidden: button.getAttribute('aria-hidden')
              });
              button.disabled = true;
              button.setAttribute('tabindex', '-1');
              button.setAttribute('aria-hidden', 'true');
              button.setAttribute('data-ml-pdf-thumbnail', '');
            };
            const restoreThumbnail = button => {
              const original = originals.get(button);
              if (!original) return;
              button.disabled = original.disabled;
              for (const [name, value] of [['tabindex', original.tabindex], ['aria-hidden', original.ariaHidden]]) {
                if (value === null) button.removeAttribute(name); else button.setAttribute(name, value);
              }
              button.removeAttribute('data-ml-pdf-thumbnail');
              originals.delete(button);
            };
            const apply = () => {
              readInitialPage();
              // Keep the sidebar button and its framework handler; replace only the known panel glyph.
              const menuLanguage = (document.documentElement.lang || 'en').split('-')[0];
              const menuLabel = { de: 'Menü öffnen', en: 'Open menu', pl: 'Otwórz menu',
                tr: 'Menüyü aç', uk: 'Відкрити меню' }[menuLanguage] || 'Open menu';
              for (const trigger of document.querySelectorAll('button[data-sidebar="trigger"]')) {
                const icon = trigger.querySelector('svg.lucide-panel-left');
                if (icon) {
                  const paths = ['M4 6h16', 'M4 12h16', 'M4 18h16'].map(d => {
                    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
                    path.setAttribute('d', d); return path;
                  });
                  icon.replaceChildren(...paths);
                  icon.setAttribute('class', 'lucide lucide-menu');
                  icon.setAttribute('data-ml-hamburger', '');
                  icon.setAttribute('aria-hidden', 'true');
                  icon.setAttribute('focusable', 'false');
                }
                if (trigger.getAttribute('aria-label') !== menuLabel) trigger.setAttribute('aria-label', menuLabel);
                const text = trigger.querySelector('.sr-only');
                if (text && text.textContent !== menuLabel) text.textContent = menuLabel;
              }
              if (clientDashboard) {
                const heading = document.querySelector('main .mb-8[class~="space-y-0.5"] > h1');
                const lang = (document.documentElement.lang || 'en').split('-')[0];
                const label = { de: 'Meine Dokumente', en: 'My documents', pl: 'Moje dokumenty',
                  tr: 'Belgelerim', uk: 'Мої документи' }[lang] || 'My documents';
                if (heading && heading.textContent !== label) heading.textContent = label;
              }
              for (const row of document.querySelectorAll('main .space-y-2 > .bg-card.flex, main .grid > .group')) {
                const file = rowFile(row);
                markType(row, file);
                const thumbnails = Array.from(row.querySelectorAll('button[aria-label]'));
                const pdf = file ? (file.mime === 'application/pdf' ?
                  thumbnails.find(button => previewName(button.getAttribute('aria-label') || '') !== null) : undefined) :
                  thumbnails.find(button => isPdfLabel(button.getAttribute('aria-label') || ''));
                for (const button of row.querySelectorAll('button[data-ml-pdf-thumbnail]')) {
                  if (button !== pdf) restoreThumbnail(button);
                }
                for (const button of row.querySelectorAll('button[data-ml-pdf-preview]')) {
                  if (!pdf || !button.querySelector('svg.lucide-eye')) button.removeAttribute('data-ml-pdf-preview');
                }
                if (!pdf) continue;
                disableThumbnail(pdf);
                for (const button of row.querySelectorAll('button')) {
                  if (button.querySelector('svg.lucide-eye')) button.setAttribute('data-ml-pdf-preview', '');
                }
              }
            };
            let queued = false;
            const schedule = () => {
              if (queued) return;
              queued = true;
              queueMicrotask(() => { queued = false; apply(); });
            };
            // SPA navigation and browser history provide fresh, already authorized page props.
            document.addEventListener('inertia:navigate', event => {
              pageReceived = true; receivePage(event.detail && event.detail.page); schedule();
            });
            const observer = new MutationObserver(schedule);
            observer.observe(document.documentElement, {
              childList: true, subtree: true, attributes: true, attributeFilter: ['aria-label']
            });
            apply();
          };
          const install = () => {
            if (!document.documentElement) return;
            if (document.getElementById('ml-documents-mobile-layout')) {
              installPdfPreviewPolicy(); return;
            }
            const style = document.createElement('style');
            style.id = 'ml-documents-mobile-layout';
            style.textContent = `
              /* Personal settings and sign-out belong to the Android container, not this account menu. */
              [data-sidebar="footer"] > [data-sidebar="menu"] { display: none !important; }
              button[data-sidebar="trigger"] { width: 40px !important; height: 40px !important; flex-shrink: 0; }
              button[data-sidebar="trigger"] > svg[data-ml-hamburger] { width: 24px !important; height: 24px !important; }
              [data-ml-client-documents-dashboard] main .mb-8[class~="space-y-0.5"]:has(> h1) { margin-bottom: 12px !important; }
              [data-ml-client-documents-dashboard] main .mb-8[class~="space-y-0.5"] > h1 { font-size: 18px !important; line-height: 24px !important; }
              [data-ml-client-documents-dashboard] main .mb-8[class~="space-y-0.5"] > p { display: none !important; }
              main button[data-ml-pdf-preview] { display: none !important; }
              main button[data-ml-pdf-thumbnail] { pointer-events: none; cursor: default; }
              main [data-ml-file-type] {
                display: inline-flex; width: 24px; height: 24px; flex-shrink: 0;
                margin-right: 6px; vertical-align: middle; color: inherit;
              }
              main [data-ml-file-type] > svg { width: 100%; height: 100%; }
              main [data-ml-file-kind="pdf"] { color: #b91c1c; }
              main [data-ml-file-kind="text"], main [data-ml-file-kind="document"] { color: #1d4ed8; }
              main [data-ml-file-kind="image"], main [data-ml-file-kind="sheet"] { color: #0f766e; }
              main [data-ml-file-kind="audio"], main [data-ml-file-kind="video"], main [data-ml-file-kind="presentation"] { color: #7e22ce; }
              .dark main [data-ml-file-kind="pdf"] { color: #fda4af; }
              .dark main [data-ml-file-kind="text"], .dark main [data-ml-file-kind="document"] { color: #93c5fd; }
              .dark main [data-ml-file-kind="image"], .dark main [data-ml-file-kind="sheet"] { color: #5eead4; }
              .dark main [data-ml-file-kind="audio"], .dark main [data-ml-file-kind="video"], .dark main [data-ml-file-kind="presentation"] { color: #d8b4fe; }
              /* Replace only redundant generic file icons, never an image/preview/download control. */
              main .bg-card.flex:has([data-ml-file-type]) > .flex.min-w-0 > :is(svg.lucide-file, svg.lucide-file-text) { display: none; }
              /* A confirmed PDF has no active preview; its old thumbnail must not duplicate the new icon. */
              main .bg-card.flex:has([data-ml-file-type]) button[data-ml-pdf-thumbnail],
              main .grid > .group:has([data-ml-file-type]) > button[data-ml-pdf-thumbnail],
              main .grid > .group:has([data-ml-file-type]) > .aspect-square:has(> svg.lucide-file) { display: none !important; }
              @media (max-width: 480px) {
                main:has(#my-files-search) {
                  min-width: 0; width: 100%; max-width: 100%;
                }
                main:has(#my-files-search) .flex.items-start.justify-between:has(h1) {
                  flex-direction: column; align-items: stretch; gap: 12px;
                  margin-bottom: 16px;
                }
                main:has(#my-files-search) .flex.items-start.justify-between:has(h1) > .mb-8 {
                  margin-bottom: 0;
                }
                main:has(#my-files-search) .flex.items-start.justify-between:has(h1) > .flex {
                  flex-wrap: wrap; gap: 8px;
                }
                main .flex.flex-wrap:has(> .grid > #my-files-search) {
                  display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
                  gap: 10px; padding: 12px;
                }
                main .flex.flex-wrap:has(> .grid > #my-files-search) > .grid {
                  min-width: 0;
                }
                main .flex.flex-wrap:has(> .grid > #my-files-search) > .grid:has(#my-files-search),
                main .flex.flex-wrap:has(> .grid > #my-files-search) > .grid:has(#my-files-sort) {
                  grid-column: 1 / -1;
                }
                main :is(#my-files-search, #my-files-category, #my-files-owner, #my-files-sort) {
                  width: 100% !important; min-width: 0; max-width: 100%;
                }
                main :is(#my-files-category, #my-files-owner, #my-files-sort) > span {
                  min-width: 0; overflow: hidden; text-overflow: ellipsis;
                }
                main:has(#my-files-search) .space-y-2 > .bg-card.flex:has(> .flex.min-w-0.flex-1) {
                  flex-direction: column; align-items: stretch;
                  gap: 8px !important; padding: 12px !important; min-width: 0;
                }
                main:has(#my-files-search) .space-y-2 > .bg-card.flex > .flex.min-w-0.flex-1 {
                  width: 100%; align-items: flex-start; gap: 8px !important;
                }
                main:has(#my-files-search) .space-y-2 > .bg-card.flex > .flex.min-w-0.flex-1 > .min-w-0 {
                  flex: 1 1 0%;
                }
                main:has(#my-files-search) .space-y-2 > .bg-card.flex .size-10 {
                  width: 32px !important; height: 32px !important;
                }
                main:has(#my-files-search) .space-y-2 > .bg-card.flex > .flex.shrink-0 {
                  width: 100%; flex-wrap: wrap; justify-content: flex-end; gap: 6px !important;
                }
                main:has(#my-files-search) .space-y-2 > .bg-card.flex p {
                  white-space: normal; overflow-wrap: anywhere; line-height: 1.4;
                }
                main:has(#my-files-search) .space-y-2 > .bg-card.flex > a.flex-1 {
                  min-width: 0;
                }
                main:has(#my-files-search) .space-y-2 > .bg-card.flex :is(button, a) {
                  max-width: 100%;
                }
                main:has(#my-files-search) .space-y-2 > .bg-card.flex .sr-only {
                  position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
                  overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0;
                }
                main:has(#my-files-search) .grid.grid-cols-2:not(:has(> a[href="/my-files"])) {
                  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 8px;
                }
                main:has(#my-files-search) .grid.grid-cols-2 > .group {
                  min-width: 0;
                }
                main:has(#my-files-search) .grid.grid-cols-2 > .group.p-6 {
                  padding: 12px !important;
                }
                main:has(#my-files-search) .grid.grid-cols-2 > .group > .aspect-square {
                  height: 88px !important; aspect-ratio: auto;
                }
                main:has(#my-files-search) .grid.grid-cols-2 > .group .size-10 {
                  width: 32px !important; height: 32px !important;
                }
                main:has(#my-files-search) .grid.grid-cols-2 > .group > .flex.items-center.justify-between {
                  flex-wrap: wrap; min-width: 0; padding: 10px !important; gap: 6px !important;
                }
                main:has(#my-files-search) .grid.grid-cols-2 > .group > .flex.items-center.justify-between > .min-w-0 {
                  flex: 1 1 100%;
                }
                main:has(#my-files-search) .grid.grid-cols-2 > .group > .flex.items-center.justify-between > .flex.shrink-0 {
                  width: 100%; flex-wrap: wrap; gap: 0; justify-content: flex-end;
                }
                main:has(#my-files-search) .grid.grid-cols-2 > .group p {
                  white-space: normal; overflow-wrap: anywhere; line-height: 1.4;
                }
                main:has(#my-files-search) .grid.grid-cols-2 > .group .sr-only {
                  position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
                  overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0;
                }
                main .grid.grid-cols-2:has(> a[href="/my-files"]) { gap: 8px !important; }
                main .grid.grid-cols-2:has(> a[href="/my-files"]) > :is(a, div) {
                  padding: 10px !important; min-width: 0;
                }
                main .grid.grid-cols-2:has(> a[href="/my-files"]) > :is(a, div) > .flex {
                  flex-direction: column; align-items: flex-start; gap: 6px !important;
                }
                main .grid.grid-cols-2:has(> a[href="/my-files"]) .size-9 {
                  width: 24px !important; height: 24px !important;
                }
                main .grid.grid-cols-2:has(> a[href="/my-files"]) .size-5 {
                  width: 16px !important; height: 16px !important;
                }
                main .grid.grid-cols-2:has(> a[href="/my-files"]) .min-w-0 { width: 100%; }
                main .grid.grid-cols-2:has(> a[href="/my-files"]) .text-sm {
                  font-size: 0.8125rem; line-height: 1.4; hyphens: auto; word-break: normal;
                }
                main .grid.grid-cols-2:has(> a[href="/my-files"]) .text-2xl {
                  font-size: 1.25rem; line-height: 1.3; white-space: nowrap;
                }
                main .grid.grid-cols-2:has(> a[href="/my-files"]) .text-xs {
                  overflow-wrap: anywhere;
                }
                main .space-y-2 > .bg-card.flex.items-center.justify-between {
                  gap: 8px !important; padding: 10px !important; min-width: 0;
                }
                main .space-y-2 > .bg-card.flex.items-center.justify-between > .min-w-0 {
                  flex: 1 1 0%; min-width: 0; gap: 8px !important;
                }
                main .space-y-2 > .bg-card.flex.items-center.justify-between > a {
                  flex-shrink: 0;
                }
                main .space-y-2 > .bg-card.flex.items-center.justify-between .sr-only {
                  position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
                  overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0;
                }
              }
            `;
            (document.head || document.documentElement).appendChild(style);
            installPdfPreviewPolicy();
          };
          if (document.documentElement) install();
          else {
            const observer = new MutationObserver(() => {
              if (!document.documentElement) return;
              observer.disconnect(); install();
            });
            observer.observe(document, {childList: true, subtree: true});
          }
        })();
    """
}
