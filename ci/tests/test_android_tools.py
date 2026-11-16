"""Execute the actual hosted workflow setup with sdkmanager absent from PATH."""
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


class AndroidVerificationToolTests(unittest.TestCase):
    def test_hosted_step_uses_the_installed_sdk_command_location(self):
        workflow = Path(__file__).resolve().parents[2] / '.github/workflows/release.yml'
        step = workflow.read_text().split('- name: Pinned APK verification tools\n', 1)[1].split('\n      - name:', 1)[0]
        script = textwrap.dedent(step.split('run: |\n', 1)[1])
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manager = root / 'SDK with spaces/cmdline-tools/latest/bin/sdkmanager'
            manager.parent.mkdir(parents=True)
            manager.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$PROBE_ARGS"\n')
            manager.chmod(0o755)
            environment = dict(os.environ, ANDROID_HOME=str(root / 'SDK with spaces'),
                JAVA_HOME_21_X64='/jdk21', GITHUB_ENV=str(root / 'github-env'),
                PROBE_ARGS=str(root / 'arguments'), PATH='/usr/bin:/bin')
            result = subprocess.run(['/bin/bash', '-e', '-c', script], env=environment,
                capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((root / 'arguments').read_text(), 'build-tools;36.0.0\n')
            self.assertEqual((root / 'github-env').read_text(), 'JAVA_HOME=/jdk21\n')
