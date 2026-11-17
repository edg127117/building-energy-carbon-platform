"""93→121 的离线发布顺序与失败现场；真实服务和数据库调用均被替换。"""
import hashlib
import io
import json
from pathlib import Path
import re
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

import test_d01_release as fixtures
from test_d01_shared_alignment import fixture_jar
import build_shared_alignment_release as builder


@unittest.skipUnless(sys.platform == "linux", "冻结文件权限依赖POSIX")
class AlignmentWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.D01ReleaseTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.release = fixtures.release
        root = self.fixture.base
        web = root / "releases/previous/web"
        web.mkdir()
        (web / "index.html").write_bytes(b"candidate")
        jar = root / "full.jar"
        fixture_jar(jar)
        result = builder.build(jar, web, root / "candidate", "a" * 40, "BLD001", "room")
        archive = root / "incoming/candidate.tar.gz"
        Path(result["archive"]).rename(archive)
        self.fixture.state = self.release.prepare("candidate", archive, result["sha256"], root / "credentials.json")
        self.fixture.schema.reset_mock()
        td_patch = patch.object(self.release, "bootstrap_room_tdengine")
        self.td = td_patch.start()
        self.addCleanup(td_patch.stop)

    def freeze(self):
        self.release.freeze(self.fixture.state, True, False, True, "approved isolated fixture")

    def test_all_26_steps_and_bootstrap_preserve_frozen_identity_until_cutover(self):
        self.assertEqual(self.fixture.current.resolve(), self.fixture.base / "releases/previous")
        self.freeze()
        frozen_hash = self.release.sha(self.fixture.state)
        self.release.migrate(self.fixture.state, self.fixture.base / "backups", Path("schema"), Path("report"), Path("td"))
        self.assertEqual([c.args[0] for c in self.fixture.schema.call_args_list],
                         ["upgrade" + str(v) for v in self.release.ALIGNMENT_VERSIONS] + ["bootstrap121"])
        self.assertEqual(self.release.sha(self.fixture.state), frozen_hash)
        self.assertEqual(self.fixture.current.resolve(), self.fixture.base / "releases/previous")
        self.release.cutover(self.fixture.state, self.fixture.base / "backups", Path("schema"), Path("report"), Path("td"))
        self.assertEqual(self.fixture.schema.call_args.args[0], "validate121")
        self.assertEqual(self.td.call_args.kwargs, {"verify_only": True})
        self.assertEqual(json.loads(self.fixture.state.read_text())["phase"], "AWAITING_BUSINESS_ACCEPTANCE")

    def test_maintenance_prepare_and_targets_share_the_release_state_path(self):
        import test_d01_maintenance as maintenance_tests
        bridge = maintenance_tests.bridge
        root = self.fixture.base
        self.fixture.controls.update(self.release.ALIGNMENT_ENV,
                                     TDENGINE_HOST="127.0.0.1", TDENGINE_PORT="16041")
        release_name = "d01-" + "b" * 12 + "-rehearsal"
        previous_web = root / "releases/previous/web"
        jar = root / "alignment-source.jar"
        fixture_jar(jar)
        built = builder.build(jar, previous_web, root / "alignment-build", "b" * 40, "BLD001", "room")
        archive_name = release_name + "-authorized.tar.gz"
        archive = root / "incoming" / archive_name
        archive.write_bytes(Path(built["archive"]).read_bytes())

        install = root / "authorized-tools"
        data = root / "authorized-data"
        private = data / "private"
        install.mkdir()
        private.mkdir(parents=True)
        (install / "release.tar.gz").write_bytes(archive.read_bytes())
        (install / self.release.JAR).write_bytes((root / "alignment-build" / self.release.JAR).read_bytes())
        (private / "operation.json").write_text(json.dumps({"phase": "AUTHORIZED", "authorized_at": 100,
                                                             "events": []}))
        config = {"release": release_name, "archive_name": archive_name,
                  "archive_sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
                  "previous_release": str(self.fixture.current.resolve()), "expires_at": 9999999999,
                  "rehearsal_only": True, "source_schema": "93", "target_schema": "121",
                  "release_mode": "SCHEMA_UPGRADE", "jar_sha256": built["manifest"]["backendJarSha256"],
                  "web_sha256": built["manifest"]["webIndexSha256"]}

        original_release_methods = {name: getattr(self.release, name)
                                    for name in ("state_directory", "stage_package", "switch_current", "schema")}
        with patch.object(bridge, "INSTALL", install), patch.object(bridge, "DATA", data), \
                patch.object(bridge, "imported_modules", return_value=(self.release, Mock())), \
                patch.object(bridge, "protected"):
            maintenance = bridge.Maintenance(config)
            actual_prepare = self.release.prepare
            prepare_results = []

            def capture_prepare_result(*args, **kwargs):
                result = actual_prepare(*args, **kwargs)
                prepare_results.append(result)
                return result

            def stage_action(action):
                if action == "stage-user":
                    original_release_methods["stage_package"](
                        archive, self.fixture.base / "releases" / release_name)
                else:
                    self.assertEqual(action, "publish-user")

            try:
                with patch.object(maintenance, "user_action", side_effect=stage_action), \
                        patch.object(self.release, "prepare", side_effect=capture_prepare_result):
                    maintenance.prepare()
                    expected_name = "d01-" + release_name + ".json"
                    self.assertEqual(len(prepare_results), 1)
                    self.assertEqual(prepare_results[0].name, expected_name)
                    self.assertEqual(prepare_results[0], maintenance.state)
                    self.assertEqual(maintenance.state.name, expected_name)
                    self.assertTrue(maintenance.state.is_file())
                    self.assertEqual(maintenance.targets(), {
                        "mysql_container": self.fixture.controls["MYSQL_CONTAINER_NAME"],
                        "mysql_volume": self.fixture.controls["MYSQL_VOLUME_NAME"],
                        "tdengine_container": self.fixture.controls["TDENGINE_CONTAINER_NAME"],
                        "tdengine_volume": self.fixture.controls["TDENGINE_VOLUME_NAME"],
                        "tdengine_database": self.fixture.controls["TDENGINE_DATABASE"],
                    })
                    self.assertEqual(self.fixture.schema.call_args.args[0], "validate93")
            finally:
                for name, method in original_release_methods.items():
                    setattr(self.release, name, method)

    def test_failed_intermediate_step_cannot_bootstrap_or_switch(self):
        self.freeze()
        def fail(action, *_):
            if action == "upgrade100":
                raise RuntimeError("fixture migration failure")
        self.fixture.schema.side_effect = fail
        with self.assertRaisesRegex(RuntimeError, "migration failure"):
            self.release.migrate(self.fixture.state, self.fixture.base / "backups", Path("schema"), Path("report"), Path("td"))
        self.assertEqual(self.td.call_count, 1)
        self.assertEqual(self.td.call_args.kwargs, {"preflight_only": True})
        self.assertFalse(self.release.alignment_ready_path(self.fixture.state).exists())
        self.assertEqual(self.fixture.current.resolve(), self.fixture.base / "releases/previous")
        self.assertEqual(json.loads(self.fixture.state.read_text())["phase"], "FROZEN")

    def test_restore_failure_precedes_any_td_schema_check_or_cutover(self):
        self.freeze()
        self.fixture.restore.side_effect = RuntimeError("fixture restore mismatch")
        with self.assertRaisesRegex(RuntimeError, "restore mismatch"):
            self.release.cutover(self.fixture.state, self.fixture.base / "backups", Path("schema"), Path("report"), Path("td"))
        self.td.assert_not_called()
        self.fixture.schema.assert_not_called()

    def test_td_precondition_failure_precedes_all_mysql_migrations(self):
        self.freeze()
        self.td.side_effect = RuntimeError("fixture TD precondition failed")
        with self.assertRaisesRegex(RuntimeError, "TD precondition"):
            self.release.migrate(self.fixture.state, self.fixture.base / "backups", Path("schema"), Path("report"), Path("td"))
        self.fixture.schema.assert_not_called()
        self.assertFalse(self.release.alignment_ready_path(self.fixture.state).exists())
        self.assertEqual(self.fixture.current.resolve(), self.fixture.base / "releases/previous")


