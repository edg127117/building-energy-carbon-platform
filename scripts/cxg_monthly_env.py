"""Manage the existing Windows-only, loopback CXG monthly test environment.

Never initializes a database, imports facts, removes a volume, or contacts a shared server.
"""
import argparse
import base64
import contextlib
import datetime
import hashlib
import json
import os
from pathlib import Path
import shlex
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from decimal import Decimal


DISABLED = (
    'mqtt.enabled', 'daikin.device-client.enabled', 'daikin.runtime.enabled',
    'daikin.control.enabled', 'daikin.control.wire-confirmed', 'daikin.control.test-access-enabled',
    'daikin.control.test-open-controls', 'weather.enabled', 'weather.open-meteo-enabled',
    'weather.china-weather-enabled', 'weather.energy-enabled', 'daikin.retention.cleanup-enabled',
    'data-retention.cleanup-enabled', 'telemetry-receipt.retention-cleanup-enabled',
    'device-onboarding.discovery.cleanup-enabled', 'aggregation.recovery-enabled',
    'data-quality.enabled', 'data-quality.reconciliation-enabled', 'data-quality.recalculation-enabled',
    'carbon-management.recalculation-enabled', 'formula.recovery-enabled',
    'telemetry-conflicts.recovery-enabled', 'security.auth-cleanup-enabled',
    'audit-governance.retention-cleanup-enabled', 'spring.flyway.enabled',
    'tdengine.initialization-enabled', 'database.charset-fix.enabled',
)


