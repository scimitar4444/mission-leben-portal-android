from __future__ import annotations

from typing import TYPE_CHECKING

from .service import supports_announcement_push

if TYPE_CHECKING:
    from .service import BridgeService


class AnnouncementPushWorker:
    """One cheap server catalogue request; fan-out only for new publications.

    The durable baseline avoids replaying historical announcements on enable.
    Commit the catalogue only after all batches succeed. Retrying is safe due
    to stable per-announcement/per-subject events and per-device delivery IDs.
    """

    def __init__(self, service: BridgeService):
        self.service = service

    def poll(self) -> int:
        service = self.service
        if not service.announcement_push_enabled:
            return 0
        client = service.announcement_client
        if client is None or not client.configured:
            return 0
        current = client.catalog()
        previous = service.store.announcement_catalog()
        if previous is None:
            service.store.save_announcement_catalog(current)
            return 0
        if current == previous:
            return 0
        service.store.invalidate_announcement_cache()
        added = sorted(set(current) - set(previous))
        recipients = {r['key']: {'key': r['key'], 'email': r['email'], 'user_id': r['user_id']}
                      for r in service.store.announcement_recipients()
                      if supports_announcement_push(r['app_version'])}
        values = list(recipients.values())
        queued = 0
        for offset in range(0, len(added), 20):
            ids = added[offset:offset + 20]
            for start in range(0, len(values), 50):
                resolved = client.resolve(ids, values[start:start + 50])
                for subject, announcements in resolved.items():
                    for announcement in announcements:
                        queued += service.queue_announcement(subject, announcement)
        service.store.save_announcement_catalog(current)
        return queued
