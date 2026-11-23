"""Reproduce long-password truncation and verify export/restore on new local containers."""
import argparse
import importlib.util
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

TD_IMAGES = {
    '3.2.3.0': 'tdengine/tdengine@sha256:ae60a3a45dee241c79a54a5b301731dd12469b8e57ac857f4df932cf136335fc',
    '3.3.0.0': 'tdengine/tdengine@sha256:c0c052a2425651ed666552f75adbc7d50e7164b32bec40b8a9cc080fcaebbae4',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tdengine-version', choices=TD_IMAGES, default='3.2.3.0')
    version = parser.parse_args().tdengine_version
    os.environ['DOCKER_HOST'] = probe.DOCKER_HOST
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
                '--mount', 'type=volume,source=' + volume + ',target=/var/lib/taos', TD_IMAGES[version], timeout=180)
            probe.RESOURCES['containers'].append(identifier)
            ports = probe.owned('container', identifier)['NetworkSettings']['Ports']['6041/tcp']
            assert len(ports) == 1 and ports[0]['HostIp'] == '127.0.0.1'
            probe.ENV['D01_TD_PORT'] = ports[0]['HostPort']
            probe.ENV['D01_TD_PASSWORD'] = 'taosdata'  # disposable image default, never a field credential
            probe.wait_for(lambda: any('ready' in str(r).lower() for r in probe.taos('SHOW DNODES')['data']), stage='export td')
            assert probe.taos('SELECT SERVER_VERSION()')['data'] == [[version]]
            return identifier, ports[0]['HostPort']
        source, source_port = instance('source')
        password = 'D01!' + uuid.uuid4().hex[:27]  # 31 bytes, above the legacy stdin limit
        probe.taos("ALTER USER root PASS '" + password + "'")
        probe.ENV['D01_TD_PASSWORD'] = password
        probe.taos('CREATE DATABASE iot_telemetry KEEP 365')
        probe.taos('CREATE STABLE iot_telemetry.samples(ts TIMESTAMP,v DOUBLE,q TINYINT) TAGS(point NCHAR(32))')
        probe.taos('CREATE STABLE iot_telemetry.door_observations(ts TIMESTAMP,door_open BOOL,space_id BINARY(32)) TAGS(source_id BINARY(32))')
        event = int(probe.ENV['D01_EVENT_TIME'])
        probe.taos(f"INSERT INTO iot_telemetry.p USING iot_telemetry.samples TAGS('室温') VALUES({event},120.125,1) ({event + 1},NULL,2)")
        probe.taos(f"INSERT INTO iot_telemetry.door USING iot_telemetry.door_observations TAGS('door-1') VALUES({event},true,'B314') ({event + 2},false,'B314')")
        def snapshot():
            return [
                probe.taos('SELECT tbname,CAST(ts AS BIGINT),v,q,point FROM iot_telemetry.samples ORDER BY ts')['data'],
                probe.taos('SELECT tbname,CAST(ts AS BIGINT),door_open,space_id,source_id FROM iot_telemetry.door_observations ORDER BY ts')['data'],
            ]
        expected = snapshot()
        assert [len(rows) for rows in expected] == [2, 2]
        with tempfile.TemporaryDirectory(dir=ROOT / 'target', prefix='d01-export-') as tmp:
            work = Path(tmp)
            legacy = subprocess.run([probe.DOCKER, '--host', probe.DOCKER_HOST,
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
            probe.taos(f"INSERT INTO iot_telemetry.p VALUES({event + 3},24.5,0)")
            source_after_backup = snapshot()
            assert source_after_backup[0][:2] == expected[0] and len(source_after_backup[0]) == 3
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
            d01_backup.verify_tdengine_client_abi(probe.DOCKER, restored, subprocess.run)
            probe.taos("ALTER USER root PASS '" + password + "'")
            probe.ENV['D01_TD_PASSWORD'] = password
            probe.docker('cp', str(output), restored + ':/tmp/d01-restore')
            probe.docker('cp', str(client), restored + ':/tmp/taosdump-d01')
            probe.docker('exec', restored, 'chmod', '700', '/tmp/taosdump-d01')
            for invalid in (b'', b'\n', b'x' * 129 + b'\n', b'x' * 31):
                rejected = subprocess.run([probe.DOCKER, '--host', probe.DOCKER_HOST,
                    'exec', '-i', restored, '/tmp/taosdump-d01', '-u', 'root', '-p', '-D', 'iot_telemetry',
                    '-o', '/tmp/invalid-input', '-r', '/dev/null', '-T', '1'],
                    input=invalid, capture_output=True, timeout=30)
                assert rejected.returncode != 0 and b'D01_TD_PASSWORD_INPUT_INVALID' in rejected.stderr
            print('EMPTY_OVERFLOW_AND_INCOMPLETE_PASSWORD_INPUT_REJECTED', flush=True)
            subprocess.run([probe.DOCKER, '--host', probe.DOCKER_HOST, 'exec', '-i', restored,
                            '/tmp/taosdump-d01', '-u', 'root', '-p', '-i', '/tmp/d01-restore', '-r', '/dev/null', '-T', '1'],
                           input=(password + '\n').encode(), capture_output=True, check=True, timeout=90)
            assert snapshot() == expected
            probe.docker('restart', restored)
            ports = probe.owned('container', restored)['NetworkSettings']['Ports']['6041/tcp']
            assert len(ports) == 1 and ports[0]['HostIp'] == '127.0.0.1'
            probe.ENV['D01_TD_PORT'] = ports[0]['HostPort']
            probe.wait_for(lambda: any('ready' in str(r).lower() for r in probe.taos('SHOW DNODES')['data']), stage='restart restored td')
            assert snapshot() == expected
            probe.ENV['D01_TD_PORT'] = source_port
            assert snapshot() == source_after_backup
            print('ISOLATED_RESTORE_AND_RESTART_ROWS_MATCH source_post_backup_write_preserved=yes', flush=True)
        print(f'D01_EXPORT_ISOLATED_PASSED version={version} synthetic-data-only', flush=True)
    finally:
        for kind, resources in (('container', probe.RESOURCES['containers']), ('network', probe.RESOURCES['networks']), ('volume', probe.RESOURCES['volumes'])):
            for identifier in reversed(resources):
                probe.owned(kind, identifier)
                probe.docker(*(['rm', '-f', identifier] if kind == 'container' else [kind, 'rm', identifier]))


if __name__ == '__main__':
    main()
