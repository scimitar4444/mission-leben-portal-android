"""Offline regression checks. No Authentik imports, credentials or database."""
import ast
import hashlib
import importlib.util
import os
from pathlib import Path
import runpy
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
NAMES = ("bootstrap_enrollment_portal.py", "bootstrap_e2e_debug.py")
spec = importlib.util.spec_from_file_location("bootstrap_sync", ROOT / "scripts/sync_authentik_bootstraps.py")
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


class MissingGroup(Exception):
    pass


def fake_group_model(groups):
    def get(name):
        if name not in groups:
            raise MissingGroup(name)
        return groups[name]
    return SimpleNamespace(DoesNotExist=MissingGroup, objects=SimpleNamespace(get=get))


class SourceTests(unittest.TestCase):
    def tree(self, name):
        return ast.parse((ROOT / "authentik" / name).read_text())

    def test_manifest_matches_reviewed_sources(self):
        sync.source_payload(ROOT, ROOT / "authentik/bootstrap-scripts.json")

    def test_both_scripts_refuse_accidental_execution_before_imports(self):
        for name in NAMES:
            with self.subTest(name=name), patch.dict(os.environ, {}, clear=True):
                with self.assertRaisesRegex(RuntimeError, "separate approval"):
                    runpy.run_path(str(ROOT / "authentik" / name))

    def test_unknown_authentik_version_is_blocked(self):
        for name in NAMES:
            # Execute only the stdlib/approval/version preamble, no Django code.
            nodes = []
            for node in self.tree(name).body:
                if isinstance(node, ast.ImportFrom) and node.module.startswith("django"):
                    break
                nodes.append(node)
            code = compile(ast.Module(nodes, type_ignores=[]), name, "exec")
            with self.subTest(name=name), patch.dict(os.environ, {"ML_AUTHENTIK_BOOTSTRAP_APPLY": "1"}), patch.dict("sys.modules", {"authentik": SimpleNamespace(VERSION="2026.11.0")}):
                with self.assertRaisesRegex(RuntimeError, "Unreviewed Authentik version"):
                    exec(code, {})

    def test_only_canonical_initializer_constants(self):
        values = {}
        for node in self.tree(NAMES[0]).body:
            if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "ROLE_GROUPS" for t in node.targets):
                values["roles"] = ast.literal_eval(node.value)
        self.assertEqual(values["roles"], ("BR_IT_MANAGEMENT", "BR_EINRICHTUNGSLEITUNG", "BR_PFLEGEDIENSTLEITUNG"))

    def test_group_model_never_creates_or_mutates_groups(self):
        for name in NAMES:
            source = (ROOT / "authentik" / name).read_text()
            self.assertNotIn("ML_DEVICE_INIT_", source)
            self.assertNotIn("ENT_DEVICE_INITIALIZE_", source)
            for node in ast.walk(self.tree(name)):
                if isinstance(node, ast.Call):
                    call = ast.unparse(node.func)
                    if call.startswith("Group.objects."):
                        self.assertEqual(call, "Group.objects.get")
                    self.assertNotIn(call, {"group.save", "initializer_role.save", "test_organization.save"})

    def test_e2e_membership_write_never_grants_an_initializer_role(self):
        calls = [n for n in ast.walk(self.tree(NAMES[1])) if isinstance(n, ast.Call) and ast.unparse(n.func).startswith("user.groups.")]
        mutations = [n for n in calls if n.func.attr not in {"exclude", "filter", "exists"}]
        self.assertEqual(len(mutations), 1)
        self.assertEqual(ast.unparse(mutations[0]), "user.groups.add(test_organization)")

    def test_portal_missing_or_invalid_group_fails_before_first_write(self):
        tree = self.tree(NAMES[0])
        resolver = next(n for n in tree.body if isinstance(n, ast.For) and ast.unparse(n.iter) == "ROLE_GROUPS")
        first_write = min(n.lineno for n in ast.walk(tree) if isinstance(n, ast.Call) and ast.unparse(n.func).endswith(".update_or_create"))
        self.assertLess(resolver.end_lineno, first_write)
        role = SimpleNamespace(is_superuser=False, attributes={"iam_group_type": "business_role"})
        for groups in ({}, {"BR_IT_MANAGEMENT": SimpleNamespace(is_superuser=True, attributes={"iam_group_type": "business_role"})}):
            with self.assertRaises(RuntimeError):
                exec(compile(ast.Module([resolver], type_ignores=[]), "prerequisites", "exec"), {"Group": fake_group_model(groups), "ROLE_GROUPS": ("BR_IT_MANAGEMENT",), "operator_groups": []})
        namespace = {"Group": fake_group_model({"BR_IT_MANAGEMENT": role}), "ROLE_GROUPS": ("BR_IT_MANAGEMENT",), "operator_groups": []}
        exec(compile(ast.Module([resolver], type_ignores=[]), "prerequisites", "exec"), namespace)
        self.assertEqual(namespace["operator_groups"], [role])

    def test_e2e_prerequisites_do_not_create_missing_groups(self):
        block = next(n for n in self.tree(NAMES[1]).body if isinstance(n, ast.With))
        nodes = []
        for n in block.body:
            if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "provider" for t in n.targets):
                break
            nodes.append(n)
        self.assertEqual(len(nodes), 3)
        namespace = {"Group": fake_group_model({}), "INITIALIZER_ROLE": "BR_EINRICHTUNGSLEITUNG", "TEST_ORGANIZATION": "ORG_E2E_TEST"}
        with self.assertRaisesRegex(RuntimeError, "missing"):
            exec(compile(ast.Module(nodes, type_ignores=[]), "prerequisites", "exec"), namespace)


class TransferTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name) / "admin-changes"
        self.backups = Path(self.temp.name) / "backups"
        self.base.mkdir()
        self.old = {}
        sources = {}
        for i, name in enumerate(NAMES):
            p = self.base / name
            p.write_text(f"# original {i}\n")
            p.chmod(0o640 if i == 0 else 0o600)
            self.old[name] = hashlib.sha256(p.read_bytes()).hexdigest()
            sources[name] = "raise RuntimeError('BOOTSTRAP MUST NEVER EXECUTE DURING TRANSFER')\n"
        self.payload = {"sources": sources, "manifest": {"files": {n: hashlib.sha256(v.encode()).hexdigest() for n, v in sources.items()}}, "apply": True, "expected": self.old.copy()}

    def run_sync(self):
        return sync.remote_sync(self.payload, self.base, self.backups)

    def assert_original(self):
        self.assertEqual({n: hashlib.sha256((self.base / n).read_bytes()).hexdigest() for n in NAMES}, self.old)

    def test_default_verification_makes_no_changes(self):
        self.payload["apply"] = False
        self.assertEqual(self.run_sync()["status"], "drift")
        self.assert_original()
        self.assertFalse(self.backups.exists())

    def test_exact_transfer_preserves_permissions_and_verified_backups_without_execution(self):
        result = self.run_sync()
        self.assertEqual(result["status"], "updated")
        self.assertFalse(result["executed_bootstraps"])
        backup = Path(result["backup"])
        self.assertEqual(stat.S_IMODE(backup.stat().st_mode), 0o700)
        for i, name in enumerate(NAMES):
            self.assertEqual(hashlib.sha256((backup / name).read_bytes()).hexdigest(), self.old[name])
            self.assertEqual((self.base / name).read_text(), self.payload["sources"][name])
            self.assertEqual(stat.S_IMODE((self.base / name).stat().st_mode), 0o640 if i == 0 else 0o600)
        self.payload["expected"] = self.payload["manifest"]["files"]
        self.assertEqual(self.run_sync()["status"], "unchanged")

    def test_unapproved_remote_drift_aborts_before_backup_or_write(self):
        self.payload["expected"][NAMES[1]] = "0" * 64
        with self.assertRaisesRegex(RuntimeError, "Remote source changed"):
            self.run_sync()
        self.assert_original()
        self.assertFalse(self.backups.exists())

    def test_transport_tamper_aborts(self):
        self.payload["sources"][NAMES[0]] += "# tampered\n"
        with self.assertRaisesRegex(RuntimeError, "hash mismatch"):
            self.run_sync()
        self.assert_original()

    def test_invalid_syntax_aborts_before_write(self):
        self.payload["sources"][NAMES[0]] = "def broken("
        self.payload["manifest"]["files"][NAMES[0]] = hashlib.sha256(b"def broken(").hexdigest()
        with self.assertRaises(SyntaxError):
            self.run_sync()
        self.assert_original()

    def test_unexpected_file_is_rejected(self):
        self.payload["sources"]["another.py"] = "pass"
        with self.assertRaisesRegex(RuntimeError, "Unexpected source file set"):
            self.run_sync()
        self.assert_original()

    def test_symlink_destination_is_rejected(self):
        target = self.base / NAMES[0]
        original = self.base / "keep-original"
        target.rename(original)
        target.symlink_to(original)
        with self.assertRaisesRegex(RuntimeError, "single regular file"):
            self.run_sync()

    def test_partial_replace_failure_restores_first_file(self):
        real_replace = os.replace
        def fail_second(src, dst):
            if Path(dst).name == NAMES[1]:
                raise OSError("synthetic replace failure")
            real_replace(src, dst)
        with patch("os.replace", fail_second), self.assertRaisesRegex(OSError, "synthetic"):
            self.run_sync()
        self.assert_original()
        self.assertFalse(list(self.base.glob(".bootstrap-source-*")))


if __name__ == "__main__":
    unittest.main()
