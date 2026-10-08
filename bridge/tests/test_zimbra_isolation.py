from __future__ import annotations

import io
import unittest
import urllib.error
from unittest.mock import Mock, patch

from mission_leben_bridge.zimbra_waitset import SOAP, ZIMBRA, ZimbraSoapClient, ZimbraSoapError
from mission_leben_bridge.zimbra_worker import ZimbraWorker


def fault_xml(code: str) -> bytes:
    return (
        f'<Envelope xmlns="{SOAP}"><Body><Fault><Reason><Text>Denied</Text></Reason>'
        f'<Detail><Error xmlns="{ZIMBRA}"><Code>{code}</Code></Error></Detail>'
        '</Fault></Body></Envelope>'
    ).encode()


class SoapFaultTest(unittest.TestCase):
    def setUp(self) -> None:
        self.soap = ZimbraSoapClient("https://invalid/admin", "https://invalid/mail", "user", "secret")
        self.soap.auth_token = "token"

    def test_http_500_preserves_structured_permission_code(self) -> None:
        error = urllib.error.HTTPError("https://invalid/admin", 500, "error", {}, io.BytesIO(fault_xml("service.PERM_DENIED")))
        self.addCleanup(error.close)
        with patch("urllib.request.urlopen", side_effect=error):
            with self.assertRaises(ZimbraSoapError) as result:
                self.soap.create_waitset(["account"])
        self.assertEqual("service.PERM_DENIED", result.exception.code)

    def test_http_200_fault_preserves_code(self) -> None:
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.read.return_value = fault_xml("account.NO_SUCH_ACCOUNT")
        with patch("urllib.request.urlopen", return_value=response):
            with self.assertRaises(ZimbraSoapError) as result:
                self.soap.create_waitset(["account"])
        self.assertEqual("account.NO_SUCH_ACCOUNT", result.exception.code)

    def test_non_soap_http_failure_never_becomes_an_account_fault(self) -> None:
        error = urllib.error.HTTPError("https://invalid/admin", 503, "error", {}, io.BytesIO(b"service.PERM_DENIED"))
        self.addCleanup(error.close)
        with patch("urllib.request.urlopen", side_effect=error):
            with self.assertRaises(ZimbraSoapError) as result:
                self.soap.create_waitset(["account"])
        self.assertEqual("", result.exception.code)


class IsolatingSoap:
    def __init__(self, denied: set[str]):
        self.denied = denied
        self.created: dict[str, list[str]] = {}
        self.live: set[str] = set()
        self.calls = 0

    def create_waitset(self, accounts: list[str]) -> tuple[str, str]:
        self.calls += 1
        if self.denied.intersection(accounts):
            raise ZimbraSoapError("cannot access mailbox", code="service.PERM_DENIED")
        identifier = f"waitset-{self.calls}"
        self.created[identifier] = accounts
        self.live.add(identifier)
        return identifier, "0"

    def destroy_waitset(self, identifier: str) -> None:
        self.live.remove(identifier)


