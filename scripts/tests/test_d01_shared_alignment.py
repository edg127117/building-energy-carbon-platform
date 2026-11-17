"""共享对齐的离线包、权限及失败边界；不连接真实数据库或服务。"""
import io
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import tarfile
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import d01_release as release
import build_shared_alignment_release as builder


def fixture_jar(path, *, omit=None, extra=None, defaults=None):
    plan = json.loads((SCRIPTS.parent / "src/main/resources/db/shared-test-alignment.json").read_text())
    if defaults is not None:
        plan["runtimeDefaults"] = defaults
    with zipfile.ZipFile(path, "w") as jar:
        jar.writestr(release.ALIGNMENT_PLAN, json.dumps(plan))
        for version, name in ((84, release.V84), (85, release.V85), (86, release.V86),
                              (87, release.V87), (88, release.V88), (93, release.V93)):
            jar.writestr(name, "fixture SQL")
        for item in plan["migrations"]:
            if item["version"] != omit:
                jar.writestr("BOOT-INF/classes/db/migration/mysql/" + item["script"], "fixture SQL")
        if extra:
            jar.writestr("BOOT-INF/classes/db/migration/mysql/V" + str(extra) + "__unexpected.sql", "fixture SQL")
        for name in ("D01SchemaMigration", "SharedAlignmentSchemaContract", "D01RoomSensorBootstrap"):
            jar.writestr("BOOT-INF/classes/com/platform/tools/" + name + ".class", b"fixture class")
        jar.writestr("BOOT-INF/classes/db/room-sensor/configure-b314.sql", b"fixture MySQL bootstrap")
        jar.writestr("BOOT-INF/classes/db/room-sensor/tdengine.sql", b"fixture TD bootstrap")


def alignment_package(path, *, building_id="building", space_id="space", mysql_sha=None, td_sha=None,
                      omit_resource=None):
    jar_path = path.with_suffix(".jar")
    fixture_jar(jar_path)
    with zipfile.ZipFile(jar_path) as source:
        entries = {name: source.read(name) for name in source.namelist()}
    entries["BOOT-INF/classes/application.properties"] = "".join(
        key + "=" + value + "\n" for key, value in release.ALIGNMENT_DEFAULTS.items()).encode()
    mysql_path = "BOOT-INF/classes/db/room-sensor/configure-b314.sql"
    td_path = "BOOT-INF/classes/db/room-sensor/tdengine.sql"
    if omit_resource == "mysql":
        entries.pop(mysql_path)
    if omit_resource == "td":
        entries.pop(td_path)
    jar = io.BytesIO()
    with zipfile.ZipFile(jar, "w") as packaged:
        for name, content in entries.items():
            packaged.writestr(name, content)
    jar_bytes = jar.getvalue()
    plan_path = release.ALIGNMENT_PLAN
    manifest = {
        "expectedMigration": "121", "sourceMigration": "93", "releaseMode": "SCHEMA_UPGRADE",
        "commit": "a" * 40, "backendJarSha256": hashlib.sha256(jar_bytes).hexdigest(),
        "webIndexSha256": hashlib.sha256(b"fixture web").hexdigest(),
        "preserveFrontendSha256": hashlib.sha256(b"fixture web").hexdigest(),
        "alignmentPlanSha256": hashlib.sha256(entries[plan_path]).hexdigest(),
        "roomSensorBuildingId": building_id, "roomSensorSpaceId": space_id,
        "roomSensorMysqlSha256": mysql_sha or hashlib.sha256(entries.get(mysql_path, b"")).hexdigest(),
        "roomSensorTdSha256": td_sha or hashlib.sha256(entries.get(td_path, b"")).hexdigest(),
    }
    with tarfile.open(path, "w:gz") as archive:
        for name, content in (("MANIFEST.txt", "".join(k + "=" + v + "\n" for k, v in manifest.items()).encode()),
                              ("building-energy-carbon-platform.jar", jar_bytes), ("web/index.html", b"fixture web")):
            info = tarfile.TarInfo(name)
            info.size = len(content)
            archive.addfile(info, io.BytesIO(content))


