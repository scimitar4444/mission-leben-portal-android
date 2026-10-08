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
ACCOUNT_ID = "11111111-1111-4111-8111-111111111111"
OTHER_ID = "22222222-2222-4222-8222-222222222222"


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
        row = f"account {ACCOUNT_ID} user@example.invalid usr uuid connector adminLoginAs"
        self.assertEqual({ACCOUNT_ID}, reconciler.parse_grants(row))
        with self.assertRaisesRegex(reconciler.ReconcileError, "non-account"):
            reconciler.parse_grants("domain uuid example.invalid usr uuid connector adminLoginAs")

    def test_prepare_adds_only_and_finalize_removes_stale(self) -> None:
        current_row = f"account {ACCOUNT_ID} old@example.invalid usr uuid connector adminLoginAs"
        calls: list[tuple[str, ...]] = []

        def fake_zmprov(*arguments: str, input_text: str | None = None) -> str:
            calls.append(arguments)
            if not arguments:
                return f"# name old@example.invalid\nzimbraId: {ACCOUNT_ID}\n"
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
            self.assertFalse(any(call and call[0] == "rvr" for call in calls))
            calls.clear()
            result = reconciler.reconcile("finalize", {"new@example.invalid"})
            self.assertEqual(1, result["removed"])
            self.assertIn(("rvr", "account", ACCOUNT_ID, "usr", reconciler.CONNECTOR, "adminLoginAs"), calls)

    def test_empty_desired_revokes_after_mapping_finalize(self) -> None:
        current_row = f"account {ACCOUNT_ID} user@example.invalid usr uuid connector adminLoginAs"
        calls: list[tuple[str, ...]] = []

        def fake_zmprov(*arguments: str, input_text: str | None = None) -> str:
            calls.append(arguments)
            if not arguments:
                return f"# name user@example.invalid\nzimbraId: {ACCOUNT_ID}\n"
            if arguments[0] == "gg":
                return current_row
            if arguments[0] == "ckr":
                return "DENIED\n"
            return ""

        with patch.object(reconciler, "zmprov", side_effect=fake_zmprov), patch.object(reconciler, "save_before"):
            self.assertEqual(0, reconciler.reconcile("prepare", set())["removed"])
            self.assertEqual(1, reconciler.reconcile("finalize", set())["removed"])
        self.assertEqual(1, sum(bool(call) and call[0] == "rvr" for call in calls))

    def test_truncated_display_name_never_becomes_a_mailbox_target(self) -> None:
        email = "firstname.longlastname@example.invalid"
        row = f"account {ACCOUNT_ID} {email[:28]} usr uuid connector adminLoginAs"
        calls = []

        def fake_zmprov(*arguments: str, input_text: str | None = None) -> str:
            calls.append(arguments)
            if not arguments:
                self.assertEqual(f"ga {ACCOUNT_ID} zimbraId\nquit\n", input_text)
                return f"prov> ga {ACCOUNT_ID} zimbraId\n# name {email}\nzimbraId: {ACCOUNT_ID}\nprov> quit\n"
            if arguments[0] == "gg":
                return row
            if arguments[0] == "ckr":
                return "DENIED\n"
            return ""

        with patch.object(reconciler, "zmprov", side_effect=fake_zmprov), patch.object(reconciler, "save_before"):
            self.assertEqual(0, reconciler.reconcile("finalize", {email})["removed"])
            self.assertEqual(1, reconciler.reconcile("finalize", set())["removed"])
        self.assertIn(("rvr", "account", ACCOUNT_ID, "usr", reconciler.CONNECTOR, "adminLoginAs"), calls)
        self.assertFalse(any(email[:28] in call for call in calls))

    def test_uuid_parser_rejects_command_injection(self) -> None:
        with self.assertRaisesRegex(reconciler.ReconcileError, "invalid account UUID"):
            reconciler.parse_grants("account bad;command target usr uuid connector adminLoginAs")

    def test_current_lookup_is_batched_and_complete(self) -> None:
        output = (
            f"# name first@example.invalid\nzimbraId: {ACCOUNT_ID}\n"
            f"# name second@example.invalid\nzimbraId: {OTHER_ID}\n"
        )
        with patch.object(reconciler, "zmprov", return_value=output) as command:
            self.assertEqual(2, len(reconciler.current_accounts({ACCOUNT_ID, OTHER_ID})))
            self.assertEqual(1, command.call_count)
        with patch.object(reconciler, "zmprov", return_value=output.split("# name second")[0]):
            with self.assertRaisesRegex(reconciler.ReconcileError, "incomplete"):
                reconciler.current_accounts({ACCOUNT_ID, OTHER_ID})

    def test_incomplete_lookup_cannot_change_rights(self) -> None:
        row = f"account {ACCOUNT_ID} truncated usr uuid connector adminLoginAs"
        with patch.object(reconciler, "zmprov", side_effect=[row, "ERROR: account.NO_SUCH_ACCOUNT"]) as command:
            with self.assertRaisesRegex(reconciler.ReconcileError, "incomplete"):
                reconciler.reconcile("finalize", set())
        self.assertEqual(2, command.call_count)

    def test_current_lookup_rejects_unknown_id_and_duplicate_mailbox(self) -> None:
        for output in (
            f"# name user@example.invalid\nzimbraId: {OTHER_ID}\n",
            f"# name user@example.invalid\nzimbraId: {ACCOUNT_ID}\n# name user@example.invalid\nzimbraId: {OTHER_ID}\n",
        ):
            with patch.object(reconciler, "zmprov", return_value=output):
                with self.assertRaises(reconciler.ReconcileError):
                    reconciler.current_accounts({ACCOUNT_ID})


if __name__ == "__main__":
    unittest.main()
