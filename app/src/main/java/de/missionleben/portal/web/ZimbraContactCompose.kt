package de.missionleben.portal.web

import de.missionleben.portal.data.ContactActions
import java.net.URI
import java.util.Locale

/** Use Zimbra's own mailto handler, which supplies the recipient to the mobile composer. */
internal object ZimbraContactCompose {
    fun script(baseUrl: String, recipient: String): String? {
        // Both replacements are strictly validated and cannot contain JS string delimiters.
        if (ContactActions.zimbraComposeUrl(baseUrl, recipient) == null) return null
        val base = URI(baseUrl)
        val origin = URI("https", null, base.host.lowercase(Locale.ROOT),
            if (base.port == 443) -1 else base.port, null, null, null).toASCIIString()
        return SCRIPT.replace("__ORIGIN__", origin).replace("__RECIPIENT__", recipient)
    }

    internal const val SCRIPT = """
        (() => {
          if (location.origin !== '__ORIGIN__' || !location.pathname.startsWith('/modern/')) return 'wrong-origin';
          if (!document.body) return 'waiting';
          const recipient = '__RECIPIENT__';
          if (window.__mlContactComposeRecipient === recipient) return 'opened';
          const link = document.createElement('a');
          link.href = 'mailto:' + encodeURIComponent(recipient);
          link.hidden = true;
          document.body.appendChild(link);
          let accepted = false;
          // Registered after Zimbra's own window listener. Block the browser's default
          // mailto action even when Zimbra has not finished loading; never open another app.
          const stopDefault = event => {
            if (event.target !== link) return;
            accepted = event.defaultPrevented;
            event.preventDefault();
          };
          window.addEventListener('click', stopDefault);
          try {
            link.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
          } finally {
            window.removeEventListener('click', stopDefault);
            link.remove();
          }
          if (!accepted) return 'waiting';
          window.__mlContactComposeRecipient = recipient;
          return 'opened';
        })();
    """
}