def rehearsal_authorization_tree(installer, root, *, event_phases=None, candidate_started=False, current_on_candidate=False):
    base = root / "test"
    baseline = base / "releases" / "baseline"
    old_release = "d01-" + "a" * 12 + "-rehearsal"
    candidate = base / "releases" / old_release
    incoming = base / "incoming"
    private = root / "data" / "private"
    install = root / "tools"
    units = root / "units"
    baseline.mkdir(parents=True)
    candidate.mkdir(parents=True)
    incoming.mkdir()
    private.mkdir(parents=True)
    install.mkdir()
    units.mkdir()
    (baseline / "building-energy-carbon-platform.jar").write_bytes(b"old baseline jar")
    (candidate / "building-energy-carbon-platform.jar").write_bytes(b"candidate jar")
    (candidate / "web").mkdir()
    (candidate / "web/index.html").write_bytes(b"candidate web")
    archive_bytes = b"old authorization package"
    (incoming / (old_release + "-authorized.tar.gz")).write_bytes(archive_bytes)
    current = base / "current"
    current.symlink_to(candidate if current_on_candidate else baseline)
    code_hashes = {}
    for name in installer.FILES:
        if name.endswith(".py") or name == "taosdump-d01":
            (install / name).write_bytes(b"pinned " + name.encode())
            code_hashes[name] = hashlib.sha256((install / name).read_bytes()).hexdigest()
    candidate_sha = hashlib.sha256(b"candidate jar").hexdigest()
    web_sha = hashlib.sha256(b"candidate web").hexdigest()
    previous_sha = hashlib.sha256(b"old baseline jar").hexdigest()
    config = {
        "format": "D01_TEMP_AUTH_V1", "user": "user1", "release": old_release,
        "archive_name": old_release + "-authorized.tar.gz", "archive_sha256": hashlib.sha256(archive_bytes).hexdigest(),
        "commit": "a" * 40, "source_schema": "93", "target_schema": "121", "release_mode": "SCHEMA_UPGRADE",
        "rehearsal_only": True, "previous_release": str(baseline), "jar_sha256": candidate_sha,
        "web_sha256": web_sha, "code_sha256": code_hashes,
    }
    (install / "config.json").write_text(json.dumps(config))
    phases = event_phases or ["PREPARED", "ONLINE_BACKUP", "ONLINE_VERIFIED"]
    times = [200 + 100 * index for index in range(len(phases))]
    operation = {"phase": phases[-1], "authorized_at": 100,
                 "events": [{"phase": phase, "at_utc_epoch": times[index]} for index, phase in enumerate(phases)]}
    (private / "operation.json").write_text(json.dumps(operation))
    (private / "revoked.json").write_text(json.dumps({"revoked_at": 500}))
    for path, content in (("online-backup/manifest.json", b"online backup evidence"),
                          ("online-restore/report.json", b"restore report"),
                          ("online-restore/evidence.json", b"restore evidence")):
        target = private / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    state = {"phase": "PREPARED", "candidate_started": candidate_started, "release": str(candidate),
             "previous_release": str(baseline), "previous_jar_sha256": previous_sha,
             "source_schema": "93", "target_schema": "121", "release_mode": "SCHEMA_UPGRADE",
             "jar_sha256": candidate_sha, "web_sha256": web_sha, "config_hashes": {"fixture": "hash"}}
    (private / ("d01-" + old_release + ".json")).write_text(json.dumps(state))
    (root / "entry").write_text("launcher")
    (units / (installer.TIMER + ".service")).write_text("service")
    (units / (installer.TIMER + ".timer")).write_text("timer")
    return {"base": base, "baseline": baseline, "candidate": candidate, "incoming": incoming,
            "private": private, "install": install, "units": units, "old_release": old_release}


