from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).parents[1] / "deploy" / "install-zimbra-grant-command.py"
SPEC = importlib.util.spec_from_file_location("install_zimbra_grant_command", SCRIPT)
assert SPEC and SPEC.loader
installer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(installer)


class ForcedCommandInstallerTest(unittest.TestCase):
    def test_install_and_remove_preserve_unrelated_keys(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "authorized_keys"
            path.write_text("ssh-ed25519 AAAAexisting other-key\n", encoding="utf-8")
            with patch.object(installer, "AUTHORIZED_KEYS", path):
                installer.update_authorized_keys("192.0.2.5", "ssh-ed25519 AAAAnew pilot")
                content = path.read_text(encoding="utf-8")
                self.assertIn("ssh-ed25519 AAAAexisting other-key", content)
                self.assertIn('from="192.0.2.5",restrict,command=', content)
                self.assertEqual(1, content.count(installer.MARKER))
                installer.update_authorized_keys("192.0.2.6", "ssh-ed25519 AAAAnew pilot")
                self.assertEqual(1, path.read_text(encoding="utf-8").count(installer.MARKER))
                installer.update_authorized_keys(None, None)
                self.assertEqual("ssh-ed25519 AAAAexisting other-key\n", path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
