import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import zipfile


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import d01_application_only_123 as release


class V123ApplicationOnlyTest(unittest.TestCase):
    def write_package(self, directory, *, migration='existing', defaults='preserved', building='PILOT-001'):
        (directory / 'web/assets').mkdir(parents=True, exist_ok=True)
        (directory / 'web/assets/FinalBPage-a.js').write_text(f'__name:"FinalBPage",setup(e){{const c="{building}"')
        index = directory / 'web/index.html'
        index.write_text('index')
        jar = directory / release.JAR
        with zipfile.ZipFile(jar, 'w') as package:
            package.writestr(release.MIGRATIONS + 'V123__existing.sql', migration)
            package.writestr('BOOT-INF/classes/application.properties', defaults)
        (directory / 'MANIFEST.txt').write_text('\n'.join((
            'sourceMigration=123', 'expectedMigration=123', 'releaseMode=APPLICATION_ONLY',
            'artifactMode=SHARED_APP_123', 'expectedDatabase=iot_platform',
            'commit=' + 'e' * 40, 'screenBuildingCode=PILOT-001',
            'webIndexSha256=' + hashlib.sha256(index.read_bytes()).hexdigest(),
            'backendJarSha256=' + hashlib.sha256(jar.read_bytes()).hexdigest(),
        )))

    def test_accepts_same_runtime_resources_and_original_building(self):
        with tempfile.TemporaryDirectory() as directory:
            old, new = Path(directory) / 'old', Path(directory) / 'new'
            self.write_package(old)
            self.write_package(new)
            self.assertEqual(release.verify_package(old, new)['expectedMigration'], '123')

    def test_rejects_changed_database_scripts_packaged_defaults_or_building(self):
        for values, reason in (({'migration': 'modified'}, 'RUNTIME_RESOURCES_CHANGED'),
                               ({'defaults': 'modified'}, 'RUNTIME_RESOURCES_CHANGED'),
                               ({'building': 'OTHER'}, 'SCREEN_BINDING_CHANGED')):
            with self.subTest(values=values), tempfile.TemporaryDirectory() as directory:
                old, new = Path(directory) / 'old', Path(directory) / 'new'
                self.write_package(old)
                self.write_package(new, **values)
                with self.assertRaisesRegex(RuntimeError, reason):
                    release.verify_package(old, new)

    def test_rolls_back_application_if_candidate_does_not_become_ready(self):
        with tempfile.TemporaryDirectory() as directory:
            old, new = Path(directory) / 'old', Path(directory) / 'new'
            self.write_package(old)
            live = [old]
            with mock.patch.object(release, 'command') as command, \
                    mock.patch.object(release, 'service_property', return_value='inactive'), \
                    mock.patch.object(release, 'current_release', side_effect=lambda: live[0]), \
                    mock.patch.object(release, 'switch_current', side_effect=lambda path: live.__setitem__(0, path)) as switch, \
                    mock.patch.object(release, 'wait_ready', side_effect=[RuntimeError('not ready'), None]):
                with self.assertRaisesRegex(RuntimeError, 'not ready'):
                    release.cutover(old, new, {'webIndexSha256': 'new'}, {}, '')
            self.assertEqual(switch.call_args_list, [mock.call(new), mock.call(old)])
            self.assertEqual([call.args[1:] for call in command.call_args_list], [
                ('stop', release.WEB, release.BACKEND), ('start', release.BACKEND, release.WEB),
                ('stop', release.WEB, release.BACKEND), ('start', release.BACKEND, release.WEB),
            ])


if __name__ == '__main__':
    unittest.main()