class AlignmentPackageTest(unittest.TestCase):
    def test_plan_has_real_gaps_and_no_unapproved_paths(self):
        self.assertEqual(release.migration_versions("93", "121"), tuple(range(94, 108)) + tuple(range(110, 122)))
        self.assertEqual(release.migration_versions("88", "93"), (93,))
        for source, target in (("94", "121"), ("93", "120"), ("120", "121")):
            with self.assertRaisesRegex(RuntimeError, "STATE_SCHEMA_MISMATCH"):
                release.validate_schema_plan({"source_schema": source, "target_schema": target})

    def test_missing_extra_and_scope_expanding_resources_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "app.jar"
            for kwargs in ({"omit": 110}, {"extra": 108}, {"defaults": {**release.ALIGNMENT_DEFAULTS, "alarm.enabled": "true"}}):
                fixture_jar(jar, **kwargs)
                with zipfile.ZipFile(jar) as archive, self.assertRaises((RuntimeError, KeyError)):
                    release.alignment_plan(archive)

    @unittest.skipUnless(sys.platform == "linux", "安装器依赖Linux权限与文件锁")
    def test_installer_pins_room_binding_ids_and_both_sql_resources(self):
        import test_d01_maintenance as tests
        with tempfile.TemporaryDirectory() as directory:
            package = Path(directory) / "candidate.tar.gz"
            alignment_package(package)
            manifest, _ = tests.installer.package_manifest(package.read_bytes())
            self.assertEqual(manifest["roomSensorBuildingId"], "building")
            self.assertEqual(manifest["roomSensorSpaceId"], "space")
            for kwargs in ({"building_id": "building;DROP"}, {"space_id": "../space"},
                           {"mysql_sha": "f" * 64}, {"td_sha": "e" * 64},
                           {"omit_resource": "mysql"}, {"omit_resource": "td"}):
                alignment_package(package, **kwargs)
                with self.subTest(kwargs=kwargs), self.assertRaises((RuntimeError, KeyError)):
                    tests.installer.package_manifest(package.read_bytes())

    def test_build_preserves_inputs_frontend_and_scoped_defaults(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / "full.jar"; fixture_jar(jar)
            frontend = root / "frontend"; frontend.mkdir()
            (frontend / "index.html").write_bytes(b"shared frontend")
            (frontend / "app.js").write_bytes(b"existing shared assets")
            before = release.sha(jar)
            result = builder.build(jar, frontend, root / "candidate", "a" * 40, "building", "space")
            self.assertEqual(release.sha(jar), before)
            self.assertEqual(release.frontend_fingerprint(frontend), release.frontend_fingerprint(root / "candidate/web"))
            self.assertEqual(result["manifest"]["preserveFrontendSha256"], release.sha(frontend / "index.html"))
            with zipfile.ZipFile(root / "candidate" / release.JAR) as candidate:
                defaults = candidate.read("BOOT-INF/classes/application.properties").decode()
                self.assertIn("dynamic-electricity.live-enabled=false\n", defaults)
                self.assertIn("room-sensor.enabled=true\n", defaults)
                self.assertNotIn("spring.datasource", defaults)

    def test_builder_never_reuses_an_existing_destination_or_invalid_binding(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); jar=root/"full.jar"; fixture_jar(jar)
            web=root/"web"; web.mkdir(); (web/"index.html").write_text("original")
            with self.assertRaisesRegex(RuntimeError, "ROOM_BINDING_REQUIRED"):
                builder.build(jar,web,root/"target","a"*40,"building;DROP", "space")
            with self.assertRaisesRegex(RuntimeError, "DESTINATION_EXISTS"):
                builder.build(jar,web,web,"a"*40,"building", "space")


@unittest.skipUnless(sys.platform == "linux", "维护授权边界需POSIX运行，依赖全部替换")
class RehearsalScopeTest(unittest.TestCase):
    def test_rehearsal_release_ids_cannot_collide_with_formal_commit_path(self):
        import test_d01_maintenance as tests
        installer = tests.installer
        commit = "a" * 40
        self.assertEqual(installer.authorization_release_id(commit), "d01-" + "a" * 12)
        self.assertEqual(installer.authorization_release_id(commit, True), "d01-" + "a" * 12 + "-rehearsal")

    def test_failed_rehearsal_prepare_state_is_retryable_only_with_exact_alignment_binding(self):
        import test_d01_maintenance as tests
        installer = tests.installer
        expected = {"format", "phase", "release", "previous_release", "previous_jar_sha256", "other_release",
                    "other_link", "other_pid", "other_reload", "config_hashes", "runtime_controls", "backup_targets",
                    "backend_service", "web_service", "candidate_started", "jar_sha256", "web_sha256"}
        state = {key: "fixture" for key in expected}
        state.update(source_schema="93", target_schema="121", release_mode="SCHEMA_UPGRADE", alignment={
            "roomSensorBuildingId": "B314_BUILDING", "roomSensorSpaceId": "B314_SPACE",
            "roomSensorMysqlSha256": "a" * 64, "roomSensorTdSha256": "b" * 64,
        })
        self.assertTrue(installer.rehearsal_prepared_fields_valid(state, expected))
        for mutate in (lambda value: value["alignment"].update(roomSensorSpaceId="bad/space"),
                       lambda value: value["alignment"].update(roomSensorMysqlSha256="short"),
                       lambda value: value["alignment"].update(extra="scope"),
                       lambda value: value.update(extra="field"),
                       lambda value: value.update(target_schema="120")):
            candidate = json.loads(json.dumps(state))
            mutate(candidate)
            self.assertFalse(installer.rehearsal_prepared_fields_valid(candidate, expected))

    def test_retry_baseline_accepts_strict_legacy_and_v121_runtime_keys(self):
        import test_d01_maintenance as tests
        installer = tests.installer

        def baseline_runtime():
            runtime = {key: "fixture" for key in installer.RUNTIME_REQUIRED}
            runtime.update(AUDIT_ENVIRONMENT_MODE="TEST", SERVER_PORT="18081",
                           MYSQL_FLYWAY_ENABLED="false", TDENGINE_INITIALIZATION_ENABLED="false")
            return runtime

        def verify(runtime, target):
            state = {"runtime_controls": runtime, "target_schema": target,
                     "other_release": "/tmp/other-release", "other_link": "fixture-link",
                     "other_pid": "456", "other_reload": "fixture-reload"}
            environment = b"\0".join((key + "=" + value).encode() for key, value in runtime.items()) + b"\0"
            original_resolve = Path.resolve
            original_read_bytes = Path.read_bytes

            def resolve(path, *args, **kwargs):
                if path == Path("/var/www/iot-platform/current"):
                    return Path("/tmp/other-release")
                return original_resolve(path, *args, **kwargs)

            def read_bytes(path):
                if path == Path("/proc/123/environ"):
                    return environment
                return original_read_bytes(path)

            def command(*args):
                service, prop = args[2], args[4]
                if prop == "ActiveState": return "active"
                if prop == "ExecMainStartTimestampMonotonic": return "5000000"
                if prop == "MainPID": return "123" if service == "building-energy-carbon-test-platform.service" else "456"
                if prop == "ExecReload": return "fixture-reload"
                raise AssertionError(args)

            with patch.object(installer, "run", side_effect=command), \
                    patch.object(installer.time, "time", return_value=10000), \
                    patch.object(installer.time, "monotonic", return_value=9000), \
                    patch.object(Path, "resolve", resolve), patch.object(Path, "read_bytes", read_bytes), \
                    patch.object(installer.os, "readlink", return_value="fixture-link"):
                installer.retry_baseline(state, 2000)

        legacy = baseline_runtime()
        verify(legacy, "93")
        current = baseline_runtime()
        current.update(installer.TDENGINE_TARGET)
        current.update(installer.ALIGNMENT_ENV)
        verify(current, "121")
        current.pop("ALARM_ENABLED")
        verify(current, "121")

    def test_retry_baseline_rejects_unknown_runtime_keys_and_wrong_v121_td_port(self):
        import test_d01_maintenance as tests
        installer = tests.installer
        runtime = {key: "fixture" for key in installer.RUNTIME_REQUIRED}
        runtime.update(AUDIT_ENVIRONMENT_MODE="TEST", SERVER_PORT="18081",
                       MYSQL_FLYWAY_ENABLED="false", TDENGINE_INITIALIZATION_ENABLED="false")
        runtime.update(installer.TDENGINE_TARGET)
        runtime.update(installer.ALIGNMENT_ENV)
        for invalid in ({**runtime, "UNREVIEWED_KEY": "true"}, {**runtime, "TDENGINE_PORT": "16042"},
                        {**runtime, "ALARM_ENABLED": "true"}):
            state = {"runtime_controls": invalid, "target_schema": "121"}
            with self.subTest(keys=set(invalid) - set(runtime), td_port=invalid.get("TDENGINE_PORT")), \
                    patch.object(installer, "run") as commands:
                with self.assertRaisesRegex(RuntimeError, "OLD_RUNTIME_CONTRACT_INVALID"):
                    installer.retry_baseline(state, 2000)
                commands.assert_not_called()

    def test_rehearsal_replacement_requires_the_alignment_migration_plan(self):
        import test_d01_maintenance as tests
        installer = tests.installer
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "bundle"
            bundle.write_bytes(b"verified fixture")
            for source, target in (("88", "121"), ("93", "120")):
                manifest = {"commit": "a" * 40, "expectedMigration": target, "sourceMigration": source,
                            "releaseMode": "SCHEMA_UPGRADE"}
                with self.subTest(source=source, target=target), \
                        patch.object(installer.os, "geteuid", return_value=0), \
                        patch.object(installer.sys.stdin, "isatty", return_value=True), \
                        patch.object(installer, "bundle_files", return_value={"release.tar.gz": b"fixture"}), \
                        patch.object(installer, "package_manifest", return_value=(manifest, b"jar")), \
                        patch.object(installer, "credentials") as credentials, \
                        patch.object(installer, "retire_revoked_rehearsal") as retire:
                    with self.assertRaisesRegex(RuntimeError, "REHEARSAL_REPLACEMENT_PLAN_REQUIRED"):
                        installer.install(bundle, hashlib.sha256(bundle.read_bytes()).hexdigest(),
                                          replace_revoked_rehearsal=True, authorization_reference="approved")
                    credentials.assert_not_called()
                    retire.assert_not_called()

    def test_revoked_verified_rehearsal_is_archived_without_moving_user_release_files(self):
        import test_d01_maintenance as tests
        installer = tests.installer
        with tempfile.TemporaryDirectory() as directory, patch.object(installer, "trusted_parent"), \
                patch.object(installer, "retry_baseline") as baseline_check, \
                patch.object(installer, "retry_configuration", return_value={"fixture": "hash"}), \
                patch.object(installer.pwd, "getpwnam", return_value=Mock(pw_uid=os.getuid())), \
                patch.object(installer, "run", side_effect=lambda *args: "inactive" if "ActiveState" in args else "disabled"):
            root = Path(directory)
            paths = rehearsal_authorization_tree(installer, root)
            with patch.object(installer, "INSTALL", paths["install"]), patch.object(installer, "DATA", root / "data"), \
                    patch.object(installer, "BASE", paths["base"]), patch.object(installer, "LAUNCHER", root / "entry"), \
                    patch.object(installer, "SUDOERS", root / "grant"), patch.object(installer, "UNITS", paths["units"]), \
                    patch.object(installer, "HISTORY", root / "history"):
                installer.retire_revoked_rehearsal("d01-" + "b" * 12, "93")
                archives = list((root / "history").iterdir())
                self.assertEqual(len(archives), 1)
                self.assertTrue((archives[0] / "data/private/online-backup/manifest.json").is_file())
                self.assertEqual((archives[0] / "data/private/online-restore/evidence.json").read_bytes(), b"restore evidence")
                self.assertFalse(paths["install"].exists())
                self.assertEqual((paths["candidate"] / "building-energy-carbon-platform.jar").read_bytes(), b"candidate jar")
                self.assertEqual((paths["incoming"] / (paths["old_release"] + "-authorized.tar.gz")).read_bytes(),
                                 b"old authorization package")
                self.assertEqual((paths["base"] / "current").resolve(), paths["baseline"])
                baseline_check.assert_called_once()

    def test_rehearsal_replacement_rejects_mutated_phase_baseline_or_revocation_before_archive(self):
        import test_d01_maintenance as tests
        installer = tests.installer
        cases = (("phase", {"event_phases": ["PREPARED", "ONLINE_BACKUP", "FREEZING"]}),
                 ("started", {"candidate_started": True}), ("switched", {"current_on_candidate": True}),
                 ("revocation", {}), ("artifact_hash", {}), ("active_timer", {}))
        for label, options in cases:
            with self.subTest(case=label), tempfile.TemporaryDirectory() as directory, patch.object(installer, "trusted_parent"), \
                    patch.object(installer, "retry_baseline"), \
                    patch.object(installer, "retry_configuration", return_value={"fixture": "hash"}), \
                    patch.object(installer.pwd, "getpwnam", return_value=Mock(pw_uid=os.getuid())), \
                    patch.object(installer, "run") as command:
                root = Path(directory)
                paths = rehearsal_authorization_tree(installer, root, **options)
                command.side_effect = (lambda *args: "active" if "ActiveState" in args else "disabled") \
                    if label == "active_timer" else (lambda *args: "inactive" if "ActiveState" in args else "disabled")
                with patch.object(installer, "INSTALL", paths["install"]), patch.object(installer, "DATA", root / "data"), \
                        patch.object(installer, "BASE", paths["base"]), patch.object(installer, "LAUNCHER", root / "entry"), \
                        patch.object(installer, "SUDOERS", root / "grant"), patch.object(installer, "UNITS", paths["units"]), \
                        patch.object(installer, "HISTORY", root / "history"):
                    if label == "phase":
                        operation_path = paths["private"] / "operation.json"
                        operation = json.loads(operation_path.read_text())
                        operation["stop_requested_at"] = 450
                        operation_path.write_text(json.dumps(operation))
                    elif label == "revocation":
                        (paths["private"] / "revoked.json").unlink()
                    elif label == "artifact_hash":
                        (paths["candidate"] / "building-energy-carbon-platform.jar").write_bytes(b"changed")
                    with self.assertRaises((RuntimeError, OSError)):
                        installer.retire_revoked_rehearsal("d01-" + "b" * 12, "93")
                    self.assertFalse((root / "history").exists())
                    self.assertTrue(paths["install"].exists())
                    self.assertTrue(paths["candidate"].exists())
                    self.assertTrue(paths["incoming"].exists())

    def test_installed_rehearsal_grant_cannot_reach_shared_mutations(self):
        import test_d01_maintenance as tests
        bridge = tests.bridge
        for action in ("freeze", "backup", "migrate", "cutover", "export-backup", "record-restore"):
            with self.subTest(action=action), patch.object(bridge.sys,"argv",["entry",action]), \
                    patch.object(bridge,"load_config",return_value={"rehearsal_only": True}), \
                    patch.object(bridge,"Maintenance") as maintenance:
                with self.assertRaisesRegex(RuntimeError,"REHEARSAL_ONLY_ACTION_REQUIRED"):
                    bridge.main()
                maintenance.assert_not_called()

    def test_installer_validates_real_package_with_preserved_frontend(self):
        import test_d01_maintenance as tests
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); jar=root/"app.jar"; fixture_jar(jar)
            web=root/"web"; web.mkdir(); (web/"index.html").write_bytes(b"shared")
            result=builder.build(jar,web,root/"candidate","a"*40,"building","space")
            manifest,_=tests.installer.package_manifest(Path(result["archive"]).read_bytes())
            self.assertEqual(manifest["expectedMigration"],"121")
            self.assertEqual(tests.installer.REHEARSAL_ACTIONS,
                             ("status","prepare","online-backup","export-online","record-online-restore","revoke"))

    def test_schema_wrapper_rehearsal_grant_only_validates_source(self):
        import test_d01_maintenance as tests
        bridge=tests.bridge
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); jar=root/release.JAR; jar.write_bytes(b"pinned")
            operation=object.__new__(bridge.Maintenance)
            operation.release=release
            operation.schema_credentials=root/"schema.json"
            operation.original_schema=Mock()
            operation.config={"source_schema":"93","target_schema":"121","release_mode":"SCHEMA_UPGRADE",
                              "rehearsal_only":True,"jar_sha256":bridge.digest(jar),"web_sha256":"b"*64}
            state={"source_schema":"93","target_schema":"121","release_mode":"SCHEMA_UPGRADE",
                   "jar_sha256":bridge.digest(jar),"web_sha256":"b"*64}
            with patch.object(bridge,"INSTALL",root),patch.object(bridge,"protected"):
                operation.schema("validate93",state,operation.schema_credentials)
                for action in ("upgrade94","upgrade110","bootstrap121","validate121"):
                    with self.assertRaisesRegex(RuntimeError,"REHEARSAL_ONLY_ACTION_REQUIRED"):
                        operation.schema(action,state,operation.schema_credentials)
            self.assertEqual(operation.original_schema.call_count,1)


