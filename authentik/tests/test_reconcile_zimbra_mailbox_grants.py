from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).parents[1] / "deploy" / "reconcile-zimbra-mailbox-grants.py"
SPEC = importlib.util.spec_from_file_location("reconcile_zimbra_mailbox_grants", SCRIPT)
assert SPEC and SPEC.loader
reconciler = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reconciler)


class ZimbraGrantReconcilerTest(unittest.TestCase):
    def test_input_is_limited_to_configured_domain(self) -> None:
        self.assertEqual(
            ("prepare", {"user@example.invalid"}),
            reconciler.parse_desired(
                {"mode": "prepare", "emails": ["user@example.invalid"]},
                {"example.invalid"},
            ),
        )
        with self.assertRaisesRegex(reconciler.ReconcileError, "not allowed"):
            reconciler.parse_desired(
                {"mode": "finalize", "emails": ["user@other.invalid"]},
                {"example.invalid"},
            )
        with self.assertRaisesRegex(reconciler.ReconcileError, "invalid mailbox"):
            reconciler.parse_desired(
                {"mode": "prepare", "emails": ["user@example.invalid;touch /tmp/bad"]},
                {"example.invalid"},
            )

    def test_grants_parser_rejects_broad_right(self) -> None:
        row = "account uuid user@example.invalid usr uuid connector adminLoginAs"
        self.assertEqual({"user@example.invalid"}, reconciler.parse_grants(row))
        with self.assertRaisesRegex(reconciler.ReconcileError, "non-account"):
            reconciler.parse_grants("domain uuid example.invalid usr uuid connector adminLoginAs")

    def test_prepare_adds_only_and_finalize_removes_stale(self) -> None:
        current_row = "account uuid old@example.invalid usr uuid connector adminLoginAs"
        calls: list[tuple[str, ...]] = []

        def fake_zmprov(*arguments: str) -> str:
            calls.append(arguments)
            if arguments[0] == "gg":
                return current_row
            if arguments[0] == "ga":
                return "# name new@example.invalid\nzimbraAccountStatus: active\n"
            if arguments[0] == "ckr":
                return "ALLOWED\n" if arguments[2] == "new@example.invalid" else "DENIED\n"
            return ""

        with patch.object(reconciler, "zmprov", side_effect=fake_zmprov), patch.object(reconciler, "save_before"):
            result = reconciler.reconcile("prepare", {"new@example.invalid"})
            self.assertEqual({"mode": "prepare", "desired": 1, "added": 1, "removed": 0}, result)
            self.assertFalse(any(call[0] == "rvr" for call in calls))
            calls.clear()
            result = reconciler.reconcile("finalize", {"new@example.invalid"})
            self.assertEqual(1, result["removed"])
            self.assertIn(("rvr", "account", "old@example.invalid", "usr", reconciler.CONNECTOR, "adminLoginAs"), calls)

    def test_empty_desired_revokes_after_mapping_finalize(self) -> None:
        current_row = "account uuid user@example.invalid usr uuid connector adminLoginAs"
        calls: list[tuple[str, ...]] = []

        def fake_zmprov(*arguments: str) -> str:
            calls.append(arguments)
            if arguments[0] == "gg":
                return current_row
            if arguments[0] == "ckr":
                return "DENIED\n"
            return ""

        with patch.object(reconciler, "zmprov", side_effect=fake_zmprov), patch.object(reconciler, "save_before"):
            self.assertEqual(0, reconciler.reconcile("prepare", set())["removed"])
            self.assertEqual(1, reconciler.reconcile("finalize", set())["removed"])
        self.assertEqual(1, sum(call[0] == "rvr" for call in calls))


if __name__ == "__main__":
    unittest.main()
