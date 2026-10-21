from __future__ import annotations

import os

import logging
import threading

from .authentik import AuthentikClient
from .employee_directory import EmployeeDirectory
from .config import Settings
from .duo_compat import DuoCompatApi, DuoCompatSettings
from .communication_directory import CommunicationDirectory
from .http_api import BridgeHttpServer
from .nextcloud_announcements import NextcloudAnnouncementClient
from .ntfy import NtfyManager, NullNtfyManager
from .security import SecretBox
from .service import BridgeService
from .store import Store
from .announcement_push import AnnouncementPushWorker


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    settings = Settings.from_env()
    store = Store(settings.database_path, settings.internal_hmac_secret, SecretBox(settings.data_key))
    authentik = AuthentikClient(
        settings.authentik_userinfo_url,
        settings.authentik_device_status_url,
    )
    ntfy = (
        NtfyManager(
            settings.ntfy_public_base_url,
            settings.ntfy_internal_base_url,
            settings.ntfy_auth_file,
            settings.ntfy_binary,
        )
        if settings.ntfy_configured and settings.ntfy_auth_file is not None
        else NullNtfyManager()
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
        ntfy,
        settings.talk_targets,
        announcement_client,
        settings.announcement_cache_ttl_seconds,
        settings.announcement_stale_ttl_seconds,
        EmployeeDirectory(settings.employee_directory_file) if settings.employee_directory_file else None,
        CommunicationDirectory(settings.communication_directory_file) if settings.communication_directory_file else None,
        announcement_push_enabled=os.getenv('BRIDGE_ANNOUNCEMENT_PUSH_ENABLED', 'false').lower() == 'true',
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
    announcement_worker = None
    if service.announcement_push_enabled:
        worker = AnnouncementPushWorker(service)

        def poll_announcements() -> None:
            while not stop_dispatcher.is_set():
                try:
                    queued = worker.poll()
                    if queued:
                        logging.getLogger('mission_leben_bridge.announcements').info('announcement deliveries queued: %d', queued)
                except Exception:
                    # Deliberately no source exception / recipient / content in logs.
                    logging.getLogger('mission_leben_bridge.announcements').warning('announcement discovery failed; will retry')
                stop_dispatcher.wait(15)

        announcement_worker = threading.Thread(target=poll_announcements, name='announcement-discovery', daemon=True)
        announcement_worker.start()
    server = BridgeHttpServer(settings, service, duo_api)
    logging.getLogger("mission_leben_bridge").info(
        "bridge listening on %s:%d (ntfy configured: %s, login approval configured: %s)",
        settings.listen_host,
        settings.listen_port,
        ntfy.configured,
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
