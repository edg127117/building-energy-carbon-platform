"""Exercise credential probes on newly owned local engines, never a deployment."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
from unittest.mock import patch
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("d01_probe", ROOT / "scripts/Verify-D01Isolated.py")
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
sys.path.insert(0, str(ROOT / "scripts"))
import d01_credentials as credentials
import d01_backup


def sql(container, statement):
    probe.owned("container", container)
    result = subprocess.run([probe.DOCKER, "--host", "npipe:////./pipe/dockerDesktopLinuxEngine",
        "exec", "-i", container, "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B'],
        input=statement.encode(), env=probe.ENV, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError("isolated SQL fixture failed")
    return result.stdout.decode().strip()


def rejected(action, code):
    try:
        action()
    except RuntimeError as error:
        assert code in str(error), "unexpected probe rejection"
    else:
        raise AssertionError("invalid credential or privilege was accepted")


def main():
    assert len(sys.argv) == 1, "does not accept an existing endpoint"
    jar = (ROOT / "target/iot-platform-demo-1.0-SNAPSHOT.jar").read_bytes()
    try:
        network = "d01-credential-" + probe.NONCE
        probe.docker("network", "create", "--label", probe.LABEL, network)
        probe.RESOURCES["networks"].append(network)
        volumes = []
        for suffix in ("mysql", "td"):
            volume = network + "-" + suffix
            probe.docker("volume", "create", "--label", probe.LABEL, volume)
            probe.RESOURCES["volumes"].append(volume)
            volumes.append(volume)
        mysql, mysql_port = probe.container("mysql:8.0", 3306, network, volumes[0],
                                            ("-e", "MYSQL_ROOT_PASSWORD", "-e", "MYSQL_DATABASE=iot_platform"))
        td, td_port = probe.container("tdengine/tdengine:3.2.3.0", 6041, network, volumes[1])
        probe.ENV["D01_TD_PORT"] = str(td_port)
        probe.wait_for(lambda: sql(mysql, "SELECT 1") == "1", stage="credential mysql")
        probe.wait_for(lambda: any("ready" in str(row).lower() for row in probe.taos("SHOW DNODES")["data"]), stage="credential td")
        password = uuid.uuid4().hex
        sql(mysql, "CREATE DATABASE iot_platform_archive; CREATE TABLE iot_platform.evidence(v INT); "
            "INSERT INTO iot_platform.evidence VALUES(123); "
            "CREATE USER 'platform_migrator'@'%' IDENTIFIED BY '" + password + "'; "
            "GRANT SELECT,INSERT,UPDATE,DELETE,CREATE ON iot_platform.* TO 'platform_migrator'@'%'; "
            "GRANT SELECT,INSERT,UPDATE,DELETE,CREATE ON iot_platform_archive.* TO 'platform_migrator'@'%'; "
            "CREATE TABLE iot_platform_archive.evidence(v INT); INSERT INTO iot_platform_archive.evidence VALUES(456); "
            "CREATE PROCEDURE iot_platform.evidence_proc() SELECT 1; "
            "CREATE EVENT iot_platform.evidence_event ON SCHEDULE AT CURRENT_TIMESTAMP + INTERVAL 1 YEAR DO SELECT 1; "
            "CREATE TRIGGER iot_platform.evidence_trigger BEFORE INSERT ON iot_platform.evidence FOR EACH ROW SET NEW.v=NEW.v;")
        probe.taos("CREATE DATABASE iot_telemetry")
        td_password = "D01a!" + uuid.uuid4().hex[:14]
        probe.taos("ALTER USER root PASS '" + td_password + "'")
        # A fresh adapter process avoids treating a cached old REST session as
        # evidence that a changed password still authenticates a new connection.
        probe.owned("container", td)
        probe.docker("restart", td)
        td_port = int(probe.owned("container", td)["NetworkSettings"]["Ports"]["6041/tcp"][0]["HostPort"])
        probe.ENV["D01_TD_PORT"] = str(td_port)
        probe.ENV["D01_TD_PASSWORD"] = td_password
        probe.wait_for(lambda: probe.taos("SHOW DATABASES"), stage="credential td restart")
        original_command = credentials.command
        original_temp = tempfile.TemporaryDirectory
        original_request = credentials.urllib.request.Request

        def local_command(args, **kwargs):
            if args[0] == "/usr/bin/java":
                args = [probe.JAVA, *args[1:]]
                kwargs["env"] = {**probe.ENV, **kwargs["env"]}
            elif args[0] == "/usr/bin/docker":
                assert args[3] == mysql
                probe.owned("container", mysql)
                args = [probe.DOCKER, "--host", "npipe:////./pipe/dockerDesktopLinuxEngine", *args[1:]]
                kwargs["env"] = probe.ENV
            else:
                raise AssertionError("unexpected probe executable")
            return original_command(args, **kwargs)

        def local_request(url, *args, **kwargs):
            assert url == "http://127.0.0.1:16041/rest/sql"
            return original_request("http://127.0.0.1:" + str(td_port) + "/rest/sql", *args, **kwargs)

        with patch.object(credentials, "command", side_effect=local_command), \
                patch.dict(credentials.EXPECTED, {"MYSQL_CONTAINER_NAME": mysql}), \
                patch.object(credentials, "JDBC_URL", credentials.JDBC_URL.replace(":13316/", ":" + str(mysql_port) + "/")), \
                patch.object(credentials.tempfile, "TemporaryDirectory", side_effect=lambda **kw: original_temp(prefix=kw["prefix"])), \
                patch.object(credentials.urllib.request, "Request", side_effect=local_request):
            identity = credentials.migration_probe(jar, password)
            assert credentials.backup_mysql_probe(probe.ENV["MYSQL_ROOT_PASSWORD"], mysql) == identity
            rejected(lambda: credentials.migration_probe(jar, "invalid-fixture-password"), "LOGIN_OR_QUERY_FAILED")
            rejected(lambda: credentials.backup_mysql_probe("invalid-fixture-password", mysql), "LOGIN_OR_QUERY_FAILED")
            sql(mysql, "REVOKE CREATE ON iot_platform.* FROM 'platform_migrator'@'%'")
            rejected(lambda: credentials.migration_probe(jar, password), "PRIVILEGES_MISSING")
            credentials.backup_td_probe(td_password)
            rejected(lambda: credentials.backup_td_probe("taosdata"), "LOGIN_OR_QUERY_FAILED")
        with tempfile.TemporaryDirectory(prefix="d01-dump-fixture-") as folder:
            def isolated_runner(args, **kwargs):
                assert args[:4] == [probe.DOCKER, "exec", "-i", mysql]
                probe.owned("container", mysql)
                return subprocess.run([probe.DOCKER, "--host", "npipe:////./pipe/dockerDesktopLinuxEngine", *args[1:]], **kwargs)
            output = Path(folder) / "mysql.sql"
            config = {"mysql_user": "root", "mysql_password": probe.ENV["MYSQL_ROOT_PASSWORD"]}
            d01_backup._dump_mysql(output, config, probe.DOCKER, mysql, isolated_runner)
            content = output.read_text(encoding="utf-8")
            assert all(name in content for name in ("iot_platform_archive", "evidence_proc", "evidence_event", "evidence_trigger"))
            config = {"mysql_user": "platform_migrator", "mysql_password": password}
            rejected(lambda: d01_backup._dump_mysql(Path(folder) / "denied.sql", config,
                     probe.DOCKER, mysql, isolated_runner), "MYSQL_DUMP_FAILED")
        assert sql(mysql, "SELECT v FROM iot_platform.evidence") == "123"
        print("D01_CREDENTIAL_REAL_ENGINES_VERIFIED jdbc_same_target=yes actual_mysql_dump=yes "
              "td_nondefault_password=yes wrong_password_rejected=yes missing_ddl_rejected=yes evidence_preserved=yes", flush=True)
    finally:
        for identifier in reversed(probe.RESOURCES["containers"]):
            probe.owned("container", identifier)
            probe.docker("rm", "-f", identifier)
        for identifier in reversed(probe.RESOURCES["networks"]):
            probe.owned("network", identifier)
            probe.docker("network", "rm", identifier)
        for identifier in reversed(probe.RESOURCES["volumes"]):
            probe.owned("volume", identifier)
            probe.docker("volume", "rm", identifier)
        print("D01_CREDENTIAL_ISOLATED_RESOURCES_REMOVED", flush=True)


if __name__ == "__main__":
    main()
