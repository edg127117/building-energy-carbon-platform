"""Reuse one known administrator credential store; probes never change source data."""
import base64
import io
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import time
import urllib.request
import zipfile

ADMIN = Path("/etc/building-energy-carbon-test/s02-admin.json")
SERVICE = "building-energy-carbon-test-platform.service"
ENV = {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C.UTF-8", "HOME": "/root"}
JDBC_URL = "jdbc:mysql://127.0.0.1:13316/iot_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000&socketTimeout=15000"
EXPECTED = {"MYSQL_HOST": "127.0.0.1", "MYSQL_PORT": "13316",
            "MYSQL_CONTAINER_NAME": "building-energy-carbon-test-mysql",
            "MYSQL_VOLUME_NAME": "building-energy-carbon-platform-test-mysql-data",
            "TDENGINE_HOST": "127.0.0.1", "TDENGINE_PORT": "16041",
            "TDENGINE_CONTAINER_NAME": "building-energy-carbon-test-tdengine",
            "TDENGINE_VOLUME_NAME": "building-energy-carbon-platform-test-tdengine-data",
            "TDENGINE_DATABASE": "iot_telemetry", "SERVER_PORT": "18081",
            "AUDIT_ENVIRONMENT_MODE": "TEST", "MYSQL_FLYWAY_ENABLED": "false",
            "TDENGINE_INITIALIZATION_ENABLED": "false"}

# This source is part of the administrator-hashed bundle. JDBC checks use the
# same host endpoint as Flyway; a successful container-local login is not proof
# that MySQL will select the same user@host for the later migration connection.
JAVA_PROBE = r'''import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
class D01CredentialProbe {
  public static void main(String[] args) {
    try (Connection c = DriverManager.getConnection(System.getenv("D01_URL"),
        System.getenv("D01_USER"), System.getenv("D01_PASSWORD")); Statement s = c.createStatement()) {
      c.setReadOnly(true);
      s.setQueryTimeout(15);
      try (ResultSet r = s.executeQuery("SELECT @@server_uuid,@@gtid_mode,@@read_only,@@super_read_only,CURRENT_USER()")) {
        if (!r.next()) throw new SQLException();
        System.out.println("META\t"+r.getString(1)+"\t"+r.getString(2)+"\t"+r.getString(3)+"\t"+r.getString(4)
            +"\t"+Base64.getEncoder().encodeToString(r.getString(5).getBytes(StandardCharsets.UTF_8)));
      }
      try (ResultSet r = s.executeQuery("SHOW GRANTS FOR CURRENT_USER")) {
        while (r.next()) System.out.println("GRANT64\t"+Base64.getEncoder().encodeToString(r.getString(1).getBytes(StandardCharsets.UTF_8)));
      }
    } catch (Exception e) { System.exit(2); }
  }
}
'''

MYSQL_PROBE = r'''set -eu
umask 077
config=$(mktemp)
trap 'rm -f "$config"' EXIT HUP INT TERM
cat > "$config"
mysql --defaults-extra-file="$config" --batch --skip-column-names --raw --connect-timeout=10 --execute="SELECT CONCAT('META',CHAR(9),@@server_uuid,CHAR(9),@@gtid_mode,CHAR(9),@@read_only,CHAR(9),@@super_read_only,CHAR(9),REPLACE(TO_BASE64(CURRENT_USER()),CHAR(10),'')); SHOW GRANTS FOR CURRENT_USER;"
'''


def require(ok, code):
    if not ok:
        raise RuntimeError(code)


def command(args, *, input_data=None, env=None, timeout=30, code="D01_CREDENTIAL_PROBE_COMMAND_FAILED"):
    try:
        result = subprocess.run(args, input=input_data, env=env or ENV, capture_output=True, timeout=timeout)
        require(result.returncode == 0 and len(result.stdout) <= 1024 * 1024, code)
        return result.stdout.decode("utf-8")
    except (OSError, subprocess.TimeoutExpired, UnicodeError):
        # Never propagate process output, request headers or credential values.
        raise RuntimeError(code) from None


def administrator_values():
    try:
        for part in (ADMIN, *ADMIN.parents):
            item = part.lstat()
            require(not stat.S_ISLNK(item.st_mode) and item.st_uid == 0 and item.st_mode & 0o022 == 0,
                    "D01_EXISTING_ADMIN_CONFIG_NOT_PROTECTED")
        with os.fdopen(os.open(ADMIN, os.O_RDONLY | os.O_NOFOLLOW), "rb") as stream:
            item = os.fstat(stream.fileno())
            require(stat.S_ISREG(item.st_mode) and item.st_uid == 0 and item.st_mode & 0o077 == 0
                    and item.st_size <= 65536, "D01_EXISTING_ADMIN_CONFIG_NOT_PRIVATE")
            value = json.loads(stream.read(65537))
        require(isinstance(value, dict), "D01_EXISTING_ADMIN_CONFIG_INVALID")
        return value
    except FileNotFoundError:
        raise RuntimeError("D01_EXISTING_ADMIN_CONFIG_MISSING") from None
    except (ValueError, UnicodeError):
        raise RuntimeError("D01_EXISTING_ADMIN_CONFIG_INVALID") from None


def runtime_target():
    pid = command(["/usr/bin/systemctl", "show", SERVICE, "-p", "MainPID", "--value"]).strip()
    require(pid.isdigit() and int(pid) > 1, "D01_RUNNING_TEST_SERVICE_REQUIRED")
    entries = (Path("/proc") / pid / "environ").read_bytes().split(b"\0")
    values = {}
    admin_keys = ("MYSQL_ROOT_PASSWORD", "MYSQL_MIGRATION_USER", "MYSQL_MIGRATION_PASSWORD", "TDENGINE_AUDIT_PASSWORD")
    for entry in entries:
        key, separator, value = entry.partition(b"=")
        require(key.decode("utf-8") not in admin_keys, "D01_ADMIN_CREDENTIAL_IN_RUNTIME")
        if separator and key.decode("utf-8") in (*EXPECTED, "MYSQL_USER", "TDENGINE_USER"):
            values[key.decode("utf-8")] = value.decode("utf-8")
    require(all(values.get(key) == value for key, value in EXPECTED.items()), "D01_CREDENTIAL_TARGET_MISMATCH")
    require(values.get("MYSQL_USER") not in (None, "", "root", "platform_migrator"),
            "D01_SEPARATE_MIGRATION_ACCOUNT_REQUIRED")
    require(values.get("TDENGINE_USER") not in (None, "", "root"), "D01_NONROOT_TDENGINE_RUNTIME_REQUIRED")
    return pid, values


def check_containers():
    identities = {}
    for engine, destination, port, host_port in (("MYSQL", "/var/lib/mysql", "3306/tcp", "13316"),
                                                ("TDENGINE", "/var/lib/taos", "6041/tcp", "16041")):
        name = EXPECTED[engine + "_CONTAINER_NAME"]
        data = json.loads(command(["/usr/bin/docker", "inspect", name]))
        require(len(data) == 1, "D01_CREDENTIAL_CONTAINER_IDENTITY_MISMATCH")
        item = data[0]
        mounts = [m for m in item.get("Mounts", []) if m.get("Destination") == destination]
        bindings = item.get("NetworkSettings", {}).get("Ports", {}).get(port)
        require(item.get("Name") == "/" + name and item.get("State", {}).get("Running") is True
                and len(mounts) == 1 and mounts[0].get("Type") == "volume"
                and mounts[0].get("Name") == EXPECTED[engine + "_VOLUME_NAME"]
                and bindings == [{"HostIp": "127.0.0.1", "HostPort": host_port}],
                "D01_CREDENTIAL_CONTAINER_IDENTITY_MISMATCH")
        require(re.fullmatch(r"[a-f0-9]{64}", item.get("Id", "")), "D01_CREDENTIAL_CONTAINER_IDENTITY_MISMATCH")
        identities[engine] = item["Id"]
    return identities


def mysql_rights(output, role, target_schema="84", release_mode="SCHEMA_UPGRADE"):
    require(target_schema in ("84", "85", "86", "87", "88", "93"), "D01_SCHEMA_TARGET_REQUIRED")
    require(release_mode == "SCHEMA_UPGRADE" or (release_mode == "APPLICATION_ONLY" and target_schema == "88"),
            "D01_CREDENTIAL_RELEASE_MODE_REQUIRED")
    lines = output.splitlines()
    require(lines and re.fullmatch(r"META\t[a-f0-9-]{36}\t[A-Z_]+\t[01]\t[01]\t[A-Za-z0-9+/=]+", lines[0]),
            "D01_MYSQL_PROBE_RESPONSE_INVALID")
    _, identity, gtid, readonly, super_readonly, principal = lines[0].split("\t")
    authenticated = base64.b64decode(principal, validate=True).decode().partition("@")
    require(authenticated[0] == ("platform_migrator" if role == "migration" else "root") and authenticated[2],
            "D01_MYSQL_AUTHENTICATED_ACCOUNT_MISMATCH")
    grants = [base64.b64decode(line[8:], validate=True).decode() if line.startswith("GRANT64\t") else line
              for line in lines[1:]]
    rights = {"*": set(), "iot_platform": set(), "iot_platform_archive": set()}
    for grant in grants:
        # Partial revokes and role-derived rights need explicit accounting; do
        # not treat a global grant as sufficient when exclusions are present.
        require(not grant.startswith("REVOKE "), "D01_MYSQL_PARTIAL_REVOKE_REQUIRES_REVIEW")
        match = re.match(r"GRANT (.+) ON (\*|`[^`]+`)\.\* TO ", grant)
        if match:
            scope = match[2].strip("`").replace("\\_", "_")
            if scope in rights:
                rights[scope].update(match[1].split(", "))

    def allows(scope, needed):
        available = rights["*"] | rights.get(scope, set())
        return "ALL PRIVILEGES" in available or needed <= available

    if role == "migration":
        require(readonly == super_readonly == "0", "D01_MYSQL_TARGET_READ_ONLY")
        # 只换应用仍复用受保护的独立账号，但核验只需双库 SELECT，不为换包新增 DDL 权限。
        needed = {"SELECT"} if release_mode == "APPLICATION_ONLY" else {"SELECT", "INSERT", "UPDATE", "DELETE", "CREATE"}
        require(all(allows(db, needed)
                    for db in ("iot_platform", "iot_platform_archive")),
                "D01_MYSQL_MIGRATION_PRIVILEGES_MISSING")
        require(release_mode == "APPLICATION_ONLY" or target_schema != "85" or allows("iot_platform", {"REFERENCES"}),
                "D01_MYSQL_V85_REFERENCES_PRIVILEGE_MISSING")
        require(release_mode == "APPLICATION_ONLY" or target_schema != "88" or allows("iot_platform", {"ALTER"}),
                "D01_MYSQL_V88_ALTER_PRIVILEGE_MISSING")
    else:
        require(all(allows(db, {"SELECT", "SHOW VIEW", "TRIGGER", "EVENT"})
                    for db in ("iot_platform", "iot_platform_archive"))
                and allows("*", {"PROCESS"}) and (allows("*", {"SELECT"}) or allows("*", {"SHOW_ROUTINE"})),
                "D01_MYSQL_BACKUP_PRIVILEGES_MISSING")
        require(gtid != "ON" or allows("*", {"RELOAD"}) or allows("*", {"FLUSH_TABLES"}),
                "D01_MYSQL_BACKUP_GTID_PRIVILEGES_MISSING")
    return identity


def migration_probe(jar, password, target_schema="84", release_mode="SCHEMA_UPGRADE"):
    with zipfile.ZipFile(io.BytesIO(jar)) as archive:
        drivers = [item for item in archive.infolist()
                   if re.fullmatch(r"BOOT-INF/lib/mysql-connector-j-[0-9.]+\.jar", item.filename)]
        require(len(drivers) == 1 and drivers[0].file_size < 16 * 1024**2, "D01_PINNED_MYSQL_DRIVER_REQUIRED")
        driver = archive.read(drivers[0])
    # /run is root-owned. No code or driver is loaded from the writable release
    # tree, and the temporary directory is removed on both success and failure.
    with tempfile.TemporaryDirectory(prefix="d01-credential-", dir="/run") as folder:
        path = Path(folder)
        (path / "driver.jar").write_bytes(driver)
        (path / "D01CredentialProbe.java").write_text(JAVA_PROBE)
        output = command(["/usr/bin/java", "--class-path", str(path / "driver.jar"),
                          str(path / "D01CredentialProbe.java")],
                         env={**ENV, "D01_URL": JDBC_URL, "D01_USER": "platform_migrator", "D01_PASSWORD": password},
                         timeout=60, code="D01_MYSQL_MIGRATION_LOGIN_OR_QUERY_FAILED")
    return mysql_rights(output, "migration", target_schema, release_mode)


def backup_mysql_probe(password, container_id):
    require(re.fullmatch(r"[a-f0-9]{64}", container_id), "D01_CREDENTIAL_CONTAINER_IDENTITY_MISMATCH")
    escaped = password.replace("\\", "\\\\").replace('"', '\\"')
    config = ('[client]\nuser="root"\npassword="' + escaped + '"\n').encode()
    output = command(["/usr/bin/docker", "exec", "-i", container_id,
                      "sh", "-ceu", MYSQL_PROBE], input_data=config,
                     code="D01_MYSQL_BACKUP_LOGIN_OR_QUERY_FAILED")
    return mysql_rights(output, "backup")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def td_query(password, sql):
    token = base64.b64encode(("root:" + password).encode()).decode()
    request = urllib.request.Request("http://127.0.0.1:16041/rest/sql", data=sql.encode(),
                                     headers={"Authorization": "Basic " + token, "Content-Type": "text/plain"})
    try:
        # Explicitly disable proxies and redirects: credentials must remain on
        # the pinned loopback test endpoint even under administrator shell env.
        with urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect()).open(request, timeout=15) as response:
            raw = response.read(1024 * 1024 + 1)
        require(len(raw) <= 1024 * 1024, "D01_TDENGINE_PROBE_RESPONSE_TOO_LARGE")
        value = json.loads(raw)
        require(value.get("code") == 0, "D01_TDENGINE_BACKUP_LOGIN_OR_QUERY_FAILED")
        fields = [item[0] for item in value["column_meta"]]
        return [dict(zip(fields, row)) for row in value["data"]]
    except Exception:
        raise RuntimeError("D01_TDENGINE_BACKUP_LOGIN_OR_QUERY_FAILED") from None


