from __future__ import annotations

import tempfile
import time
import unittest
from pathlib import Path

from mission_leben_bridge.nextcloud_announcements import AnnouncementFetchError, HISTORY_PATH, NextcloudAnnouncementClient
from mission_leben_bridge.security import SecretBox
from mission_leben_bridge.service import ApiError, BridgeService
from mission_leben_bridge.store import Store
from test_service import FakeAuthentik, FakeNtfy


class HistoryClient(NextcloudAnnouncementClient):
    def __init__(self):
        super().__init__('https://source.example.invalid', b'h' * 32)
        self.values = []
        self.fail = False
        self.calls = []
        self.retention = 14

    def _post(self, path, payload):
        self.calls.append((path, payload))
        if self.fail:
            raise AnnouncementFetchError('synthetic unavailable')
        return {'announcements': self.values, 'retention_days': self.retention}


def item(**changes):
    return {'id': 19, 'subject': 'Synthetic notice', 'message': 'Still readable\nSecond line',
            'author': 'IT', 'time': int(time.time()) - 60, 'delete_time': int(time.time()) - 1,
            'archived': True} | changes


class AnnouncementHistoryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.tmp.name) / 'bridge.sqlite3', b'h' * 32, SecretBox(b'd' * 32))
        self.client = HistoryClient()
        self.service = BridgeService(self.store, FakeAuthentik(), FakeNtfy(), (), self.client)

    def tearDown(self):
        self.tmp.cleanup()

    def test_expired_or_deleted_announcement_is_still_readable_in_history_only(self):
        self.client.values = [item()]
        result = self.service.announcement_history('valid-token')
        self.assertEqual(14, result['retention_days'])
        self.assertTrue(result['results'][0]['archived'])
        self.assertEqual('Still readable\nSecond line', result['results'][0]['message'])
        self.assertEqual([], self.service._announcement_response(result['results'], int(time.time()), stale=False, cache_hit=False)['results'])
        self.assertEqual(HISTORY_PATH, self.client.calls[0][0])
        self.assertEqual({'user_id': '', 'email': 'user@example.invalid'}, self.client.calls[0][1])

    def test_history_always_rechecks_source_and_does_not_use_stale_display_cache(self):
        self.client.values = [item()]
        self.assertEqual(1, len(self.service.announcement_history('valid-token')['results']))
        self.client.values = []
        self.assertEqual([], self.service.announcement_history('valid-token')['results'])
        self.client.fail = True
        with self.assertRaises(ApiError) as caught:
            self.service.announcement_history('valid-token')
        self.assertEqual(503, caught.exception.status)
        self.assertEqual(3, len(self.client.calls))

    def test_exact_retention_boundary_excluded_but_old_active_is_not_falsely_archived(self):
        self.client.values = [item(time=int(time.time()) - 14 * 86400),
                              item(id=20, time=int(time.time()) - 30 * 86400, archived=False, delete_time=0)]
        self.assertEqual([20], [r['id'] for r in self.service.announcement_history('valid-token')['results']])

    def test_malformed_duplicate_or_oversized_history_is_denied(self):
        for values in ([item(), item()], [item(archived='true')], [item(id='19')], [item(subject='')],
                       [item(id=i) for i in range(1, 502)]):
            self.client.values = values
            with self.subTest(values_count=len(values)):
                with self.assertRaises(ApiError):
                    self.service.announcement_history('valid-token')

    def test_unknown_retention_or_future_publication_is_denied(self):
        self.client.retention = 90
        with self.assertRaises(ApiError):
            self.service.announcement_history('valid-token')
        self.client.retention = 14
        self.client.values = [item(time=int(time.time()) + 3600)]
        with self.assertRaises(ApiError):
            self.service.announcement_history('valid-token')

    def test_bearer_authentication_precedes_history_fetch(self):
        with self.assertRaises(AssertionError):
            self.service.announcement_history('not-valid')
        self.assertEqual([], self.client.calls)


if __name__ == '__main__':
    unittest.main()