class TdBootstrapBoundaryTest(unittest.TestCase):
    def test_td_target_mismatch_is_rejected_before_credentials_or_network(self):
        with patch.object(release,"protected_json") as credentials, patch.object(release.urllib.request,"build_opener") as network:
            with self.assertRaisesRegex(RuntimeError,"TARGET_MISMATCH"):
                release.bootstrap_room_tdengine({"runtime_controls":{"TDENGINE_HOST":"remote"}},Path("not-read"))
            credentials.assert_not_called(); network.assert_not_called()

    def test_td_keep_accepts_integer_minutes_and_legacy_days(self):
        for keep in ("5256000m,5256000m,5256000m", "10080m,5256000m,10080m",
                     "7,7,7", "3650,3650,3650"):
            with self.subTest(keep=keep):
                opener = self.keep_opener(keep)
                with patch.object(release, "protected_json", return_value={"tdengine": {"user": "root", "password": "fixture-private"}}), \
                        patch.object(release.urllib.request, "build_opener", return_value=opener):
                    release.bootstrap_room_tdengine(self.td_state(), Path("fixture"), preflight_only=True)
                self.assertEqual(opener.open.call_count, 1)
                self.assertNotIn("fixture-private", repr(opener.open.call_args.args))

    def test_td_non_millisecond_precision_is_still_rejected_before_writes(self):
        opener = self.keep_opener("3650,3650,3650", precision="us")
        with patch.object(release, "protected_json", return_value={"tdengine": {"user": "root", "password": "fixture-private"}}), \
                patch.object(release.urllib.request, "build_opener", return_value=opener):
            with self.assertRaisesRegex(RuntimeError, "MILLISECOND_PRECISION_REQUIRED"):
                release.bootstrap_room_tdengine(self.td_state(), Path("fixture"), preflight_only=True)
        self.assertEqual(opener.open.call_count, 1)

    def test_td_keep_rejects_any_short_period_unknown_unit_or_bad_format(self):
        for keep in ("10079m,5256000m,5256000m", "5256000m,0m,5256000m", "7,6,7",
                     "5256000s,5256000s,5256000s", "10080 m,10080m,10080m", "10080m,,10080m"):
            with self.subTest(keep=keep):
                opener = self.keep_opener(keep)
                with patch.object(release, "protected_json", return_value={"tdengine": {"user": "root", "password": "fixture-private"}}), \
                        patch.object(release.urllib.request, "build_opener", return_value=opener):
                    with self.assertRaisesRegex(RuntimeError, "KEEP_AT_LEAST_SEVEN_REQUIRED"):
                        release.bootstrap_room_tdengine(self.td_state(), Path("fixture"), preflight_only=True)
                self.assertEqual(opener.open.call_count, 1)
                self.assertNotIn("fixture-private", repr(opener.open.call_args.args))

    def test_show_create_numeric_day_fallback_remains_supported(self):
        for ddl, valid in (("CREATE DATABASE iot_telemetry KEEP 7,3650,3650", True),
                           ("CREATE DATABASE iot_telemetry KEEP 6,3650,3650", False)):
            responses = [
                {"code": 0, "column_meta": [["name", "VARCHAR", 64], ["precision", "VARCHAR", 64]],
                 "data": [["iot_telemetry", "ms"]]},
                {"code": 0, "data": [[ddl]]},
            ]
            opener = Mock()
            opener.open.side_effect = [io.BytesIO(json.dumps(item).encode()) for item in responses]
            with patch.object(release, "protected_json", return_value={"tdengine": {"user": "root", "password": "fixture-private"}}), \
                    patch.object(release.urllib.request, "build_opener", return_value=opener):
                if valid:
                    release.bootstrap_room_tdengine(self.td_state(), Path("fixture"), preflight_only=True)
                else:
                    with self.assertRaisesRegex(RuntimeError, "KEEP_AT_LEAST_SEVEN_REQUIRED"):
                        release.bootstrap_room_tdengine(self.td_state(), Path("fixture"), preflight_only=True)
            self.assertEqual(opener.open.call_count, 2)

    @staticmethod
    def td_state():
        return {"runtime_controls": {"TDENGINE_HOST": "127.0.0.1", "TDENGINE_PORT": "16041",
                                     "TDENGINE_DATABASE": "iot_telemetry"}}

    @staticmethod
    def keep_opener(keep, precision="ms"):
        row = {"name": "iot_telemetry", "precision": precision, "keep": keep}
        result = {"code": 0, "column_meta": [[key, "VARCHAR", 64] for key in row],
                  "data": [list(row.values())]}
        opener = Mock()
        opener.open.return_value = io.BytesIO(json.dumps(result).encode())
        return opener


if __name__ == "__main__":
    unittest.main()
