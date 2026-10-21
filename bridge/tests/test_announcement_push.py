from __future__ import annotations

import sqlite3
import tempfile
import time
import unittest
from contextlib import closing
from pathlib import Path
from unittest.mock import patch

from mission_leben_bridge.announcement_push import AnnouncementPushWorker
from mission_leben_bridge.nextcloud_announcements import AnnouncementFetchError
from mission_leben_bridge.security import SecretBox
from mission_leben_bridge.service import ApiError, BridgeService, supports_announcement_push
from mission_leben_bridge.store import Store
from test_service import FakeNtfy


class AudienceClient:
    configured = True

    def __init__(self):
        self.ids = []
        self.allowed = {}
        self.calls = []
        self.fail_call = -1

    def catalog(self):
        return list(self.ids)

    def resolve(self, ids, recipients):
        self.calls.append((ids, recipients))
        if len(self.calls) == self.fail_call:
            raise AnnouncementFetchError('synthetic unavailable')
        return {r['key']: [{'id': i, 'subject': 'Maintenance', 'message': 'Private details',
                           'time': int(time.time()), 'delete_time': 0}
                          for i in ids if i in self.ids and i in self.allowed.get(r['key'], set())]
                for r in recipients}


class DeviceAuth:
    def device_status(self, token):
        return {'device_id': token.removeprefix('agent:')}


class AnnouncementPushTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / 'bridge.sqlite3'
        self.store = Store(self.path, b'h' * 32, SecretBox(b'd' * 32))
        self.client = AudienceClient()
        self.ntfy = FakeNtfy()
        self.service = BridgeService(self.store, DeviceAuth(), self.ntfy, (), self.client)
        self.worker = AnnouncementPushWorker(self.service)

    def tearDown(self):
        self.tmp.cleanup()

    def register(self, subject='person', device='phone', version='0.23.2', **settings):
        self.store.upsert_user(subject, subject + '@example.invalid', 'Synthetic person')
        self.store.register_push(device_id=device, subject=subject, subscribe_token='reader',
            publish_token='writer', topic='topic', reader_username='reader', writer_username='writer',
            agent_token='agent:' + device, key_id=device, public_jwk={}, mode='personal',
            privacy='standard', app_version=version, **settings)

    def publish(self, ids=(12,), subject='person'):
        self.client.ids = list(ids)
        self.client.allowed[subject] = set(ids)

    def test_first_poll_is_durable_baseline_not_historical_broadcast(self):
        self.register()
        self.publish()
        self.assertEqual(0, self.worker.poll())
        restarted = BridgeService(Store(self.path, b'h' * 32, SecretBox(b'd' * 32)),
                                  DeviceAuth(), self.ntfy, (), self.client)
        self.assertEqual(0, AnnouncementPushWorker(restarted).poll())
        self.assertEqual([], self.client.calls)
        self.assertEqual([], self.store.due_delivery_queue())

    def test_new_publication_is_one_event_per_person_and_once_per_phone(self):
        self.register(device='one')
        self.register(device='two')
        self.worker.poll()
        self.publish()
        self.assertEqual(2, self.worker.poll())
        self.assertEqual(2, self.service.dispatch_due_events())
        self.assertEqual(0, self.worker.poll())
        # Even a retry after a crash between fan-out and catalogue commit is safe.
        self.store.save_announcement_catalog([])
        self.assertEqual(0, self.worker.poll())
        self.assertEqual(0, self.service.dispatch_due_events())
        self.assertEqual(2, len(self.ntfy.messages))
        events = {m[2]['event_id'] for m in self.ntfy.messages}
        self.assertEqual(1, len(events))
        self.assertEqual({'action', 'event_id', 'event_type', 'revision'}, set(self.ntfy.messages[0][2]))
        self.assertNotIn('Private details', str(self.ntfy.messages))

    def test_wrong_audience_old_version_and_disabled_user_are_excluded(self):
        self.register()
        self.register('other', 'other')
        self.register('old', 'old', version='0.23.1')
        self.register('disabled', 'disabled')
        self.store.set_user_active('disabled', False)
        self.worker.poll()
        self.publish()
        self.assertEqual(1, self.worker.poll())
        keys = {r['key'] for _, batch in self.client.calls for r in batch}
        self.assertEqual({'person', 'other'}, keys)
        self.assertEqual(1, self.service.dispatch_due_events())

    def test_system_announcements_bypass_communication_off_and_quiet_hours(self):
        self.register(communication_enabled=False, quiet_hours_enabled=True)
        self.worker.poll()
        self.publish()
        self.worker.poll()
        self.assertEqual(1, self.service.dispatch_due_events())

    def test_live_revocation_blocks_delivery_and_detail(self):
        self.register()
        self.worker.poll()
        self.publish()
        self.worker.poll()
        queued = self.store.due_delivery_queue()[0]
        self.client.allowed['person'] = set()
        self.assertEqual(0, self.service.dispatch_due_events())
        self.assertEqual([], self.ntfy.messages)
        with patch.object(self.service, '_verify_device_request', return_value={}):
            with self.assertRaises(ApiError) as error:
                self.service.notification_detail(event_id=queued['event_id'], device_id='phone',
                    key_id='key', timestamp='0', nonce='nonce', signature='sig', path='/synthetic')
        self.assertEqual(403, error.exception.status)

    def test_transient_source_failure_retries_without_finishing_delivery(self):
        self.register()
        self.worker.poll()
        self.publish()
        self.worker.poll()
        self.client.fail_call = len(self.client.calls) + 1
        self.assertEqual(0, self.service.dispatch_due_events())
        self.assertEqual(1, len(self.store.due_delivery_queue()))
        self.assertEqual(1, self.service.dispatch_due_events())

    def test_deletion_invalidates_display_cache_and_blocks_pending_push(self):
        self.register()
        self.worker.poll()
        self.publish()
        self.worker.poll()
        self.store.put_announcement_cache('person', [{'id': 12}], int(time.time()))
        self.client.ids = []
        self.worker.poll()
        self.assertIsNone(self.store.get_announcement_cache('person'))
        self.assertEqual(0, self.service.dispatch_due_events())

    def test_discovery_batches_and_recovers_after_partial_fanout(self):
        for i in range(101):
            subject = f'person-{i}'
            self.register(subject, f'phone-{i}')
            self.client.allowed[subject] = {12}
        self.worker.poll()
        self.client.ids = [12]
        self.client.fail_call = 2
        with self.assertRaises(AnnouncementFetchError):
            self.worker.poll()
        self.assertEqual([], self.store.announcement_catalog())
        self.assertEqual(50, len(self.store.due_delivery_queue()))
        self.client.calls.clear()
        self.client.fail_call = -1
        self.assertEqual(51, self.worker.poll())
        self.assertEqual([50, 50, 1], [len(batch) for _, batch in self.client.calls])
        self.client.calls.clear()
        self.assertEqual(0, self.worker.poll())
        self.assertEqual([], self.client.calls)

    def test_external_sources_cannot_forge_announcement_events(self):
        for source in ('test', 'nextcloud', 'announcements'):
            with self.assertRaises(ApiError):
                self.service.ingest_event(source, {'event_type': 'open_announcements'})

    def test_disabling_feature_holds_pending_announcements_without_blocking_mail(self):
        self.register()
        self.worker.poll()
        self.publish()
        self.worker.poll()
        self.service.announcement_push_enabled = False
        mail = self.service.ingest_event('test', {'source_event_id': 'synthetic-mail',
            'user_subject': 'person', 'event_type': 'open_mail', 'title': 'Synthetic mail'})
        self.assertEqual(1, mail['dispatched'])
        self.assertEqual(0, self.service.dispatch_due_events())
        self.assertEqual(1, len(self.store.due_delivery_queue()))

    def test_source_outage_uses_one_failed_batch_not_one_timeout_per_phone(self):
        for i in range(5):
            self.register(f'person-{i}', f'phone-{i}')
            self.client.allowed[f'person-{i}'] = {12}
        self.worker.poll()
        self.client.ids = [12]
        self.worker.poll()
        before = len(self.client.calls)
        self.client.fail_call = before + 1
        self.assertEqual(0, self.service.dispatch_due_events())
        self.assertEqual(before + 1, len(self.client.calls))
        self.assertEqual(5, self.service.dispatch_due_events())

    def test_stable_nextcloud_identity_is_preserved_and_used_without_mailbox(self):
        self.register()
        self.store.upsert_user('person', '', 'Synthetic person', nextcloud_user_id='stable-nextcloud-uid')
        self.store.upsert_user('person', '', 'Synthetic person')
        self.worker.poll()
        self.publish()
        self.worker.poll()
        recipient = self.client.calls[0][1][0]
        self.assertEqual('stable-nextcloud-uid', recipient['user_id'])
        self.assertEqual('', recipient['email'])

    def test_version_gate_is_strict(self):
        for version in ('', 'invalid', '0.23.1', '0.23.10oops'):
            self.assertFalse(supports_announcement_push(version))
        for version in ('0.23.2', '0.23.2-local', '0.24.0'):
            self.assertTrue(supports_announcement_push(version))

    def test_schema_upgrade_preserves_delivery_foreign_keys(self):
        self.register()
        self.worker.poll()
        self.publish()
        self.worker.poll()
        before = self.store.due_delivery_queue()
        # Recreate the preceding CHECK definition, then run the real migration.
        with closing(sqlite3.connect(self.path)) as connection:
            connection.execute('PRAGMA writable_schema=ON')
            connection.execute("UPDATE sqlite_master SET sql=replace(sql, ?, '') WHERE name='notification_events'",
                               (", 'open_announcements'",))
            connection.execute('PRAGMA writable_schema=OFF')
            connection.commit()
        Store(self.path, b'h' * 32, SecretBox(b'd' * 32))
        self.assertEqual(before, self.store.due_delivery_queue())
        with closing(sqlite3.connect(self.path)) as connection:
            self.assertEqual([], connection.execute('PRAGMA foreign_key_check').fetchall())
            self.assertEqual(12, connection.execute('PRAGMA user_version').fetchone()[0])
