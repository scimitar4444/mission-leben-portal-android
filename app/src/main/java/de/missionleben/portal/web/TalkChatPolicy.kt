package de.missionleben.portal.web

import java.net.URI

object TalkChatPolicy {
    private const val NEXTCLOUD_HOST = "nextcloud.mission-leben.de"
    const val NEXTCLOUD_ORIGIN = "https://nextcloud.mission-leben.de"

    /**
     * Talk uses /call/<token> for the whole conversation, including its chat. Therefore the
     * navigation itself must stay available and only the call controls are removed. The
     * selectors below are component classes from Talk's CallButton.vue rather than translated
     * labels, so the guard works with every supported app language.
     */
    const val CHAT_ONLY_SCRIPT = """
        (() => {
          if (window.__mlTalkChatOnlyInstalled) return;
          window.__mlTalkChatOnlyInstalled = true;

          const callControlSelectors = [
            '.join-call',
            '.leave-call',
            '.leave-call-button--split',
            '.call-button',
            '.talk-tab__call-button',
            '.talk-dashboard__actions button:has(.video-outline-icon)',
            'button:has(.microphone-outline-icon)',
            '.talk-dashboard__actions button:has(.phone-outline-icon)',
            '.instant-meeting__dialog',
            '.event-section',
            '.upcoming-meeting',
            '.calendar-events__buttons',
            '#calendar-meeting',
            '#header'
          ];
          const callControlSelector = callControlSelectors.join(',');
          const scopedCallControlSelector = callControlSelectors
            .map((selector) => 'html[data-ml-talk-chat-only] ' + selector)
            .join(',');
          const styleId = 'ml-talk-chat-only-style';
          const lastRoomStorageKey = 'ml-talk-last-room-path';
          const roomPathPattern = /^\/(?:index\.php\/)?call\/[A-Za-z0-9_-]{4,128}$/;
          const roomTokenPattern = /\/call\/([A-Za-z0-9_-]{4,128})(?:\/|${'$'})/;
          let navigationOpenedForRoot = false;
          let restoreAttempted = false;
          let observerStarted = false;
          let allowedRoomTokens = null;
          let roomFilterRequested = false;
          let roomFilterFailed = false;

          const normalizedPath = () => window.location.pathname.replace(/\/+$/, '');

          const isTalkPath = () => {
            const path = normalizedPath();
            return path.includes('/apps/spreed')
              || path.startsWith('/call/')
              || path.includes('/call/');
          };

          const isTalkRoot = () => normalizedPath().endsWith('/apps/spreed');

          const talkRootPath = () => normalizedPath().startsWith('/index.php/')
            ? '/index.php/apps/spreed/'
            : '/apps/spreed/';

          const roomTokenFromPath = (path) => {
            const match = path.match(roomTokenPattern);
            return match ? match[1] : null;
          };

          const loadAllowedRooms = () => {
            if (roomFilterRequested || !isTalkPath()) return;
            roomFilterRequested = true;
            const prefix = normalizedPath().startsWith('/index.php/') ? '/index.php' : '';
            fetch(window.location.origin + prefix
              + '/apps/missionleben_announcements/api/v1/my-talk-rooms', {
                credentials: 'same-origin',
                headers: { Accept: 'application/json' },
              })
              .then((response) => {
                if (!response.ok) throw new Error('room filter unavailable');
                return response.json();
              })
              .then((payload) => {
                if (!payload || !Array.isArray(payload.room_tokens)) {
                  throw new Error('invalid room filter');
                }
                allowedRoomTokens = new Set(payload.room_tokens);
                updateMode();
              })
              .catch(() => {
                roomFilterFailed = true;
                updateMode();
              });
          };

          const filterConversationList = () => {
            if (!isTalkPath() || allowedRoomTokens === null) return;
            document.querySelectorAll('.conversation[data-nav-id^="conversation_"]').forEach((element) => {
              const token = (element.getAttribute('data-nav-id') || '').replace(/^conversation_/, '');
              if (allowedRoomTokens.has(token)) {
                element.removeAttribute('data-ml-talk-hidden-room');
              } else {
                element.setAttribute('data-ml-talk-hidden-room', '');
              }
            });
          };

          const rememberOrRestoreLastRoom = () => {
            const path = normalizedPath();
            try {
              if (roomPathPattern.test(path)) {
                const currentToken = roomTokenFromPath(path);
                if (allowedRoomTokens !== null && !allowedRoomTokens.has(currentToken)) {
                  window.localStorage.removeItem(lastRoomStorageKey);
                  window.location.replace(window.location.origin + talkRootPath());
                  return true;
                }
                window.localStorage.setItem(lastRoomStorageKey, path);
                restoreAttempted = true;
                return false;
              }

              const unavailableRoom = path.endsWith('/apps/spreed/not-found')
                || path.endsWith('/apps/spreed/forbidden');
              if (unavailableRoom && window.localStorage.getItem(lastRoomStorageKey)) {
                window.localStorage.removeItem(lastRoomStorageKey);
                restoreAttempted = true;
                window.location.replace(window.location.origin + talkRootPath());
                return true;
              }

              if (!isTalkRoot() || restoreAttempted) return false;
              if (allowedRoomTokens === null && !roomFilterFailed) return false;
              restoreAttempted = true;
              const lastRoomPath = window.localStorage.getItem(lastRoomStorageKey);
              if (!lastRoomPath) return false;
              if (!roomPathPattern.test(lastRoomPath)) {
                window.localStorage.removeItem(lastRoomStorageKey);
                return false;
              }
              const lastRoomToken = roomTokenFromPath(lastRoomPath);
              if (allowedRoomTokens !== null && !allowedRoomTokens.has(lastRoomToken)) {
                window.localStorage.removeItem(lastRoomStorageKey);
                return false;
              }
              window.location.replace(window.location.origin + lastRoomPath);
              return true;
            } catch (_error) {
              restoreAttempted = true;
              return false;
            }
          };

          const openConversationNavigation = () => {
            if (!isTalkRoot()) {
              navigationOpenedForRoot = false;
              return;
            }
            if (navigationOpenedForRoot) return;
            const navigation = document.querySelector('.app-navigation');
            const toggle = document.querySelector('.app-navigation-toggle');
            if (!navigation || !toggle) return;
            navigationOpenedForRoot = true;
            if (navigation.getBoundingClientRect().right <= 0) toggle.click();
          };

          const installStyle = () => {
            if (!document.documentElement || document.getElementById(styleId)) return;
            const style = document.createElement('style');
            style.id = styleId;
            style.textContent = scopedCallControlSelector + ' {'
              + 'display:none !important;'
              + 'visibility:hidden !important;'
              + 'pointer-events:none !important;'
              + '}'
              + 'html[data-ml-talk-chat-only] #content {'
              + 'top:0 !important;'
              + 'margin-top:0 !important;'
              + 'bottom:0 !important;'
              + 'height:auto !important;'
              + '}'
              + 'html[data-ml-talk-chat-only] #content-vue {'
              + 'top:0 !important;'
              + 'bottom:0 !important;'
              + 'height:100% !important;'
              + '}'
              + 'html[data-ml-talk-chat-only] [data-ml-talk-hidden-room] {'
              + 'display:none !important;'
              + '}';
            (document.head || document.documentElement).appendChild(style);
          };

          const lockRestrictedControls = () => {
            if (!isTalkPath()) return;
            document.querySelectorAll(callControlSelector).forEach((element) => {
              element.setAttribute('data-ml-talk-blocked', '');
              element.setAttribute('aria-hidden', 'true');
              element.setAttribute('inert', '');
              if ('disabled' in element) element.disabled = true;
              element.style.setProperty('display', 'none', 'important');
              element.style.setProperty('visibility', 'hidden', 'important');
              element.style.setProperty('pointer-events', 'none', 'important');
            });
          };

          const dismissUnsupportedBrowserWarning = () => {
            if (!isTalkPath()) return;
            document.querySelectorAll('.toastify.toast-error').forEach((toast) => {
              const message = toast.textContent || '';
              const isTalkBrowserWarning = message.includes('Nextcloud Talk')
                && message.includes('Mozilla Firefox')
                && message.includes('Google Chrome');
              if (isTalkBrowserWarning) toast.remove();
            });
          };

          const updateMode = () => {
            installStyle();
            if (!document.documentElement) return;
            if (isTalkPath()) {
              document.documentElement.setAttribute('data-ml-talk-chat-only', '');
            } else {
              document.documentElement.removeAttribute('data-ml-talk-chat-only');
            }
            lockRestrictedControls();
            dismissUnsupportedBrowserWarning();
            loadAllowedRooms();
            filterConversationList();
            if (rememberOrRestoreLastRoom()) return;
            openConversationNavigation();
          };

          const blockCallControl = (event) => {
            if (!isTalkPath() || !(event.target instanceof Element)) return;
            if (!event.target.closest(callControlSelector)) return;
            event.preventDefault();
            event.stopImmediatePropagation();
          };

          const blockHiddenConversation = (event) => {
            if (!isTalkPath() || allowedRoomTokens === null || !(event.target instanceof Element)) return;
            const conversation = event.target.closest('.conversation[data-nav-id^="conversation_"]');
            if (!conversation) return;
            const token = (conversation.getAttribute('data-nav-id') || '').replace(/^conversation_/, '');
            if (allowedRoomTokens.has(token)) return;
            event.preventDefault();
            event.stopImmediatePropagation();
          };

          document.addEventListener('pointerdown', blockCallControl, true);
          document.addEventListener('touchstart', blockCallControl, true);
          document.addEventListener('mousedown', blockCallControl, true);
          document.addEventListener('click', blockCallControl, true);
          document.addEventListener('pointerdown', blockHiddenConversation, true);
          document.addEventListener('touchstart', blockHiddenConversation, true);
          document.addEventListener('click', blockHiddenConversation, true);
          window.addEventListener('popstate', updateMode);
          window.addEventListener('hashchange', updateMode);

          const startObserver = () => {
            if (observerStarted) return;
            observerStarted = true;
            updateMode();
            if (!document.documentElement) return;
            new MutationObserver(updateMode).observe(document.documentElement, {
              childList: true,
              subtree: true,
            });
          };

          if (document.documentElement) {
            startObserver();
          } else {
            const documentObserver = new MutationObserver(() => {
              if (!document.documentElement) return;
              documentObserver.disconnect();
              startObserver();
            });
            documentObserver.observe(document, { childList: true, subtree: true });
          }
        })();
    """

    fun isTalkPage(url: String): Boolean {
        val parsed = runCatching { URI(url) }.getOrNull() ?: return false
        if (!parsed.scheme.equals("https", ignoreCase = true)) return false
        if (!parsed.host.equals(NEXTCLOUD_HOST, ignoreCase = true)) return false
        val path = parsed.path.orEmpty().trimEnd('/')
        return path.contains("/apps/spreed") ||
            path.startsWith("/call/") ||
            path.contains("/call/")
    }
}