def backup_td_probe(password):
    users = td_query(password, "SHOW USERS")
    require(any(row.get("name") == "root" and row.get("super") == 1 and row.get("enable") == 1 for row in users),
            "D01_TDENGINE_BACKUP_PRIVILEGES_MISSING")
    databases = td_query(password, "SHOW DATABASES")
    require(any(row.get("name") == "iot_telemetry" for row in databases), "D01_TDENGINE_SOURCE_DATABASE_MISSING")


def discover(jar, target_schema="84", release_mode="SCHEMA_UPGRADE"):
    before = runtime_target()
    containers = check_containers()
    values = administrator_values()
    selected, identities, failures = {}, {}, []
    for role, key, probe in (("MYSQL_MIGRATION", "mysql_migration_password", lambda p: migration_probe(jar, p, target_schema, release_mode)),
                             ("MYSQL_BACKUP", "mysql_root_password", lambda p: backup_mysql_probe(p, containers["MYSQL"])),
                             ("TDENGINE_BACKUP", "td_root_password", backup_td_probe)):
        try:
            password = values.get(key)
            require(isinstance(password, str) and password, "D01_" + role + "_CREDENTIAL_MISSING")
            require(16 <= len(password) <= 512 and not any(c in password for c in "\0\r\n"),
                    "D01_" + role + "_CREDENTIAL_POLICY_FAILED")
            identities[role] = probe(password)
            selected[role] = password
        except RuntimeError as error:
            failures.append(str(error))
    for failure in failures:
        print(failure)
    require(not failures, "D01_EXISTING_CREDENTIALS_NOT_READY")
    require(identities["MYSQL_MIGRATION"] == identities["MYSQL_BACKUP"], "D01_MYSQL_SOURCE_IDENTITY_MISMATCH")
    require(check_containers() == containers, "D01_CREDENTIAL_CONTAINERS_CHANGED")
    require(runtime_target() == before, "D01_RUNNING_TEST_SERVICE_CHANGED")
    schema = {"url": JDBC_URL, "user": "platform_migrator", "password": selected["MYSQL_MIGRATION"]}
    backup = {"mysql": {"user": "root", "password": selected["MYSQL_BACKUP"]},
              "tdengine": {"user": "root", "password": selected["TDENGINE_BACKUP"]}}
    report = {"source": str(ADMIN), "checked_at": int(time.time()), "checks": list(selected),
              "mode": "read-only-login-and-direct-grants", "release_mode": release_mode,
              "backup_and_restore_verified": False}
    return schema, backup, report
