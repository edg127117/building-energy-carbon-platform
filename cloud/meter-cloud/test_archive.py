import json, sqlite3, tempfile, unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from service import RawUploads, Receipts, UPLINK, process_upload

class Client:
    def __init__(self, fixture):
        self.fixture = fixture
        self.published = []
        self.acked = []
    def durable_count(self):
        c = sqlite3.connect(self.fixture.raw_path)
        try: return c.execute("SELECT count(*) FROM uploads").fetchone()[0]
        finally: c.close()
    def publish(self, topic, payload, **kwargs):
        assert self.durable_count() > 0
        assert self.fixture.receipts.db.execute("SELECT count(*) FROM receipts").fetchone()[0] > 0
        self.published.append((topic, json.loads(payload), kwargs))
        return SimpleNamespace(rc=0)
    def ack(self, mid, qos):
        assert self.durable_count() > 0
        self.acked.append((mid, qos))
        return 0

class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.raw_path = str(Path(self.tmp.name) / "raw.db")
        self.archive = RawUploads(self.raw_path)
        self.receipts = Receipts(str(Path(self.tmp.name) / "receipts.db"))
        self.client = Client(self)
    def tearDown(self):
        self.archive.db.close()
        self.receipts.db.close()
        self.tmp.cleanup()
    def msg(self, value, qos=1, retain=False, raw=False, topic=UPLINK):
        return SimpleNamespace(payload=value if raw else json.dumps(value).encode(),
                               topic=topic, qos=qos, retain=retain, mid=17)
    def handle(self, m):
        process_upload(self.receipts, self.archive, self.client, m, clock_ok=False)
    def valid(self):
        return {"device_id":"000000000000002","seq":1,"need_time":0,
                "SN":"synthetic-meter","ID255":{}}
    def rows(self):
        return self.archive.db.execute("SELECT reported_sn,payload,qos,retained FROM uploads").fetchall()
    def test_both_legacy_shapes_saved_without_inventing_receipts_or_application_ack(self):
        for sn, param in [("synthetic-027",{"ID255":{"value":1}}),("synthetic-011",{"ID1":{"value":2}})]:
            m = self.msg({"SN":sn,"param":param})
            self.handle(m)
            self.assertEqual(self.rows()[-1][:2],(sn,m.payload))
        self.assertEqual(self.receipts.db.execute("SELECT count(*) FROM receipts").fetchone()[0],0)
        self.assertEqual(self.client.published,[])
        self.assertEqual(len(self.client.acked),2)
    def test_new_protocol_ack_contract_and_durable_order_preserved(self):
        self.handle(self.msg(self.valid()))
        topic,response,options = self.client.published[0]
        self.assertEqual(topic,"EMETER/000000000000002/REPLY")
        self.assertEqual(response,{"v":1,"seq":1,"ack":1})
        self.assertEqual(options,{"qos":1,"retain":False})
        self.assertEqual(self.client.acked,[(17,1)])
    def test_qos_zero_report_is_saved_without_transport_ack(self):
        self.handle(self.msg({"SN":"synthetic-027","param":{"ID255":{}}},qos=0))
        self.assertEqual(self.rows()[0][2],0)
        self.assertEqual(self.client.acked,[])
        self.assertEqual(self.client.published,[])
    def test_archive_disk_failure_never_acknowledges_or_processes_receipt(self):
        with patch.object(self.archive,"persist",side_effect=sqlite3.OperationalError("disk full")):
            with self.assertRaises(sqlite3.OperationalError): self.handle(self.msg(self.valid()))
        self.assertEqual(self.client.acked,[])
        self.assertEqual(self.client.published,[])
        self.assertEqual(self.receipts.db.execute("SELECT count(*) FROM receipts").fetchone()[0],0)
    def test_same_sequence_changed_payload_is_archived_without_overwriting_valid_receipt(self):
        value=self.valid(); self.handle(self.msg(value)); value["SN"]="changed"
        self.handle(self.msg(value))
        self.assertEqual(len(self.rows()),2)
        self.assertEqual(self.receipts.db.execute("SELECT count(*) FROM receipts").fetchone()[0],1)
        self.assertEqual(len(self.client.published),1)
    def test_duplicate_valid_delivery_keeps_raw_evidence_and_one_correlated_receipt(self):
        m=self.msg(self.valid()); self.handle(m); self.handle(m)
        self.assertEqual(len(self.rows()),2)
        self.assertEqual(self.archive.db.execute("SELECT count(distinct event_id) FROM uploads").fetchone()[0],2)
        self.assertEqual(self.receipts.db.execute("SELECT count(*) FROM receipts").fetchone()[0],1)
        self.assertEqual(len(self.client.published),2)
    def test_malformed_and_duplicate_key_payloads_are_preserved_without_application_ack(self):
        for b in [b"not-json",b'{"SN":"a","SN":"b"}',b"\xff",b"[]"]:
            self.handle(self.msg(b,raw=True))
            self.assertEqual(self.rows()[-1][1],b)
        self.assertEqual(self.client.published,[])
    def test_retained_report_is_archived_and_never_application_acknowledged(self):
        self.handle(self.msg(self.valid(),retain=True))
        self.assertEqual(self.rows()[0][3],1)
        self.assertEqual(self.client.published,[])
    def test_business_database_failure_preserves_raw_and_withholds_application_ack(self):
        self.receipts.db.close()
        self.handle(self.msg(self.valid()))
        self.assertEqual(len(self.rows()),1)
        self.assertEqual(self.client.published,[])
        self.assertEqual(self.client.acked,[(17,1)])
    def test_sqlite_full_durability_and_reopen(self):
        self.assertEqual(self.archive.db.execute("PRAGMA synchronous").fetchone()[0],2)
        self.assertEqual(self.archive.db.execute("PRAGMA journal_mode").fetchone()[0],"wal")
        m=self.msg({"SN":"synthetic","param":{}})
        self.handle(m); self.archive.db.close()
        self.archive=RawUploads(self.raw_path)
        self.assertEqual(self.rows()[0][1],m.payload)
    def test_unexpected_topic_not_acknowledged(self):
        with self.assertRaises(ValueError):
            self.handle(self.msg(self.valid(),topic="other/topic"))
        self.assertEqual(self.rows(),[])
        self.assertEqual(self.client.acked,[])
        self.assertEqual(self.client.published,[])
    def test_transport_ack_failure_retains_committed_raw_evidence(self):
        with patch.object(self.client,"ack",return_value=1):
            with self.assertRaises(RuntimeError): self.handle(self.msg({"SN":"synthetic","param":{}}))
        self.assertEqual(len(self.rows()),1)

    def test_archive_permissions_with_permissive_process_umask(self):
        import os, stat
        path = str(Path(self.tmp.name) / "permissions.db")
        previous = os.umask(0o022)
        try:
            other = RawUploads(path)
            self.assertEqual(stat.S_IMODE(Path(path).stat().st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(Path(path + "-wal").stat().st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(Path(path + "-shm").stat().st_mode), 0o600)
            other.db.close()
        finally:
            os.umask(previous)

if __name__=="__main__": unittest.main()
