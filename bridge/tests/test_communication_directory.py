from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from mission_leben_bridge.communication_directory import CommunicationDirectory


class CommunicationDirectoryTest(unittest.TestCase):
    def test_only_talk_entitled_users_are_resolved(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "assignments.json"
            path.write_text(
                json.dumps(
                    {
                        "assignments": [
                            {
                                "subject": "subject-a",
                                "nextcloud_user_id": "user-a",
                                "talk": True,
                            },
                            {
                                "subject": "subject-b",
                                "nextcloud_user_id": "user-b",
                                "talk": False,
                            },
                        ]
                    }
                ),
                encoding="utf-8",
            )
            resolved = CommunicationDirectory(path).talk_subjects(
                {"user-a", "user-b", "unknown"}
            )
            self.assertEqual(("subject-a",), resolved)


if __name__ == "__main__":
    unittest.main()
