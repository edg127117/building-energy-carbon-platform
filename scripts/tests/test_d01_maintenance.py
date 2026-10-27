"""Offline privilege-boundary tests; no live systemd, Docker or database calls."""
import hashlib
import base64
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
import zipfile
from unittest.mock import Mock, patch

SCRIPTS = Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


bridge = load("d01_maintenance", "d01_maintenance.py")
installer = load("d01_auth_installer", "Install-D01TemporaryAuthorization.py")
credential_source = load("d01_credentials", "d01_credentials.py")


class CredentialReuseTest(unittest.TestCase):
    metadata = "META\t12345678-1234-1234-1234-123456789abc\tOFF\t0\t0\tcm9vdEBsb2NhbGhvc3Q=\n"
    migration_metadata = metadata.replace("cm9vdEBsb2NhbGhvc3Q=", base64.b64encode(b"platform_migrator@%").decode())
    complete = metadata + "GRANT ALL PRIVILEGES ON *.* TO 'root'@'localhost'\n"

    def test_backup_requires_both_databases_and_global_dump_privileges(self):
        grants = ("GRANT SELECT, SHOW VIEW, TRIGGER, EVENT ON `iot_platform`.* TO 'backup'@'localhost'\n"
                  "GRANT PROCESS, SHOW_ROUTINE ON *.* TO 'backup'@'localhost'\n")
        with self.assertRaisesRegex(RuntimeError, "BACKUP_PRIVILEGES_MISSING"):
            credential_source.mysql_rights(self.metadata + grants, "backup")
        grants += "GRANT SELECT, SHOW VIEW, TRIGGER, EVENT ON `iot_platform_archive`.* TO 'backup'@'localhost'\n"
        self.assertEqual(credential_source.mysql_rights(self.metadata + grants, "backup"),
                         "12345678-1234-1234-1234-123456789abc")

    def test_schema_ddl_is_required_and_read_only_source_is_rejected(self):
        dml = "GRANT SELECT, INSERT, UPDATE, DELETE ON `iot_platform`.* TO 'app'@'%'\n"
        with self.assertRaisesRegex(RuntimeError, "MIGRATION_PRIVILEGES_MISSING"):
            credential_source.mysql_rights(self.migration_metadata + dml, "migration")
        with self.assertRaisesRegex(RuntimeError, "TARGET_READ_ONLY"):
            credential_source.mysql_rights(self.migration_metadata.replace("OFF\t0\t0", "OFF\t1\t1"), "migration")

    def test_sufficient_privileges_from_wrong_authenticated_account_are_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "AUTHENTICATED_ACCOUNT_MISMATCH"):
            credential_source.mysql_rights(self.complete, "migration")

    def test_v85_requires_parent_references_but_does_not_grant_anything(self):
        grants = "".join("GRANT SELECT, INSERT, UPDATE, DELETE, CREATE ON `" + db + "`.* TO 'platform_migrator'@'%'\n"
                         for db in ("iot_platform", "iot_platform_archive"))
        output = self.migration_metadata + grants
        credential_source.mysql_rights(output, "migration", "84")
        with self.assertRaisesRegex(RuntimeError, "V85_REFERENCES_PRIVILEGE_MISSING"):
            credential_source.mysql_rights(output, "migration", "85")
        credential_source.mysql_rights(output + "GRANT REFERENCES ON `iot_platform`.* TO 'platform_migrator'@'%'", "migration", "85")

    def test_v88_uses_the_pinned_migration_credential_probe(self):
        grants = "".join("GRANT SELECT, INSERT, UPDATE, DELETE, CREATE ON `" + db + "`.* TO 'platform_migrator'@'%'\n"
                         for db in ("iot_platform", "iot_platform_archive"))
        with self.assertRaisesRegex(RuntimeError, "V88_ALTER_PRIVILEGE_MISSING"):
            credential_source.mysql_rights(self.migration_metadata + grants, "migration", "88")
        credential_source.mysql_rights(self.migration_metadata + grants
                                       + "GRANT ALTER ON `iot_platform`.* TO 'platform_migrator'@'%'", "migration", "88")
        with self.assertRaisesRegex(RuntimeError, "SCHEMA_TARGET_REQUIRED"):
            credential_source.mysql_rights(self.migration_metadata + grants, "migration", "89")

    def test_application_only_uses_select_without_weakening_upgrade_or_backup_privileges(self):
        grants = "".join("GRANT SELECT ON `" + db + "`.* TO 'platform_migrator'@'%'\n"
                         for db in ("iot_platform", "iot_platform_archive"))
        credential_source.mysql_rights(self.migration_metadata + grants, "migration", "88", "APPLICATION_ONLY")
        for mode, target in (("SCHEMA_UPGRADE", "88"), ("APPLICATION_ONLY", "87"), ("unknown", "88")):
            with self.subTest(mode=mode, target=target), self.assertRaises(RuntimeError):
                credential_source.mysql_rights(self.migration_metadata + grants, "migration", target, mode)
        with self.assertRaisesRegex(RuntimeError, "BACKUP_PRIVILEGES_MISSING"):
            credential_source.mysql_rights(self.metadata + "GRANT SELECT ON *.* TO 'root'@'localhost'", "backup", "88", "APPLICATION_ONLY")

    def test_partial_revokes_and_unresolved_roles_never_become_global_permission(self):
        with self.assertRaisesRegex(RuntimeError, "PARTIAL_REVOKE"):
            credential_source.mysql_rights(self.complete + "REVOKE SELECT ON `iot_platform_archive`.* FROM 'root'@'localhost'", "backup")
        with self.assertRaisesRegex(RuntimeError, "MIGRATION_PRIVILEGES_MISSING"):
            credential_source.mysql_rights(self.migration_metadata + "GRANT 'admin_role'@'%' TO 'app'@'%'", "migration")

    def test_gtid_backup_needs_reload_or_flush_tables(self):
        output = self.metadata.replace("OFF", "ON") + "GRANT SELECT, SHOW VIEW, TRIGGER, EVENT, PROCESS ON *.* TO 'backup'@'localhost'\n"
        with self.assertRaisesRegex(RuntimeError, "GTID_PRIVILEGES_MISSING"):
            credential_source.mysql_rights(output, "backup")
        credential_source.mysql_rights(output + "GRANT FLUSH_TABLES ON *.* TO 'backup'@'localhost'", "backup")

    def test_fixed_mysql_backup_probe_never_places_password_in_argv_or_environment(self):
        secret = 'fixture-quoted"password\\only'
        with patch.object(credential_source, "command", return_value=self.complete) as command:
            credential_source.backup_mysql_probe(secret, "a" * 64)
        args, kwargs = command.call_args
        self.assertNotIn(secret, repr(args))
        self.assertNotIn("env", kwargs)
        self.assertEqual(args[0][:4], ["/usr/bin/docker", "exec", "-i", "a" * 64])
        self.assertIn(b'fixture-quoted\\"password\\\\only', kwargs["input_data"])

    def test_runtime_requires_fixed_test_endpoint_and_separate_migration_identity(self):
        values = {**credential_source.EXPECTED, "MYSQL_USER": "platform_app", "TDENGINE_USER": "platform_app"}
        for change, error in (({"MYSQL_PORT": "3306"}, "TARGET_MISMATCH"),
                              ({"AUDIT_ENVIRONMENT_MODE": "PROD"}, "TARGET_MISMATCH"),
                              ({"MYSQL_USER": "platform_migrator"}, "SEPARATE_MIGRATION"),
                              ({"TDENGINE_USER": "root"}, "NONROOT_TDENGINE"),
                              ({"MYSQL_MIGRATION_PASSWORD": "private"}, "ADMIN_CREDENTIAL_IN_RUNTIME")):
            raw = b"\0".join((key + "=" + value).encode() for key, value in {**values, **change}.items())
            with patch.object(credential_source, "command", return_value="123"), patch.object(Path, "read_bytes", return_value=raw):
                with self.assertRaisesRegex(RuntimeError, error):
                    credential_source.runtime_target()

    def test_container_binding_and_volume_identity_fail_before_database_probe(self):
        item = {"Name": "/building-energy-carbon-test-mysql", "State": {"Running": True},
                "Mounts": [{"Destination": "/var/lib/mysql", "Type": "volume", "Name": "unrelated-volume"}],
                "NetworkSettings": {"Ports": {"3306/tcp": [{"HostIp": "127.0.0.1", "HostPort": "13316"}]}}}
        with patch.object(credential_source, "command", return_value=json.dumps([item])):
            with self.assertRaisesRegex(RuntimeError, "CONTAINER_IDENTITY_MISMATCH"):
                credential_source.check_containers()

    def test_td_probe_disables_proxies_and_redirects(self):
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.read.return_value = b'{"code":0,"column_meta":[],"data":[]}'
        with patch.object(credential_source.urllib.request, "build_opener") as opener:
            opener.return_value.open.return_value = response
            credential_source.td_query("private", "SHOW USERS")
        self.assertEqual(opener.call_args.args[0].proxies, {})
        self.assertIsInstance(opener.call_args.args[1], credential_source.NoRedirect)
        self.assertIsNone(opener.call_args.args[1].redirect_request(None, None, 302, None, None, "https://elsewhere"))
        request = opener.return_value.open.call_args.args[0]
        self.assertEqual(request.full_url, "http://127.0.0.1:16041/rest/sql")

    def test_failed_process_output_and_timeout_never_expose_secret(self):
        for result in (Mock(returncode=1, stdout=b"password=private", stderr=b"private"),):
            with patch.object(credential_source.subprocess, "run", return_value=result):
                with self.assertRaisesRegex(RuntimeError, "^D01_CREDENTIAL_PROBE_COMMAND_FAILED$"):
                    credential_source.command(["fixture"])

    def test_td_requires_enabled_admin_and_source_database(self):
        for rows in ([{"name": "root", "super": 0, "enable": 1}], [{"name": "root", "super": 1, "enable": 0}]):
            with patch.object(credential_source, "td_query", return_value=rows):
                with self.assertRaisesRegex(RuntimeError, "BACKUP_PRIVILEGES_MISSING"):
                    credential_source.backup_td_probe("private")
        with patch.object(credential_source, "td_query", side_effect=[
            [{"name": "root", "super": 1, "enable": 1}], [{"name": "unrelated"}]]):
            with self.assertRaisesRegex(RuntimeError, "SOURCE_DATABASE_MISSING"):
                credential_source.backup_td_probe("private")

    def test_discovery_collects_missing_categories_without_prompt_or_secret_output(self):
        output = io.StringIO()
        with patch.object(credential_source, "runtime_target"), patch.object(credential_source, "check_containers"), \
                patch.object(credential_source, "administrator_values", return_value={"unrelated": "hidden-secret"}), \
                patch("builtins.input", side_effect=AssertionError("must not prompt")), contextlib.redirect_stdout(output):
            with self.assertRaisesRegex(RuntimeError, "EXISTING_CREDENTIALS_NOT_READY"):
                credential_source.discover(b"jar")
        self.assertNotIn("hidden-secret", output.getvalue())
        for role in ("MYSQL_MIGRATION", "MYSQL_BACKUP", "TDENGINE_BACKUP"):
            self.assertIn(role + "_CREDENTIAL_MISSING", output.getvalue())

    def test_discovery_binds_same_mysql_source_and_detects_concurrent_service_change(self):
        values = {"mysql_migration_password": "migrate" * 4, "mysql_root_password": "mysql" * 4, "td_root_password": "td" * 8}
        with contextlib.ExitStack() as stack:
            runtime = stack.enter_context(patch.object(credential_source, "runtime_target", return_value=("123", {})))
            containers = stack.enter_context(patch.object(credential_source, "check_containers", return_value={"MYSQL": "a" * 64}))
            stack.enter_context(patch.object(credential_source, "administrator_values", return_value=values))
            stack.enter_context(patch.object(credential_source, "migration_probe", return_value="same-server"))
            backup = stack.enter_context(patch.object(credential_source, "backup_mysql_probe", return_value="other-server"))
            stack.enter_context(patch.object(credential_source, "backup_td_probe"))
            with self.assertRaisesRegex(RuntimeError, "SOURCE_IDENTITY_MISMATCH"):
                credential_source.discover(b"jar")
            backup.return_value = "same-server"
            runtime.side_effect = [("123", {}), ("456", {})]
            with self.assertRaisesRegex(RuntimeError, "SERVICE_CHANGED"):
                credential_source.discover(b"jar")
            runtime.side_effect = None
            containers.side_effect = [{"MYSQL": "a" * 64}, {"MYSQL": "b" * 64}]
            with self.assertRaisesRegex(RuntimeError, "CONTAINERS_CHANGED"):
                credential_source.discover(b"jar")
            containers.side_effect = None
            schema, backup, report = credential_source.discover(b"jar")
            self.assertEqual(schema["user"], "platform_migrator")
            self.assertEqual(backup["tdengine"]["password"], "td" * 8)
            self.assertFalse(report["backup_and_restore_verified"])
            self.assertNotIn("password", json.dumps(report))

    def test_application_only_discovery_passes_mode_to_probe_without_printing_credentials(self):
        values = {"mysql_migration_password": "migrate" * 4, "mysql_root_password": "mysql" * 4, "td_root_password": "td" * 8}
        with patch.object(credential_source, "runtime_target"), patch.object(credential_source, "check_containers"), \
                patch.object(credential_source, "administrator_values", return_value=values), \
                patch.object(credential_source, "migration_probe", return_value="same-mysql") as probe, \
                patch.object(credential_source, "backup_mysql_probe", return_value="same-mysql"), \
                patch.object(credential_source, "backup_td_probe", return_value="td"):
            _, _, report = credential_source.discover(b"pinned-jar", "88", "APPLICATION_ONLY")
        probe.assert_called_once_with(b"pinned-jar", values["mysql_migration_password"], "88", "APPLICATION_ONLY")
        self.assertEqual(report["release_mode"], "APPLICATION_ONLY")
        self.assertNotIn(values["mysql_migration_password"], json.dumps(report))

    @unittest.skipUnless(sys.platform == "linux", "POSIX root configuration boundary")
    def test_credential_source_rejects_symlinks_and_open_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "admin.json"
            config.write_text('{}')
            config.chmod(0o644)
            # A /tmp ancestor is intentionally untrusted, even for a private leaf.
            with patch.object(credential_source, "ADMIN", config):
                with self.assertRaisesRegex(RuntimeError, "NOT_PROTECTED|NOT_PRIVATE"):
                    credential_source.administrator_values()
            link = root / "link.json"
            link.symlink_to(config)
            with patch.object(credential_source, "ADMIN", link):
                with self.assertRaisesRegex(RuntimeError, "NOT_PROTECTED"):
                    credential_source.administrator_values()


