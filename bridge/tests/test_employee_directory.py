from __future__ import annotations

import json
import http.client
import os
import runpy
import tempfile
import threading
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from mission_leben_bridge.authentik import AuthenticationError, UserInfo
from mission_leben_bridge.employee_directory import EmployeeDirectory, DirectoryDenied, DirectoryUnavailable
from mission_leben_bridge.service import BridgeService, ApiError
from mission_leben_bridge.config import Settings
from mission_leben_bridge.http_api import BridgeHttpServer


CONTRACT = runpy.run_path(str(Path(__file__).resolve().parents[2] / "authentik/export_employee_directory.py"))


class DirectoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "directory.json"
        self.data = {
            "version": 1, "generated_at": int(time.time()),
            "facilities": {"ORG_ML_H001": "Zentrale", "ORG_ML_H002": "Haus Zwei"},
            "audience": {
                "one": {"kind": "person", "facilities": ["ORG_ML_H001", "ORG_ML_H002"]},
                "two": {"kind": "person", "facilities": ["ORG_ML_H002"]},
                "shared": {"kind": "shared", "facilities": ["ORG_ML_H001"]},
            },
            "devices": {
                "personal": {"mode": "personal", "subject": "one"},
                "tablet": {"mode": "shared", "facility": "ORG_ML_H001"},
            },
            "entries": [
                {"id": "one", "name": "Müller, Maria", "email": "maria@example.invalid", "phone": "+49 123 45",
                 "mobile": "", "job_title": "Pflege", "department": "Team A", "facilities": ["ORG_ML_H001"]},
                {"id": "two", "name": "Test, Theo", "email": "theo@example.invalid", "phone": "", "mobile": "+49 170 123",
                 "job_title": "Verwaltung", "department": "Team B", "facilities": ["ORG_ML_H002"]},
            ],
        }
        self.write()
        self.directory = EmployeeDirectory(self.path)

    def write(self):
        temp = self.path.with_suffix(".new")
        temp.write_text(json.dumps(self.data), encoding="utf-8")
        temp.replace(self.path)

    def test_company_wide_and_search_unicode_facility_function(self):
        self.assertEqual(self.directory.search("one", "personal")["total"], 2)
        for query in ("müller", "muller", "Zentrale", "pflege", "maria pflege"):
            self.assertEqual(self.directory.search("one", "personal", query)["total"], 1)

    def test_person_multiple_houses_and_tablet_single_house(self):
        self.assertEqual(self.directory.search("one", "personal", mine=True)["total"], 2)
        self.assertEqual(self.directory.search("one", "tablet", mine=True)["total"], 1)
        self.assertEqual(self.directory.search("shared", "tablet")["total"], 2)
        self.assertEqual(self.directory.search("shared", "tablet", mine=True)["my_facilities"], ["Zentrale"])

    def test_missing_house_never_means_all_for_mine(self):
        self.data["audience"]["one"]["facilities"] = []
        self.write()
        self.assertEqual(self.directory.search("one", "personal", mine=True)["results"], [])

    def test_house_options_are_sorted_stable_and_independent_of_search(self):
        options = self.directory.search("one", "personal")["facility_options"]
        self.assertEqual([o["name"] for o in options], ["Haus Zwei", "Zentrale"])
        self.assertEqual(options, self.directory.search("one", "personal", query="no-match", mine=True)["facility_options"])
        self.assertEqual(options, self.directory.search("shared", "tablet")["facility_options"])
        self.data["facilities"]["ORG_ML_H002"] = "Haus Zwei umbenannt"
        self.write()
        self.assertEqual(options[0]["id"], self.directory.search("one", "personal")["facility_options"][0]["id"])

    def test_selected_house_intersects_query_not_authorization(self):
        house = self.directory.search("one", "personal")["facility_options"][0]["id"]
        for subject, device in (("one", "personal"), ("one", "tablet"), ("shared", "tablet")):
            result = self.directory.search(subject, device, facility=house)
            self.assertEqual([e["id"] for e in result["results"]], ["two"])
            self.assertEqual(self.directory.search(subject, device, query="Pflege", facility=house)["total"], 0)
        with self.assertRaises(DirectoryDenied):
            self.directory.search("two", "tablet", facility=house)
        self.assertEqual(self.directory.search("one", "tablet", mine=True)["total"], 1)

    def test_unknown_removed_conflicting_and_oversized_house_fail_closed(self):
        house = self.directory.search("one", "personal")["facility_options"][0]["id"]
        for value, mine in (("missing", False), ("x" * 65, False), (house, True), (None, False)):
            with self.assertRaises(ValueError):
                self.directory.search("one", "personal", mine=mine, facility=value)
        self.data["entries"] = self.data["entries"][:1]
        del self.data["facilities"]["ORG_ML_H002"]
        self.data["audience"]["one"]["facilities"] = ["ORG_ML_H001"]
        self.write()
        with self.assertRaises(ValueError):
            self.directory.search("one", "personal", facility=house)

    def test_selected_house_paging_stays_filtered(self):
        house = self.directory.search("one", "personal")["facility_options"][0]["id"]
        self.data["entries"] += [dict(self.data["entries"][1], id=str(i), name="Person %03d" % i) for i in range(80)]
        self.write()
        first = self.directory.search("one", "personal", facility=house)
        second = self.directory.search("one", "personal", facility=house, offset=first["next_offset"])
        last = self.directory.search("one", "personal", facility=house, offset=second["next_offset"])
        self.assertEqual([len(p["results"]) for p in (first, second, last)], [40, 40, 1])
        self.assertTrue(all(e["facilities"] == ["Haus Zwei"] for p in (first, second, last) for e in p["results"]))
        self.assertIsNone(last["next_offset"])

    def test_initials_apply_to_entire_result_not_first_page(self):
        self.data["entries"] += [dict(self.data["entries"][0], id=str(i), name="Anna %03d" % i) for i in range(90)]
        self.write()
        page = self.directory.search("one", "personal")
        self.assertEqual(page["initials"], ["A", "M", "T"])
        self.assertTrue(all(e["name"].startswith("A") for e in page["results"]))
        selected = self.directory.search("one", "personal", initial="T")
        self.assertEqual([e["id"] for e in selected["results"]], ["two"])
        self.assertEqual(selected["initials"], ["A", "M", "T"])
        first = self.directory.search("one", "personal", initial="A")
        second = self.directory.search("one", "personal", initial="A", offset=first["next_offset"])
        self.assertEqual((first["total"], len(second["results"])), (90, 40))
        self.assertEqual(self.directory.search("one", "personal", query="Pflege", initial="T")["total"], 0)

    def test_initials_respect_house_and_normalize_accents(self):
        self.data["entries"][0]["name"] = "Öztürk, Maria"
        self.data["entries"][1]["name"] = "123"
        self.write()
        page = self.directory.search("one", "tablet", mine=True, initial="O")
        self.assertEqual((page["total"], page["initials"]), (1, ["O"]))
        self.assertEqual(self.directory.search("one", "personal", initial="#")["total"], 1)
        house = self.directory.search("one", "personal")["facility_options"][0]["id"]
        self.assertEqual(self.directory.search("one", "personal", facility=house)["initials"], ["#"])
        for initial in (None, "AA", "a", "Ö", "*", " "):
            with self.assertRaises(ValueError):
                self.directory.search("one", "personal", initial=initial)

    def test_reject_wrong_owner_house_shared_on_personal_and_unknown(self):
        for subject, device in (("two", "personal"), ("two", "tablet"), ("shared", "personal"),
                                ("missing", "tablet"), ("one", "deleted")):
            with self.assertRaises(DirectoryDenied):
                self.directory.search(subject, device)

    def test_deleted_and_disabled_subject_not_in_snapshot_is_denied(self):
        del self.data["audience"]["one"]
        self.data["entries"] = self.data["entries"][1:]
        self.write()
        with self.assertRaises(DirectoryDenied):
            self.directory.search("one", "personal")
        self.assertEqual(self.directory.search("shared", "tablet")["total"], 1)

    def test_stale_future_missing_broken_fail_closed(self):
        for timestamp in (time.time() - 421, time.time() + 60):
            self.data["generated_at"] = timestamp
            self.write()
            with self.assertRaises(DirectoryUnavailable):
                self.directory.search("one", "personal")
        self.path.write_text("broken", encoding="utf-8")
        with self.assertRaises(DirectoryUnavailable):
            self.directory.search("one", "personal")
        self.path.unlink()
        with self.assertRaises(DirectoryUnavailable):
            self.directory.search("one", "personal")

    def test_no_raw_attributes_membership_or_audience_leak(self):
        self.data["entries"][0]["private"] = "do not expose"
        self.write()
        result = self.directory.search("one", "personal")
        self.assertEqual(set(result["results"][0]), {"id", "name", "email", "phone", "mobile", "job_title", "department", "facilities"})
        self.assertNotIn("ORG_ML_", json.dumps(result))
        self.assertNotIn("audience", result)

    def test_paging_bounded_and_stable(self):
        self.data["entries"] = [dict(self.data["entries"][0], id=str(i), name="Person %03d" % i) for i in range(95)]
        self.write()
        first = self.directory.search("one", "personal")
        second = self.directory.search("one", "personal", offset=first["next_offset"])
        last = self.directory.search("one", "personal", offset=second["next_offset"])
        self.assertEqual([len(page["results"]) for page in (first, second, last)], [40, 40, 15])
        self.assertIsNone(last["next_offset"])

    def test_invalid_query(self):
        for query, offset in (("x" * 101, 0), ("", -1), ("", 100001)):
            with self.assertRaises(ValueError):
                self.directory.search("one", "personal", query, offset=offset)

    def test_service_requires_live_bearer_and_device_without_push_dependency(self):
        class Auth:
            def user_info(self, token):
                if token != "good":
                    raise AuthenticationError("rejected")
                return UserInfo("one", "", "")

            def device_id(self, token):
                if token != "device":
                    raise AuthenticationError("revoked", permanent=True)
                return "personal"

        service = BridgeService(None, Auth(), None, (), employee_directory=self.directory)
        self.assertEqual(service.contacts("good", "device", "", False, 0)["total"], 2)
        with self.assertRaises(AuthenticationError):
            service.contacts("bad", "device", "", False, 0)
        for token in ("", "revoked"):
            with self.assertRaises(ApiError) as error:
                service.contacts("good", token, "", False, 0)
            self.assertEqual(error.exception.status, 403)

        with patch.dict(os.environ, {"BRIDGE_DEV_MODE": "true", "BRIDGE_LISTEN_HOST": "127.0.0.1", "BRIDGE_LISTEN_PORT": "0"}, clear=True):
            server = BridgeHttpServer(Settings.from_env(), service)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)

        def request(body, bearer="good", device="device", method="POST"):
            client = http.client.HTTPConnection(*server.server_address, timeout=5)
            headers = {"Content-Type": "application/json", "X-ML-Device-Token": device}
            if bearer:
                headers["Authorization"] = "Bearer " + bearer
            try:
                client.request(method, "/v1/contacts/search", json.dumps(body) if method == "POST" else None, headers)
                response = client.getresponse()
                data = json.loads(response.read())
                self.assertEqual(response.getheader("Cache-Control"), "no-store")
                return response.status, data
            finally:
                client.close()

        status, data = request({"q": "Pflege", "mine": False, "offset": 0})
        self.assertEqual((status, data["total"]), (200, 1))
        house = data["facility_options"][0]["id"]
        status, data = request({"facility": house})
        self.assertEqual((status, [e["id"] for e in data["results"]]), (200, ["two"]))
        for value in (None, [], True, 5, "missing", "x" * 65):
            self.assertEqual(request({"facility": value})[0], 400)
        self.assertEqual(request({"facility": house, "mine": True})[0], 400)
        status, data = request({"initial": "M"})
        self.assertEqual((status, [e["id"] for e in data["results"]]), (200, ["one"]))
        for initial in (None, [], True, 5, "MM", "m"):
            self.assertEqual(request({"initial": initial})[0], 400)
        self.assertEqual(request({}, bearer="")[0], 401)
        self.assertEqual(request({}, bearer="bad")[0], 401)
        self.assertEqual(request({}, device="revoked")[0], 403)
        self.assertEqual(request({"mine": "true"})[0], 400)
        self.assertEqual(request({"offset": True})[0], 400)
        self.assertEqual(request({"q": []})[0], 400)
        self.assertEqual(request({}, method="GET")[0], 404)


