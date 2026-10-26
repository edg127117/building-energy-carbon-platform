"""D01 发布失败保护：仅在临时目录中替换命令，不接触服务、数据库或网络。"""

import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parents[1]


@unittest.skipUnless(sys.platform == "linux", "发布流程需要 Linux bash；Windows 运行配置单元验证")
class TestD01DeploymentRollbackGuard(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.base = self.root / "deploy"
        for name in ["incoming", "shared", "releases/previous/web", "backups"]:
            (self.base / name).mkdir(parents=True)
        (self.base / "releases/previous/web/index.html").write_text("previous")
        (self.base / "current").symlink_to(self.base / "releases/previous")
        self.port80 = self.root / "other-current"
        self.port80.symlink_to("unchanged-other-site")
        (self.base / "shared/nginx-user.conf").write_text(
            f"listen 18080;\nroot {self.base}/current/web;\nproxy_pass http://127.0.0.1:18081;\n"
        )
        self.config = self.root / "control.env"
        self.original_config = b"UNRELATED=preserved\nDAIKIN_CONTROL_ENABLED=false\n"
        self.config.write_bytes(self.original_config)
        self.runtime = self.root / "runtime"
        self.runtime.write_text("AUDIT_ENVIRONMENT_MODE=TEST\0")
        self.raw_evidence = self.root / "d01-raw-evidence"
        self.raw_evidence.write_text("previous raw evidence\n")

        script = (SCRIPTS / "Deploy-BuildingEnergyCarbonTest.sh").read_text()
        script = script.replace("/home/user1/deployments/building-energy-carbon-test", str(self.base))
        script = script.replace("/var/www/iot-platform/current", str(self.port80))
        self.assertEqual(script.count("if (( EUID != 0 ));"), 1)
        script = script.replace("if (( EUID != 0 ));", "if false;")
        self.script = self.base / "incoming/Deploy-BuildingEnergyCarbonTest.sh"
        self.script.write_text(script)

        helper = (SCRIPTS / "daikin_test_deployment_config.py").read_text()
        helper = helper.replace("/etc/building-energy-carbon-test/daikin-control-disabled.env", str(self.config))
        expected = 'Path("/proc", pid, "environ").read_bytes()'
        self.assertEqual(helper.count(expected), 1)
        helper = helper.replace(expected, 'Path(os.environ["TEST_RUNTIME_ENV"]).read_bytes()')
        helper = helper.replace('elif action == "restore":', 'elif action == "restore":\n        if os.environ.get("TEST_FAIL_RESTORE") == "1": raise SystemExit("fixture restore failure")')
        (self.base / "incoming/daikin_test_deployment_config.py").write_text(helper)

        mock = self.root / "bin"
        mock.mkdir()
        systemctl = '''import os,pathlib,sys
args=sys.argv[1:]
root=pathlib.Path(os.environ['TEST_ROOT'])
base=pathlib.Path(os.environ['TEST_BASE'])
current=(base/'current').resolve()
with (root/'systemctl-events').open('a') as events:
    events.write(args[0] + (' ' + str(current) if args[0] in ('restart','stop') else '') + '\\n')
if args[0]=='is-active':
    sys.exit(0)
if args[0]=='show':
    print('user1' if '--property=User' in args else '1')
    sys.exit(0)
if args[0]=='restart':
    if current.name.startswith('d01-'):
        (root/'d01-raw-evidence').write_text('candidate immutable evidence\\n')
    if os.environ.get('TEST_FAIL_RESTART')=='1':
        sys.exit(1)
    sys.exit(0)
if args[0]=='stop':
    sys.exit(1 if os.environ.get('TEST_FAIL_STOP')=='1' else 0)
raise SystemExit('unexpected systemctl call: ' + ' '.join(args))
'''
        curl = '''import pathlib,sys
if '-w' in sys.argv:
    print('401', end='')
else:
    print((pathlib.Path(__import__('os').environ['TEST_BASE'])/'current/web/index.html').read_text(), end='')
'''
        mv = '''#!/bin/sh
if [ "${TEST_FAIL_RELEASE_MOVE:-}" = 1 ]; then
  exit 1
fi
exec /bin/mv "$@"
'''
        for name, body in [("systemctl", systemctl), ("curl", curl), ("mv", mv)]:
            path = mock / name
            path.write_text("#!" + sys.executable + "\n" + body if name != "mv" else body)
            path.chmod(0o755)
        (mock / "runuser").write_text("#!/bin/sh\nexit 0\n")
        (mock / "runuser").chmod(0o755)
        self.env = {
            **os.environ,
            "PATH": str(mock) + ":" + os.environ["PATH"],
            "TEST_ROOT": str(self.root),
            "TEST_BASE": str(self.base),
            "TEST_RUNTIME_ENV": str(self.runtime),
        }

    def deploy(self, name, expected_archive=None, expected_migration=None, source_migration=None):
        package = self.root / ("package-" + name)
        (package / "web").mkdir(parents=True)
        (package / "building-energy-carbon-platform.jar").write_bytes(b"offline d01 candidate")
        (package / "web/index.html").write_text(name)
        sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
        manifest = ("commit=" + "a" * 40 + "\nbackendJarSha256=" + sha(package / "building-energy-carbon-platform.jar")
                    + "\nwebIndexSha256=" + sha(package / "web/index.html") + "\n")
        if expected_migration is not None:
            manifest += "expectedMigration=" + expected_migration + "\n"
        if source_migration is not None:
            manifest += "sourceMigration=" + source_migration + "\n"
        (package / "MANIFEST.txt").write_text(manifest)
        archive = self.base / "incoming" / (name + ".tar.gz")
        with tarfile.open(archive, "w:gz") as stream:
            for path in package.iterdir():
                stream.add(path, arcname=path.name)
        result = subprocess.run(
            ["bash", str(self.script), name, str(archive), expected_archive or sha(archive)],
            env=self.env,
            text=True,
            capture_output=True,
            timeout=15,
        )
        self.assertEqual(os.readlink(self.port80), "unchanged-other-site")
        return result

    def events(self):
        event_file = self.root / "systemctl-events"
        return event_file.read_text().splitlines() if event_file.exists() else []

    def test_d01_start_failure_keeps_candidate_stopped_without_legacy_restart(self):
        self.env["TEST_FAIL_RESTART"] = "1"
        result = self.deploy("d01-candidate")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("TEST_DEPLOY_FAIL_STOP_D01_GUARD", result.stderr)
        self.assertEqual((self.base / "current").resolve(), self.base / "releases/d01-candidate")
        self.assertEqual(self.raw_evidence.read_text(), "candidate immutable evidence\n")
        self.assertEqual(self.config.read_bytes(), self.original_config)
        events = self.events()
        self.assertIn("restart " + str(self.base / "releases/d01-candidate"), events)
        self.assertIn("stop " + str(self.base / "releases/d01-candidate"), events)
        self.assertNotIn("restart " + str(self.base / "releases/previous"), events)

    def test_pre_switch_failure_restores_control_config_without_stopping_previous_service(self):
        self.env["TEST_FAIL_RELEASE_MOVE"] = "1"
        result = self.deploy("d01-before-switch")

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.base / "current").resolve(), self.base / "releases/previous")
        self.assertEqual(self.config.read_bytes(), self.original_config)
        self.assertEqual(self.raw_evidence.read_text(), "previous raw evidence\n")
        events = self.events()
        self.assertNotIn("restart " + str(self.base / "releases/previous"), events)
        self.assertFalse(any(event.startswith("stop ") for event in events), events)

    def test_generic_deploy_rejects_v88_controlled_package_before_switch(self):
        result = self.deploy("d01-v88", expected_migration="88", source_migration="87")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("D01_STAGED_RELEASE_REQUIRED", result.stderr)
        self.assertEqual((self.base / "current").resolve(), self.base / "releases/previous")
        self.assertEqual(self.config.read_bytes(), self.original_config)
        self.assertFalse(any(event.startswith(("restart ", "stop ")) for event in self.events()), self.events())

    def test_fail_stop_error_is_explicit_and_never_restarts_previous_release(self):
        self.env["TEST_FAIL_RESTART"] = "1"
        self.env["TEST_FAIL_STOP"] = "1"
        result = self.deploy("d01-stop-failure")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("TEST_DEPLOY_FAIL_STOP_FAILED", result.stderr)
        self.assertIn("TEST_DEPLOY_RECOVERY_FAILED", result.stderr)
        self.assertEqual((self.base / "current").resolve(), self.base / "releases/d01-stop-failure")
        self.assertEqual(self.config.read_bytes(), self.original_config)
        events = self.events()
        self.assertIn("stop " + str(self.base / "releases/d01-stop-failure"), events)
        self.assertNotIn("restart " + str(self.base / "releases/previous"), events)

    def test_config_restore_failure_is_explicit_and_keeps_candidate_stopped(self):
        self.env["TEST_FAIL_RESTART"] = "1"
        self.env["TEST_FAIL_RESTORE"] = "1"
        result = self.deploy("d01-config-restore-failure")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("TEST_DEPLOY_CONTROL_RESTORE_FAILED", result.stderr)
        self.assertIn("TEST_DEPLOY_RECOVERY_FAILED", result.stderr)
        self.assertEqual((self.base / "current").resolve(), self.base / "releases/d01-config-restore-failure")
        self.assertIn("stop " + str(self.base / "releases/d01-config-restore-failure"), self.events())
        self.assertNotIn("restart " + str(self.base / "releases/previous"), self.events())


if __name__ == "__main__":
    unittest.main()
