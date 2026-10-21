import datetime as dt
import json
import tempfile
import unittest
from service import Receipts


class ProtocolTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.receipts = Receipts(self.temp.name + "/test.db")
        self.data = {"device_id": "000000000000002", "seq": 1, "need_time": 1,
                     "SN": "cloud-smoke-only", "ID255": {}}
        self.now = 1792202400

    def tearDown(self):
        self.receipts.db.close()
        self.temp.cleanup()

    def report(self, **kwargs):
        return self.receipts.handle(json.dumps(self.data).encode(), now=self.now, **kwargs)

    def test_first_time_and_ordinary_restart(self):
        topic, r = self.report()
        self.assertEqual(topic, "EMETER/000000000000002/REPLY")
        self.assertEqual((r['ack'], r['seq'], r['tz']), (1, 1, 480))
        bj = dt.datetime.fromtimestamp(self.now, dt.timezone(dt.timedelta(hours=8)))
        self.assertEqual((r['year'], r['month'], r['day'], r['hour']), (bj.year,bj.month,bj.day,bj.hour))
        self.data.update(seq=2, need_time=0)
        self.assertEqual(self.report()[1], {"v":1, "seq":2, "ack":1})

    def test_lost_time_and_lost_ack_retry(self):
        self.report(); self.report()
        self.assertEqual(self.receipts.db.execute('SELECT count(*) FROM receipts').fetchone()[0], 1)
        self.assertIn('ts', self.report()[1])
        self.data['seq'] = 2
        self.assertIn('ts', self.report()[1])

    def test_no_time_when_server_unsynchronized(self):
        self.assertNotIn('ts', self.report(clock_ok=False)[1])

    def test_conflict_and_retained(self):
        self.report(); self.data['SN'] = 'changed'
        with self.assertRaises(ValueError): self.report()
        with self.assertRaises(ValueError): self.report(retained=True)

    def test_bad_inputs(self):
        for field, bad in [('seq',True),('seq',0),('seq',2**32),('need_time',2),('device_id','+/x')]:
            old = self.data[field]; self.data[field] = bad
            with self.assertRaises(ValueError): self.report()
            self.data[field] = old
        with self.assertRaises(ValueError): self.receipts.handle(b'{"seq":1,"seq":2}')


if __name__ == '__main__': unittest.main()
