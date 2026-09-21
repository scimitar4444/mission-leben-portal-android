package de.missionleben.portal.web

import java.net.URI

object AnnouncementCenterPolicy {
    const val NEXTCLOUD_ORIGIN = "https://nextcloud.mission-leben.de"

    const val EMBEDDED_SCRIPT = """
        (() => {
          if (window.__mlAnnouncementEmbeddedInstalled) return;
          window.__mlAnnouncementEmbeddedInstalled = true;

          const styleId = 'ml-announcement-embedded-style';
          const isAnnouncementPath = () => window.location.pathname
            .replace(/\/+$/, '')
            .includes('/apps/announcementcenter');

          const installStyle = () => {
            if (!document.documentElement || document.getElementById(styleId)) return;
            const style = document.createElement('style');
            style.id = styleId;
            style.textContent = 'html[data-ml-announcement-embedded] #header {'
              + 'display:none !important;visibility:hidden !important;pointer-events:none !important;}'
              + 'html[data-ml-announcement-embedded] #content {'
              + 'top:0 !important;margin-top:0 !important;bottom:0 !important;height:auto !important;}'
              + 'html[data-ml-announcement-embedded] #content-vue {'
              + 'top:0 !important;bottom:0 !important;height:100% !important;}';
            (document.head || document.documentElement).appendChild(style);
          };

          const updateMode = () => {
            installStyle();
            if (!document.documentElement) return;
            if (isAnnouncementPath()) {
              document.documentElement.setAttribute('data-ml-announcement-embedded', '');
            } else {
              document.documentElement.removeAttribute('data-ml-announcement-embedded');
            }
          };

          const startObserver = () => {
            updateMode();
            if (!document.documentElement) return;
            new MutationObserver(updateMode).observe(document.documentElement, {
              childList: true,
              subtree: true,
            });
          };

          window.addEventListener('popstate', updateMode);
          window.addEventListener('hashchange', updateMode);
          if (document.documentElement) {
            startObserver();
          } else {
            const observer = new MutationObserver(() => {
              if (!document.documentElement) return;
              observer.disconnect();
              startObserver();
            });
            observer.observe(document, { childList: true, subtree: true });
          }
        })();
    """

    fun isAnnouncementPage(url: String): Boolean {
        val parsed = runCatching { URI(url) }.getOrNull() ?: return false
        if (!parsed.scheme.equals("https", ignoreCase = true)) return false
        if (!parsed.host.equals("nextcloud.mission-leben.de", ignoreCase = true)) return false
        return parsed.path.orEmpty().contains("/apps/announcementcenter")
    }
}
