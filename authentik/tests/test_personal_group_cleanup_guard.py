import ast
from pathlib import Path
from types import SimpleNamespace
import unittest


class Query(list):
    def count(self):
        return len(self)


def cleanup_guard(total_bindings, direct_bindings):
    path = Path(__file__).resolve().parents[1] / "reconcile_inactive_personal_devices.py"
    tree = ast.parse(path.read_text())
    node = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "_has_expected_personal_binding")
    namespace = {"DeviceAccessGroup": object,
                 "PolicyBinding": SimpleNamespace(objects=SimpleNamespace(filter=lambda **kwargs: Query(total_bindings))),
                 "DeviceUserBinding": SimpleNamespace(objects=SimpleNamespace(filter=lambda **kwargs: Query(direct_bindings)))}
    exec(compile(ast.Module(body=[node], type_ignores=[]), str(path), "exec"), namespace)
    return namespace["_has_expected_personal_binding"](object())


def direct(**changes):
    fields = {"enabled": True, "negate": False, "user_id": 42, "group_id": None, "policy_id": None}
    fields.update(changes)
    return SimpleNamespace(**fields)


class PersonalCleanupGuardTests(unittest.TestCase):
    def test_simple_empty_personal_binding_keeps_existing_cleanup(self):
        self.assertTrue(cleanup_guard([direct()], [direct()]))

    def test_extra_positive_policy_is_never_deleted_as_empty_personal_group(self):
        self.assertFalse(cleanup_guard([direct(), object()], [direct()]))

    def test_disabled_unknown_policy_also_protects_custom_graph(self):
        self.assertFalse(cleanup_guard([direct(), SimpleNamespace(enabled=False)], [direct()]))

    def test_no_binding_or_multiple_direct_bindings_remain_closed(self):
        self.assertFalse(cleanup_guard([], []))
        self.assertFalse(cleanup_guard([direct(), direct()], [direct(), direct()]))

    def test_not_a_single_direct_enabled_user_binding(self):
        for binding in (direct(enabled=False), direct(negate=True), direct(user_id=None),
                        direct(group_id=3), direct(policy_id="policy")):
            self.assertFalse(cleanup_guard([binding], [binding]))


if __name__ == "__main__":
    unittest.main()
