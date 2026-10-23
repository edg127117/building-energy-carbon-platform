"""Reproduce long-password truncation and verify export/restore on new local containers."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('d01_probe', ROOT / 'scripts/Verify-D01Isolated.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
sys.path.insert(0, str(ROOT / 'scripts'))
import d01_backup


def main():
    assert len(sys.argv) == 1, 'No existing endpoint accepted'
    os.environ['DOCKER_HOST'] = 'npipe:////./pipe/dockerDesktopLinuxEngine'
    client = ROOT / 'target/d01-taosdump-client/taosdump-d01'
    assert client.is_file(), 'Build scripts/d01-taosdump first'
    d01_backup.TD_CLIENT = client
    try:
        network = 'd01-export-' + probe.NONCE
        probe.docker('network', 'create', '--label', probe.LABEL, network)
        probe.RESOURCES['networks'].append(network)
        def instance(suffix):
            volume = network + '-' + suffix
            probe.docker('volume', 'create', '--label', probe.LABEL, volume)
            probe.RESOURCES['volumes'].append(volume)
            # The shared helper names by nonce; use a distinct suffix per instance.
            identifier = probe.docker('run', '-d', '--name', volume, '--label', probe.LABEL,
                '--network', network, '--memory', '768m', '--cpus', '1', '-p', '127.0.0.1::6041',
                '--mount', 'type=volume,source=' + volume + ',target=/var/lib/taos', 'tdengine/tdengine:3.2.3.0')
            probe.RESOURCES['containers'].append(identifier)
            ports = probe.owned('container', identifier)['NetworkSettings']['Ports']['6041/tcp']
            assert len(ports) == 1 and ports[0]['HostIp'] == '127.0.0.1'
            probe.ENV['D01_TD_PORT'] = ports[0]['HostPort']
            probe.ENV['D01_TD_PASSWORD'] = 'taosdata'  # disposable image default, never a field credential
            probe.wait_for(lambda: any('ready' in str(r).lower() for r in probe.taos('SHOW DNODES')['data']), stage='export td')
            return identifier, ports[0]['HostPort']
        source, source_port = instance('source')
        password = 'D01!' + uuid.uuid4().hex[:27]  # 31 bytes, above the legacy stdin limit
        probe.taos("ALTER USER root PASS '" + password + "'")
        probe.ENV['D01_TD_PASSWORD'] = password
        probe.taos('CREATE DATABASE iot_telemetry KEEP 365')
        probe.taos('CREATE STABLE iot_telemetry.samples(ts TIMESTAMP,v DOUBLE,q TINYINT) TAGS(point NCHAR(32))')
        event = int(probe.ENV['D01_EVENT_TIME'])
        probe.taos("INSERT INTO iot_telemetry.p USING iot_telemetry.samples TAGS('D01') VALUES(" + str(event) + ',120,1)')
        expected = probe.taos('SELECT CAST(ts AS BIGINT),v,q,point FROM iot_telemetry.samples')['data']
        with tempfile.TemporaryDirectory(dir=ROOT / 'target', prefix='d01-export-') as tmp:
            work = Path(tmp)
            legacy = subprocess.run([probe.DOCKER, '--host', 'npipe:////./pipe/dockerDesktopLinuxEngine',
                'exec', '-i', source, 'sh', '-c',
                'mkdir /tmp/d01-legacy; taosdump -u root -p -D iot_telemetry -o /tmp/d01-legacy -r /dev/null -T 1'],
                input=(password + '\n').encode(), capture_output=True, timeout=60)
            assert legacy.returncode != 0 and b'authentication failure' in (legacy.stdout + legacy.stderr).lower()
            print('LEGACY_STDIN_31_BYTE_PASSWORD_TRUNCATION_REPRODUCED', flush=True)
            output = work / 'tdengine'
            d01_backup._dump_tdengine(output, {'tdengine_user': 'root', 'tdengine_password': password},
                                     probe.DOCKER, source, 'iot_telemetry', subprocess.run)
            assert any(output.rglob('*.avro'))
            assert not any(p.name in ('client', 'dump_result.txt') for p in output.rglob('*'))
            print('PINNED_CLIENT_LONG_PASSWORD_REAL_EXPORT_PASSED', flush=True)
            bad_dir = work / 'wrong'; bad_dir.mkdir()
            try:
                d01_backup._dump_tdengine(bad_dir / 'tdengine', {'tdengine_user': 'root', 'tdengine_password': 'X' + password[1:]},
                                         probe.DOCKER, source, 'iot_telemetry', subprocess.run)
            except d01_backup.BackupError as error:
                assert error.diagnostic['category'] == 'authentication_failed'
            else:
                raise AssertionError('Wrong password accepted')
            diagnostic = (bad_dir / 'tdengine-diagnostic.json').read_text()
            assert password not in diagnostic and 'authentication_failed' in diagnostic
            print('WRONG_PASSWORD_REJECTED_WITH_SANITIZED_DIAGNOSTIC', flush=True)
            oversized = work / 'native-limit'; oversized.mkdir()
            try:
                d01_backup._dump_tdengine(oversized / 'tdengine', {'tdengine_user': 'root', 'tdengine_password': password + 'x'},
                                         probe.DOCKER, source, 'iot_telemetry', subprocess.run)
            except d01_backup.BackupError as error:
                assert error.diagnostic['category'] == 'password_input_invalid'
            else:
                raise AssertionError('Native password limit not enforced')
            assert password not in (oversized / 'tdengine-diagnostic.json').read_text()
            print('NATIVE_PASSWORD_LIMIT_REJECTED_WITH_SANITIZED_DIAGNOSTIC', flush=True)
            restored, _ = instance('restored')
            probe.docker('cp', str(output), restored + ':/tmp/d01-restore')
            probe.docker('cp', str(client), restored + ':/tmp/taosdump-d01')
            probe.docker('exec', restored, 'chmod', '700', '/tmp/taosdump-d01')
            for invalid in (b'', b'\n', b'x' * 129 + b'\n', b'x' * 31):
                rejected = subprocess.run([probe.DOCKER, '--host', 'npipe:////./pipe/dockerDesktopLinuxEngine',
                    'exec', '-i', restored, '/tmp/taosdump-d01', '-u', 'root', '-p', '-D', 'iot_telemetry',
                    '-o', '/tmp/invalid-input', '-r', '/dev/null', '-T', '1'],
                    input=invalid, capture_output=True, timeout=30)
                assert rejected.returncode != 0 and b'D01_TD_PASSWORD_INPUT_INVALID' in rejected.stderr
            print('EMPTY_OVERFLOW_AND_INCOMPLETE_PASSWORD_INPUT_REJECTED', flush=True)
            probe.docker('exec', restored, '/tmp/taosdump-d01', '-i', '/tmp/d01-restore', '-r', '/dev/null', '-T', '1')
            assert probe.taos('SELECT CAST(ts AS BIGINT),v,q,point FROM iot_telemetry.samples')['data'] == expected
            probe.ENV['D01_TD_PORT'] = source_port
            probe.ENV['D01_TD_PASSWORD'] = password
            assert probe.taos('SELECT CAST(ts AS BIGINT),v,q,point FROM iot_telemetry.samples')['data'] == expected
            print('ISOLATED_RESTORE_TIMESTAMP_VALUE_QUALITY_TAGS_MATCH_SOURCE_UNCHANGED', flush=True)
        print('D01_EXPORT_ISOLATED_PASSED synthetic-data-only no-field-backup-claim', flush=True)
    finally:
        for kind, resources in (('container', probe.RESOURCES['containers']), ('network', probe.RESOURCES['networks']), ('volume', probe.RESOURCES['volumes'])):
            for identifier in reversed(resources):
                probe.owned(kind, identifier)
                probe.docker(*(['rm', '-f', identifier] if kind == 'container' else [kind, 'rm', identifier]))


if __name__ == '__main__':
    main()
