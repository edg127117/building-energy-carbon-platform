"""Offline lifecycle and isolation contracts; never opens databases or stops real processes."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('cxg_monthly_env', Path(__file__).resolve().parents[1] / 'cxg_monthly_env.py')
env = importlib.util.module_from_spec(spec)
spec.loader.exec_module(env)


class EnvironmentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ('private', 'config', 'backend', 'logs', 'data/redis'):
            (self.root / name).mkdir(parents=True)
        jar = self.root / 'backend/application.jar'; jar.write_bytes(b'accepted fixture')
        self.config = {'format': 'CXG_MONTHLY_RUNTIME_V1', 'purpose': 'TEST', 'dataset': 'CXG_MONTHLY_V2',
                       'buildingId': '123', 'ports': {'api': 50001, 'mysql': 50002, 'redis': 50003, 'tdengine': 50004},
                       'mysqlUrl': 'jdbc:mysql://127.0.0.1:50002/iot_platform?useSSL=false',
                       'mysqlPassword': 'mysql-private', 'redisPassword': 'redis-private',
                       'backendJarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
                       'executables': {'java': 'java.exe', 'mysql': 'mysqld.exe', 'redis': 'redis-server.exe'},
                       'tdengine': {'username': 'test_reader', 'password': 'td-private', 'containerId': 'a' * 64,
                                    'containerName': 'owned-test', 'owner': 'nonce', 'volume': 'owned-data'},
                       'accounts': {'admin': {'username': 'admin', 'password': 'account-private'}}, 'expectedMonths': {}}
        self.settings = {k: False for k in env.DISABLED}
        self.settings.update({'server.address': '127.0.0.1', 'server.port': 50001,
            'spring.datasource.url': self.config['mysqlUrl'], 'spring.datasource.password': 'mysql-private',
            'spring.data.redis.host': '127.0.0.1', 'spring.data.redis.port': 50003,
            'spring.data.redis.password': 'redis-private', 'spring.sql.init.mode': 'never',
            'tdengine.url': 'jdbc:TAOS-RS://127.0.0.1:50004', 'tdengine.username': 'test_reader',
            'tdengine.password': 'td-private', 'audit-governance.environment-mode': 'TEST',
            'relation-governance.deployment-environment': 'test'})
        self.write()

    def write(self):
        env.write_json(self.root / 'private/instance.json', self.config)
        (self.root / 'config/backend.yml').write_text('\n'.join(k + ': ' + json.dumps(v) for k, v in self.settings.items()), encoding='utf-8')
        (self.root / 'config/redis.conf').write_text('bind 127.0.0.1\nport 50003\nrequirepass redis-private\ndir "' + (self.root / 'data/redis').as_posix() + '"\nsave ""\nappendonly no\n', encoding='utf-8')

    def instance(self):
        return env.Environment(self.root)

    def td(self):
        return {'Id': 'a' * 64, 'Name': '/owned-test', 'State': {'Running': True},
                'Config': {'Labels': {'bec.test.owner': 'nonce', 'bec.test.dataset': 'CXG_MONTHLY_V2', 'bec.security.tdengine-exclusive': 'true'}},
                'HostConfig': {'PortBindings': {'6041/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '50004'}]}},
                'Mounts': [{'Destination': '/var/lib/taos', 'Type': 'volume', 'Name': 'owned-data'}]}

    def test_valid_configuration_is_accepted(self):
        self.assertEqual(self.instance().api, 'http://127.0.0.1:50001/api')

    def test_each_live_ingress_and_background_task_is_refused(self):
        for key in env.DISABLED:
            with self.subTest(key=key):
                self.settings[key] = True; self.write()
                with self.assertRaisesRegex(RuntimeError, 'LIVE_INGRESS'):
                    self.instance()
                self.settings[key] = False

    def test_remote_database_target_and_duplicate_ports_are_refused(self):
        self.config['mysqlUrl'] = 'jdbc:mysql://192.0.2.1:50002/iot_platform'; self.write()
        with self.assertRaisesRegex(RuntimeError, 'LOCAL_MYSQL_URL'):
            self.instance()
        self.config['mysqlUrl'] = self.settings['spring.datasource.url']
        self.config['ports']['mysql'] = self.config['ports']['api']; self.write()
        with self.assertRaisesRegex(RuntimeError, 'DISTINCT_LOCAL_PORTS'):
            self.instance()

    def test_endpoint_config_and_jar_tampering_are_refused(self):
        self.settings['spring.data.redis.host'] = '192.0.2.2'; self.write()
        with self.assertRaisesRegex(RuntimeError, 'CONFIG_TARGET'):
            self.instance()
        self.settings['spring.data.redis.host'] = '127.0.0.1'; self.write()
        (self.root / 'backend/application.jar').write_bytes(b'different')
        with self.assertRaisesRegex(RuntimeError, 'JAR_CHANGED'):
            self.instance()

    def test_added_redis_bind_cannot_override_the_loopback_target(self):
        with (self.root / 'config/redis.conf').open('a', encoding='utf-8') as stream:
            stream.write('bind 0.0.0.0\n')
        with self.assertRaisesRegex(RuntimeError, 'REDIS_CONFIG_TARGET'):
            self.instance()

    def test_operation_lock_is_exclusive_and_released_on_failure(self):
        with self.assertRaisesRegex(RuntimeError, 'fixture failure'):
            with env.operation_lock(self.root):
                with self.assertRaisesRegex(RuntimeError, 'ANOTHER_ENVIRONMENT_OPERATION'):
                    with env.operation_lock(self.root):
                        self.fail('second operation acquired the lock')
                raise RuntimeError('fixture failure')
        with env.operation_lock(self.root):
            pass

    def test_process_identity_requires_exact_executable_and_argument(self):
        good = {'ExecutablePath': 'C:\\Apps\\mysqld.exe', 'CommandLine': 'mysqld.exe "--datadir=C:\\Test Data\\mysql"'}
        self.assertTrue(env.owned_process(good, 'c:/apps/mysqld.exe', 'C:/Test Data/mysql'))
        self.assertFalse(env.owned_process({**good, 'ExecutablePath': 'other.exe'}, 'C:/Apps/mysqld.exe', 'C:/Test Data/mysql'))
        self.assertFalse(env.owned_process({**good, 'CommandLine': 'mysqld.exe "--datadir=C:\\Test Data\\mysql-other"'}, 'C:/Apps/mysqld.exe', 'C:/Test Data/mysql'))
        self.assertFalse(env.owned_process(None, 'java.exe', 'app.jar'))

    def test_foreign_container_volume_and_public_binding_are_refused(self):
        instance = self.instance()
        for scenario in ('owner', 'volume', 'binding', 'id'):
            data = self.td()
            if scenario == 'owner': data['Config']['Labels']['bec.test.owner'] = 'someone-else'
            if scenario == 'volume': data['Mounts'][0]['Name'] = 'shared-data'
            if scenario == 'binding': data['HostConfig']['PortBindings']['6041/tcp'][0]['HostIp'] = '0.0.0.0'
            if scenario == 'id': data['Id'] = 'b' * 64
            with self.subTest(scenario=scenario), patch.object(env, 'run', return_value=json.dumps([data])) as command:
                with self.assertRaisesRegex(RuntimeError, 'TDENGINE_IDENTITY'):
                    instance.td_info()
                self.assertEqual(command.call_args.args[0], ['docker', 'inspect', 'a' * 64])

    def test_stop_validates_all_processes_before_stopping_anything(self):
        instance = self.instance()
        with patch.object(instance, 'td_info', return_value=self.td()), \
                patch.object(instance, 'role_info', side_effect=[{'ProcessId': 1}, RuntimeError('PROCESS_IDENTITY_MISMATCH_MYSQL')]), \
                patch.object(env, 'run') as command:
            with self.assertRaisesRegex(RuntimeError, 'IDENTITY_MISMATCH'):
                instance.stop()
            command.assert_not_called()

    def test_occupied_port_does_not_launch_or_stop_a_process(self):
        instance = self.instance()
        with patch.object(instance, 'role_info', return_value=None), patch.object(env, 'port_open', return_value=True), patch.object(env.subprocess, 'Popen') as launch:
            with self.assertRaisesRegex(RuntimeError, 'PORT_OCCUPIED'):
                instance.launch('backend', ['java.exe'])
            launch.assert_not_called()

    def test_reused_pid_after_reboot_is_ignored_without_stopping_foreign_process(self):
        instance = self.instance(); instance.state['backend'] = 123
        with patch.object(env, 'process', return_value={'ExecutablePath': 'other.exe', 'CommandLine': 'other.exe'}), \
                patch.object(env, 'port_open', return_value=False), patch.object(env, 'run') as command:
            self.assertIsNone(instance.role_info('backend'))
            command.assert_not_called()
        with patch.object(env, 'process', return_value={'ExecutablePath': 'other.exe', 'CommandLine': 'other.exe'}), \
                patch.object(env, 'port_open', return_value=True):
            with self.assertRaisesRegex(RuntimeError, 'PROCESS_IDENTITY_MISMATCH'):
                instance.role_info('backend')

    def test_existing_owned_process_start_is_idempotent(self):
        instance = self.instance()
        with patch.object(instance, 'role_info', return_value={'ProcessId': 1}), patch.object(env, 'port_open', return_value=True), patch.object(env.subprocess, 'Popen') as launch:
            instance.launch('backend', ['java.exe'])
            launch.assert_not_called()

    def test_backend_does_not_inherit_shell_data_sources_or_vendor_credentials(self):
        instance = self.instance(); child = Mock(pid=123); child.poll.return_value = None
        with patch.object(instance, 'role_info', return_value=None), patch.object(env, 'port_open', side_effect=[False, True]), \
                patch.dict(os.environ, {'SPRING_DATASOURCE_URL': 'remote', 'SPRING_APPLICATION_JSON': 'remote', 'MQTT_ENABLED': 'true', 'DAIKIN_PASSWORD': 'private'}), \
                patch.object(env.subprocess, 'Popen', return_value=child) as launch:
            instance.launch('backend', ['java.exe'])
            for key in ('SPRING_DATASOURCE_URL', 'SPRING_APPLICATION_JSON', 'MQTT_ENABLED', 'DAIKIN_PASSWORD'):
                self.assertNotIn(key, launch.call_args.kwargs['env'])

    def test_status_redacts_credentials(self):
        instance = self.instance()
        with patch.object(instance, 'role_info', return_value={}), patch.object(instance, 'td_info', return_value=self.td()), patch.object(env, 'port_open', return_value=True):
            text = json.dumps(instance.status())
        for secret in ('mysql-private', 'redis-private', 'td-private', 'account-private'):
            self.assertNotIn(secret, text)

    def test_dual_store_missing_results_fail_before_reporting_success(self):
        instance = self.instance()
        with patch.object(instance, 'status'), patch.object(instance, 'redis', return_value=b'+PONG\r\n'), \
                patch.object(instance, 'mysql', side_effect=[str(self.root / 'data/mysql'), '']):
            with self.assertRaisesRegex(RuntimeError, 'SNAPSHOT_COUNT'):
                instance.check()

    def test_monthly_query_preserves_building_period_and_frozen_area_identity(self):
        instance = self.instance()
        expected = {'carbonBatchId': 'carbon', 'statisticsBatchId': 'statistics', 'areaVersionId': 'area',
                    'kWh': '10', 'tce': '1', 'kgCo2e': '5', 'kgCo2ePerM2': '0.5'}
        instance.config['expectedMonths'] = {'2026-09': expected}
        for scenario in ('valid', 'area', 'building', 'period'):
            carbon = {'buildingId': 'other' if scenario == 'building' else '123', 'status': 'COMPLETED_COMPLETE',
                      'startInclusive': '2026-07-31T16:00:00Z' if scenario == 'period' else '2026-08-31T16:00:00Z',
                      'items': [{'emissionKgCo2e': '5'}], 'summaries': [{'metricCode': 'MONTH_AREA_INTENSITY', 'finalValue': '0.5',
                                  'denominatorVersionId': 'other' if scenario == 'area' else 'area'}]}
            batch = {'batchId': 'statistics', 'buildingId': '123', 'month': '2026-09-01', 'status': 'SEALED',
                     'snapshot': {'rows': [{'nativeQuantity': '10', 'tce': '1'}]}}
            with self.subTest(scenario=scenario), patch.object(instance, 'request', side_effect=[{'token': 'private'}, carbon, {'batches': [batch]}]):
                if scenario == 'valid':
                    self.assertEqual(instance.check_months()['2026-09']['kWh'], '10')
                else:
                    with self.assertRaises(RuntimeError): instance.check_months()


if __name__ == '__main__':
    unittest.main()
