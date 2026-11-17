"""统一发布只验证产物、库绑定和维护边界；容器及服务调用全部隔离替换。"""
import copy
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_unified_release as builder
import d01_release as shared
import demo_release as demo


def source_jar(path, bad_sql=False):
    with zipfile.ZipFile(path, 'w') as jar:
        for filename in (shared.V84, shared.V85, shared.V86, shared.V87, shared.V88, shared.V93):
            jar.writestr(filename, 'fixture old migration')
        for version, filename in builder.SCRIPTS.items():
            jar.writestr('BOOT-INF/classes/db/migration/mysql/' + filename,
                         'USE iot_platform;' if bad_sql and version == 122 else 'SELECT 1;')
        for name in ('com/platform/tools/DemoSchemaMigration',
                     'com/platform/tools/D01SchemaMigration',
                     'com/platform/hvac/asset/ledger/LedgerController',
                     'com/platform/iot/roomsensor/RoomSensorIngestor'):
            jar.writestr('BOOT-INF/classes/' + name + '.class', b'fixture')


class ArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.jar = self.root / 'source.jar'
        source_jar(self.jar)
        self.web = self.root / 'web'
        (self.web / 'assets').mkdir(parents=True)
        (self.web / 'index.html').write_text('fixture')
        (self.web / 'assets/FinalBPage-test.js').write_text('__name:"FinalBPage",setup(e){const a="BUILDING"')

    def build(self, environment, database, **metadata):
        (self.web / 'release-build.json').write_text(json.dumps({
            'environment': environment, 'buildingCode': 'BUILDING', **metadata}))
        return builder.build(environment, self.jar, self.web, self.root / 'candidate', 'a' * 40, database)

    def test_shared_keeps_six_runtime_boundaries_and_does_not_modify_source_jar(self):
        before = shared.sha(self.jar)
        result = self.build('shared', 'iot_platform')
        self.assertEqual(shared.sha(self.jar), before)
        self.assertEqual(result['manifest']['sourceMigration'], '121')
        self.assertEqual(result['manifest']['backupPolicy'], 'FROZEN_SINGLE_RESTORE_V1')
        with zipfile.ZipFile(self.root / 'candidate' / shared.JAR) as jar:
            shared.unified_shared_package(jar, result['manifest'])
            self.assertIn(b'room-sensor.enabled=true', jar.read('BOOT-INF/classes/application.properties'))
        self.assertEqual(shared.frontend_fingerprint(self.web), shared.frontend_fingerprint(self.root / 'candidate/web'))

    def test_demo_keeps_independent_database_and_does_not_copy_shared_flags(self):
        result = self.build('demo', 'independent_fixture')
        self.assertEqual(result['manifest']['sourceMigration'], '120')
        self.assertEqual(result['manifest']['expectedDatabase'], 'independent_fixture')
        with zipfile.ZipFile(self.root / 'candidate' / shared.JAR) as jar:
            self.assertNotIn('BOOT-INF/classes/application.properties', jar.namelist())

    def test_cross_environment_database_and_frontend_bindings_are_rejected(self):
        for environment, database in (('demo', 'iot_platform'), ('shared', 'independent_fixture'), ('demo', 'MYSQL')):
            with self.subTest(environment=environment, database=database), self.assertRaisesRegex(RuntimeError, 'DATABASE_ENVIRONMENT'):
                self.build(environment, database)
        with self.assertRaisesRegex(RuntimeError, 'FRONTEND_BINDING'):
            self.build('shared', 'iot_platform', buildingCode='OTHER_BUILDING')

    def test_cross_database_sql_is_rejected_before_creating_candidate(self):
        source_jar(self.jar, bad_sql=True)
        with self.assertRaisesRegex(RuntimeError, 'CROSS_DATABASE_SQL'):
            self.build('shared', 'iot_platform')
        self.assertFalse((self.root / 'candidate').exists())

    def test_only_new_explicit_upgrade_pair_is_added(self):
        self.assertEqual(shared.migration_versions('121', '123'), (122, 123))
        for source, target, mode in (('93', '123', 'SCHEMA_UPGRADE'), ('120', '123', 'SCHEMA_UPGRADE'),
                                    ('121', '121', 'APPLICATION_ONLY'), ('121', '123', 'APPLICATION_ONLY')):
            with self.subTest(source=source, target=target), self.assertRaisesRegex(RuntimeError, 'SCHEMA_MISMATCH'):
                shared.validate_schema_plan({'source_schema': source, 'target_schema': target, 'release_mode': mode})


