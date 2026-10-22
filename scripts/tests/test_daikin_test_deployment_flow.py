"""Linux 离线发布流程：替换系统命令与文件路径，不接触运行服务或网络。"""

import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parents[1]


@unittest.skipUnless(sys.platform == "linux", "发布流程需要 Linux bash；Windows 运行配置单元验证")
class TestDeploymentFlow(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.base = self.root / "deploy"
        for name in ["incoming", "shared", "releases/previous/web", "backups"]:
            (self.base / name).mkdir(parents=True)
        self.config = self.root / "control.env"
        self.original = b"UNRELATED=preserved\nDAIKIN_CONTROL_ENABLED=false\n"
        self.config.write_bytes(self.original)
        self.runtime = self.root / "runtime"
        self.runtime.write_text("AUDIT_ENVIRONMENT_MODE=TEST\0")
        (self.base / "current").symlink_to(self.base / "releases/previous")
        self.port80 = self.root / "other-current"
        self.port80.symlink_to("unchanged-other-site")
        (self.base / "shared/nginx-user.conf").write_text(
            f"listen 18080;\nroot {self.base}/current/web;\nproxy_pass http://127.0.0.1:18081;\n"
        )
        script = (SCRIPTS / "Deploy-BuildingEnergyCarbonTest.sh").read_text()
        script = script.replace("/home/user1/deployments/building-energy-carbon-test", str(self.base))
        script = script.replace("/var/www/iot-platform/current", str(self.port80))
        # 仅离线副本免 root；生产脚本仍要求 sudo，其他发布及回滚逻辑原样执行。
        self.assertEqual(script.count("if (( EUID != 0 ));"), 1)
        script = script.replace("if (( EUID != 0 ));", "if false;")
        self.script = self.base / "incoming/Deploy-BuildingEnergyCarbonTest.sh"
        self.script.write_text(script)
        helper = (SCRIPTS / "daikin_test_deployment_config.py").read_text()
        helper = helper.replace("/etc/building-energy-carbon-test/daikin-control-disabled.env", str(self.config))
        # 进程环境是外部依赖，改读离线 fixture；配置准备、校验及恢复仍执行真实 helper。
        expected = 'Path("/proc", pid, "environ").read_bytes()'
        self.assertEqual(helper.count(expected), 1)
        helper = helper.replace(expected, 'Path(os.environ["TEST_RUNTIME_ENV"]).read_bytes()')
        (self.base / "incoming/daikin_test_deployment_config.py").write_text(helper)
        mock = self.root / "bin"
        mock.mkdir()
        systemctl = '''import os,pathlib,sys
a=sys.argv[1:]; root=pathlib.Path(os.environ['TEST_ROOT'])
if a[0]=='show':
    print('user1' if '--property=User' in a else '1')
elif a[0]=='restart':
    if os.environ.get('TEST_FAIL_RESTART')=='1' and not (root/'restart-failed').exists():
        (root/'restart-failed').touch();sys.exit(1)
    config=(root/'control.env').read_text().splitlines()
    mode=os.environ.get('TEST_MODE','TEST')
    entries=['AUDIT_ENVIRONMENT_MODE='+mode]+[s for s in config if '=' in s]
    if os.environ.get('TEST_RUNTIME_OVERRIDE')=='1':entries.append('DAIKIN_CONTROL_ENABLED=false')
    (root/'runtime').write_text('\\0'.join(entries)+'\\0')
'''
        curl = '''import os,pathlib,sys
if '-w' in sys.argv:print('401',end='')
else:print((pathlib.Path(os.environ['TEST_BASE'])/'current/web/index.html').read_text(),end='')
'''
        for name, body in [("systemctl", systemctl), ("curl", curl)]:
            path = mock / name
            path.write_text("#!" + sys.executable + "\n" + body)
            path.chmod(0o755)
        (mock / "runuser").write_text("#!/bin/sh\nexit 0\n")
        (mock / "runuser").chmod(0o755)
        self.env = {**os.environ, "PATH": str(mock) + ":" + os.environ["PATH"],
                    "TEST_ROOT": str(self.root), "TEST_BASE": str(self.base), "TEST_RUNTIME_ENV": str(self.runtime)}

    def deploy(self, name="candidate"):
        package = self.root / ("package-" + name)
        (package / "web").mkdir(parents=True)
        (package / "building-energy-carbon-platform.jar").write_bytes(b"offline candidate")
        (package / "web/index.html").write_text(name)
        sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
        (package / "MANIFEST.txt").write_text(
            "commit=" + "a" * 40 + "\nbackendJarSha256=" + sha(package / "building-energy-carbon-platform.jar")
            + "\nwebIndexSha256=" + sha(package / "web/index.html") + "\n"
        )
        archive = self.base / "incoming" / (name + ".tar.gz")
        with tarfile.open(archive, "w:gz") as stream:
            for path in package.iterdir():
                stream.add(path, arcname=path.name)
        result = subprocess.run(["bash", str(self.script), name, str(archive), sha(archive)],
                                env=self.env, text=True, capture_output=True, timeout=15)
        self.assertEqual(os.readlink(self.port80), "unchanged-other-site")
        return result

    def test_publish_and_next_update_both_keep_test_open(self):
        first = self.deploy()
        self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
        self.assertIn("DAIKIN_TEST_OPEN_DEPLOY_VERIFIED", first.stdout)
        opened = self.config.read_bytes()
        second = self.deploy("next-candidate")
        self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
        self.assertEqual(self.config.read_bytes(), opened)
        self.assertEqual((self.base / "current").resolve(), self.base / "releases/next-candidate")
        self.assertEqual(len(list((self.base / "backups").glob("*/control-before.env"))), 2)

    def test_restart_failure_keeps_candidate_stopped_and_restores_exact_configuration(self):
        self.env["TEST_FAIL_RESTART"] = "1"
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("TEST_DEPLOY_FAIL_STOP_D01_GUARD", result.stderr)
        self.assertEqual(self.config.read_bytes(), self.original)
        self.assertEqual((self.base / "current").resolve(), self.base / "releases/candidate")

    def test_runtime_override_fails_acceptance_and_keeps_candidate_stopped(self):
        self.env["TEST_RUNTIME_OVERRIDE"] = "1"
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("TEST_CONTROL_RUNTIME_CONFIG_MISMATCH", result.stderr)
        self.assertEqual(self.config.read_bytes(), self.original)
        self.assertEqual((self.base / "current").resolve(), self.base / "releases/candidate")

    def test_non_test_environment_never_opens_or_switches_release(self):
        self.env["TEST_MODE"] = "PRODUCTION"
        self.runtime.write_text("AUDIT_ENVIRONMENT_MODE=PRODUCTION\0")
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("EXPLICIT_TEST_ENVIRONMENT_REQUIRED", result.stderr)
        self.assertEqual(self.config.read_bytes(), self.original)
        self.assertEqual((self.base / "current").resolve(), self.base / "releases/previous")

    def test_obsolete_preserve_flag_cannot_keep_test_controls_closed(self):
        self.env["BEC_TEST_PRESERVE_CONTROL_CONFIG"] = "true"
        self.runtime.write_text("AUDIT_ENVIRONMENT_MODE=TEST\0DAIKIN_CONTROL_ENABLED=false\0")
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("DAIKIN_TEST_OPEN_DEPLOY_VERIFIED", result.stdout)
        self.assertIn(b"DAIKIN_CONTROL_ENABLED=true\n", self.config.read_bytes())
        self.assertIn(b"DAIKIN_CONTROL_TEST_OPEN_CONTROLS=true\n", self.config.read_bytes())
        backups = list((self.base / "backups").glob("*/control-before.env"))
        self.assertEqual(len(backups), 1)
        self.assertEqual(backups[0].read_bytes(), self.original)


if __name__ == "__main__":
    unittest.main()