def require(ok, code):
    if not ok:
        raise RuntimeError(code)


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def write_json(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(path)


def run(args, *, env=None, timeout=30):
    result = subprocess.run(args, env=env, capture_output=True, timeout=timeout)
    # Command output can contain credentials; callers receive stable codes instead.
    require(result.returncode == 0, 'COMMAND_FAILED_' + Path(args[0]).stem.upper())
    return result.stdout.decode('utf-8-sig').strip()


def process(pid):
    if not pid:
        return None
    script = ("[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false); "
              f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId = {int(pid)}'; "
              "if ($p) {$p | Select-Object ProcessId,ExecutablePath,CommandLine | ConvertTo-Json -Compress}")
    value = run(['powershell', '-NoProfile', '-Command', script])
    return json.loads(value) if value else None


def normalized(value):
    return str(value).replace('\\', '/').casefold()


def owned_process(info, executable, marker):
    if info is None or normalized(info.get('ExecutablePath', '')) != normalized(executable):
        return False
    arguments = {normalized(v.strip('"')) for v in shlex.split(info.get('CommandLine') or '', posix=False)}
    return normalized(marker) in arguments or '--datadir=' + normalized(marker) in arguments


def port_open(port):
    with socket.socket() as connection:
        connection.settimeout(0.5)
        return connection.connect_ex(('127.0.0.1', port)) == 0


@contextlib.contextmanager
def operation_lock(root):
    # Serialize lifecycle operations so two launchers cannot start competing processes.
    with (root / 'private/operation.lock').open('a+b') as stream:
        stream.seek(0)
        if os.name == 'nt':
            import msvcrt
            if os.fstat(stream.fileno()).st_size == 0:
                stream.write(b'0'); stream.flush()
            stream.seek(0)
            try:
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError:
                raise RuntimeError('ANOTHER_ENVIRONMENT_OPERATION_IS_RUNNING') from None
            try:
                yield
            finally:
                stream.seek(0); msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            try:
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                raise RuntimeError('ANOTHER_ENVIRONMENT_OPERATION_IS_RUNNING') from None
            yield


class Environment:
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.config = read_json(self.root / 'private/instance.json')
        self.state_path = self.root / 'private/processes.json'
        self.state = read_json(self.state_path) if self.state_path.exists() else {}
        self.settings = {k: json.loads(v) for line in (self.root / 'config/backend.yml').read_text(encoding='utf-8').splitlines()
                         if line.strip() for k, v in [line.split(': ', 1)]}
        self.validate()

    @property
    def api(self):
        return f"http://127.0.0.1:{self.config['ports']['api']}/api"

    def validate(self):
        c = self.config
        require(c.get('format') == 'CXG_MONTHLY_RUNTIME_V1' and c.get('purpose') == 'TEST'
                and c.get('dataset') == 'CXG_MONTHLY_V2', 'TEST_INSTANCE_REQUIRED')
        ports = list(c['ports'].values())
        require(len(ports) == 4 and len(set(ports)) == 4 and all(type(p) is int and 1024 <= p <= 65535 for p in ports), 'DISTINCT_LOCAL_PORTS_REQUIRED')
        require(str(c['buildingId']).isdecimal(), 'BUILDING_ID_REQUIRED')
        s = self.settings
        require(all(s.get(k) is False for k in DISABLED), 'LIVE_INGRESS_OR_BACKGROUND_TASK_ENABLED')
        url = c['mysqlUrl']
        require(url.split('?', 1)[0] == f"jdbc:mysql://127.0.0.1:{c['ports']['mysql']}/iot_platform"
                and not any(v in url.lower() for v in ('user=', 'password=')), 'LOCAL_MYSQL_URL_REQUIRED')
        expected = {'server.address': '127.0.0.1', 'server.port': c['ports']['api'],
                    'spring.datasource.url': url, 'spring.datasource.password': c['mysqlPassword'],
                    'spring.data.redis.host': '127.0.0.1', 'spring.data.redis.port': c['ports']['redis'],
                    'spring.data.redis.password': c['redisPassword'], 'spring.sql.init.mode': 'never',
                    'tdengine.url': f"jdbc:TAOS-RS://127.0.0.1:{c['ports']['tdengine']}",
                    'tdengine.username': c['tdengine']['username'], 'tdengine.password': c['tdengine']['password'],
                    'audit-governance.environment-mode': 'TEST', 'relation-governance.deployment-environment': 'test'}
        require(all(s.get(k) == v for k, v in expected.items()), 'INSTANCE_CONFIG_TARGET_MISMATCH')
        jar = self.root / 'backend/application.jar'
        require(hashlib.sha256(jar.read_bytes()).hexdigest() == c['backendJarSha256'], 'BACKEND_JAR_CHANGED')
        redis = (self.root / 'config/redis.conf').read_text(encoding='utf-8')
        expected_redis = (f'bind 127.0.0.1\nport {c["ports"]["redis"]}\nrequirepass {c["redisPassword"]}\n'
                          f'dir "{(self.root / "data/redis").as_posix()}"\nsave ""\nappendonly no\n')
        require(redis == expected_redis, 'REDIS_CONFIG_TARGET_MISMATCH')

    def save_state(self):
        write_json(self.state_path, self.state)

    def role_info(self, role):
        c = self.config
        marker = {'backend': self.root / 'backend/application.jar', 'mysql': self.root / 'data/mysql',
                  'redis': self.root / 'config/redis.conf'}[role]
        executable = c['executables']['java' if role == 'backend' else role]
        info = process(self.state.get(role))
        if info is not None and not owned_process(info, executable, marker):
            # PIDs can be reused after reboot. Ignore a stale reference only when its service port is free;
            # never terminate the unrelated process or adopt a listener using the same port.
            port = c['ports']['api' if role == 'backend' else role]
            require(not port_open(port), 'PROCESS_IDENTITY_MISMATCH_' + role.upper())
            return None
        return info

    def td_info(self):
        t = self.config['tdengine']
        info = json.loads(run(['docker', 'inspect', t['containerId']]))[0]
        labels = info['Config'].get('Labels', {})
        ports = info['HostConfig']['PortBindings'].get('6041/tcp', [])
        mounts = [m for m in info['Mounts'] if m['Destination'] == '/var/lib/taos']
        require(info['Id'] == t['containerId'] and info['Name'] == '/' + t['containerName']
                and labels.get('bec.test.owner') == t['owner'] and labels.get('bec.test.dataset') == self.config['dataset']
                and labels.get('bec.security.tdengine-exclusive') == 'true'
                and ports == [{'HostIp': '127.0.0.1', 'HostPort': str(self.config['ports']['tdengine'])}]
                and len(mounts) == 1 and mounts[0].get('Type') == 'volume' and mounts[0].get('Name') == t['volume'],
                'TDENGINE_IDENTITY_MISMATCH')
        return info

    def mysql(self, sql):
        require(self.role_info('mysql') is not None, 'MYSQL_NOT_RUNNING')
        c = self.config
        return run([c['executables']['mysqlClient'], '-h127.0.0.1', '-P' + str(c['ports']['mysql']),
                    '-uroot', '-N', '-B', '--default-character-set=utf8mb4', '-e', sql],
                   env={**os.environ, 'MYSQL_PWD': c['mysqlPassword']})

    def redis(self, *parts):
        require(self.role_info('redis') is not None, 'REDIS_NOT_RUNNING')
        with socket.create_connection(('127.0.0.1', self.config['ports']['redis']), timeout=5) as connection:
            stream = connection.makefile('rb')
            def send(values):
                values = [str(v).encode() for v in values]
                connection.sendall(b'*' + str(len(values)).encode() + b'\r\n' + b''.join(b'$' + str(len(v)).encode() + b'\r\n' + v + b'\r\n' for v in values))
                return stream.readline()
            require(send(['AUTH', self.config['redisPassword']]) == b'+OK\r\n', 'REDIS_AUTHENTICATION_FAILED')
            try:
                result = send(parts)
            except ConnectionResetError:
                result = b''
            require(not result.startswith(b'-'), 'REDIS_COMMAND_FAILED')
            return result

    def td_sql(self, sql):
        t = self.config['tdengine']
        auth = base64.b64encode((t['username'] + ':' + t['password']).encode()).decode()
        request = urllib.request.Request(f"http://127.0.0.1:{self.config['ports']['tdengine']}/rest/sql",
                                         data=sql.encode(), headers={'Authorization': 'Basic ' + auth})
        with urllib.request.urlopen(request, timeout=20) as response:
            result = json.load(response)
        require(result.get('code') == 0, 'TDENGINE_QUERY_FAILED')
        return result

    def launch(self, role, args):
        port = self.config['ports']['api' if role == 'backend' else role]
        if self.role_info(role) is not None:
            require(port_open(port), 'OWNED_PROCESS_NOT_READY_' + role.upper())
            return
        require(not port_open(port), 'PORT_OCCUPIED_' + str(port))
        # Inherit OS runtime paths only; shell Spring/MQTT/vendor variables must not redirect this instance.
        allowed = ('SystemRoot', 'WINDIR', 'PATH', 'TEMP', 'TMP', 'COMSPEC', 'PATHEXT', 'USERPROFILE',
                   'LOCALAPPDATA', 'APPDATA', 'SystemDrive', 'USERNAME', 'COMPUTERNAME', 'NUMBER_OF_PROCESSORS')
        env = {k: v for k, v in os.environ.items() if k.casefold() in {a.casefold() for a in allowed}}
        with (self.root / f'logs/{role}.log').open('ab') as log:
            child = subprocess.Popen(args, cwd=self.root, env=env, stdout=log, stderr=subprocess.STDOUT,
                                     creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        self.state[role] = child.pid
        self.save_state()
        deadline = time.monotonic() + (150 if role == 'backend' else 90)
        while time.monotonic() < deadline:
            require(child.poll() is None, 'START_FAILED_' + role.upper())
            if port_open(port):
                return
            time.sleep(0.5)
        raise RuntimeError('START_TIMEOUT_' + role.upper())

    def start(self):
        t = self.td_info()
        if not t['State']['Running']:
            run(['docker', 'start', t['Id']])
        mysql = Path(self.config['executables']['mysql'])
        self.launch('mysql', [str(mysql), '--no-defaults', '--basedir=' + str(mysql.parent.parent),
                    '--datadir=' + str(self.root / 'data/mysql'), '--bind-address=127.0.0.1',
                    '--port=' + str(self.config['ports']['mysql']), '--mysqlx=0', '--event-scheduler=OFF',
                    '--max-connections=80', '--innodb-buffer-pool-size=128M', '--secure-file-priv=' + str(self.root / 'data/secure')])
        require(Path(self.mysql('SELECT @@datadir')).resolve() == (self.root / 'data/mysql').resolve(), 'MYSQL_DATADIR_MISMATCH')
        self.launch('redis', [self.config['executables']['redis'], str(self.root / 'config/redis.conf')])
        require(self.redis('PING') == b'+PONG\r\n', 'REDIS_NOT_READY')
        deadline = time.monotonic() + 90
        while True:
            try:
                self.td_sql('SELECT SERVER_VERSION()')
                break
            except (OSError, urllib.error.URLError):
                require(time.monotonic() < deadline, 'TDENGINE_START_TIMEOUT'); time.sleep(1)
        args = [self.config['executables']['java'], '-Dfile.encoding=UTF-8', '-jar', str(self.root / 'backend/application.jar'),
                '--spring.profiles.active=cxg-monthly-acceptance', '--spring.config.additional-location=' +
                (self.root / 'config/test-base.yml').as_uri() + ',' + (self.root / 'config/backend.yml').as_uri()]
        args += ['--' + key + '=false' for key in DISABLED]
        self.launch('backend', args)
        deadline = time.monotonic() + 90
        while True:
            try:
                self.request('/auth/me')
                break
            except urllib.error.HTTPError as error:
                if error.code == 401:
                    break
                raise
            except urllib.error.URLError:
                require(time.monotonic() < deadline, 'BACKEND_API_START_TIMEOUT'); time.sleep(1)
        self.status(require_ready=True)

    def stop(self):
        # Validate every identity before stopping the first component, including when one is already down.
        self.td_info()
        for role in ('backend', 'mysql', 'redis'):
            self.role_info(role)
        if self.role_info('backend'):
            run(['powershell', '-NoProfile', '-Command', f"Stop-Process -Id {int(self.state['backend'])} -ErrorAction Stop"])
            self.wait_stopped('backend')
        if self.role_info('redis'):
            self.redis('SHUTDOWN', 'SAVE'); self.wait_stopped('redis')
        if self.role_info('mysql'):
            require(Path(self.mysql('SELECT @@datadir')).resolve() == (self.root / 'data/mysql').resolve(), 'MYSQL_DATADIR_MISMATCH')
            self.mysql('SHUTDOWN'); self.wait_stopped('mysql')
        if self.td_info()['State']['Running']:
            run(['docker', 'stop', '--time', '30', self.config['tdengine']['containerId']], timeout=45)

    def wait_stopped(self, role):
        deadline = time.monotonic() + 45
        while self.role_info(role):
            require(time.monotonic() < deadline, 'STOP_TIMEOUT_' + role.upper()); time.sleep(0.5)
        self.state.pop(role, None); self.save_state()

    def status(self, require_ready=False):
        services = {role: self.role_info(role) is not None and port_open(self.config['ports']['api' if role == 'backend' else role])
                    for role in ('backend', 'mysql', 'redis')}
        services['tdengine'] = self.td_info()['State']['Running'] and port_open(self.config['ports']['tdengine'])
        if require_ready:
            require(all(services.values()), 'ENVIRONMENT_NOT_READY')
        return {'scope': 'LOCAL_LOOPBACK_TEST_ONLY', 'dataset': self.config['dataset'], 'apiBase': self.api,
                'services': services, 'buildingId': self.config['buildingId'], 'defaultMonth': '2026-09',
                'accounts': {k: v['username'] for k, v in self.config['accounts'].items()}, 'root': str(self.root)}

    def check(self):
        self.status(require_ready=True)
        require(Path(self.mysql('SELECT @@datadir')).resolve() == (self.root / 'data/mysql').resolve(), 'MYSQL_DATADIR_MISMATCH')
        require(self.redis('PING') == b'+PONG\r\n', 'REDIS_NOT_READY')
        expected = {}
        building = self.config['buildingId']
        for table in ('biz_energy_period_result_current', 'biz_energy_period_result_snapshot'):
            rows = self.mysql('SELECT result_key,native_quantity,coverage_ratio,evidence_hash FROM iot_platform.' + table + f" WHERE building_id='{building}'").splitlines()
            require(len(rows) == 2977, 'MONTHLY_SNAPSHOT_COUNT_MISMATCH')
            for row in rows:
                key, quantity, coverage, digest = row.split('\t')
                require(key not in expected, 'DUPLICATE_RESULT_KEY')
                expected[key] = (Decimal(quantity), Decimal(coverage), digest)
        actual = self.td_sql('SELECT result_key,native_quantity_decimal,coverage_ratio_decimal,evidence_hash FROM iot_telemetry.st_energy_period_result' + f" WHERE building_id='{building}'")['data']
        require(len(actual) == len(expected), 'DUAL_STORE_ROW_COUNT_MISMATCH')
        for key, quantity, coverage, digest in actual:
            require(key in expected and expected.pop(key) == (Decimal(quantity), Decimal(coverage), digest), 'DUAL_STORE_VALUE_OR_EVIDENCE_MISMATCH')
        require(not expected, 'DUAL_STORE_MISSING_RESULTS')
        values = self.check_months()
        return {**self.status(), 'checksPassed': True, 'mysqlCurrentRows': 2977, 'mysqlSnapshots': 2977,
                'tdengineCopies': 5954, 'monthlyComparisons': values, 'liveIngressEnabled': False}

    def request(self, path, *, body=None, token=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        request = urllib.request.Request(self.api + path, data=json.dumps(body).encode() if body is not None else None, headers=headers)
        with urllib.request.urlopen(request, timeout=60) as response:
            value = json.load(response, parse_float=Decimal)
        require(value.get('success') is not False and value.get('code') in (None, 0, 200), 'API_QUERY_REJECTED')
        return value.get('data', value)

    def check_months(self):
        admin = self.config['accounts']['admin']
        token = self.request('/auth/login', body=admin)['token']
        result = {}
        for month, expected in self.config['expectedMonths'].items():
            carbon = self.request('/v1/carbon-management/calculations/' + expected['carbonBatchId'], token=token)
            context = self.request('/v1/energy-reporting/buildings/' + self.config['buildingId'] + '/context?month=' + month + '-01', token=token)
            batch = next(b for b in context['batches'] if b['batchId'] == expected['statisticsBatchId'])
            require(carbon['status'] == 'COMPLETED_COMPLETE' and batch['status'] == 'SEALED', 'MONTHLY_RESULT_NOT_SEALED')
            start = datetime.datetime.fromisoformat(carbon['startInclusive'].replace('Z', '+00:00'))
            require(carbon['buildingId'] == self.config['buildingId'] and batch['buildingId'] == self.config['buildingId']
                    and start.astimezone(datetime.timezone(datetime.timedelta(hours=8))).strftime('%Y-%m') == month
                    and batch['month'] == month + '-01', 'MONTHLY_BUILDING_OR_PERIOD_MISMATCH')
            row = batch['snapshot']['rows'][0]
            intensity = next(v for v in carbon['summaries'] if v['metricCode'] == 'MONTH_AREA_INTENSITY')
            actual = {'kWh': Decimal(str(row['nativeQuantity'])), 'tce': Decimal(str(row['tce'])),
                      'kgCo2e': sum(Decimal(str(v['emissionKgCo2e'])) for v in carbon['items']),
                      'kgCo2ePerM2': Decimal(str(intensity['finalValue']))}
            require(all(v == Decimal(expected[k]) for k, v in actual.items())
                    and intensity['denominatorVersionId'] == expected['areaVersionId'], 'MONTHLY_VALUE_OR_AREA_VERSION_MISMATCH')
            result[month] = {k: str(v) for k, v in actual.items()}
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('start', 'stop', 'restart', 'status', 'check'))
    parser.add_argument('--root', type=Path, default=Path(os.environ.get('USERPROFILE', str(Path.home()))) / '.building-energy-carbon/cxg-monthly-test')
    args = parser.parse_args()
    require(os.name == 'nt', 'WINDOWS_RUNTIME_REQUIRED')
    environment = Environment(args.root)
    with operation_lock(environment.root):
        if args.action in ('stop', 'restart'):
            environment.stop()
        if args.action in ('start', 'restart'):
            environment.start()
        result = environment.check() if args.action == 'check' else environment.status()
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        code = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        print('CXG_MONTHLY_ENV_ERROR: ' + code, file=sys.stderr)
        sys.exit(1)
