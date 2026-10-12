package de.missionleben.portal.web

import java.net.URI

/** Presentation inside the protected WebView only; Nextcloud and its permissions stay unchanged. */
internal object AppHelpLayout {
    const val ORIGIN = "https://nextcloud.mission-leben.de"
    private val helpPath = Regex("^/(?:index\\.php/)?apps/collectives/User-Guides-7(?:/.*)?$")

    fun isHelpPage(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", true) && uri.host.equals("nextcloud.mission-leben.de", true) &&
            uri.port in setOf(-1, 443) && uri.rawUserInfo == null && helpPath.matches(uri.rawPath.orEmpty())
    }

    const val SCRIPT = """
        (() => {
          if (location.origin !== 'https://nextcloud.mission-leben.de' || window.top !== window) return;
          if (window.__mlAppHelpLayoutInstalled) return;
          window.__mlAppHelpLayoutInstalled = true;

          const styleId = 'ml-app-help-layout';
          const isHelpPath = () => /^\/(?:index\.php\/)?apps\/collectives\/User-Guides-7(?:\/|${'$'})/
            .test(location.pathname);
          const updateMode = () => {
            const root = document.documentElement;
            if (!root) return;
            if (!document.getElementById(styleId)) {
              const style = document.createElement('style');
              style.id = styleId;
              style.textContent = `
                html[data-ml-app-help-embedded] { --header-height: 0px; }
                html[data-ml-app-help-embedded] #header { display: none !important; }
                html[data-ml-app-help-embedded] #content,
                html[data-ml-app-help-embedded] #content-vue {
                  top: 0 !important; margin-top: 0 !important; bottom: 0 !important; height: 100% !important;
                }
              `;
              (document.head || root).appendChild(style);
            }
            if (isHelpPath()) {
              root.setAttribute('data-ml-app-help-embedded', '');
            } else {
              // Nextcloud is an SPA: restore its own header when leaving User Guides.
              root.removeAttribute('data-ml-app-help-embedded');
            }
          };

          updateMode();
          new MutationObserver(updateMode).observe(document, { childList: true, subtree: true });
          window.addEventListener('popstate', updateMode);
          window.addEventListener('hashchange', updateMode);
        })();
    """
}
