import base64
import hashlib
import hmac
import io
import json
import unittest
from unittest.mock import patch

from mission_leben_bridge.nextcloud_announcements import (
    AnnouncementFetchError, NextcloudAnnouncementClient, PUSH_PATH,
)


class AnnouncementPushClientTest(unittest.TestCase):
    def setUp(self):
        self.secret = b's' * 48
        self.client = NextcloudAnnouncementClient('https://cloud.example.invalid', self.secret)

    def test_catalogue_uses_its_own_signed_path_without_recipient_data(self):
        with patch('urllib.request.urlopen', return_value=io.BytesIO(b'{"ids":[12]}')) as call:
            self.assertEqual([12], self.client.catalog())
        request = call.call_args.args[0]
        self.assertEqual('https://cloud.example.invalid' + PUSH_PATH + 'catalog', request.full_url)
        self.assertEqual({}, json.loads(request.data))
        canonical = '\n'.join(('POST', PUSH_PATH + 'catalog', request.get_header('X-ml-timestamp'),
            request.get_header('X-ml-nonce'), hashlib.sha256(request.data).hexdigest())).encode()
        expected = base64.urlsafe_b64encode(hmac.new(self.secret, canonical, hashlib.sha256).digest()).rstrip(b'=').decode()
        self.assertEqual(expected, request.get_header('X-ml-signature'))

    def test_catalogue_rejects_malformed_and_oversized_responses(self):
        for value in ({}, {'ids': [True]}, {'ids': ['12']}, {'ids': [-1]}, {'ids': list(range(1, 502))}):
            with self.subTest(value=str(value)[:40]), patch('urllib.request.urlopen', return_value=io.BytesIO(json.dumps(value).encode())):
                with self.assertRaises(AnnouncementFetchError):
                    self.client.catalog()

    def test_recipient_resolution_requires_exact_complete_matching_results(self):
        recipient = {'key': 'person', 'email': 'person@example.invalid', 'user_id': 'stable-uid'}
        invalid = [[], [{'key': 'other', 'announcements': []}], [{'key': 'person', 'announcements': [{'id': 99}]}],
            [{'key': [], 'announcements': []}], [{'key': 'person', 'announcements': []}] * 2]
        for values in invalid:
            with self.subTest(value=values), patch('urllib.request.urlopen', return_value=io.BytesIO(json.dumps({'recipients': values}).encode())):
                with self.assertRaises(AnnouncementFetchError):
                    self.client.resolve([12], [recipient])
        with patch('urllib.request.urlopen', return_value=io.BytesIO(b'{"recipients":[{"key":"person","announcements":[]}]}')) as call:
            self.assertEqual({'person': []}, self.client.resolve([12], [recipient]))
        self.assertEqual({'ids': [12], 'recipients': [recipient]}, json.loads(call.call_args.args[0].data))

    def test_read_timeout_is_a_retryable_announcement_failure(self):
        with patch('urllib.request.urlopen', side_effect=TimeoutError('synthetic timeout')):
            with self.assertRaises(AnnouncementFetchError):
                self.client.catalog()
