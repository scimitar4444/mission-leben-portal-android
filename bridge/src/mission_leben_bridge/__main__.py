from __future__ import annotations

import logging
import threading

from .authentik import AuthentikClient
from .config import Settings
from .duo_compat import DuoCompatApi, DuoCompatSettings
from .fcm import FcmSender, NullFcmSender
from .http_api import BridgeHttpServer
from .nextcloud_announcements import NextcloudAnnouncementClient
from .security import SecretBox
from .service import BridgeService
from .store import Store


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    settings = Settings.from_env()
    store = Store(settings.database_path, settings.internal_hmac_secret, SecretBox(settings.data_key))
    authentik = AuthentikClient(
        settings.authentik_userinfo_url,
        settings.authentik_device_status_url,
    )
    fcm = (
        FcmSender(settings.firebase_project_id, settings.google_credentials_path)
        if settings.fcm_configured and settings.google_credentials_path is not None
        else NullFcmSender()
    )
    announcement_client = None
    if (
        settings.nextcloud_announcements_url
        and settings.nextcloud_announcements_secret is not None
    ):
        announcement_client = NextcloudAnnouncementClient(
            settings.nextcloud_announcements_url,
            settings.nextcloud_announcements_secret,
        )
    service = BridgeService(
        store,
        authentik,
        fcm,
        settings.talk_targets,
        announcement_client,
        settings.announcement_cache_ttl_seconds,
        settings.announcement_stale_ttl_seconds,
    )
    duo_api = None
    if settings.duo_configured and settings.duo_secret_key is not None:
        duo_api = DuoCompatApi(
            DuoCompatSettings(
                integration_key=settings.duo_integration_key,
                secret_key=settings.duo_secret_key,
                api_hostname=settings.duo_api_hostname,
                approval_timeout_seconds=settings.duo_approval_timeout_seconds,
            ),
            service,
        )
    stop_dispatcher = threading.Event()

    def dispatch_due_events() -> None:
        while not stop_dispatcher.wait(15):
            try:
                service.dispatch_due_events()
            except Exception:
                logging.getLogger("mission_leben_bridge.dispatch").exception("scheduled dispatch failed")

    dispatcher = threading.Thread(target=dispatch_due_events, name="notification-dispatcher", daemon=True)
    dispatcher.start()
    server = BridgeHttpServer(settings, service, duo_api)
    logging.getLogger("mission_leben_bridge").info(
        "bridge listening on %s:%d (FCM configured: %s, login approval configured: %s)",
        settings.listen_host,
        settings.listen_port,
        fcm.configured,
        duo_api is not None,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop_dispatcher.set()
        server.server_close()


if __name__ == "__main__":
    main()
