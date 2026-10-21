"""MQTT application receipt ACK + first-use Beijing clock provisioning.

Every received upload is archived before protocol handling. Correlated ACK means
this service committed a valid new-protocol receipt, never that billing finished.
"""
import datetime as dt
import hashlib
import json
import logging
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import time
import uuid

UPLINK = "device/raw/energy/up"
DEVICE = re.compile(r"[0-9]{15}\Z")
BEIJING = dt.timezone(dt.timedelta(hours=8))


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate field")
        result[key] = value
    return result


class RawUploads:
    """Keep transport evidence without inventing an authoritative device identity."""

    def __init__(self, filename):
        # WAL sidecars inherit the database mode; restrict it before SQLite opens them.
        fd = os.open(filename, os.O_RDWR | os.O_CREAT, 0o600)
        try:
            os.fchmod(fd, 0o600)
        finally:
            os.close(fd)
        self.db = sqlite3.connect(filename)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.execute("""CREATE TABLE IF NOT EXISTS uploads (
            event_id TEXT PRIMARY KEY,
            received_utc_ms INTEGER NOT NULL,
            receiver TEXT NOT NULL,
            topic TEXT NOT NULL,
            qos INTEGER NOT NULL,
            retained INTEGER NOT NULL,
            reported_sn TEXT,
            digest TEXT NOT NULL,
            payload BLOB NOT NULL)""")
        self.db.execute("CREATE INDEX IF NOT EXISTS uploads_received ON uploads(received_utc_ms)")
        self.db.commit()

    def persist(self, message, receiver="meter-cloud", now_ms=None):
        if message.topic != UPLINK:
            raise ValueError("unexpected upload topic")
        payload = bytes(message.payload)
        reported_sn = None
        try:
            data = json.loads(payload, object_pairs_hook=strict_object)
            if isinstance(data, dict) and isinstance(data.get("SN"), str):
                reported_sn = data["SN"] if len(data["SN"]) <= 256 else None
        except (ValueError, TypeError, UnicodeError, RecursionError):
            pass
        event_id = uuid.uuid4().hex
        received = time.time_ns() // 1000000 if now_ms is None else now_ms
        # Each delivery remains auditable; SN is self-reported metadata, not an IMEI mapping.
        with self.db:
            self.db.execute("INSERT INTO uploads VALUES (?,?,?,?,?,?,?,?,?)", (
                event_id, received, receiver, message.topic, int(message.qos),
                int(bool(message.retain)), reported_sn,
                hashlib.sha256(payload).hexdigest(), sqlite3.Binary(payload)))
        return event_id


def process_upload(receipts, archive, client, message, clock_ok=True):
    # A storage failure must escape the callback before a transport ACK can be sent.
    event_id = archive.persist(message)
    try:
        topic, response = receipts.handle(bytes(message.payload), bool(message.retain),
                                          clock_ok=clock_ok)
    except (ValueError, TypeError, UnicodeError, RecursionError, sqlite3.Error) as error:
        # Legacy/invalid reports are preserved, but cannot receive a correlated application ACK.
        logging.info("Raw upload committed; no application ACK: reason=%s event=%s",
                     error, event_id)
    else:
        result = client.publish(topic, json.dumps(response, separators=(",", ":")),
                                qos=1, retain=False)
        if result.rc:
            logging.error("Reply queue failed: rc=%s", result.rc)
        else:
            logging.info("ACK device=%s seq=%s time=%s", topic.split("/")[1],
                         response["seq"], "ts" in response)
    if message.qos in (1, 2):
        if client.ack(message.mid, message.qos):
            raise RuntimeError("MQTT transport ACK failed after raw commit")
    logging.info("Raw upload saved: event=%s bytes=%s qos=%s",
                 event_id, len(message.payload), message.qos)