@unittest.skipUnless(sys.platform == "linux", "POSIX permissions and uid checks")
class PrivilegeBoundaryTest(unittest.TestCase):
    def test_revocation_config_does_not_depend_on_damaged_migration_modules(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = {"user": "user1", "format": "D01_TEMP_AUTH_V1",
                      "code_sha256": {name: "a" * 64 for name in bridge.CODE_FILES}}
            (root / "config.json").write_text(json.dumps(config))
            with patch.object(bridge, "INSTALL", root), patch.object(bridge, "protected"), \
                    patch.object(bridge, "digest", return_value="b" * 64):
                with self.assertRaisesRegex(RuntimeError, "INSTALLED_CODE_CHANGED"):
                    bridge.load_config()
                self.assertEqual(bridge.load_config(verify_code=False), config)

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
            operation.release.target_schema.side_effect = lambda state: state.get("target_schema", "84")
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
                with self.assertRaisesRegex(RuntimeError, "AUTH_SCHEMA_MISMATCH"):
                    operation.schema("upgrade85", state, operation.schema_credentials)
                operation.config["target_schema"] = "85"
                with self.assertRaisesRegex(RuntimeError, "AUTH_SCHEMA_MISMATCH"):
                    operation.schema("upgrade85", state, operation.schema_credentials)
                operation.schema("upgrade85", {**state, "source_schema": "84", "target_schema": "85"}, operation.schema_credentials)
                operation.config.update(source_schema="85", target_schema="87")
                reporting = {**state, "source_schema": "85", "target_schema": "87"}
                for action in ("validate85", "upgrade86", "upgrade87", "validate87"):
                    operation.schema(action, reporting, operation.schema_credentials)
                for action in ("upgrade85", "upgrade88", "validate84"):
                    with self.assertRaisesRegex(RuntimeError, "AUTH_SCHEMA_MISMATCH"):
                        operation.schema(action, reporting, operation.schema_credentials)
                with self.assertRaisesRegex(RuntimeError, "AUTH_SCHEMA_MISMATCH"):
                    operation.schema("upgrade87", {**reporting, "source_schema": "86"}, operation.schema_credentials)
                operation.config.update(source_schema="87", target_schema="88")
                default_year = {**state, "source_schema": "87", "target_schema": "88"}
                for action in ("validate87", "upgrade88", "validate88"):
                    operation.schema(action, default_year, operation.schema_credentials)
                for action in ("upgrade87", "upgrade89", "validate86"):
                    with self.assertRaisesRegex(RuntimeError, "AUTH_SCHEMA_MISMATCH"):
                        operation.schema(action, default_year, operation.schema_credentials)

    def test_application_only_schema_wrapper_binds_mode_and_has_no_upgrade_action(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / "application.jar"
            jar.write_bytes(b"pinned fixture")
            operation = object.__new__(bridge.Maintenance)
            operation.release = Mock(JAR=jar.name)
            operation.release.target_schema.return_value = "88"
            operation.schema_credentials = root / "schema.json"
            operation.config = {"jar_sha256": bridge.digest(jar), "web_sha256": "b" * 64,
                                "source_schema": "88", "target_schema": "88", "release_mode": "APPLICATION_ONLY"}
            operation.original_schema = Mock()
            state = {**operation.config, "release": "/user/writable/release"}
            with patch.object(bridge, "INSTALL", root), patch.object(bridge, "protected"):
                operation.schema("validate88", state, operation.schema_credentials)
                self.assertEqual(operation.original_schema.call_args.args[1]["release"], str(root))
                operation.original_schema.reset_mock()
                for action in ("upgrade88", "upgrade89", "validate87", "repair", "clean", "baseline"):
                    with self.subTest(action=action), self.assertRaisesRegex(RuntimeError, "AUTH_SCHEMA_MISMATCH"):
                        operation.schema(action, state, operation.schema_credentials)
                with self.assertRaisesRegex(RuntimeError, "AUTH_SCHEMA_MISMATCH"):
                    operation.schema("validate88", {**state, "release_mode": "SCHEMA_UPGRADE"}, operation.schema_credentials)
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
        # 降权进程不依赖 runner 工作目录的遍历权限；按安装后的公开工具权限放置同一模块。
        with tempfile.TemporaryDirectory(dir="/run", prefix="d01-worker-") as directory:
            root = Path(directory)
            root.chmod(0o755)
            module = root / "d01_maintenance.py"
            module.write_bytes((SCRIPTS / "d01_maintenance.py").read_bytes())
            module.chmod(0o644)
            for expires, expected_success in ((config["expires_at"], True), (1, False)):
                parent, child = bridge.socket.socketpair()
                with parent, child:
                    parent.sendall(json.dumps({"action": "stage-user", "phase": "AUTHORIZED", "expires_at": expires}).encode())
                    parent.shutdown(bridge.socket.SHUT_WR)
                    result = bridge.subprocess.run([sys.executable, "-I", "-c", code,
                        str(module), str(child.fileno()), str(expires)],
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
    def test_completed_record_is_archived_only_after_revocation_and_baseline_checks(self):
        for scenario in ("success", "frontend_successor", "backend_changed", "foreign_current",
                         "active_grant", "failed", "credential", "timer", "baseline", "code", "config", "source_mismatch", "completed85", "completed86", "completed87", "completed88", "completed88_application"):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
                root = Path(directory)
                for name, path in (("INSTALL", root / "tools"), ("DATA", root / "data"), ("BASE", root / "test"),
                                   ("LAUNCHER", root / "entry"), ("SUDOERS", root / "grant"),
                                   ("UNITS", root / "units"), ("HISTORY", root / "history")):
                    stack.enter_context(patch.object(installer, name, path))
                private = installer.DATA / "private"
                private.mkdir(parents=True)
                installer.INSTALL.mkdir()
                installer.UNITS.mkdir()
                old_release = "d01-" + "a" * 12
                candidate = installer.BASE / "releases" / old_release
                candidate.mkdir(parents=True)
                (installer.BASE / "current").symlink_to(candidate)
                (candidate / "building-energy-carbon-platform.jar").write_bytes(b"deployed v84")
                if scenario in ("frontend_successor", "backend_changed", "foreign_current"):
                    successor = root / "foreign" if scenario == "foreign_current" else installer.BASE / "releases/frontend-successor"
                    successor.mkdir()
                    (successor / "building-energy-carbon-platform.jar").write_bytes(
                        b"new backend" if scenario == "backend_changed" else b"deployed v84")
                    (installer.BASE / "current").unlink()
                    (installer.BASE / "current").symlink_to(successor)
                config = {"format": "D01_TEMP_AUTH_V1", "user": "user1", "release": old_release,
                          "archive_name": old_release + "-authorized.tar.gz",
                          "jar_sha256": hashlib.sha256(b"deployed v84").hexdigest(),
                          "code_sha256": {name: hashlib.sha256(b"pinned").hexdigest() for name in installer.FILES
                                          if name.endswith(".py") or name == "taosdump-d01"}}
                for name in config["code_sha256"]:
                    (installer.INSTALL / name).write_bytes(b"pinned")
                source = "85" if scenario in ("source_mismatch", "completed85") else "86" if scenario == "completed86" else "87" if scenario == "completed87" else "88" if scenario in ("completed88", "completed88_application") else "84"
                if scenario in ("completed85", "completed86", "completed87", "completed88", "completed88_application"):
                    config["target_schema"] = source
                if scenario == "completed88_application":
                    config.update(source_schema="88", release_mode="APPLICATION_ONLY")
                (installer.INSTALL / "config.json").write_text(json.dumps(config))
                phase = "FAILED" if scenario == "failed" else "AWAITING_BUSINESS_ACCEPTANCE"
                (private / "operation.json").write_text(json.dumps({"phase": phase, "events": [{"phase": phase}],
                                                                   "services_restored_at": 1}))
                (private / "revoked.json").write_text('{"revoked_at":2}')
                (private / "backup-evidence").write_bytes(b"old complete backup retained")
                state = {"phase": "AWAITING_BUSINESS_ACCEPTANCE", "candidate_started": True,
                         "release": str(candidate), "jar_sha256": config["jar_sha256"], "config_hashes": {"config": "hash"}}
                if scenario in ("completed85", "completed86", "completed87", "completed88", "completed88_application"):
                    state["target_schema"] = source
                if scenario == "completed88_application":
                    state.update(source_schema="88", release_mode="APPLICATION_ONLY")
                (private / ("d01-" + old_release + ".json")).write_text(json.dumps(state))
                for path in (installer.LAUNCHER, installer.UNITS / (installer.TIMER + ".service"),
                             installer.UNITS / (installer.TIMER + ".timer")):
                    path.write_text("old installed entry")
                if scenario == "active_grant":
                    installer.SUDOERS.write_text("active grant")
                if scenario == "credential":
                    (private / "schema.json").write_text("unrevoked secret")
                if scenario == "code":
                    (installer.INSTALL / "d01_release.py").write_text("changed")
                stack.enter_context(patch.object(installer, "trusted_parent"))
                baseline = stack.enter_context(patch.object(installer, "retry_baseline"))
                if scenario == "baseline":
                    baseline.side_effect = RuntimeError("D01_OLD_BASELINE_CHANGED")
                stack.enter_context(patch.object(installer, "retry_configuration",
                    return_value={} if scenario == "config" else {"config": "hash"}))
                calls = []
                def run(*args):
                    calls.append(args)
                    return "active" if scenario == "timer" else "inactive" if "ActiveState" in args else "disabled"
                stack.enter_context(patch.object(installer, "run", side_effect=run))
                if scenario in ("success", "frontend_successor", "completed85", "completed86", "completed87", "completed88", "completed88_application"):
                    installer.retire_completed("d01-" + "b" * 12, source)
                    archives = list(installer.HISTORY.iterdir())
                    self.assertEqual(len(archives), 1)
                    self.assertEqual((archives[0] / "data/private/backup-evidence").read_bytes(), b"old complete backup retained")
                    self.assertFalse(installer.INSTALL.exists())
                else:
                    with self.assertRaises(RuntimeError):
                        installer.retire_completed("d01-" + "b" * 12, source)
                    self.assertTrue((private / "backup-evidence").exists())
                    self.assertFalse(installer.HISTORY.exists())
                self.assertFalse(any(verb in args for args in calls for verb in ("stop", "restart", "start")))

    def test_retry_cannot_change_application_only_mode_or_archive_a_different_plan(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tools, data = root / "tools", root / "data"
            tools.mkdir()
            private = data / "private"
            private.mkdir(parents=True)
            config = {"target_schema": "88", "source_schema": "88", "release_mode": "APPLICATION_ONLY"}
            (tools / "config.json").write_text(json.dumps(config))
            (private / "operation.json").write_text('{"phase":"FAILED"}')
            original = (tools / "config.json").read_bytes()
            with patch.object(installer, "INSTALL", tools), patch.object(installer, "DATA", data), \
                    patch.object(installer, "SUDOERS", root / "missing-grant"), patch.object(installer, "trusted_parent"):
                with self.assertRaisesRegex(RuntimeError, "RETRY_SCHEMA_PLAN_MISMATCH"):
                    installer.retire_failed_prepare(source_schema="88", target_schema="88", release_mode="SCHEMA_UPGRADE")
            self.assertEqual((tools / "config.json").read_bytes(), original)
            self.assertTrue((private / "operation.json").exists())

    def test_package_manifest_pins_supported_migrations_and_rejects_mislabeled_jar(self):
        for declared, actual, source, mode, valid in (("84", "84", None, None, True), ("85", "85", None, None, True),
                                                ("84", "85", None, None, False), ("85", "84", None, None, False),
                                                ("86", "85", "85", None, False), ("86", "86", "85", None, True),
                                                ("87", "87", "85", None, True), ("87", "87", "86", None, True),
                                                ("87", "87", None, None, False), ("87", "87", "84", None, False),
                                                ("88", "88", "87", None, True), ("88", "88", "85", None, False),
                                                ("88", "88", "86", None, False), ("88", "88", None, None, False),
                                                ("88", "87", "87", None, False),
                                                ("88", "88", "88", "APPLICATION_ONLY", True),
                                                ("88", "88", "88", None, False),
                                                ("88", "88", "87", "APPLICATION_ONLY", False),
                                                ("88", "88", "89", "APPLICATION_ONLY", False),
                                                ("88", "89", "88", "APPLICATION_ONLY", False),
                                                ("88", "88", "88", "unknown", False),
                                                ("87", "87", "87", "APPLICATION_ONLY", False)):
            jar = io.BytesIO()
            with zipfile.ZipFile(jar, "w") as packaged:
                packaged.writestr("BOOT-INF/classes/db/migration/mysql/V84__mysql_telemetry_immutable_conflicts.sql", "fixture")
                for version in (85, 86, 87, 88):
                    if int(actual) >= version:
                        filename = next((SCRIPTS.parent / "src/env/init").glob("V" + str(version) + "__*.sql")).name
                        packaged.writestr("BOOT-INF/classes/db/migration/mysql/" + filename, "fixture")
                if int(actual) > 88:
                    packaged.writestr("BOOT-INF/classes/db/migration/mysql/V" + actual + "__unreviewed_fixture.sql", "fixture")
                packaged.writestr("BOOT-INF/classes/com/platform/tools/D01SchemaMigration.class", "fixture")
            jar_bytes = jar.getvalue()
            manifest = ("expectedMigration=" + declared + ("\nsourceMigration=" + source if source else "") + ("\nreleaseMode=" + mode if mode else "") + "\ncommit=" + "a" * 40 + "\nbackendJarSha256="
                        + hashlib.sha256(jar_bytes).hexdigest() + "\nwebIndexSha256=" + hashlib.sha256(b"web").hexdigest())
            stream = io.BytesIO()
            with tarfile.open(fileobj=stream, mode="w:gz") as archive:
                for name, content in (("MANIFEST.txt", manifest.encode()), ("building-energy-carbon-platform.jar", jar_bytes),
                                      ("web/index.html", b"web")):
                    info = tarfile.TarInfo(name)
                    info.size = len(content)
                    archive.addfile(info, io.BytesIO(content))
            if valid:
                self.assertEqual(installer.package_manifest(stream.getvalue())[0]["expectedMigration"], declared)
            else:
                with self.subTest(declared=declared, actual=actual, source=source, mode=mode), self.assertRaises(RuntimeError):
                    installer.package_manifest(stream.getvalue())

    @unittest.skipUnless(getattr(os, "geteuid", lambda: -1)() == 0, "real root/user permission regression")
    def test_secret_umask_does_not_block_unprivileged_tool_read(self):
        with tempfile.TemporaryDirectory(dir="/run", prefix="d01-permissions-") as directory:
            root = Path(directory)
            root.chmod(0o755)
            previous = os.umask(0o077)
            try:
                with patch.object(installer, "CREATED", []):
                    installer.mkdir(root / "tools", 0o755)
                    installer.mkdir(root / "private", 0o700)
                    installer.create(root / "tools/config.json", b'{"fixture":true}', 0o644)
                    installer.create(root / "private/schema.json", b'private-fixture', 0o600)
            finally:
                os.umask(previous)
            self.assertEqual((root / "tools").stat().st_mode & 0o777, 0o755)
            self.assertEqual((root / "private").stat().st_mode & 0o777, 0o700)
            code = ("from pathlib import Path; import sys; p=Path(sys.argv[1]); "
                    "assert p.joinpath('tools/config.json').read_text() == '{\"fixture\":true}';\n"
                    "try: p.joinpath('private/schema.json').read_bytes()\n"
                    "except PermissionError: print('PUBLIC_READ_PRIVATE_DENIED')\n"
                    "else: raise AssertionError('private credential readable')")
            result = bridge.subprocess.run([sys.executable, "-I", "-c", code, str(root)],
                user=65534, group=65534, extra_groups=[], env=bridge.SAFE_ENV,
                capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("PUBLIC_READ_PRIVATE_DENIED", result.stdout)

    def test_reinstall_archives_only_revoked_failed_prepare_and_preserves_evidence(self):
        for scenario in ("success", "active_grant", "frozen", "changed_code", "credentials_present", "late_prepare", "rename_failure",
                         "online_success", "online_frozen_field", "online_missing_field", "online_started", "online_missing_backup",
                         "online_old_artifact_changed", "online_new_collision", "online_same_release", "online_baseline_changed",
                         "online_rename_failure", "online_future_event"):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
                root = Path(directory)
                base = root / "test"
                previous = base / "releases/previous"
                previous.mkdir(parents=True)
                (base / "current").symlink_to(previous)
                units = root / "units"
                units.mkdir()
                for name, path in (("INSTALL", root / "tools"), ("DATA", root / "data"),
                                   ("LAUNCHER", root / "entry"), ("SUDOERS", root / "grant"),
                                   ("UNITS", units), ("BASE", base), ("HISTORY", root / "history")):
                    stack.enter_context(patch.object(installer, name, path))
                installer.INSTALL.mkdir()
                private = installer.DATA / "private"
                private.mkdir(parents=True)
                (private / "operation.json").write_text(json.dumps({"phase": "FROZEN" if scenario == "frozen" else "FAILED",
                    "failed_action": "prepare", "events": [{"phase": "FAILED"}]}))
                (private / "revoked.json").write_text('{"revoked_at":123}')
                (private / "audit-evidence.json").write_text('preserved')
                names = [name for name in installer.FILES if name.endswith('.py')]
                for name in names:
                    (installer.INSTALL / name).write_bytes(b"old verified code")
                (installer.INSTALL / "config.json").write_text(json.dumps({"format": "D01_TEMP_AUTH_V1", "user": "user1",
                    "release": "d01-012345abcdef", "archive_name": "d01-012345abcdef-authorized.tar.gz",
                    "previous_release": str(previous), "code_sha256": {name: hashlib.sha256(b"old verified code").hexdigest() for name in names}}))
                installer.LAUNCHER.write_text('fixed old launcher')
                for suffix in ("service", "timer"):
                    (units / (installer.TIMER + "." + suffix)).write_text('old unit')
                if scenario == "active_grant":
                    installer.SUDOERS.write_text('active')
                if scenario == "changed_code":
                    (installer.INSTALL / names[0]).write_text('changed')
                if scenario == "credentials_present":
                    (private / "schema.json").write_text('still present')
                if scenario == "late_prepare":
                    (base / "incoming").mkdir()
                    (base / "incoming/d01-012345abcdef-authorized.tar.gz").write_text('published before later failure')
                online = scenario.startswith('online_')
                kwargs = {}
                if online:
                    kwargs = {'online_backup': True, 'next_release': 'd01-abcdef012345'}
                    old_release = base / 'releases/d01-012345abcdef'
                    (old_release / 'web').mkdir(parents=True)
                    (base / 'incoming').mkdir()
                    artifact = base / 'incoming/d01-012345abcdef-authorized.tar.gz'
                    artifact.write_bytes(b'old package')
                    (old_release / 'building-energy-carbon-platform.jar').write_bytes(b'candidate')
                    (old_release / 'web/index.html').write_bytes(b'web')
                    (previous / 'building-energy-carbon-platform.jar').write_bytes(b'baseline')
                    setting = root / 'runtime.env'; setting.write_bytes(b'unchanged')
                    config = json.loads((installer.INSTALL / 'config.json').read_text())
                    config.update(archive_sha256=hashlib.sha256(b'old package').hexdigest(),
                                  jar_sha256=hashlib.sha256(b'candidate').hexdigest(), web_sha256=hashlib.sha256(b'web').hexdigest())
                    (installer.INSTALL / 'config.json').write_text(json.dumps(config))
                    (private / 'operation.json').write_text(json.dumps({'phase':'FAILED','failed_action':'online-backup','authorized_at':999,
                        'events':[{'phase':'PREPARED','at_utc_epoch':1000},{'phase':'FAILED','at_utc_epoch':1001}]}))
                    if scenario == 'online_future_event':
                        op = json.loads((private / 'operation.json').read_text())
                        op['events'][1]['at_utc_epoch'] = int(installer.time.time()) + 3600
                        (private / 'operation.json').write_text(json.dumps(op))
                    (private / 'online-backup').mkdir()
                    partial = private / 'online-backup/mysql.sql'; partial.write_bytes(b'partial mysql backup')
                    state = dict.fromkeys(('other_release','other_link','other_pid','other_reload','runtime_controls','backup_targets',
                                           'backend_service','web_service'), 'fixture')
                    state.update(format='D01_PREPARED_V1',phase='PREPARED',release=str(old_release),previous_release=str(previous),
                        previous_jar_sha256=hashlib.sha256(b'baseline').hexdigest(),candidate_started=False,
                        jar_sha256=config['jar_sha256'],web_sha256=config['web_sha256'],
                        config_hashes={str(setting):hashlib.sha256(b'unchanged').hexdigest()})
                    if scenario == 'online_frozen_field': state['writers_fenced'] = True
                    if scenario == 'online_missing_field': del state['other_pid']
                    if scenario == 'online_started': state['candidate_started'] = True
                    if scenario == 'online_missing_backup': partial.unlink()
                    if scenario == 'online_old_artifact_changed': artifact.write_bytes(b'tampered')
                    if scenario == 'online_new_collision': (base / 'releases/d01-abcdef012345').mkdir()
                    if scenario == 'online_same_release': kwargs['next_release']='d01-012345abcdef'
                    (private / 'd01-d01-012345abcdef.json').write_text(json.dumps(state))
                    stack.enter_context(patch.object(installer.pwd, 'getpwnam', return_value=Mock(pw_uid=os.getuid())))
                    stack.enter_context(patch.object(installer, 'retry_baseline',
                        side_effect=RuntimeError('D01_OLD_BASELINE_PROCESS_CHANGED') if scenario == 'online_baseline_changed' else None))
                    stack.enter_context(patch.object(installer, 'retry_configuration',
                        return_value={str(setting):hashlib.sha256(b'unchanged').hexdigest()}))
                stack.enter_context(patch.object(installer, "trusted_parent"))
                def state(*args):
                    if "UnitFileState" in args:
                        return "disabled"
                    if "building-energy-carbon-test-platform.service" in args:
                        return "active"
                    return "inactive" if "ActiveState" in args else ""
                commands = stack.enter_context(patch.object(installer, "run", side_effect=state))
                if scenario in ("rename_failure", "online_rename_failure"):
                    original = Path.rename
                    def rename(path, target):
                        if path == installer.LAUNCHER:
                            raise OSError('fixture move failure')
                        return original(path, target)
                    stack.enter_context(patch.object(Path, "rename", rename))
                if scenario in ("success", "rename_failure", "online_success", "online_rename_failure"):
                    if scenario in ("success", "online_success"):
                        installer.retire_failed_prepare(**kwargs)
                    else:
                        with self.assertRaisesRegex(RuntimeError, "ARCHIVE_INCOMPLETE"):
                            installer.retire_failed_prepare(**kwargs)
                    archived = list(installer.HISTORY.iterdir())
                    self.assertEqual(len(archived), 1)
                    self.assertEqual((archived[0] / "data/private/audit-evidence.json").read_text(), 'preserved')
                    self.assertEqual((base / "current").resolve(), previous)
                    if online:
                        self.assertEqual((archived[0] / 'data/private/online-backup/mysql.sql').read_bytes(), b'partial mysql backup')
                        self.assertEqual(artifact.read_bytes(), b'old package')
                        self.assertEqual((old_release / 'building-energy-carbon-platform.jar').read_bytes(), b'candidate')
                    self.assertTrue(all(not any(verb in args for verb in ("stop", "start", "restart"))
                                        for args, _ in commands.call_args_list))
                else:
                    with self.assertRaises(RuntimeError):
                        installer.retire_failed_prepare(**kwargs)
                    self.assertTrue(installer.INSTALL.exists())
                    self.assertFalse(installer.HISTORY.exists())

    def test_failed_authorization_cannot_expand_to_reporting_plan_before_archival(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
            root = Path(directory)
            for name in ("INSTALL", "DATA", "LAUNCHER", "UNITS", "SUDOERS", "HISTORY"):
                stack.enter_context(patch.object(installer, name, root / name.lower()))
            (installer.DATA / "private").mkdir(parents=True)
            installer.INSTALL.mkdir()
            (installer.DATA / "private/operation.json").write_text('{}')
            (installer.INSTALL / "config.json").write_text('{"target_schema":"84","source_schema":"83"}')
            stack.enter_context(patch.object(installer, "trusted_parent"))
            commands = stack.enter_context(patch.object(installer, "run"))
            for online in (False, True):
                with self.assertRaisesRegex(RuntimeError, "RETRY_SCHEMA_PLAN_MISMATCH"):
                    installer.retire_failed_prepare(online_backup=online, next_release="d01-" + "b" * 12,
                                                    source_schema="85", target_schema="87")
            self.assertTrue(installer.INSTALL.exists())
            self.assertFalse(installer.HISTORY.exists())
            commands.assert_not_called()

    def test_online_retry_baseline_rejects_restarts_and_runtime_or_other_site_changes(self):
        runtime = dict.fromkeys(installer.RUNTIME_REQUIRED, 'fixture')
        runtime.update(AUDIT_ENVIRONMENT_MODE='TEST',SERVER_PORT='18081',MYSQL_FLYWAY_ENABLED='false',TDENGINE_INITIALIZATION_ENABLED='false')
        state = {'runtime_controls': runtime, 'other_release':'/old',
                 'other_link':'/old','other_pid':'77','other_reload':'unchanged'}
        for scenario in ('valid','backend_restart','web_restart','runtime','other_pid','other_reload','missing_runtime_key'):
            state['runtime_controls'] = dict(runtime)
            if scenario == 'missing_runtime_key': del state['runtime_controls']['SERVER_PORT']
            def run(*args):
                if 'ActiveState' in args: return 'active'
                if 'ExecMainStartTimestampMonotonic' in args:
                    changed = (scenario == 'backend_restart' and 'building-energy-carbon-test-platform.service' in args
                               or scenario == 'web_restart' and 'building-energy-carbon-test-nginx.service' in args)
                    return '99000000' if changed else '10000000'
                if 'ExecReload' in args: return 'changed' if scenario == 'other_reload' else 'unchanged'
                return '88' if scenario == 'other_pid' and 'nginx.service' in args else '77'
            with self.subTest(scenario=scenario), patch.object(installer,'run',side_effect=run), \
                    patch.object(installer.time,'time',return_value=1000), patch.object(installer.time,'monotonic',return_value=100), \
                    patch.object(Path,'read_bytes',return_value='\0'.join(k+'='+('PROD' if scenario=='runtime' and k=='AUDIT_ENVIRONMENT_MODE' else v) for k,v in runtime.items()).encode()), \
                    patch.object(Path,'resolve',return_value=Path('/old')), patch.object(installer.os,'readlink',return_value='/old'):
                if scenario == 'valid': installer.retry_baseline(state,950)
                else:
                    with self.assertRaises(RuntimeError): installer.retry_baseline(state,950)

    def test_retry_configuration_includes_current_environment_units_and_added_dropins(self):
        control = '/etc/building-energy-carbon-test/daikin-control-disabled.env'
        def run(*args):
            if 'EnvironmentFiles' in args: return control + ' (ignore_errors=no) /etc/runtime.env (ignore_errors=no)'
            if 'FragmentPath' in args: return '/etc/' + args[2]
            if 'DropInPaths' in args: return '/etc/new-dropin.conf'
            raise AssertionError(args)
        with patch.object(installer, 'run', side_effect=run), patch.object(Path, 'read_bytes', return_value=b'fixture'):
            result = installer.retry_configuration()
        self.assertEqual(set(result), {control, '/etc/runtime.env', '/etc/new-dropin.conf',
            '/etc/building-energy-carbon-test-platform.service', '/etc/building-energy-carbon-test-nginx.service',
            str(installer.BASE / 'shared/nginx-user.conf')})

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

    def test_v85_requires_new_maintenance_reference_before_touching_old_authorization(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
            bundle = Path(directory) / "bundle"
            bundle.write_bytes(b"verified fixture")
            stack.enter_context(patch.object(installer.os, "geteuid", return_value=0))
            stack.enter_context(patch.object(installer.sys.stdin, "isatty", return_value=True))
            stack.enter_context(patch.dict(os.environ))
            stack.enter_context(patch.object(installer, "bundle_files", return_value={"release.tar.gz": b"fixture"}))
            stack.enter_context(patch.object(installer, "package_manifest", return_value=({"expectedMigration": "85"}, b"jar")))
            retired = stack.enter_context(patch.object(installer, "retire_completed"))
            credentials = stack.enter_context(patch.object(installer, "credentials"))
            for reference in (None, "", " ", "x\n", "x" * 513):
                with self.assertRaisesRegex(RuntimeError, "NEW_MAINTENANCE_REFERENCE_REQUIRED"):
                    installer.install(bundle, hashlib.sha256(bundle.read_bytes()).hexdigest(),
                                      replace_revoked_completed=True, authorization_reference=reference)
            retired.assert_not_called()
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
        self.install_publishes_rule("87", "SCHEMA_UPGRADE")

    def test_application_only_install_binds_v88_mode_without_stopping_or_adding_commands(self):
        self.install_publishes_rule("88", "APPLICATION_ONLY")

    def install_publishes_rule(self, source, mode):
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
            credentials = stack.enter_context(patch.object(installer, "credentials", return_value=(
                {"password": "private-schema"}, {"password": "private-backup"}, {"backup_and_restore_verified": False})))
            stack.enter_context(patch.object(installer, "package_manifest", return_value=({"commit": "a" * 40,
                "expectedMigration": "88", "sourceMigration": source, "releaseMode": mode,
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
            credentials.side_effect = RuntimeError("D01_EXISTING_CREDENTIALS_NOT_READY")
            with self.assertRaisesRegex(RuntimeError, "EXISTING_CREDENTIALS_NOT_READY"):
                installer.install(bundle, hashlib.sha256(bundle.read_bytes()).hexdigest(),
                                  replace_revoked_completed=True, authorization_reference="approved V88 maintenance")
            self.assertFalse(installer.SUDOERS.exists())
            self.assertFalse(installer.INSTALL.exists())
            self.assertEqual(installer.CREATED, [])
            self.assertFalse(any("enable" in args for args in calls))
            credentials.side_effect = None
            installer.install(bundle, hashlib.sha256(bundle.read_bytes()).hexdigest(),
                              replace_revoked_completed=True, authorization_reference="approved V88 maintenance")
            self.assertEqual(credentials.call_args.args[2], "88")
            rule = installer.SUDOERS.read_text()
            command_line = next(line for line in rule.splitlines() if line.startswith("user1 "))
            self.assertNotIn("*", command_line)
            self.assertNotIn("/bin/sh", command_line)
            self.assertNotIn("python", command_line)
            self.assertNotIn("docker", command_line)
            self.assertEqual(command_line.split("NOPASSWD: ", 1)[1].split(", "),
                             [str(installer.LAUNCHER) + " " + action for action in installer.ACTIONS])
            self.assertTrue((units / (installer.TIMER + ".timer")).exists())
            self.assertEqual(installer.INSTALL.stat().st_mode & 0o777, 0o755)
            self.assertEqual(installer.DATA.stat().st_mode & 0o777, 0o755)
            self.assertEqual((installer.DATA / "private").stat().st_mode & 0o777, 0o700)
            self.assertEqual((installer.DATA / "private/schema.json").stat().st_mode & 0o777, 0o600)
            config = json.loads((installer.INSTALL / "config.json").read_text())
            self.assertNotIn("password", json.dumps(config))
            self.assertEqual((config["source_schema"], config["target_schema"]), (source, "88"))
            self.assertEqual(config["release_mode"], mode)
            self.assertEqual(credentials.call_args.args[3], mode)
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
