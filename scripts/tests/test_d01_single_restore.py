"""只核验本轮单次最终恢复的状态机；无真实Docker、systemd或数据库调用。"""
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

if sys.platform == 'linux':
    import test_d01_maintenance as fixtures
    import test_unified_release as artifacts
    bridge = fixtures.bridge


@unittest.skipUnless(sys.platform == 'linux', '维护入口的POSIX权限在Linux离线验证')
class SingleFinalRestoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.operation = op = object.__new__(bridge.Maintenance)
        op.config = {'backup_policy': 'FROZEN_SINGLE_RESTORE_V1', 'source_schema': '121',
                     'target_schema': '123', 'release_mode': 'SCHEMA_UPGRADE',
                     'authorization_reference': 'approved single final restore'}
        op.operation = {'phase': 'PREPARED', 'events': []}
        op.private, op.ledger = root, root / 'operation.json'
        op.state, op.backup_credentials, op.schema_credentials = root / 'frozen.json', root / 'backup.json', root / 'schema.json'
        op.release = Mock()
        op.release.protected_json.return_value = {'backup_targets': {
            'mysql_container': 'fixture-mysql', 'mysql_volume': 'fixture-mysql-volume',
            'tdengine_container': 'fixture-td', 'tdengine_volume': 'fixture-td-volume'}}
        op.backup_tool = Mock(DEFAULT_MIN_FREE_BYTES=10)
        self.record = {'checked_at': int(time.time()), 'no_other_deployment': True,
                       'external_writers_fenced': True, 'reference': 'current approved window'}
        self.addCleanup(patch.stopall)
        patch.object(bridge, 'protected').start()
        self.input = patch.object(bridge, 'bounded_input', return_value=self.record).start()

    def test_new_policy_freezes_after_readiness_without_online_dump_or_restore(self):
        op = self.operation
        op.freeze()
        self.assertEqual(op.operation['phase'], 'FROZEN')
        self.assertEqual([e['phase'] for e in op.operation['events']], ['FREEZING', 'FROZEN'])
        op.release.freeze.assert_called_once()
        op.release.checked_restore.assert_not_called()
        op.backup_tool._dump_mysql.assert_not_called()
        op.backup_tool._dump_tdengine.assert_not_called()
        op.backup_tool._output_root.assert_called_once_with(op.private, 10)
        self.assertEqual(op.backup_tool._container.call_count, 2)
        op.backup_tool.verify_tdengine_client_abi.assert_called_once_with(
            '/usr/bin/docker', 'fixture-td', bridge.subprocess.run)

    def test_readiness_failure_never_stops_services(self):
        op = self.operation
        op.backup_tool._output_root.side_effect = RuntimeError('insufficient space')
        with self.assertRaisesRegex(RuntimeError, 'insufficient space'):
            op.freeze()
        op.release.freeze.assert_not_called()
        self.assertEqual(op.operation['phase'], 'PREPARED')

    def test_incompatible_backup_client_never_stops_services(self):
        op = self.operation
        op.backup_tool.verify_tdengine_client_abi.side_effect = RuntimeError('TDENGINE_CLIENT_ABI_MISMATCH')
        with self.assertRaisesRegex(RuntimeError, 'CLIENT_ABI_MISMATCH'):
            op.freeze()
        op.release.freeze.assert_not_called()

    def test_recent_writer_and_window_confirmation_still_required(self):
        self.input.return_value = {}
        with self.assertRaisesRegex(RuntimeError, 'RECENT_WINDOW_CHECK'):
            self.operation.freeze()
        self.operation.release.freeze.assert_not_called()
        self.operation.backup_tool._output_root.assert_not_called()

    def test_final_backup_and_real_restore_gate_cannot_be_skipped(self):
        op = self.operation
        for phase in ('PREPARED', 'FROZEN', 'BACKED_UP', 'ONLINE_VERIFIED'):
            op.operation['phase'] = phase
            with self.subTest(phase=phase), self.assertRaisesRegex(RuntimeError, 'PHASE_MISMATCH'):
                op.migrate()
        op.operation['phase'] = 'BACKED_UP'
        op.operation['backup_directory'] = str(op.private / 'backup')
        self.input.return_value = {'report': {}, 'evidence': {}}
        op.release.checked_restore.side_effect = RuntimeError('restore evidence mismatch')
        with self.assertRaisesRegex(RuntimeError, 'restore evidence mismatch'):
            op.restore_record(False)
        self.assertEqual(op.operation['phase'], 'BACKED_UP')
        op.release.migrate.assert_not_called()
        op.release.cutover.assert_not_called()

    def test_old_authorization_keeps_online_restore_and_cannot_take_new_shortcut(self):
        op = self.operation
        del op.config['backup_policy']
        op.config.update(source_schema='93', target_schema='121')
        with self.assertRaisesRegex(RuntimeError, 'PHASE_MISMATCH'):
            op.freeze()
        op.operation['phase'] = 'ONLINE_VERIFIED'
        op.freeze()
        op.release.checked_restore.assert_called_once_with(op.private / 'online-backup', op.private / 'online-restore/report.json')
        op.backup_tool._output_root.assert_not_called()

    def test_other_schema_plans_cannot_reuse_policy_and_rehearsal_remains_read_only(self):
        op = self.operation
        for changes in ({'source_schema': '93'}, {'target_schema': '121'}, {'release_mode': 'APPLICATION_ONLY'},
                        {'backup_policy': 'unknown'}):
            with self.subTest(changes=changes), patch.dict(op.config, changes), self.assertRaisesRegex(RuntimeError, 'BACKUP_POLICY_INVALID'):
                op.freeze()
        with patch.dict(op.config, {'rehearsal_only': True}), self.assertRaisesRegex(RuntimeError, 'PHASE_MISMATCH'):
            op.freeze()
        op.release.freeze.assert_not_called()

    def test_formal_single_restore_authorization_rejects_duplicate_online_actions(self):
        for action in ('online-backup', 'export-online', 'record-online-restore'):
            with self.subTest(action=action), patch.object(bridge, 'load_config', return_value=self.operation.config), \
                    patch.object(sys, 'argv', ['maintenance', action]), patch.object(bridge, 'Maintenance') as constructor:
                with self.assertRaisesRegex(RuntimeError, 'SINGLE_RESTORE_ONLY_ACTION'):
                    bridge.main()
                constructor.assert_not_called()

    def test_policy_is_pinned_in_real_package_contract_and_not_assumed_from_version(self):
        artifact = artifacts.ArtifactTest()
        artifact.setUp()
        self.addCleanup(artifact.doCleanups)
        result = artifact.build('shared', 'iot_platform')
        package = Path(result['archive']).read_bytes()
        fixtures.installer.package_manifest(package)
        for policy in (None, 'unknown'):
            output = io.BytesIO()
            with tarfile.open(fileobj=io.BytesIO(package)) as source, tarfile.open(fileobj=output, mode='w:gz') as target:
                for member in source.getmembers():
                    if member.name == 'MANIFEST.txt':
                        manifest = dict(result['manifest'])
                        manifest.pop('backupPolicy')
                        if policy is not None:
                            manifest['backupPolicy'] = policy
                        data = ''.join(k + '=' + v + '\n' for k, v in manifest.items()).encode()
                        member.size = len(data)
                        target.addfile(member, io.BytesIO(data))
                    else:
                        target.addfile(member, source.extractfile(member) if member.isfile() else None)
            with self.subTest(policy=policy), self.assertRaisesRegex(RuntimeError, 'BACKUP_POLICY|UNIFIED_PACKAGE_PLAN'):
                fixtures.installer.package_manifest(output.getvalue())


if __name__ == '__main__':
    unittest.main()