class WaitSetIsolationTest(unittest.TestCase):
    def worker(self, soap: object, count: int = 8) -> ZimbraWorker:
        return ZimbraWorker(soap, Mock(), {str(index): {"subject": f"subject-{index}"} for index in range(count)})

    def test_normal_path_uses_one_waitset_without_probes(self) -> None:
        soap = IsolatingSoap(set())
        worker = self.worker(soap)
        identifier, _, active = worker._create_waitset()
        self.assertEqual(set(worker.account_map), active)
        self.assertEqual({identifier}, soap.live)
        self.assertEqual(1, soap.calls)

    def test_one_denied_account_cannot_block_others_or_leak_probes(self) -> None:
        soap = IsolatingSoap({"3"})
        worker = self.worker(soap)
        identifier, _, active = worker._create_waitset()
        self.assertEqual(set(worker.account_map) - {"3"}, active)
        self.assertEqual({identifier}, soap.live)
        self.assertEqual(active, set(soap.created[identifier]))
        self.assertEqual(8, len(worker.account_map))

    def test_one_denied_among_2000_needs_logarithmic_requests(self) -> None:
        soap = IsolatingSoap({"1017"})
        identifier, _, active = self.worker(soap, 2000)._create_waitset()
        self.assertEqual(1999, len(active))
        self.assertLessEqual(soap.calls, 26)
        self.assertEqual({identifier}, soap.live)

    def test_multiple_and_all_denied_accounts(self) -> None:
        soap = IsolatingSoap({"1", "4", "7"})
        self.assertEqual({"0", "2", "3", "5", "6"}, self.worker(soap)._create_waitset()[2])
        for count in (1, 8):
            soap = IsolatingSoap({str(index) for index in range(count)})
            with self.assertRaisesRegex(ZimbraSoapError, "No mapped"):
                self.worker(soap, count)._create_waitset()
            self.assertEqual(set(), soap.live)

    def test_transport_and_authentication_failures_do_not_isolate_accounts(self) -> None:
        for code in ("", "service.AUTH_REQUIRED", "service.FAILURE"):
            soap = Mock()
            soap.create_waitset.side_effect = ZimbraSoapError("unavailable", code=code)
            with self.assertRaises(ZimbraSoapError):
                self.worker(soap)._create_waitset()
            soap.create_waitset.assert_called_once()
            soap.destroy_waitset.assert_not_called()

    def test_restored_right_is_automatically_reincluded(self) -> None:
        soap = IsolatingSoap({"3"})
        worker = self.worker(soap)
        identifier, _, active = worker._create_waitset()
        self.assertNotIn("3", active)
        soap.destroy_waitset(identifier)
        soap.denied.clear()
        self.assertIn("3", worker._create_waitset()[2])

    def test_scan_targets_only_accessible_accounts_and_continues_after_failure(self) -> None:
        worker = self.worker(IsolatingSoap({"3"}))
        worker._scan_calendar = Mock(side_effect=[ZimbraSoapError("unavailable"), None])
        worker._initial_calendar_scan({"1", "2"})
        self.assertEqual([(("1",),), (("2",),)], worker._scan_calendar.call_args_list)

    def test_run_continues_waiting_for_remaining_accounts(self) -> None:
        soap = IsolatingSoap({"3"})
        soap.authenticate = Mock()
        worker = self.worker(soap)
        worker._initial_calendar_scan = Mock()

        def wait(*args: object, **kwargs: object) -> tuple[str, list[str]]:
            worker.stop()
            return "1", []

        soap.wait = Mock(side_effect=wait)
        worker.run()
        self.assertEqual(set(), soap.live)
        self.assertEqual(set(worker.account_map) - {"3"}, worker._initial_calendar_scan.call_args[0][0])
        soap.wait.assert_called_once()

    def test_run_rechecks_isolated_accounts_after_five_minutes(self) -> None:
        soap = IsolatingSoap({"3"})
        soap.authenticate = Mock()
        worker = self.worker(soap)
        worker._initial_calendar_scan = Mock()
        now = [0.0]

        def wait(*args: object, **kwargs: object) -> tuple[str, list[str]]:
            if soap.denied:
                soap.denied.clear()
                now[0] += 301
            else:
                worker.stop()
            return "1", []

        soap.wait = Mock(side_effect=wait)
        with patch("mission_leben_bridge.zimbra_worker.time.monotonic", side_effect=lambda: now[0]):
            worker.run()
        self.assertEqual(2, worker._initial_calendar_scan.call_count)
        self.assertEqual({"3"}, worker._initial_calendar_scan.call_args[0][0])
        self.assertEqual(set(), soap.live)

    def test_persistently_isolated_account_does_not_rescan_every_other_calendar(self) -> None:
        soap = IsolatingSoap({"3"})
        soap.authenticate = Mock()
        worker = self.worker(soap)
        worker._initial_calendar_scan = Mock()
        now = [0.0]

        def wait(*args: object, **kwargs: object) -> tuple[str, list[str]]:
            now[0] += 301
            if soap.wait.call_count == 2:
                worker.stop()
            return "1", []

        soap.wait = Mock(side_effect=wait)
        with patch("mission_leben_bridge.zimbra_worker.time.monotonic", side_effect=lambda: now[0]):
            worker.run()
        worker._initial_calendar_scan.assert_called_once()
        self.assertEqual(2, soap.authenticate.call_count)
        self.assertEqual(set(), soap.live)
