"""Exercise the wrapper with a harmless stand-in for the upstream launcher."""
from pathlib import Path
import json
import os
import subprocess
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parents[1] / 'deploy/cli-metrics-guard/ak-cli-metrics-guard.sh'


class CliMetricsGuardTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='ml-cli-guard-test-')
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        vendor = root / 'vendor'
        vendor.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
metric_dir = os.environ.get('PROMETHEUS_MULTIPROC_DIR')
if sys.argv[1] not in ('server', 'worker', 'allinone', 'debug'):
    pathlib.Path(metric_dir, 'counter_12345.db').write_bytes(b'statistic')
print(json.dumps({'argv': sys.argv[1:], 'metrics': metric_dir,
                  'tmpdir': os.environ.get('TMPDIR'), 'stdin': sys.stdin.read()}))
sys.exit(int(os.environ.get('ML_TEST_EXIT', '0')))
''')
        vendor.chmod(0o755)
        self.wrapper = root / 'ak'
        self.wrapper.write_text(SOURCE.read_text().replace('/lifecycle/ak-vendor', str(vendor)))
        self.wrapper.chmod(0o755)
        self.env = dict(os.environ, TMPDIR='/dev/shm',
                        PROMETHEUS_MULTIPROC_DIR='/dev/shm/authentik_prometheus_tmp')

    def run_guard(self, *args, status=0, input=''):
        result = subprocess.run([str(self.wrapper), *args], input=input, text=True,
                                capture_output=True, env=dict(self.env, ML_TEST_EXIT=str(status)))
        self.assertEqual(result.returncode, status, result.stderr)
        return json.loads(result.stdout)

    def test_long_running_commands_keep_existing_environment(self):
        for command in ('server', 'worker', 'allinone', 'debug'):
            data = self.run_guard(command)
            self.assertEqual(data['metrics'], '/dev/shm/authentik_prometheus_tmp')
            self.assertEqual(data['tmpdir'], '/dev/shm')

    def test_healthcheck_is_isolated_and_cleaned(self):
        data = self.run_guard('healthcheck')
        self.assertTrue(data['metrics'].startswith('/tmp/ml-authentik-cli-metrics.'))
        self.assertEqual(data['tmpdir'], '/dev/shm')
        self.assertFalse(Path(data['metrics']).exists())

    def test_shell_arguments_and_stdin_are_preserved(self):
        args = ('shell', '-c', 'import sys; print(sys.stdin.read())')
        data = self.run_guard(*args, input='bounded projection input\n')
        self.assertEqual(data['argv'], list(args))
        self.assertEqual(data['stdin'], 'bounded projection input\n')
        self.assertFalse(Path(data['metrics']).exists())

    def test_failed_and_sigbus_exit_are_preserved_and_cleaned(self):
        for code in (1, 42, 135):
            data = self.run_guard('shell', status=code)
            self.assertFalse(Path(data['metrics']).exists())

    def test_consecutive_runs_never_reuse_a_directory(self):
        first = self.run_guard('healthcheck')
        second = self.run_guard('healthcheck')
        self.assertNotEqual(first['metrics'], second['metrics'])

    def test_cleanup_does_not_use_recursive_delete_or_change_tmpdir(self):
        source = SOURCE.read_text()
        self.assertNotIn('rm -rf', source)
        self.assertNotIn('export TMPDIR', source)
        self.assertIn("-maxdepth 1 -type f -name '*.db'", source)

    def test_image_preserves_non_root_vendor_user_and_checks_vendor_hash(self):
        source = SOURCE.with_name('Dockerfile').read_text()
        self.assertTrue(source.rstrip().endswith('USER 1000'))
        self.assertIn('ebd34b20ab047cf912e619e7d9aeb260a4f51e3c7840d3691c053ee0de2bb62d', source)
        self.assertIn('sha256sum -c -', source)


if __name__ == '__main__':
    unittest.main()