class ExportContractTest(unittest.TestCase):
    def user(self, **attrs):
        return SimpleNamespace(uid="test-id", name="Test Person", email="person@example.invalid", is_active=True,
                               type="external", attributes={"iam_account_kind": "person", "iam_directory_class": "person", **attrs})

    def test_only_active_physical_people_listed(self):
        user = self.user()
        self.assertEqual(CONTRACT["account_kind"](user), "person")
        for kind in ("service", "test", "HealthMailbox"):
            self.assertEqual(CONTRACT["account_kind"](self.user(iam_account_kind=kind)), "")
        self.assertEqual(CONTRACT["account_kind"](self.user(iam_directory_class="shared")), "")
        user.is_active = False
        self.assertEqual(CONTRACT["account_kind"](user), "")
        user = self.user()
        user.type = "service_account"
        self.assertEqual(CONTRACT["account_kind"](user), "")

    def test_allowlisted_company_phone_and_mobile_only(self):
        result = CONTRACT["contact"](self.user(telephoneNumber="+49 123/456", mobile="+49 170 999",
                                               homePhone="SECRET", phone_number="SECRET", notes="SECRET"), [])
        self.assertEqual(result["phone"], "+49 123/456")
        self.assertEqual(result["mobile"], "+49 170 999")
        self.assertNotIn("SECRET", json.dumps(result))
        result = CONTRACT["contact"](self.user(phone_number="12345", homePhone="12345"), [])
        self.assertEqual(result["phone"], "")

    def test_dedup_numbers_reject_control_dial_and_email_header_injection(self):
        result = CONTRACT["contact"](self.user(telephoneNumber="+49 123/456", mobile="+49123456"), [])
        self.assertEqual(result["mobile"], "")
        user = self.user(telephoneNumber="*21*123#", mobile="https://example.invalid")
        user.email = "person@example.invalid?bcc=someone@example.invalid"
        result = CONTRACT["contact"](user, [])
        self.assertEqual((result["phone"], result["mobile"], result["email"]), ("", "", ""))

    def test_canonical_facility_not_department(self):
        attrs = {"iam_group_type": "organization_unit", "iam_managed": True, "iam_plan_status": "AKTIV", "iam_org_level": "Einrichtung"}
        predicate = CONTRACT["real_facility"]
        self.assertTrue(predicate("ORG_ML_H002", attrs))
        self.assertTrue(predicate("ORG_ML_H002_01", attrs))
        self.assertFalse(predicate("ORG_ML_ZD_IT", attrs))
        self.assertFalse(predicate("ORG_ML_H002_01", dict(attrs, iam_org_level="Teilbereich")))
        self.assertTrue(predicate("ORG_ML_H001", dict(attrs, iam_org_level="Geschäftseinheit/Standort")))
        self.assertFalse(predicate("ORG_ML_H002", dict(attrs, iam_org_level="Geschäftseinheit/Standort")))

    def test_only_explicit_company_email_domains(self):
        user = self.user()
        for domain in ("mission-leben.de", "akademie-mission-leben.de"):
            user.email = "person@" + domain
            self.assertEqual(CONTRACT["contact"](user, [])["email"], user.email)
        for domain in ("private.invalid", "mission-leben.de.evil.invalid", "mission-leben,de"):
            user.email = "person@" + domain
            self.assertEqual(CONTRACT["contact"](user, [])["email"], "")


if __name__ == "__main__":
    unittest.main()