class DemoBoundaryTest(unittest.TestCase):
    def test_mount_order_is_ignored_but_actual_mount_or_limit_changes_are_rejected(self):
        original = {'Id': 'fixture', 'Image': 'sha256:pinned', 'Config': {'Env': ['KEPT=value']},
                    'HostConfig': {'Memory': 1024}, 'Mounts': [
                        {'Destination': '/app', 'Source': '/old.jar'}, {'Destination': '/config', 'Source': '/private'}]}
        reordered = copy.deepcopy(original)
        reordered['Mounts'].reverse()
        self.assertEqual(demo.signature(original), demo.signature(reordered))
        changed = copy.deepcopy(original)
        changed['Mounts'][0]['Source'] = '/different.jar'
        self.assertNotEqual(demo.signature(original), demo.signature(changed))
        changed = copy.deepcopy(original)
        changed['HostConfig']['Memory'] = 2048
        self.assertNotEqual(demo.signature(original), demo.signature(changed))

    def test_old_resource_preservation_allows_only_identical_source_map_with_different_build_depth(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, target = root / 'old.js.map', root / 'new.js.map'
            value = {'version': 3, 'sources': ['../../src/page.ts'], 'mappings': 'AAAA', 'sourcesContent': ['const a=1']}
            source.write_text(json.dumps(value))
            target.write_text(json.dumps({**value, 'sources': ['../../../../../web/src/page.ts']}))
            demo.preserve_resource(source, target)
            self.assertEqual(source.read_bytes(), target.read_bytes())
            target.write_text(json.dumps({**value, 'mappings': 'BBBB'}))
            with self.assertRaisesRegex(RuntimeError, 'RESOURCE_HASH_COLLISION'):
                demo.preserve_resource(source, target)
            source, target = root / 'old.js', root / 'new.js'
            source.write_text('different executable code')
            target.write_text('candidate code')
            with self.assertRaisesRegex(RuntimeError, 'RESOURCE_HASH_COLLISION'):
                demo.preserve_resource(source, target)

    def test_schema_password_only_crosses_stdin_and_source_validation_is_read_only(self):
        state = {'phase': 'PREPARED', 'mysqlId': '1' * 64, 'appId': '2' * 64,
                 'database': 'independent_fixture', 'release': '/fixture/candidate'}
        mysql = {'Config': {'Env': ['MYSQL_ROOT_PASSWORD=fixture-private']}}
        app = {'Image': 'sha256:pinned'}
        result = Mock(returncode=0, stdout=b'DEMO_SCHEMA_VALIDATED=120\n')
        with patch.object(demo, 'inspect', side_effect=lambda cid: mysql if cid == state['mysqlId'] else app), \
                patch.object(demo.subprocess, 'run', return_value=result) as run:
            demo.schema_job(state, 'validate120', read_only=True)
            args, kwargs = run.call_args
            self.assertNotIn('fixture-private', repr(args))
            self.assertNotIn('D01_JDBC_PASSWORD', kwargs['env'])
            self.assertEqual(kwargs['input'], b'fixture-private\n')
            self.assertIn('sessionVariables=transaction_read_only=1', kwargs['env']['D01_JDBC_URL'])
            with self.assertRaisesRegex(RuntimeError, 'WRITERS_NOT_FROZEN'):
                demo.schema_job(state, 'upgrade121')

    def test_candidate_clones_existing_limits_config_networks_and_image(self):
        old = {'Id': '1' * 64, 'Image': 'sha256:pinned', 'Name': '/old-app',
               'Config': {'Env': ['BUSINESS=kept'], 'Cmd': ['original']},
               'HostConfig': {'Binds': ['/old.jar:/app/application.jar:ro', '/private.yml:/config/backend.yml:ro'],
                              'Memory': 1073741824, 'NetworkMode': 'app', 'Privileged': False},
               'NetworkSettings': {'Networks': {'app': {'Aliases': ['canonical-app'], 'IPAddress': 'old-address'},
                                                'data': {'Aliases': ['app']}}}}
        original = copy.deepcopy(old)
        actual = {'Mounts': [{'Destination': '/app/application.jar', 'Source': '/new.jar'}]}
        with patch.object(demo, 'api', return_value={'Id': '2' * 64}) as api, patch.object(demo, 'inspect', return_value=actual):
            demo.clone(old, 'candidate', {'/app/application.jar': '/new.jar'})
        self.assertEqual(old, original)
        created = api.call_args_list[0].args[2]
        self.assertEqual(created['Image'], 'sha256:pinned')
        self.assertEqual(created['Env'], ['BUSINESS=kept'])
        self.assertEqual(created['HostConfig']['Memory'], 1073741824)
        self.assertIn('/private.yml:/config/backend.yml:ro', created['HostConfig']['Binds'])
        self.assertIn('canonical-app', created['NetworkingConfig']['EndpointsConfig']['app']['Aliases'])
        self.assertNotIn('IPAddress', created['NetworkingConfig']['EndpointsConfig']['app'])

    def test_failed_backup_proof_prevents_any_migration_or_candidate_start(self):
        for action in (demo.migrate, demo.cutover):
            with self.subTest(action=action.__name__), patch.object(demo, 'state_file', return_value={'phase': 'FROZEN'}), \
                    patch.object(demo, 'unchanged'), patch.object(demo, 'checked_restore', side_effect=RuntimeError('backup mismatch')), \
                    patch.object(demo, 'schema_job') as schema, patch.object(demo, 'api') as api:
                with self.assertRaisesRegex(RuntimeError, 'backup mismatch'):
                    action(Path('operation'), Path('backup'), Path('report'))
                schema.assert_not_called()
                api.assert_not_called()

    def test_restore_rejects_source_volumes_and_exposed_database_ports(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = {'database': 'independent_fixture', 'operation': 'fixture',
                     'mysqlId': '1' * 64, 'tdengineId': '2' * 64}
            frozen = root / 'operation.json'
            shared.write_state(frozen, state)
            (root / 'mysql.sql').write_bytes(b'actual dump fixture')
            (root / 'tdengine').mkdir()
            (root / 'tdengine/data').write_bytes(b'actual td fixture')
            backup = root / 'manifest.json'
            shared.write_state(backup, {'format': demo.BACKUP_FORMAT, 'freezeSha256': shared.sha(frozen),
                'database': state['database'], 'mysqlSourceId': state['mysqlId'], 'tdengineSourceId': state['tdengineId'],
                'files': {n: shared.sha(root / n) for n in ('mysql.sql', 'tdengine/data')}})
            evidence = root / 'evidence.json'
            fingerprints = {'mysql': {'tables': 'fixture hash'}, 'tdengine': {'tables': 'fixture hash'}}
            shared.write_state(evidence, {'database': state['database'], 'source': fingerprints, 'restored': fingerprints})
            report = root / 'report.json'
            shared.write_state(report, {'format': 'D01_RESTORE_VERIFIED_V1',
                'backup_manifest_sha256': shared.sha(backup), 'mysql_verified': True, 'tdengine_verified': True,
                'source_preserved': True, 'restored_container_ids': ['3' * 64, '4' * 64],
                'evidence_file': evidence.name, 'evidence_sha256': shared.sha(evidence)})
            containers = {}
            for index, destination in enumerate(('/var/lib/mysql', '/var/lib/taos')):
                containers[str(index + 1) * 64] = {'Image': 'sha256:engine' + str(index),
                    'Mounts': [{'Type': 'volume', 'Name': 'source' + str(index), 'Destination': destination}]}
                containers[str(index + 3) * 64] = {'Image': 'sha256:engine' + str(index),
                    'Config': {'Labels': {'bec.operation': 'fixture'}}, 'HostConfig': {'PortBindings': {}},
                    'Mounts': [{'Type': 'volume', 'Name': 'restore' + str(index), 'Destination': destination}]}
            with patch.object(demo, 'inspect', side_effect=lambda cid: containers[cid]):
                demo.checked_restore(state, frozen, backup, report)
                containers['3' * 64]['Mounts'][0]['Name'] = 'source0'
                with self.assertRaisesRegex(RuntimeError, 'VOLUME_NOT_ISOLATED'):
                    demo.checked_restore(state, frozen, backup, report)
                containers['3' * 64]['Mounts'][0]['Name'] = 'restore0'
                containers['3' * 64]['HostConfig']['PortBindings'] = {'3306/tcp': [{'HostPort': '3306'}]}
                with self.assertRaisesRegex(RuntimeError, 'VOLUME_NOT_ISOLATED'):
                    demo.checked_restore(state, frozen, backup, report)

    def test_candidate_start_failure_stops_candidate_without_restarting_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / 'operation.json'
            state = {'phase': 'FROZEN', 'release': str(root), 'commit': 'a' * 40,
                     'jarSha256': 'b' * 64, 'appId': '1' * 64, 'appNetwork': 'fixture'}
            shared.write_state(path, state)
            ready = {'freezeSha256': shared.sha(path), 'jarSha256': state['jarSha256']}
            with patch.object(demo, 'state_file', return_value=state), patch.object(demo, 'unchanged'), \
                    patch.object(demo, 'checked_restore'), patch.object(demo, 'protected_json', return_value=ready), \
                    patch.object(demo, 'schema_job'), patch.object(demo, 'clone', return_value='3' * 64), \
                    patch.object(demo, 'inspect', return_value={'State': {'Running': False}}), \
                    patch.object(demo, 'healthy', side_effect=RuntimeError('startup failure')), patch.object(demo, 'api') as api:
                with self.assertRaisesRegex(RuntimeError, 'startup failure'):
                    demo.cutover(path, root / 'backup', root / 'report')
            calls = [c.args[1] for c in api.call_args_list]
            self.assertEqual(calls, ['/containers/' + '3' * 64 + '/start', '/containers/' + '3' * 64 + '/stop?t=60'])
            self.assertEqual(json.loads(path.read_text())['phase'], 'FAILED_STOPPED')


@unittest.skipUnless(sys.platform == 'linux', '共享维护依赖POSIX权限，Linux CI验证')
class SharedUpgradeTest(unittest.TestCase):
    def test_prepare_and_upgrade_only_add_122_123_without_room_bootstrap(self):
        import test_d01_release as fixtures
        fixture = fixtures.D01ReleaseTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        release = fixtures.release
        root = fixture.base
        jar = root / 'source.jar'
        source_jar(jar)
        web = root / 'frontend'
        (web / 'assets').mkdir(parents=True)
        (web / 'index.html').write_bytes(b'candidate')
        (web / 'assets/FinalBPage-test.js').write_text('__name:"FinalBPage",setup(e){const a="BUILDING"')
        (web / 'release-build.json').write_text(json.dumps({'environment': 'shared', 'buildingCode': 'BUILDING'}))
        package = builder.build('shared', jar, web, root / 'built', 'a' * 40, 'iot_platform')
        archive = root / 'incoming/package.tar.gz'
        Path(package['archive']).rename(archive)
        fixture.state = release.prepare('d01-unified', archive, package['sha256'], root / 'credentials')
        self.assertEqual(fixture.schema.call_args.args[0], 'validate121')
        self.assertEqual(fixture.events, [])
        fixture.schema.reset_mock()
        release.freeze(fixture.state, True, True)
        frozen = release.sha(fixture.state)
        with patch.object(release, 'bootstrap_room_tdengine') as bootstrap:
            release.migrate(fixture.state, root / 'backups', Path('credentials'), Path('report'))
            self.assertEqual([c.args[0] for c in fixture.schema.call_args_list], ['upgrade122', 'upgrade123'])
            self.assertEqual(release.sha(fixture.state), frozen)
            release.cutover(fixture.state, root / 'backups', Path('credentials'), Path('report'))
            bootstrap.assert_not_called()
        self.assertEqual(fixture.schema.call_args.args[0], 'validate123')

    def test_installer_accepts_new_plan_without_reusing_old_authorization_or_expanding_probe(self):
        import test_d01_maintenance as fixtures
        installer = fixtures.installer
        self.assertTrue(installer.valid_schema_plan('121', '123', 'SCHEMA_UPGRADE'))
        self.assertFalse(installer.valid_schema_plan('93', '123', 'SCHEMA_UPGRADE'))
        self.assertFalse(installer.valid_schema_plan('121', '123', 'APPLICATION_ONLY'))
        metadata = fixtures.CredentialReuseTest.migration_metadata
        select_only = metadata + "GRANT SELECT ON `iot_platform`.* TO 'platform_migrator'@'%'\n" + \
            "GRANT SELECT ON `iot_platform_archive`.* TO 'platform_migrator'@'%'\n"
        fixtures.credential_source.mysql_rights(select_only, 'migration', '121', 'APPLICATION_ONLY')
        with self.assertRaisesRegex(RuntimeError, 'MIGRATION_PRIVILEGES'):
            fixtures.credential_source.mysql_rights(select_only, 'migration', '123')

    def test_revoked_new_rehearsal_can_be_archived_without_removing_source_or_backup(self):
        import test_d01_maintenance as fixtures
        from test_d01_shared_alignment import rehearsal_authorization_tree
        installer = fixtures.installer
        with tempfile.TemporaryDirectory() as directory, patch.object(installer, 'trusted_parent'), \
                patch.object(installer, 'retry_baseline'), \
                patch.object(installer, 'retry_configuration', return_value={'fixture': 'hash'}), \
                patch.object(installer.pwd, 'getpwnam', return_value=Mock(pw_uid=os.getuid())), \
                patch.object(installer, 'run', side_effect=lambda *a: 'inactive' if 'ActiveState' in a else 'disabled'):
            root = Path(directory)
            paths = rehearsal_authorization_tree(installer, root)
            state_path = paths['private'] / ('d01-' + paths['old_release'] + '.json')
            for path in (paths['install'] / 'config.json', state_path):
                value = json.loads(path.read_text())
                value.update(source_schema='121', target_schema='123')
                path.write_text(json.dumps(value))
            with patch.object(installer, 'INSTALL', paths['install']), patch.object(installer, 'DATA', root / 'data'), \
                    patch.object(installer, 'BASE', paths['base']), patch.object(installer, 'LAUNCHER', root / 'entry'), \
                    patch.object(installer, 'SUDOERS', root / 'grant'), patch.object(installer, 'UNITS', paths['units']), \
                    patch.object(installer, 'HISTORY', root / 'history'):
                installer.retire_revoked_rehearsal('d01-' + 'b' * 12, '121')
            archive = next((root / 'history').iterdir())
            self.assertTrue(archive.name.startswith('rehearsal-v121-'))
            self.assertTrue((archive / 'data/private/online-restore/evidence.json').is_file())
            self.assertEqual((paths['base'] / 'current').resolve(), paths['baseline'])


if __name__ == '__main__':
    unittest.main()
