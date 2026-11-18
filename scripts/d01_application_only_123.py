"""共享测试 18080 的同版本应用换包入口，沿用现有服务、库和配置。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.error
import urllib.request
from uuid import uuid4
import zipfile


BASE = Path('/home/user1/deployments/building-energy-carbon-test')
BACKEND = 'building-energy-carbon-test-platform.service'
WEB = 'building-energy-carbon-test-nginx.service'
JAR = 'building-energy-carbon-platform.jar'
STAGED = 'application-only-stage.json'
MIGRATIONS = 'BOOT-INF/classes/db/migration/mysql/'


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as file:
        for block in iter(lambda: file.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def command(*args):
    # 只把返回码和命令名带入错误信息，配置或进程输出可能包含凭据。
    result = subprocess.run(args, capture_output=True, text=True, timeout=60)
    require(result.returncode == 0, f'APP_ONLY_COMMAND_FAILED: {args[0]}')
    return result.stdout.strip()


def service_property(name, service=BACKEND):
    return command('systemctl', 'show', service, '-p', name, '--value')


def current_release():
    link = BASE / 'current'
    current = link.resolve(strict=True)
    require(link.is_symlink() and current.parent == (BASE / 'releases').resolve(), 'APP_ONLY_CURRENT_RELEASE_REQUIRED')
    return current


def release_path(name):
    require(re.fullmatch(r'v[1-9][0-9]{2,8}-app-[a-z0-9]+(?:-[a-z0-9]+)*', name), 'APP_ONLY_RELEASE_NAME_REQUIRED')
    return BASE / 'releases' / name


def manifest(directory):
    return dict(line.split('=', 1) for line in (directory / 'MANIFEST.txt').read_text().splitlines() if '=' in line)


def runtime_resources(path, version=None):
    with zipfile.ZipFile(path) as jar:
        names = [name for name in jar.namelist() if not name.endswith('/') and (
            name.startswith(MIGRATIONS) or name.startswith('BOOT-INF/classes/application')
            or name.startswith('BOOT-INF/classes/db/room-sensor/'))]
        require(len(names) == len(set(names)), 'APP_ONLY_DUPLICATE_RUNTIME_RESOURCE')
        versions = [int(match[1]) for name in names
                    if (match := re.fullmatch(MIGRATIONS + r'V(\d+)__[^/]+\.sql', name))]
        require(versions and len(versions) == len(set(versions)) and max(versions) >= 123
                and (version is None or str(max(versions)) == version), 'APP_ONLY_PACKAGE_SCHEMA_REQUIRED')
        return {name: hashlib.sha256(jar.read(name)).hexdigest() for name in names}


def verify_package(previous, target):
    old, new = manifest(previous), manifest(target)
    version = new.get('expectedMigration', '')
    require(re.fullmatch(r'[1-9][0-9]{2,8}', version) and int(version) >= 123
            and old.get('expectedMigration') == new.get('sourceMigration') == version
            and new.get('releaseMode') == 'APPLICATION_ONLY'
            and new.get('artifactMode') == f'SHARED_APP_{version}'
            and new.get('expectedDatabase') == 'iot_platform', 'APP_ONLY_MANIFEST_REQUIRED')
    require(re.fullmatch(r'[a-f0-9]{40}', new.get('commit', '')), 'APP_ONLY_COMMIT_REQUIRED')
    building = old.get('screenBuildingCode', '')
    require(re.fullmatch(r'[A-Za-z0-9_-]+', building) and new.get('screenBuildingCode') == building,
            'APP_ONLY_SCREEN_BINDING_CHANGED')
    final_b, = (target / 'web/assets').glob('FinalBPage-*.js')
    compiled = re.search(r'__name:"FinalBPage",setup\(\w+\)\{const \w+="([^"]*)"', final_b.read_text(encoding='utf-8'))
    require(compiled and compiled[1] == building, 'APP_ONLY_SCREEN_BINDING_CHANGED')
    require(sha(target / JAR) == new.get('backendJarSha256')
            and sha(target / 'web/index.html') == new.get('webIndexSha256'), 'APP_ONLY_ARTIFACT_HASH_MISMATCH')
    require(runtime_resources(previous / JAR, version) == runtime_resources(target / JAR, version),
            'APP_ONLY_RUNTIME_RESOURCES_CHANGED')
    return new


def web_fingerprint(directory):
    paths = sorted(directory.rglob('*'))
    require(not directory.is_symlink() and not any(path.is_symlink() for path in paths), 'APP_ONLY_WEB_SYMLINK_FORBIDDEN')
    items = [(p.relative_to(directory).as_posix(), sha(p)) for p in paths if p.is_file()]
    return hashlib.sha256(json.dumps(items).encode()).hexdigest()


def stage(name, archive, expected_sha):
    from d01_release import stage_package

    require(os.geteuid() != 0, 'APP_ONLY_STAGE_AS_RUNTIME_USER')
    target = release_path(name)
    require(archive.resolve(strict=True).parent == (BASE / 'incoming').resolve()
            and sha(archive) == expected_sha, 'APP_ONLY_ARCHIVE_MISMATCH')
    previous = current_release()
    stage_package(archive, target)
    new = verify_package(previous, target)
    require(name.startswith(f"v{new['expectedMigration']}-app-"), 'APP_ONLY_RELEASE_SCHEMA_MISMATCH')
    # 保留旧哈希资源，避免恢复访问时已打开的浏览器请求到缺失的模块。
    for source in (previous / 'web/assets').iterdir():
        destination = target / 'web/assets' / source.name
        if source.is_file() and not destination.exists():
            shutil.copyfile(source, destination)
    state = {'previous': str(previous), 'previousJar': sha(previous / JAR), 'release': name,
             'manifest': new, 'web': web_fingerprint(target / 'web')}
    state_path = target / STAGED
    with state_path.open('x', encoding='utf-8') as stream:
        json.dump(state, stream, sort_keys=True)
    print('APPLICATION_STAGE_READY', name, sha(state_path))


def runtime_boundary():
    require(service_property('User') == 'user1', 'APP_ONLY_RUNTIME_USER_REQUIRED')
    pid = service_property('MainPID')
    require(pid.isdecimal() and int(pid) > 0, 'APP_ONLY_RUNNING_BASELINE_REQUIRED')
    require(Path('/proc', pid, 'cwd').resolve() == current_release(), 'APP_ONLY_RUNNING_RELEASE_MISMATCH')
    expected = {'AUDIT_ENVIRONMENT_MODE': 'TEST', 'SERVER_PORT': '18081', 'MYSQL_FLYWAY_ENABLED': 'false',
                'DATABASE_CHARSET_FIX_ENABLED': 'false',
                'TDENGINE_INITIALIZATION_ENABLED': 'false', 'MYSQL_HOST': '127.0.0.1', 'MYSQL_PORT': '13316'}
    env = dict(s.split(b'=', 1) for s in Path('/proc', pid, 'environ').read_bytes().split(b'\0') if b'=' in s)
    require(all(env.get(key.encode()) == val.encode() for key, val in expected.items()), 'APP_ONLY_RUNTIME_DDL_OR_TARGET_MISMATCH')
    config = [service_property('FragmentPath'), *service_property('DropInPaths').split(),
              service_property('FragmentPath', WEB), *service_property('DropInPaths', WEB).split(),
              str(BASE / 'shared/nginx-user.conf'),
              *re.findall(r'(/\S+) \(ignore_errors=(?:yes|no)\)', service_property('EnvironmentFiles'))]
    return {p: sha(Path(p)) for p in config}


def schema_history(version):
    query = 'SET SESSION TRANSACTION READ ONLY; SELECT version,checksum,success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank;'
    history = command('/usr/bin/docker', 'exec', 'building-energy-carbon-test-mysql', 'sh', '-c',
                      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B iot_platform -e "$1"', '_', query)
    validate_history(history, version)
    return history


def validate_history(history, version):
    rows = [line.split('\t') for line in history.splitlines()]
    require(rows and all(len(row) == 3 and row[2] == '1' for row in rows)
            and rows[-1][0] == version, 'APP_ONLY_LIVE_SCHEMA_MISMATCH')


def switch_current(release):
    link = BASE / ('.application-next-' + uuid4().hex)
    link.symlink_to(release)
    try:
        os.replace(link, BASE / 'current')
    finally:
        link.unlink(missing_ok=True)


def wait_ready(web_hash, timeout=85):
    deadline = time.monotonic() + timeout
    while True:
        try:
            with urllib.request.urlopen('http://127.0.0.1:18080/', timeout=2) as page:
                require(hashlib.sha256(page.read()).hexdigest() == web_hash, 'APP_ONLY_WEB_RESPONSE_MISMATCH')
            try:
                with urllib.request.urlopen('http://127.0.0.1:18080/api/auth/me', timeout=2) as api:
                    require(api.status == 200, 'APP_ONLY_API_NOT_READY')
            except urllib.error.HTTPError as error:
                require(error.code == 401, 'APP_ONLY_API_NOT_READY')
            return
        except (OSError, RuntimeError):
            if time.monotonic() >= deadline:
                raise RuntimeError('APP_ONLY_STARTUP_NOT_READY') from None
            time.sleep(1)


def cutover(previous, target, new, config, history):
    try:
        command('/usr/bin/systemctl', 'stop', WEB, BACKEND)
        require(service_property('ActiveState') == 'inactive', 'APP_ONLY_BACKEND_MUST_STOP_BEFORE_SWITCH')
        switch_current(target)
        # 网页服务 Requires 后端。停止后端会连带停网页，启动时要显式带上两者。
        command('/usr/bin/systemctl', 'start', BACKEND, WEB)
        wait_ready(new['webIndexSha256'])
        require(runtime_boundary() == config and schema_history(new['expectedMigration']) == history, 'APP_ONLY_RUNTIME_OR_SCHEMA_CHANGED')
    except Exception:
        # 同结构且兼容原有写入协议的应用发布可以回退到已保留的原包。
        live = current_release()
        require(live in (previous, target), 'APP_ONLY_CURRENT_CHANGED_DURING_CUTOVER')
        if live == target:
            command('/usr/bin/systemctl', 'stop', WEB, BACKEND)
            require(service_property('ActiveState') == 'inactive', 'APP_ONLY_BACKEND_MUST_STOP_BEFORE_ROLLBACK')
            switch_current(previous)
        command('/usr/bin/systemctl', 'start', BACKEND, WEB)
        wait_ready(sha(previous / 'web/index.html'))
        print('APP_ONLY_APPLICATION_ROLLED_BACK', flush=True)
        raise


def apply(name, state_sha):
    require(os.geteuid() == 0, 'APP_ONLY_ADMINISTRATOR_REQUIRED')
    target = release_path(name)
    require(target.resolve().parent == (BASE / 'releases').resolve() and not target.is_symlink(), 'APP_ONLY_RELEASE_PATH_REQUIRED')
    state_path = target / STAGED
    require(not state_path.is_symlink() and sha(state_path) == state_sha, 'APP_ONLY_STAGE_HASH_MISMATCH')
    state = json.loads(state_path.read_text())
    previous = current_release()
    require(state['release'] == name and state['previous'] == str(previous)
            and state['previousJar'] == sha(previous / JAR), 'APP_ONLY_BASELINE_CHANGED')
    require(not (target / JAR).is_symlink() and not (target / 'MANIFEST.txt').is_symlink(), 'APP_ONLY_PACKAGE_SYMLINK_FORBIDDEN')
    require(web_fingerprint(target / 'web') == state['web'], 'APP_ONLY_STAGED_WEB_CHANGED')
    # 只检查候选资源，stage 另保留给已打开页面用的旧 FinalB 资源。
    new = manifest(target)
    require(new == state['manifest'] and sha(target / JAR) == new['backendJarSha256']
            and sha(target / 'web/index.html') == new['webIndexSha256'], 'APP_ONLY_STAGED_ARTIFACT_CHANGED')
    version = new['expectedMigration']
    require(name.startswith(f'v{version}-app-'), 'APP_ONLY_RELEASE_SCHEMA_MISMATCH')
    require(runtime_resources(previous / JAR, version) == runtime_resources(target / JAR, version),
            'APP_ONLY_RUNTIME_RESOURCES_CHANGED')
    route = (BASE / 'shared/nginx-user.conf').read_text()
    require(re.search(r'(?m)^\s*listen\s+18080;', route) and f'root {BASE}/current/web;' in route
            and 'proxy_pass http://127.0.0.1:18081;' in route, 'APP_ONLY_SHARED_ROUTE_REQUIRED')
    config = runtime_boundary()
    history = schema_history(version)
    cutover(previous, target, new, config, history)
    print('APPLICATION_UPDATED', name, new['commit'], flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('stage', 'apply'))
    parser.add_argument('release')
    parser.add_argument('sha256')
    parser.add_argument('--archive', type=Path)
    args = parser.parse_args()
    require(re.fullmatch(r'[a-f0-9]{64}', args.sha256), 'APP_ONLY_SHA256_REQUIRED')
    if args.action == 'stage':
        require(args.archive, 'APP_ONLY_ARCHIVE_REQUIRED')
        stage(args.release, args.archive, args.sha256)
    else:
        import fcntl
        with os.fdopen(os.open(BASE / '.d01-release.lock', os.O_RDONLY | os.O_NOFOLLOW), 'r') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            apply(args.release, args.sha256)


if __name__ == '__main__':
    main()
