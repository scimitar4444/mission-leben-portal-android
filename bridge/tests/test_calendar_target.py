import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch

from mission_leben_bridge.calendar_target import calendar_target, valid_calendar_target
from mission_leben_bridge.service import _notification_target, ApiError
from mission_leben_bridge.zimbra_worker import ZimbraWorker
from test_zimbra import RecordingSoapClient, RecordingBridge


class CalendarTargetTest(unittest.TestCase):
    def test_local_invitation_and_exact_occurrence_travel_through_worker(self):
        client = RecordingSoapClient()
        client.responses = [ET.fromstring('''<SearchResponse xmlns="urn:zimbraMail">
          <appt id="account-a:123" invId="account-a:123-456" dur="1800000" name="Meeting">
            <inst s="1790146800000" ridZ="20260923T070000Z"/>
            <inst s="1790751600000" ridZ="20260930T070000Z" invId="account-a:123-789" dur="900000"/>
          </appt></SearchResponse>''')]
        appointments = client.upcoming_appointments('account-a')
        bridge = RecordingBridge()
        worker = ZimbraWorker(client, bridge, {'account-a': {'subject': 'person'}})
        with patch('time.time', return_value=1790140000):
            worker._publish_appointments('account-a', appointments)
        self.assertEqual('123-456|20260923T070000Z|1790146800000|1790148600000', bridge.events[0]['target_id'])
        self.assertEqual('123-789|20260930T070000Z|1790751600000|1790752500000', bridge.events[1]['target_id'])
        for event in bridge.events:
            self.assertEqual(event['target_id'], _notification_target('open_calendar', event['target_id']))
            self.assertNotIn('account-a', event['target_id'])
        self.assertEqual('calendar:account-a:account-a:123:1790146800000', bridge.events[0]['source_event_id'])

    def test_single_and_all_day_recurrence_targets(self):
        for recurrence in ('', '20260923', '20260923T070000Z'):
            self.assertTrue(valid_calendar_target(calendar_target('123-456', recurrence, 1790146800000, 1790150400000)))

    def test_invalid_targets_are_rejected_without_arbitrary_urls(self):
        target = '123-456|20260923T070000Z|1790146800000|1790150400000'
        for invalid in (
            'https://evil.invalid', target.replace('123-456', '123'),
            target.replace('123-456', '../edit'), target.replace('123-456', 'other:123-456'),
            target.replace('20260923T070000Z', '20260230T070000Z'),
            target.replace('20260923T070000Z', '20260230'),
            target.replace('20260923T070000Z', '20260923T250000Z'),
            target.replace('1790150400000', '1790146800000'),
            target + '&redirect=https://evil.invalid',
        ):
            with self.subTest(invalid=invalid):
                self.assertFalse(valid_calendar_target(invalid))
                with self.assertRaises(ApiError):
                    _notification_target('open_calendar', invalid)

    def test_missing_invite_id_uses_safe_calendar_fallback(self):
        self.assertEqual('', calendar_target('', '', 1790146800000, 1790150400000))
        self.assertEqual('', _notification_target('open_calendar', ''))
