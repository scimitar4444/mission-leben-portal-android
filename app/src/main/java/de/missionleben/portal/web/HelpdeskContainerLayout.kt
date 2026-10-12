package de.missionleben.portal.web

import java.net.URI

/** Container-only presentation: the OTOBO owner supplies the theme and all ticket permissions. */
internal object HelpdeskContainerLayout {
    val ORIGINS = setOf("https://it-service.mission-leben.de", "https://it-service.mission-leben.de:8443")

    fun isHelpdeskPage(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", true) && uri.host.equals("it-service.mission-leben.de", true) &&
            uri.port in setOf(-1, 443, 8443) && uri.rawUserInfo == null && uri.path == "/otobo/customer.pl"
    }

    fun script(uploadLabel: String): String = SCRIPT.replace("__ML_UPLOAD_LABEL_JSON__", quote(uploadLabel))

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

    internal const val SCRIPT = """
        (() => {
          if (!['https://it-service.mission-leben.de', 'https://it-service.mission-leben.de:8443'].includes(location.origin) ||
              location.pathname !== '/otobo/customer.pl' || window.top !== window) return;
          if (window.__mlHelpdeskContainerLayout) {
            window.__mlHelpdeskContainerLayout.refresh();
            return;
          }
          const uploadLabel = __ML_UPLOAD_LABEL_JSON__;
          const install = () => {
            if (!document.documentElement) return;
            // Rename only the known dashboard heading, never a ticket title, form or article.
            const heading = document.querySelector('#oooHeader .oooWithSub h1');
            if (heading && heading.textContent.trim() === 'IT-Service – Mission Leben') {
              heading.textContent = 'IT Hilfe'; heading.setAttribute('title', 'IT Hilfe');
            }
            // Preserve the existing upload control, icons, input, handlers and progress/error labels.
            document.querySelectorAll('.DnDUpload .ReadyForUpload').forEach(label => {
              if (label.textContent !== uploadLabel) label.textContent = uploadLabel;
              const button = label.closest('.DnDUpload');
              if (button && button.getAttribute('aria-label') !== uploadLabel) {
                button.setAttribute('aria-label', uploadLabel);
              }
            });
            if (document.getElementById('ml-helpdesk-container-layout')) return;
            const style = document.createElement('style');
            style.id = 'ml-helpdesk-container-layout';
            style.textContent = `
              /* Sign out once in the Android app, not independently inside this embedded portal. */
              #oooNavigation a[href*="Action=Logout"], #oooNavigation #oooLogoutIcon,
              #oooNavigation a[href*="Action=CustomerPreferences"], #oooNavigation #oooUser { display: none !important; }
              /* Vendor mobile CSS hides server-authorized tickets that are already in the page. */
              @media only screen and (max-width: 767px) {
                #oooHeader { height: auto !important; min-height: 0 !important; padding: 12px 16px !important; }
                #oooHeader h1 { font-size: 20px !important; line-height: 1.3 !important; margin: 0 !important; }
                /* The search heading .ooog also contains the magnifier: keep its vendor sizing. */
                #oooHeader .oooWithSub h3:not(.ooog) { font-size: 13px !important; line-height: 1.4 !important; margin: 6px 0 0 !important; }
                /* Vendor search glyph uses page-absolute top:152px; anchor it to its compact field instead. */
                #oooSearchBox h3.ooog { position: relative !important; margin: 0 !important; }
                #oooSearchBox i.ooofo-search {
                  font-size: 20px !important; line-height: 20px !important; padding: 0 !important;
                  top: 50% !important; left: 10px !important; transform: translateY(-50%) !important;
                }
                #oooSearch { padding-left: 36px !important; }
                #oooContent > #oooMainBox { height: auto !important; min-height: 0 !important; }
                #oooMainBox > .oooTile_TicketList {
                  display: block !important; width: 100% !important; height: auto !important;
                  min-width: 0 !important; box-sizing: border-box; padding: 12px !important;
                }
                #oooMainBox > .oooTile_TicketList > h2 {
                  margin: 0 0 8px !important; font-size: 18px !important; line-height: 1.3 !important;
                }
                #oooMainBox > .oooTile_TicketList > div { height: auto !important; overflow: visible !important; }
                #oooMainBox > .oooTile_TicketList .oooTicketListItem {
                  box-sizing: border-box; width: 100% !important; height: auto !important;
                  padding: 10px 12px !important; margin-top: 6px !important;
                  grid-template-columns: 24px minmax(0, 1fr) 64px !important;
                  grid-template-rows: auto 24px !important; gap: 6px 8px !important;
                }
                #oooMainBox > .oooTile_TicketList .oooTicketItemAvatar { height: 24px !important; }
                #oooMainBox > .oooTile_TicketList .oooTIDTitle { font-size: 16px !important; line-height: 1.4 !important; }
                #oooMainBox > .oooTile_TicketList .oooTIDNumber { font-size: 12px !important; line-height: 1.4 !important; }
              }
            `;
            document.documentElement.appendChild(style);
          };
          // OTOBO creates its ready label asynchronously. Coalesce DOM changes; never poll.
          let queued = false;
          const schedule = () => {
            if (queued) return;
            queued = true;
            Promise.resolve().then(() => { queued = false; install(); });
          };
          const observer = new MutationObserver(schedule);
          observer.observe(document, { childList: true, subtree: true });
          window.__mlHelpdeskContainerLayout = { refresh: install };
          install();
          if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', install, { once: true });
        })();
    """
}
