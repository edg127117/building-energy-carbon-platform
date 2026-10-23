#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# This server's test release is served by 18080 -> 18081. Port 80 belongs to a separate site.
base=/home/user1/deployments/building-energy-carbon-test
service=building-energy-carbon-test-platform.service
nginx_config="$base/shared/nginx-user.conf"
port80_link=/var/www/iot-platform/current
control_config_tool="$(dirname "$(readlink -f "$0")")/daikin_test_deployment_config.py"

assert_test_route() {
  grep -Eq '^[[:space:]]*listen[[:space:]]+18080;' "$nginx_config"
  grep -Fq "root $base/current/web;" "$nginx_config"
  grep -Fq 'proxy_pass http://127.0.0.1:18081;' "$nginx_config"
  [[ "$(readlink -f "$base/current")" == "$base/releases/"* ]]
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:18080/api/auth/me || true)
  [[ "$code" == 200 || "$code" == 401 ]]
}

if [[ "${1:-}" == --check-route ]]; then
  assert_test_route
  echo BUILDING_ENERGY_CARBON_TEST_ROUTE_OK
  exit 0
fi

if (( $# != 3 )); then
  echo 'Usage: sudo bash Deploy-BuildingEnergyCarbonTest.sh RELEASE_NAME ARCHIVE_PATH ARCHIVE_SHA256' >&2
  exit 2
fi
name=$1
archive=$2
expected_archive=$3
[[ "$name" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]] || { echo INVALID_RELEASE_NAME >&2; exit 2; }
[[ "$archive" == "$base/incoming/"* && "$archive" != *'/../'* ]] || { echo INVALID_ARCHIVE_PATH >&2; exit 2; }
[[ "$(readlink -f "$archive")" == "$base/incoming/"* ]] || { echo ARCHIVE_OUTSIDE_INCOMING >&2; exit 2; }
[[ "$expected_archive" =~ ^[0-9a-f]{64}$ ]] || { echo INVALID_ARCHIVE_SHA256 >&2; exit 2; }
assert_test_route
if (( EUID != 0 )); then echo RUN_AS_ROOT_REQUIRED >&2; exit 1; fi
systemctl is-active --quiet "$service"

previous=$(readlink -f "$base/current")
previous_port80=$(readlink "$port80_link" 2>/dev/null || true)
release="$base/releases/$name"
[[ ! -e "$release" ]] || { echo RELEASE_EXISTS >&2; exit 1; }
[[ -f "$archive" ]] || { echo ARCHIVE_MISSING >&2; exit 1; }
[[ "$(sha256sum "$archive" | cut -d' ' -f1)" == "$expected_archive" ]] || { echo ARCHIVE_HASH_MISMATCH >&2; exit 1; }

switched=false
control_backup=
control_prepared=false
fail_stop() {
  code=$?
  trap - ERR
  recovery_failed=false
  if [[ "$switched" == true ]]; then
    # The candidate may already have written D01 immutable evidence or migrations.
    # Never revive an older last-write-wins package automatically; retain the candidate for manual recovery.
    if ! systemctl stop "$service"; then
      echo "TEST_DEPLOY_FAIL_STOP_FAILED service=$service release=$release" >&2
      recovery_failed=true
    fi
  fi
  if [[ "$control_prepared" == true ]]; then
    if ! python3 "$control_config_tool" restore "$control_backup"; then
      echo "TEST_DEPLOY_CONTROL_RESTORE_FAILED backup=$control_backup" >&2
      recovery_failed=true
    fi
  fi
  if [[ "$switched" == true ]]; then
    echo "TEST_DEPLOY_FAIL_STOP_D01_GUARD release=$release previous=$previous" >&2
  fi
  if [[ "$recovery_failed" == true ]]; then
    echo "TEST_DEPLOY_RECOVERY_FAILED original_code=$code" >&2
    exit 1
  fi
  echo "TEST_DEPLOY_FAILED code=$code" >&2
  exit "$code"
}
trap fail_stop ERR

stage=$(mktemp -d "$base/releases/.stage-$name-XXXXXX")
python3 - "$archive" "$stage" <<'PY'
import pathlib
import sys
import tarfile

with tarfile.open(sys.argv[1], "r:gz") as package:
    members = package.getmembers()
    for member in members:
        parts = pathlib.PurePosixPath(member.name).parts
        if member.name.startswith("/") or ".." in parts or not (member.isfile() or member.isdir()):
            raise SystemExit("INVALID_ARCHIVE_ENTRY")
    package.extractall(sys.argv[2], members=members)
PY
manifest=$(tr -d '\r' < "$stage/MANIFEST.txt")
commit=$(sed -n 's/^commit=//p' <<< "$manifest")
jar_hash=$(sed -n 's/^backendJarSha256=//p' <<< "$manifest")
web_hash=$(sed -n 's/^webIndexSha256=//p' <<< "$manifest")
[[ "$commit" =~ ^[0-9a-f]{40}$ && "$jar_hash" =~ ^[0-9a-f]{64}$ && "$web_hash" =~ ^[0-9a-f]{64}$ ]]
[[ "$(sha256sum "$stage/building-energy-carbon-platform.jar" | cut -d' ' -f1)" == "$jar_hash" ]]
[[ "$(sha256sum "$stage/web/index.html" | cut -d' ' -f1)" == "$web_hash" ]]
# D01 必须经过停写、双库备份和独立迁移；旧的单步发布入口不能承载这次升级。
python3 - "$stage/building-energy-carbon-platform.jar" "$stage/MANIFEST.txt" <<'PY'
import pathlib
import sys
import zipfile

manifest = dict(line.split('=', 1) for line in pathlib.Path(sys.argv[2]).read_text().splitlines() if '=' in line)
requires_d01 = manifest.get('expectedMigration', '').isdigit() and int(manifest['expectedMigration']) >= 84
if zipfile.is_zipfile(sys.argv[1]):
    with zipfile.ZipFile(sys.argv[1]) as jar:
        requires_d01 = requires_d01 or any('/V84__mysql_telemetry_immutable_conflicts.sql' in name for name in jar.namelist())
if requires_d01:
    raise SystemExit('D01_STAGED_RELEASE_REQUIRED: use d01_release.py')
PY
# mktemp creates a root-only directory; normalize artifact access before switching the running service.
# Packages contain only public application artifacts, never server credentials or shared configuration.
find "$stage" -type d -exec chmod 0755 {} +
find "$stage" -type f -exec chmod 0644 {} +
service_user=$(systemctl show "$service" --property=User --value)
[[ -n "$service_user" && "$service_user" != root ]] || { echo INVALID_SERVICE_USER >&2; exit 1; }
runuser -u "$service_user" -- bash -c 'cd "$1" && test -r building-energy-carbon-platform.jar && test -r web/index.html' _ "$stage"
[[ -f "$control_config_tool" ]] || { echo TEST_CONFIG_TOOL_MISSING >&2; exit 1; }
control_backup_dir=$(mktemp -d "$base/backups/control-$name-XXXXXX")
control_backup="$control_backup_dir/control-before.env"
python3 "$control_config_tool" prepare "$control_backup"
control_prepared=true
mv "$stage" "$release"
ln -s "$release" "$base/.next-$name-$$"
mv -Tf "$base/.next-$name-$$" "$base/current"
switched=true
systemctl restart "$service"

healthy=false
for _ in $(seq 1 90); do
  status=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://127.0.0.1:18080/api/auth/me || true)
  if [[ "$status" == 200 || "$status" == 401 ]]; then healthy=true; break; fi
  sleep 2
done
[[ "$healthy" == true ]]
[[ "$(curl -fsS --max-time 5 http://127.0.0.1:18080/ | sha256sum | cut -d' ' -f1)" == "$web_hash" ]]
[[ "$(readlink "$port80_link" 2>/dev/null || true)" == "$previous_port80" ]]
assert_test_route
python3 "$control_config_tool" verify

trap - ERR
echo "BUILDING_ENERGY_CARBON_TEST_DEPLOY_OK commit=$commit"
echo "TEST_URL=http://<server>:18080/"