class ThreeDigitSchemaMarkerTest(unittest.TestCase):
    def test_full_three_digit_success_marker_is_required(self):
        release = fixtures.release
        state = {"source_schema": "93", "target_schema": "121", "release_mode": "SCHEMA_UPGRADE",
                 "runtime_controls": {"MYSQL_HOST": "127.0.0.1", "MYSQL_PORT": "13316", "MYSQL_USER": "app"},
                 "release": "/fixture/candidate"}
        credentials = {"url": "jdbc:mysql://127.0.0.1:13316/iot_platform", "user": "migration", "password": "fixture-private"}
        result = Mock(returncode=0, stdout="D01_SCHEMA_VALIDATED=121\n")
        with patch.object(release, "protected_json", return_value=credentials), patch.object(release.subprocess, "run", return_value=result) as run:
            release.schema("upgrade121", state, Path("fixture"))
            self.assertNotIn("fixture-private", repr(run.call_args.args))
            result.stdout = "D01_SCHEMA_VALIDATED=21\n"
            with self.assertRaisesRegex(RuntimeError, "SCHEMA_CHECK_FAILED"):
                release.schema("upgrade121", state, Path("fixture"))


class TdSchemaResourceTest(unittest.TestCase):
    def test_creates_only_two_fixed_tables_and_verify_is_read_only(self):
        release = fixtures.release
        content = (fixtures.SCRIPTS.parent / "src/env/room-sensor/tdengine.sql").read_bytes()
        descriptions = {}
        for name, columns, tags in re.findall(r"CREATE STABLE IF NOT EXISTS (st_\w+)\s*\((.*?)\)\s*TAGS\s*\((.*?)\);", content.decode(), re.S):
            rows = []
            for text, note in ((columns, ""), (tags, "TAG")):
                for column, kind, length in re.findall(r"(\w+)\s+(TIMESTAMP|BIGINT|BINARY|INT|BOOL)(?:\((\d+)\))?", text):
                    size = int(length) if length else {"TIMESTAMP": 8, "BIGINT": 8, "INT": 4, "BOOL": 1}[kind]
                    rows.append([column, kind, size, note])
            descriptions[name] = rows
        statements = []
        def execute(request, **_):
            sql = request.data.decode()
            statements.append(sql)
            result = {"code": 0, "data": []}
            if sql.startswith("SELECT"):
                result.update(column_meta=[[k, "VARCHAR", 64] for k in ("name", "precision", "keep")],
                              data=[["iot_telemetry", "ms", "7,7,7"]])
            elif sql.startswith("DESCRIBE"):
                result["data"] = descriptions[sql.split(".")[-1]]
            return io.BytesIO(json.dumps(result).encode())
        opener = Mock()
        opener.open.side_effect = execute
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with zipfile.ZipFile(root / release.JAR, "w") as jar:
                jar.writestr("BOOT-INF/classes/db/room-sensor/tdengine.sql", content)
            state = {"release": str(root), "runtime_controls": {"TDENGINE_HOST": "127.0.0.1", "TDENGINE_PORT": "16041",
                      "TDENGINE_DATABASE": "iot_telemetry"}, "alignment": {"roomSensorTdSha256": hashlib.sha256(content).hexdigest()}}
            with patch.object(release, "protected_json", return_value={"tdengine": {"user": "root", "password": "fixture-private"}}), \
                    patch.object(release.urllib.request, "build_opener", return_value=opener):
                release.bootstrap_room_tdengine(state, Path("fixture"), preflight_only=True)
                self.assertEqual(len(statements), 1)
                self.assertTrue(statements[0].startswith("SELECT"))
                statements.clear()
                release.bootstrap_room_tdengine(state, Path("fixture"))
                creates = [sql for sql in statements if sql.startswith("CREATE")]
                self.assertEqual(len(creates), 2)
                self.assertTrue(all(sql.startswith("CREATE STABLE IF NOT EXISTS iot_telemetry.st_") for sql in creates))
                statements.clear()
                release.bootstrap_room_tdengine(state, Path("fixture"), verify_only=True)
                self.assertEqual(len(statements), 3)
                self.assertTrue(all(sql.startswith(("SELECT", "DESCRIBE")) for sql in statements))
