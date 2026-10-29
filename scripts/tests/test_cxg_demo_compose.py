"""Validate deployment isolation without creating containers or contacting a daemon."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class DemoComposeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        release = cls.root / 'releases/test'
        release.mkdir(parents=True)
        private = cls.root / 'private'
        private.mkdir()
        source = Path(__file__).resolve().parents[2] / 'src/env/cxg-demo'
        for name in ('compose.yml', 'nginx.conf'):
            shutil.copy2(source / name, release / name)
        for name in ('mysql.env', 'redis.env'):
            (private / name).write_text('UNUSED=offline-validation\n')
        (release / '.env').write_text('\n'.join(
            name + '=example.invalid/image@sha256:' + 'a' * 64
            for name in ('MYSQL_IMAGE', 'TDENGINE_IMAGE', 'REDIS_IMAGE', 'JAVA_IMAGE', 'NGINX_IMAGE')))
        result = subprocess.run(
            ['docker', 'compose', '--env-file', str(release / '.env'), '-f', str(release / 'compose.yml'),
             'config', '--format', 'json'], capture_output=True, check=True, text=True, timeout=30)
        cls.config = json.loads(result.stdout)
        cls.nginx = (release / 'nginx.conf').read_text()

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_only_the_demo_proxy_publishes_a_host_port(self):
        self.assertEqual(self.config['name'], 'building-energy-carbon-demo')
        for name, service in self.config['services'].items():
            ports = service.get('ports', [])
            if name == 'web':
                self.assertEqual([(str(p['published']), p['target']) for p in ports], [('19080', 8080)])
            else:
                self.assertEqual(ports, [], name)

    def test_data_stores_and_application_cannot_join_the_external_network(self):
        self.assertTrue(self.config['networks']['data']['internal'])
        self.assertTrue(self.config['networks']['application']['internal'])
        self.assertEqual(set(self.config['services']['app']['networks']), {'application', 'data'})
        for name in ('mysql', 'tdengine', 'redis'):
            self.assertEqual(set(self.config['services'][name]['networks']), {'data'})

    def test_storage_and_bind_mounts_are_owned_by_this_instance(self):
        for volume in self.config['volumes'].values():
            self.assertTrue(volume['name'].startswith('building-energy-carbon-demo-'))
        for service in self.config['services'].values():
            self.assertGreater(int(service['mem_limit']), 0)
            for mount in service.get('volumes', []):
                if mount['type'] == 'bind':
                    self.assertTrue(Path(mount['source']).resolve().is_relative_to(self.root.resolve()))
                    self.assertTrue(mount['read_only'])

    def test_proxy_targets_only_this_application_and_hides_management(self):
        self.assertIn('proxy_pass http://app:18081;', self.nginx)
        self.assertIn('/actuator', self.nginx)
        self.assertNotIn('192.168.', self.nginx)
        self.assertNotIn('18080', self.nginx)
        self.assertEqual(self.config['services']['app']['user'], '1000:1000')
        command = self.config['services']['app']['command']
        self.assertIn('--spring.config.additional-location=file:/config/test-base.yml,file:/config/backend.yml', command)
        self.assertFalse(any(arg.startswith('--spring.config.location=') for arg in command))
        self.assertIn('tdengine-core', self.config['volumes'])


if __name__ == '__main__':
    unittest.main()
