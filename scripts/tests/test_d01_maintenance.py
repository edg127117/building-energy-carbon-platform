"""Offline privilege-boundary tests; no live systemd, Docker or database calls."""
import hashlib
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import Mock, patch

SCRIPTS = Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


bridge = load("d01_maintenance", "d01_maintenance.py")
installer = load("d01_auth_installer", "Install-D01TemporaryAuthorization.py")


@unittest.skipUnless(sys.platform == "linux", "POSIX permissions and uid checks")
class PrivilegeBoundaryTest(unittest.TestCase):
    def test_arbitrary_program_paths_and_extra_arguments_are_rejected_before_configuration(self):
        for args in (("entry", "/bin/sh"), ("entry", "backup", "--docker-bin", "/tmp/evil"),
                     ("entry", "migrate", "--state", "/tmp/forged"), ("entry",),
                     ("entry", "switch-user"), ("entry", "stage-user")):
            with self.subTest(args=args), patch.object(sys, "argv", args), patch.object(bridge, "load_config") as config:
                with self.assertRaises(RuntimeError):
                    bridge.main()
                config.assert_not_called()

    def test_unauthenticated_worker_is_not_exposed_for_direct_import(self):
        with self.assertRaises(AttributeError):
            getattr(bridge, "worker")

    def test_mutable_parent_and_symlink_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            leaf = root / "code.py"
            leaf.write_text("fixture")
            leaf.chmod(0o644)
            root.chmod(0o777)
            with self.assertRaisesRegex(RuntimeError, "UNPROTECTED"):
                bridge.protected(leaf)
            link = root / "link"
            link.symlink_to(leaf)
            with self.assertRaisesRegex(RuntimeError, "UNPROTECTED"):
                bridge.protected(link)

    def test_schema_uses_pinned_copy_and_rejects_mutable_candidate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / "application.jar"
            jar.write_bytes(b"administrator pinned executable")
            operation = object.__new__(bridge.Maintenance)
            operation.release = Mock(JAR=jar.name)
            operation.schema_credentials = root / "private/schema.json"
            operation.config = {"jar_sha256": bridge.digest(jar), "web_sha256": "b" * 64}
            operation.original_schema = Mock()
            state = {"release": "/user/writable/release", "jar_sha256": bridge.digest(jar), "web_sha256": "b" * 64}
            with patch.object(bridge, "INSTALL", root), patch.object(bridge, "protected"):
                operation.schema("upgrade84", state, operation.schema_credentials)
                self.assertEqual(operation.original_schema.call_args.args[1]["release"], str(root))
                operation.original_schema.reset_mock()
                with self.assertRaisesRegex(RuntimeError, "TRUSTED_JAR"):
                    operation.schema("upgrade84", {**state, "jar_sha256": "f" * 64}, operation.schema_credentials)
                operation.original_schema.assert_not_called()

    def test_fixed_worker_command_drops_privileges(self):
        operation = object.__new__(bridge.Maintenance)
        operation.operation = {"phase": "AUTHORIZED"}
        operation.config = {"expires_at": 9999999999}
        with patch.object(bridge.subprocess, "run", return_value=Mock(returncode=0)) as command, \
                patch.object(bridge.pwd, "getpwnam", return_value=Mock(pw_uid=1000, pw_gid=1000)):
            operation.user_action("stage-user")
        args = command.call_args.args[0]
        self.assertEqual(args[:4], ["/usr/bin/python3", "-I", str(bridge.INSTALL / "d01_maintenance.py"),
                                   "--authorized-worker"])
        self.assertEqual(command.call_args.kwargs["user"], 1000)
        self.assertEqual(command.call_args.kwargs["group"], 1000)
        self.assertEqual(command.call_args.kwargs["extra_groups"], [])
        self.assertEqual(len(command.call_args.kwargs["pass_fds"]), 1)
        self.assertNotIn("shell", command.call_args.kwargs)

    def test_worker_rejects_channel_not_created_by_its_root_parent(self):
        parent, child = bridge.socket.socketpair()
        with parent, child, patch.object(bridge, "imported_modules") as modules:
            # Even a socket created by this root test process is not from its
            # parent. No filesystem worker may run on a forged inherited fd.
            with self.assertRaisesRegex(RuntimeError, "ROOT_PARENT_CHANNEL"):
                bridge.authorized_worker(os.dup(child.fileno()), {"expires_at": 9999999999})
            modules.assert_not_called()

    @unittest.skipUnless(getattr(os, "geteuid", lambda: -1)() == 0, "needs isolated root to exercise real uid drop")
    def test_root_parent_socket_authenticates_real_unprivileged_child_and_expiry(self):
        config = {"expires_at": 9999999999}
        code = ("import importlib.util,sys,types; from pathlib import Path; "
                "s=importlib.util.spec_from_file_location('bridge',sys.argv[1]); "
                "m=importlib.util.module_from_spec(s); s.loader.exec_module(m); "
                "m.pwd.getpwnam=lambda user: types.SimpleNamespace(pw_uid=65534); "
                "r=types.SimpleNamespace(BASE=Path('/unused'),stage_package=lambda a,t: print('stage-user:'+str(m.os.getuid()))); "
                "m.imported_modules=lambda: (r,None); "
                "m.authorized_worker(sys.argv[2], {'expires_at':int(sys.argv[3]),'release':'fixture'})")
        for expires, expected_success in ((config["expires_at"], True), (1, False)):
            parent, child = bridge.socket.socketpair()
            with parent, child:
                parent.sendall(json.dumps({"action": "stage-user", "phase": "AUTHORIZED", "expires_at": expires}).encode())
                parent.shutdown(bridge.socket.SHUT_WR)
                result = bridge.subprocess.run([sys.executable, "-I", "-c", code,
                    str(SCRIPTS / "d01_maintenance.py"), str(child.fileno()), str(expires)],
                    pass_fds=(child.fileno(),), user=65534, group=65534, extra_groups=[],
                    capture_output=True, text=True, timeout=10, env=bridge.SAFE_ENV)
            self.assertEqual(result.returncode == 0, expected_success, result.stderr)
            if expected_success:
                self.assertIn("stage-user:65534", result.stdout)

    def test_command_environment_does_not_inherit_python_or_java_injection(self):
        with patch.dict(os.environ, {"PYTHONPATH": "/tmp/evil", "JAVA_TOOL_OPTIONS": "-javaagent:/tmp/evil.jar"}), \
                patch.object(bridge.subprocess, "run") as command:
            command.return_value = Mock(returncode=0, stdout=b"ok")
            bridge.call(["/usr/bin/systemctl", "show", "fixture"])
            self.assertEqual(command.call_args.kwargs["env"], bridge.SAFE_ENV)
            self.assertNotIn("JAVA_TOOL_OPTIONS", command.call_args.kwargs["env"])
            self.assertNotIn("PYTHONPATH", command.call_args.kwargs["env"])

    def test_missing_recent_writer_evidence_never_stops_services(self):
        operation = object.__new__(bridge.Maintenance)
        operation.operation = {"phase": "ONLINE_VERIFIED"}
        operation.release = Mock()
        for record in ({}, {"checked_at": 0, "no_other_deployment": True,
                           "external_writers_fenced": True, "reference": "stale"}):
            with patch.object(bridge, "bounded_input", return_value=record):
                with self.assertRaisesRegex(RuntimeError, "RECENT_WINDOW_CHECK"):
                    operation.freeze()
        operation.release.freeze.assert_not_called()

    def test_stages_cannot_skip_online_restore_final_restore_or_migration(self):
        operation = object.__new__(bridge.Maintenance)
        operation.operation = {"phase": "PREPARED"}
        operation.release = Mock()
        for method in (operation.freeze, operation.backup, operation.migrate, operation.cutover):
            with self.assertRaisesRegex(RuntimeError, "PHASE_MISMATCH"):
                method()
        self.assertEqual(operation.release.mock_calls, [])

    def test_revoke_removes_only_grant_and_copied_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            private = root / "private"
            private.mkdir()
            for name in ("schema.json", "backup.json", "restore-evidence.json"):
                (private / name).write_text("fixture")
            sudoers = root / "grant"
            sudoers.write_text("fixed grant")
            with patch.object(bridge, "DATA", root), patch.object(bridge, "SUDOERS", sudoers), \
                    patch.object(bridge, "protected"), patch.object(bridge, "call") as command:
                bridge.revoke({"sudoers_sha256": bridge.digest(sudoers)})
            self.assertFalse(sudoers.exists())
            self.assertFalse((private / "schema.json").exists())
            self.assertFalse((private / "backup.json").exists())
            self.assertTrue((private / "restore-evidence.json").exists())
            self.assertTrue((private / "revoked.json").exists())
            self.assertTrue(all(call.args[0][-1] == bridge.TIMER + ".timer" for call in command.call_args_list))

    def test_revocation_does_not_delete_changed_administrator_grant(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "grant"
            path.write_text("changed by administrator")
            with patch.object(bridge, "SUDOERS", path), patch.object(bridge, "protected"):
                with self.assertRaisesRegex(RuntimeError, "SUDOERS_CHANGED"):
                    bridge.revoke({"sudoers_sha256": "a" * 64})
            self.assertTrue(path.exists())

    def test_expired_grant_rejects_operations_before_workflow_construction(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "private").mkdir()
            with patch.object(bridge, "DATA", root), patch.object(bridge, "INSTALL", root), \
                    patch.object(bridge, "protected"), patch.object(bridge.os, "chdir"), \
                    patch.object(bridge.os, "geteuid", return_value=0), \
                    patch.object(bridge, "load_config", return_value={"expires_at": 1}), \
                    patch.object(bridge, "Maintenance") as workflow, patch.object(sys, "argv", ["entry", "freeze"]), \
                    patch.dict(os.environ):
                with self.assertRaisesRegex(RuntimeError, "EXPIRED_OR_REVOKED"):
                    bridge.main()
                workflow.assert_not_called()


@unittest.skipUnless(sys.platform == "linux", "installer requires POSIX uid and filesystem semantics")
class InstallerTest(unittest.TestCase):
    def tar(self, names):
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode="w:gz") as archive:
            for name in names:
                info = tarfile.TarInfo(name)
                info.size = 1
                archive.addfile(info, io.BytesIO(b"x"))
        return stream.getvalue()

    def test_bundle_rejects_extra_traversal_and_duplicate_members(self):
        for names in ((*installer.FILES, "../evil"), (*installer.FILES, installer.FILES[0]),
                      ("../" + installer.FILES[0], *installer.FILES[1:])):
            with self.assertRaisesRegex(RuntimeError, "BUNDLE_MEMBERS"):
                installer.bundle_files(self.tar(names))

    def test_only_exact_bundle_files_are_loaded(self):
        self.assertEqual(set(installer.bundle_files(self.tar(installer.FILES))), set(installer.FILES))

    def test_hash_mismatch_never_prompts_for_credentials_or_installs(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "bundle"
            bundle.write_bytes(b"altered")
            with patch.object(installer.os, "geteuid", return_value=0), \
                    patch.object(installer.sys.stdin, "isatty", return_value=True), \
                    patch.object(installer, "credentials") as credentials, patch.dict(os.environ):
                with self.assertRaisesRegex(RuntimeError, "BUNDLE_HASH_MISMATCH"):
                    installer.install(bundle, "a" * 64)
                credentials.assert_not_called()

    def test_failed_install_cleanup_preserves_preexisting_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "private").mkdir()
            old = root / "grant"
            old.write_text("preexisting administrator grant")
            secret = root / "private/schema.json"
            secret.write_text("new secret")
            with patch.object(installer, "DATA", root), patch.object(installer, "SUDOERS", old), \
                    patch.object(installer, "CREATED", [secret]), patch.object(installer, "TIMER_ATTEMPTED", False):
                installer.cleanup_failed_install()
            self.assertTrue(old.exists())
            self.assertFalse(secret.exists())

    def test_install_publishes_exact_rule_only_after_timer_and_keeps_application_running(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
            root = Path(directory)
            base = root / "test"
            previous = base / "releases/previous"
            previous.mkdir(parents=True)
            (base / "current").symlink_to(previous)
            units = root / "units"
            units.mkdir()
            for name, path in (("INSTALL", root / "tools"), ("DATA", root / "data"),
                               ("LAUNCHER", root / "entry"), ("SUDOERS", root / "grant"),
                               ("UNITS", units), ("BASE", base)):
                stack.enter_context(patch.object(installer, name, path))
            stack.enter_context(patch.object(installer, "CREATED", []))
            stack.enter_context(patch.object(installer, "TIMER_ATTEMPTED", False))
            stack.enter_context(patch.object(installer, "trusted_parent"))
            stack.enter_context(patch.object(installer.os, "geteuid", return_value=0))
            stack.enter_context(patch.object(installer.sys.stdin, "isatty", return_value=True))
            stack.enter_context(patch.object(installer.pwd, "getpwnam", return_value=Mock(pw_uid=1000)))
            stack.enter_context(patch.object(installer, "credentials", return_value=({"password": "private-schema"}, {"password": "private-backup"})))
            stack.enter_context(patch.object(installer, "package_manifest", return_value=({"commit": "a" * 40,
                "backendJarSha256": "b" * 64, "webIndexSha256": "c" * 64}, b"pinned jar")))
            stack.enter_context(patch.dict(os.environ))
            calls = []
            def command(*args):
                calls.append(args)
                if "enable" in args:
                    self.assertFalse(installer.SUDOERS.exists())
                return "user1" if "show" in args else "active" if "is-active" in args else ""
            stack.enter_context(patch.object(installer, "run", side_effect=command))
            bundle = root / "bundle.tar.gz"
            bundle.write_bytes(self.tar(installer.FILES))
            installer.install(bundle, hashlib.sha256(bundle.read_bytes()).hexdigest())
            rule = installer.SUDOERS.read_text()
            command_line = next(line for line in rule.splitlines() if line.startswith("user1 "))
            self.assertNotIn("*", command_line)
            self.assertNotIn("/bin/sh", command_line)
            self.assertNotIn("python", command_line)
            self.assertNotIn("docker", command_line)
            self.assertEqual(command_line.split("NOPASSWD: ", 1)[1].split(", "),
                             [str(installer.LAUNCHER) + " " + action for action in installer.ACTIONS])
            self.assertTrue((units / (installer.TIMER + ".timer")).exists())
            self.assertEqual((installer.DATA / "private/schema.json").stat().st_mode & 0o777, 0o600)
            config = json.loads((installer.INSTALL / "config.json").read_text())
            self.assertNotIn("password", json.dumps(config))
            self.assertTrue(all(not any(action in args for action in ("stop", "restart", "start")) for args in calls))
            unrelated = units / "unrelated.service"
            unrelated.write_text("preserved")
            installer.cleanup_failed_install()
            self.assertFalse(installer.INSTALL.exists())
            self.assertFalse(installer.DATA.exists())
            self.assertFalse(installer.SUDOERS.exists())
            self.assertFalse(installer.LAUNCHER.exists())
            self.assertFalse((units / (installer.TIMER + ".timer")).exists())
            self.assertEqual(unrelated.read_text(), "preserved")
            self.assertIn(("/usr/bin/systemctl", "stop", installer.TIMER + ".timer"), calls)
            self.assertIn(("/usr/bin/systemctl", "disable", installer.TIMER + ".timer"), calls)


if __name__ == "__main__":
    unittest.main()
