"""Read-only export contract; no Authentik database or group mutations."""
import contextlib
import io
import json
import os
import runpy
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import patch


class HelpdeskAssignmentExportTest(unittest.TestCase):
    def export(self, *, email="user@example.invalid", active=True, entitled=True, helpdesk_present=True):
        groups = {"APP_ZIMBRA_USER", "APP_NEXTCLOUD_USER", "APP_NEXTCLOUD_NATIVE"}
        class Query(list):
            def values_list(self, *args, **kwargs): return list(self)
            def order_by(self, *args): return self
        user = types.SimpleNamespace(uid="subject", email=email, attributes={
            "iam_account_kind": "person", "iam_directory_class": "person"}, all_groups=lambda: [])
        slugs = ["zimbra-mail", "talk"] + (["otobo-mitarbeiter"] if helpdesk_present else [])
        models = types.ModuleType("authentik.core.models")
        models.Application = types.SimpleNamespace(objects=types.SimpleNamespace(filter=lambda **k:
            Query(types.SimpleNamespace(slug=slug) for slug in slugs)))
        models.Group = types.SimpleNamespace(objects=types.SimpleNamespace(filter=lambda **k: Query(groups)))
        models.User = types.SimpleNamespace(objects=types.SimpleNamespace(filter=lambda **k: Query([user] if active else [])))
        engine = types.ModuleType("authentik.policies.engine")
        engine.PolicyEngine = lambda app, user: types.SimpleNamespace(build=lambda:
            types.SimpleNamespace(passing=entitled and app.slug == "otobo-mitarbeiter"))
        output = io.StringIO()
        with patch.dict(sys.modules, {"authentik.core.models": models, "authentik.policies.engine": engine}), \
            patch.dict(os.environ, {"ML_COMMUNICATION_SUBJECTS_JSON": '["subject"]'}), contextlib.redirect_stdout(output):
            runpy.run_path(str(Path(__file__).resolve().parents[1] / "export_communication_assignments.py"))
        return json.loads(output.getvalue().removeprefix("ML_COMMUNICATION_ASSIGNMENTS="))

    def test_helpdesk_only_recipient_is_exported_without_new_groups(self):
        result = self.export()
        self.assertGreater(result["generated_at"], 0)
        assignment = result["assignments"][0]
        self.assertTrue(assignment["helpdesk"])
        self.assertEqual("user@example.invalid", assignment["helpdesk_login"])
        self.assertFalse(assignment["zimbra"] or assignment["talk"] or assignment["personal_calendar"])

    def test_denied_inactive_missing_email_or_missing_app_is_not_exported(self):
        for arguments in ({"entitled": False}, {"active": False}, {"email": ""}, {"helpdesk_present": False}):
            self.assertEqual([], self.export(**arguments)["assignments"])
