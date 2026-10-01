import copy
import importlib.util
from pathlib import Path
import unittest


module_spec = importlib.util.spec_from_file_location(
    "handset_provision", Path(__file__).resolve().parents[1] / "provision_shared_handset_exception.py")
provision = importlib.util.module_from_spec(module_spec)
module_spec.loader.exec_module(provision)


def spec():
    return {"account_pk": 42, "account_uuid": "00000000-0000-4000-8000-000000000001",
            "entitlement_uuid": "00000000-0000-4000-8000-000000000002",
            "positive_policy_uuid": "00000000-0000-4000-8000-000000000003", "positive_policy_sha256": "a" * 64,
            "dag_uuid": "00000000-0000-4000-8000-000000000004",
            "direct_binding_uuid": "00000000-0000-4000-8000-000000000005",
            "positive_binding_uuid": "00000000-0000-4000-8000-000000000006"}


def baseline():
    return {"spec": spec(), "user": {"pk": 42, "uuid": spec()["account_uuid"], "username": "team.example",
            "type": "internal", "active": True, "attributes": {"iam_account_kind": "shared", "iam_directory_class": "mailbox",
            "iam_interactive_login_allowed": False, "iam_noninteractive_account": True}},
            "entitlement": {"name": "ENT_SHARED_ACCOUNT_HANDSET", "is_superuser": False, "direct_members": []},
            "positive_policy": {"name": "Mission Leben Zentral Android - Shared-Diensthandy-Ausnahme positiv", "expression_sha256": "a" * 64},
            "groups": [], "devices": [], "bindings": [], "direct_binding_primary": [], "planned_id_collisions": []}


def installed():
    state = baseline()
    identifiers = state["spec"]
    state["groups"] = [{"uuid": identifiers["dag_uuid"], "mode": "all"}]
    common = {"target_id": identifiers["dag_uuid"], "enabled": True, "negate": False,
              "expiring": False, "expires": None, "group_id": None}
    state["bindings"] = [dict(common, pk=identifiers["direct_binding_uuid"], user_id=42, policy_id=None, order=10),
                         dict(common, pk=identifiers["positive_binding_uuid"], user_id=None,
                              policy_id=identifiers["positive_policy_uuid"], order=20, failure_result=False, timeout=2)]
    state["direct_binding_primary"] = [{"pk": identifiers["direct_binding_uuid"], "is_primary": True}]
    return state


class ProvisionGuardTests(unittest.TestCase):
    def test_valid_spec_and_empty_scoped_baseline(self):
        provision.validate_spec(spec())
        provision.assert_prerequisites(baseline())

    def test_spec_rejects_ambiguous_objects_and_hashes(self):
        for key, value in (("account_pk", True), ("account_pk", 0), ("positive_policy_sha256", "A" * 64),
                           ("direct_binding_uuid", spec()["dag_uuid"]), ("extra", "unknown")):
            with self.subTest(key=key, value=value):
                candidate = spec()
                candidate[key] = value
                with self.assertRaises((RuntimeError, ValueError)):
                    provision.validate_spec(candidate)

    def test_flag_drift_and_live_membership_prevent_initial_apply(self):
        for key, value in (("iam_interactive_login_allowed", True), ("iam_noninteractive_account", False),
                           ("iam_account_kind", "person"), ("iam_directory_class", "person")):
            candidate = baseline()
            candidate["user"]["attributes"][key] = value
            with self.assertRaises(RuntimeError):
                provision.assert_prerequisites(candidate)
        candidate = baseline()
        candidate["entitlement"]["direct_members"] = [42]
        with self.assertRaises(RuntimeError):
            provision.assert_prerequisites(candidate)

    def test_existing_device_profile_or_binding_is_not_migrated(self):
        for key in ("groups", "devices", "planned_id_collisions"):
            candidate = baseline()
            candidate[key] = ["existing"]
            with self.assertRaises(RuntimeError):
                provision.assert_prerequisites(candidate)

    def test_policy_source_drift_prevents_apply(self):
        candidate = baseline()
        candidate["positive_policy"]["expression_sha256"] = "b" * 64
        with self.assertRaises(RuntimeError):
            provision.assert_prerequisites(candidate)

    def test_exact_native_graph(self):
        provision.assert_provisioned(installed())

    def test_wrong_graph_any_primary_fallback_or_extra_binding_rejected(self):
        candidates = []
        state = installed(); state["groups"][0]["mode"] = "any"; candidates.append(state)
        state = installed(); state["direct_binding_primary"][0]["is_primary"] = False; candidates.append(state)
        state = installed(); state["bindings"][1]["failure_result"] = True; candidates.append(state)
        state = installed(); state["bindings"][1]["timeout"] = 30; candidates.append(state)
        state = installed(); state["bindings"][1]["enabled"] = False; candidates.append(state)
        state = installed(); state["bindings"].append(dict(state["bindings"][1], pk="unknown")); candidates.append(state)
        state = installed(); state["devices"] = ["unexpected"]; candidates.append(state)
        for candidate in candidates:
            with self.assertRaises(RuntimeError):
                provision.assert_provisioned(candidate)

    def test_cas_digest_catches_primary_binding_drift(self):
        first = installed()
        changed = copy.deepcopy(first)
        changed["direct_binding_primary"][0]["is_primary"] = False
        self.assertNotEqual(provision.digest(first), provision.digest(changed))
        self.assertEqual(provision.digest(first), provision.digest(copy.deepcopy(first)))


if __name__ == "__main__":
    unittest.main()
