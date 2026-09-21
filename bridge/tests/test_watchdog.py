from __future__ import annotations

import os
import subprocess
import tempfile
import time
import unittest
from pathlib import Path


WATCHDOG = Path(__file__).parents[1] / "deploy" / "watchdog.sh"


class WatchdogTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.state = self.root / "docker-state"
        self.runtime = self.root / "runtime"
        self.state.mkdir()
        self.compose = self.root / "compose.yml"
        self.compose.write_text("services: {}\n", encoding="utf-8")
        self.log = self.root / "docker.log"
        self.docker = self.root / "docker"
        self.docker.write_text(
            """#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == "inspect" ]]; then
    container="${@: -1}"
    state_file="$FAKE_DOCKER_STATE/$container"
    if [[ ! -f "$state_file" ]]; then
        exit 1
    fi
    cat "$state_file"
    exit 0
fi
printf '%s\\n' "$*" >> "$FAKE_DOCKER_LOG"
service="${@: -1}"
case "$service" in
    ntfy) container=mission-leben-ntfy ;;
    bridge) container=mission-leben-device-bridge ;;
    zimbra-worker) container=mission-leben-zimbra-worker ;;
    *) exit 2 ;;
esac
printf '%s\\n' 'running|healthy' > "$FAKE_DOCKER_STATE/$container"
""",
            encoding="utf-8",
        )
        self.docker.chmod(0o755)
        self.environment = {
            **os.environ,
            "ML_BRIDGE_COMPOSE_FILE": str(self.compose),
            "ML_BRIDGE_DOCKER_BIN": str(self.docker),
            "ML_BRIDGE_WATCHDOG_STATE_DIR": str(self.runtime),
            "ML_BRIDGE_WATCHDOG_WAIT_SECONDS": "0",
            "ML_BRIDGE_WATCHDOG_COOLDOWN_SECONDS": "600",
            "FAKE_DOCKER_STATE": str(self.state),
            "FAKE_DOCKER_LOG": str(self.log),
        }

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def set_state(self, container: str, value: str) -> None:
        (self.state / container).write_text(value + "\n", encoding="utf-8")

    def all_healthy(self) -> None:
        self.set_state("mission-leben-ntfy", "running|healthy")
        self.set_state("mission-leben-device-bridge", "running|healthy")
        self.set_state("mission-leben-zimbra-worker", "running|healthy")

    def run_watchdog(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["bash", str(WATCHDOG)],
            check=False,
            capture_output=True,
            text=True,
            env=self.environment,
        )

    def test_healthy_stack_is_not_restarted(self) -> None:
        self.all_healthy()

        result = self.run_watchdog()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(self.log.exists())

    def test_unhealthy_worker_is_restarted_and_verified(self) -> None:
        self.all_healthy()
        self.set_state("mission-leben-zimbra-worker", "running|unhealthy")

        result = self.run_watchdog()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("restart zimbra-worker", self.log.read_text(encoding="utf-8"))
        self.assertIn("recovered and is healthy", result.stdout)

    def test_missing_container_is_started_through_compose(self) -> None:
        self.all_healthy()
        (self.state / "mission-leben-ntfy").unlink()

        result = self.run_watchdog()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("up -d --no-deps ntfy", self.log.read_text(encoding="utf-8"))

    def test_recovery_cooldown_prevents_restart_loops(self) -> None:
        self.all_healthy()
        self.set_state("mission-leben-device-bridge", "running|unhealthy")
        self.runtime.mkdir()
        (self.runtime / "bridge.last-recovery").write_text(str(int(time.time())), encoding="ascii")

        result = self.run_watchdog()

        self.assertEqual(1, result.returncode)
        self.assertIn("cooling down", result.stderr)
        self.assertFalse(self.log.exists())


if __name__ == "__main__":
    unittest.main()