class Receipts:
    def __init__(self, filename):
        self.db = sqlite3.connect(filename)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.execute("""CREATE TABLE IF NOT EXISTS receipts (
            device TEXT NOT NULL, seq INTEGER NOT NULL, received_utc INTEGER NOT NULL,
            digest TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(device,seq))""")
        self.db.commit()

    def handle(self, payload, retained=False, now=None, clock_ok=True):
        if retained or not 2 <= len(payload) <= 2048:
            raise ValueError("retained/oversize upload")
        data = json.loads(payload, object_pairs_hook=strict_object,
                          parse_constant=lambda _: (_ for _ in ()).throw(ValueError("nonfinite")))
        if (not isinstance(data, dict) or not isinstance(data.get("device_id"), str)
                or not DEVICE.fullmatch(data["device_id"])):
            raise ValueError("missing device_id")
        device = data["device_id"]
        seq = data.get("seq")
        need_time = data.get("need_time")
        if type(seq) is not int or not 1 <= seq <= 0xffffffff:
            raise ValueError("invalid seq")
        if type(need_time) is not int or need_time not in (0, 1):
            raise ValueError("invalid need_time")
        # Require an actual meter report, not an arbitrary time request.
        if not isinstance(data.get("ID255"), dict) or not isinstance(data.get("SN"), str):
            raise ValueError("missing meter report")
        now = int(time.time()) if now is None else now
        digest = hashlib.sha256(payload).hexdigest()
        with self.db:
            old = self.db.execute("SELECT digest FROM receipts WHERE device=? AND seq=?",
                                  (device, seq)).fetchone()
            if old and old[0] != digest:
                raise ValueError("sequence reused with changed payload")
            self.db.execute("INSERT OR IGNORE INTO receipts VALUES (?,?,?,?,?)",
                            (device, seq, now, digest, payload))
        reply = {"v": 1, "seq": seq, "ack": 1}
        # Device is authoritative about retained RTC validity. No 24-hour
        # refresh and no server 'already sent' flag: lost replies must retry.
        if need_time and clock_ok and 1767225600 <= now <= 4102415997:
            bj = dt.datetime.fromtimestamp(now, BEIJING)
            reply.update(ts=now, tz=480, year=bj.year, month=bj.month, day=bj.day,
                         hour=bj.hour, minute=bj.minute, second=bj.second)
        return f"EMETER/{device}/REPLY", reply


def clock_synchronized():
    try:
        return subprocess.check_output(
            ["timedatectl", "show", "-p", "NTPSynchronized", "--value"],
            timeout=3, text=True).strip() == "yes"
    except (OSError, subprocess.SubprocessError):
        return False


def main():
    import paho.mqtt.client as mqtt
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    root = Path(os.environ.get("METER_CLOUD_DATA", "/var/lib/meter-cloud"))
    root.mkdir(parents=True, exist_ok=True)
    receipts = Receipts(root / "receipts.sqlite3")
    archive = RawUploads(root / "raw_uploads.sqlite3")
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                         client_id="meter-cloud-receipt-v1", clean_session=False, manual_ack=True)
    client.username_pw_set(os.environ["METER_CLOUD_USER"], os.environ["METER_CLOUD_PASSWORD"])
    clock_state = {"checked": -1e9, "ok": False}

    def on_connect(c, userdata, flags, reason, properties):
        if reason == 0:
            c.subscribe(UPLINK, qos=1)
            logging.info("MQTT connected; subscribed to meter uploads")
        else:
            logging.error("MQTT connection refused: %s", reason)

    def on_message(c, userdata, msg):
        if time.monotonic() - clock_state["checked"] >= 30:
            clock_state.update(checked=time.monotonic(), ok=clock_synchronized())
        process_upload(receipts, archive, c, msg, clock_ok=clock_state["ok"])

    client.on_connect = on_connect
    client.on_message = on_message
    client.reconnect_delay_set(1, 30)
    client.connect("127.0.0.1", 1883, keepalive=60)
    client.loop_forever()


if __name__ == "__main__":
    main()
