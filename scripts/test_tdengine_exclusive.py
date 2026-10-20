"""独占核验的拒绝分支；真实 Docker/TDengine 另由 S02 隔离集成脚本验证。"""
import copy
import unittest
from unittest.mock import patch

import verify_tdengine_exclusive as guard


class ExclusiveBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.details = {
            "Id": "owned-id", "State": {"Running": True},
            "Config": {"Image": "tdengine/tdengine:3.2.3.0", "Labels": {
                guard.PROJECT_LABEL: guard.PROJECT, guard.EXCLUSIVE_LABEL: "true"}},
            "NetworkSettings": {"Networks": {"owned-network": {}}, "Ports": {
                "6030/tcp": None, "6041/tcp": [{"HostIp": "127.0.0.1", "HostPort": "16041"}]}},
            "Mounts": [{"Destination": "/var/lib/taos", "Type": "volume", "Name": "owned-volume"}]}
        self.network = {"Driver": "bridge", "Internal": False,
                        "Options": {"com.docker.network.bridge.enable_icc": "false",
                                    "com.docker.network.bridge.host_binding_ipv4": "127.0.0.1"},
                        "Labels": {guard.PROJECT_LABEL: guard.PROJECT}, "Containers": {"owned-id": {}}}

    def test_valid_dedicated_container(self):
        self.assertEqual("http://127.0.0.1:16041/rest/sql",
                         guard.check_container(self.details, "owned-volume", "owned-network"))
        guard.check_network(self.network)
        guard.check_catalog(guard.SYSTEM_DATABASES | {"iot_telemetry"}, "iot_telemetry")

    def test_data_subdirectory_bind_volume_and_tmpfs_mounts_are_rejected(self):
        for kind in ["bind", "volume", "tmpfs"]:
            for destination in ["/var/lib/taos/vnode", "/var/lib/taos/dnode/", "/var/lib/taos/vnode/data",
                                "/var/lib/taos/./mnode", "/var/lib/taos/vnode/../dnode", "//var/lib/taos/vnode"]:
                with self.subTest(kind=kind, destination=destination):
                    details = copy.deepcopy(self.details)
                    details["Mounts"].append({"Type": kind, "Destination": destination})
                    with self.assertRaisesRegex(guard.BoundaryError, "overriding submount"):
                        guard.check_container(details, "owned-volume", "owned-network")

    def test_tmpfs_declared_only_in_host_config_is_also_rejected(self):
        for host_config in [{"Tmpfs": {"/var/lib/taos/vnode": "rw"}},
                            {"Tmpfs": {"/var/lib/taos": "rw"}},
                            {"Mounts": [{"Type": "tmpfs", "Target": "/var/lib/taos/vnode"}]},
                            {"Mounts": [{"Type": "tmpfs", "Target": "/var/lib/taos"}]}]:
            with self.subTest(host_config=host_config):
                details = copy.deepcopy(self.details)
                details["HostConfig"] = host_config
                with self.assertRaisesRegex(guard.BoundaryError, "overriding submount"):
                    guard.check_container(details, "owned-volume", "owned-network")

    def test_unrelated_logs_and_similar_prefix_mounts_remain_allowed(self):
        self.details["Mounts"].extend([{"Type": "volume", "Destination": "/var/log/taos"},
                                       {"Type": "bind", "Destination": "/var/lib/taos-backup"}])
        self.details["HostConfig"] = {"Tmpfs": {"/tmp": "rw"}, "Mounts": [
            {"Type": "volume", "Target": "/var/lib/taos", "Source": "owned-volume"}]}
        guard.check_container(self.details, "owned-volume", "owned-network")
        self.details["HostConfig"]["Mounts"] = None
        guard.check_container(self.details, "owned-volume", "owned-network")

    def test_submount_is_rejected_before_shared_volume_or_catalog_probes(self):
        self.details["Mounts"].append({"Type": "bind", "Destination": "/var/lib/taos/vnode"})
        with patch.object(guard, "inspect", return_value=self.details), \
                patch.object(guard, "docker") as docker, patch.object(guard, "read_catalog") as catalog:
            with self.assertRaisesRegex(guard.BoundaryError, "overriding submount"):
                guard.verify_instance("owned-id", "owned-volume", "owned-network", "iot_telemetry", "x" * 24)
        docker.assert_not_called()
        catalog.assert_not_called()

    def test_public_ipv4_ipv6_and_native_publications_are_rejected(self):
        for address in ["0.0.0.0", "::", "::1", "192.168.3.22"]:
            with self.subTest(address=address):
                self.details["NetworkSettings"]["Ports"]["6041/tcp"][0]["HostIp"] = address
                with self.assertRaises(guard.BoundaryError):
                    guard.check_container(self.details, "owned-volume", "owned-network")
        self.details["NetworkSettings"]["Ports"]["6041/tcp"][0]["HostIp"] = "127.0.0.1"
        self.details["NetworkSettings"]["Ports"]["6030/tcp"] = [{"HostIp": "127.0.0.1", "HostPort": "16030"}]
        with self.assertRaises(guard.BoundaryError):
            guard.check_container(self.details, "owned-volume", "owned-network")

    def test_unowned_container_extra_network_and_wrong_volume_are_rejected(self):
        for mutate in [lambda d: d["Config"]["Labels"].clear(),
                       lambda d: d["NetworkSettings"]["Networks"].update({"broker-network": {}}),
                       lambda d: d["Mounts"][0].update({"Name": "another-volume"}),
                       lambda d: d["Mounts"][0].update({"Type": "bind"}),
                       lambda d: d["State"].update({"Running": False})]:
            details = copy.deepcopy(self.details)
            mutate(details)
            with self.assertRaises(guard.BoundaryError):
                guard.check_container(details, "owned-volume", "owned-network")

    def test_shared_or_incomplete_catalog_is_rejected(self):
        for names in [guard.SYSTEM_DATABASES, guard.SYSTEM_DATABASES | {"iot_telemetry", "other_project"},
                      {"iot_telemetry"}, set()]:
            with self.subTest(names=names), self.assertRaises(guard.BoundaryError):
                guard.check_catalog(names, "iot_telemetry")

    def test_unsafe_data_network_is_rejected(self):
        for mutate in [lambda n: n["Options"].clear(), lambda n: n["Labels"].clear(),
                       lambda n: n.update({"Driver": "host"}), lambda n: n.update({"Internal": True})]:
            network = copy.deepcopy(self.network)
            mutate(network)
            with self.assertRaises(guard.BoundaryError):
                guard.check_network(network)

    def test_another_container_even_stopped_cannot_share_the_volume(self):
        with patch.object(guard, "inspect", return_value=self.details), \
                patch.object(guard, "docker", return_value="owned-id\nstopped-other-id"), \
                patch.object(guard, "read_catalog") as catalog, self.assertRaises(guard.BoundaryError):
            guard.verify_instance("owned-id", "owned-volume", "owned-network", "iot_telemetry", "x" * 24)
        catalog.assert_not_called()

    def test_unknown_network_member_is_rejected(self):
        self.network["Containers"]["other-id"] = {}
        other = copy.deepcopy(self.details)
        other["Config"]["Labels"] = {}
        def inspected(kind, name):
            return self.network if kind == "network" else other if name == "other-id" else self.details
        with patch.object(guard, "inspect", side_effect=inspected), \
                patch.object(guard, "docker", return_value="owned-id"), self.assertRaises(guard.BoundaryError):
            guard.verify_instance("owned-id", "owned-volume", "owned-network", "iot_telemetry", "x" * 24)

    def test_catalog_failure_is_not_treated_as_exclusive(self):
        with patch.object(guard, "inspect", side_effect=lambda kind, name: self.network if kind == "network" else self.details), \
                patch.object(guard, "docker", return_value="owned-id"), \
                patch.object(guard, "read_catalog", side_effect=guard.BoundaryError("unavailable")), \
                self.assertRaises(guard.BoundaryError):
            guard.verify_instance("owned-id", "owned-volume", "owned-network", "iot_telemetry", "x" * 24)


if __name__ == "__main__":
    unittest.main()
