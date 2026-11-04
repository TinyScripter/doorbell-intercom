"""Tests für appdaemon/doorbell_push.py ohne AppDaemon und ohne Netzwerk.

Ausführen (nur Standardbibliothek, pytest geht ebenso):

    python3 -m unittest discover -s tools/homeassistant/tests
"""

import json
import os
import sys
import tempfile
import time
import types
import unittest
import urllib.error

# Ersatz für appdaemon.plugins.hass.Hass: nur, was die App benutzt.
class FakeHass:
    def __init__(self, args):
        self.args = args
        self.logs = []
        self.endpoints = []
        self.listeners = []

    def log(self, message, level="INFO"):
        self.logs.append((level, message))

    def register_endpoint(self, callback, name):
        self.endpoints.append((callback, name))

    def listen_state(self, callback, entity, **kwargs):
        self.listeners.append((callback, entity, kwargs))


_hass_module = types.ModuleType("appdaemon.plugins.hass")
_hass_module.Hass = FakeHass
sys.modules.setdefault("appdaemon", types.ModuleType("appdaemon"))
sys.modules.setdefault("appdaemon.plugins", types.ModuleType("appdaemon.plugins"))
sys.modules["appdaemon.plugins.hass"] = _hass_module
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "appdaemon"))

import doorbell_push  # noqa: E402

RING_ID = "2026-11-04T17:24:06.519261+00:00"


class FakeRequest:
    def __init__(self, method="POST", key=None):
        self.method = method
        self.query = {"key": key} if key is not None else {}


class DoorbellPushTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.devices_file = os.path.join(self.tmp.name, "devices.json")
        self.sent = []
        self.fail_with = {}

        def fake_send(app, endpoint, data):
            if endpoint in self.fail_with:
                raise self.fail_with[endpoint]
            self.sent.append((endpoint, data))

        self._orig_send = doorbell_push.DoorbellPush._send
        doorbell_push.DoorbellPush._send = fake_send
        self.app = self.make_app(secret="geheim")

    def tearDown(self):
        doorbell_push.DoorbellPush._send = self._orig_send
        self.tmp.cleanup()

    def make_app(self, secret=None):
        args = {"trigger_entity": "binary_sensor.klingel", "devices_file": self.devices_file}
        if secret:
            args["secret"] = secret
        app = doorbell_push.DoorbellPush(args)
        app.initialize()
        return app

    def write_devices(self, devices):
        with open(self.devices_file, "w", encoding="utf-8") as f:
            json.dump(devices, f)

    def read_devices(self):
        with open(self.devices_file, encoding="utf-8") as f:
            return json.load(f)

    def request(self, body, key="geheim", method="POST"):
        return run(self.app.on_request(body, {"request": FakeRequest(method, key)}))

    @staticmethod
    def wait_for(condition):
        """Antworten auf "answered" und Test-Klingeln laufen in eigenen Threads."""
        deadline = time.time() + 2
        while time.time() < deadline:
            if condition():
                return
            time.sleep(0.01)
        raise AssertionError("Bedingung nicht erfüllt")

    # --- Endpunkt ----------------------------------------------------------

    def test_wrong_or_missing_key_is_rejected(self):
        self.assertEqual(401, self.request({"device": "A", "endpoint": "https://e/a"}, key="falsch")[1])
        self.assertEqual(401, self.request({"device": "A", "endpoint": "https://e/a"}, key=None)[1])

    def test_register_stores_device_atomically(self):
        self.assertEqual(200, self.request({"device": "Pixel", "endpoint": "https://ntfy.example/up1"})[1])
        self.assertEqual("https://ntfy.example/up1", self.read_devices()["Pixel"]["endpoint"])
        self.assertFalse(os.path.exists(self.devices_file + ".tmp"))

    def test_register_rejects_non_http_endpoint(self):
        self.assertEqual(400, self.request({"device": "Pixel", "endpoint": "file:///etc/passwd"})[1])
        self.assertEqual(400, self.request({"device": "", "endpoint": "https://e/a"})[1])
        self.assertEqual(400, self.request({"endpoint": "https://e/a"})[1])

    def test_unregister_removes_device(self):
        self.write_devices({"A": {"endpoint": "https://e/a"}, "B": {"endpoint": "https://e/b"}})
        self.assertEqual(200, self.request({"device": "A", "action": "unregister"})[1])
        self.assertEqual(["B"], list(self.read_devices()))
        self.assertEqual(404, self.request({"device": "A", "action": "unregister"})[1])

    def test_get_lists_devices_without_endpoint_urls(self):
        self.write_devices({"A": {"endpoint": "https://ntfy.example/secret123", "last_seen": "x"}})
        body, status = self.request({}, method="GET")
        self.assertEqual(200, status)
        self.assertEqual([{"device": "A", "last_seen": "x", "endpoint_end": "ret123"}], body["devices"])

    def test_missing_secret_logs_warning(self):
        app = self.make_app(secret=None)
        self.assertTrue(any(level == "WARNING" and "secret" in msg for level, msg in app.logs))

    # --- Klingeln ----------------------------------------------------------

    def test_ring_pushes_all_devices_with_ring_id(self):
        self.write_devices({"A": {"endpoint": "https://e/a"}, "B": {"endpoint": "https://e/b"}})
        self.app.on_ring("binary_sensor.klingel", "all", {"state": "off"}, {"state": "on", "last_changed": RING_ID})
        self.assertEqual(
            {("https://e/a", RING_ID), ("https://e/b", RING_ID)},
            {(ep, data["id"]) for ep, data in self.sent},
        )
        self.assertTrue(all(data["type"] == "doorbell_ring" for _, data in self.sent))

    def test_ring_id_from_datetime_uses_isoformat(self):
        from datetime import datetime, timezone
        self.write_devices({"A": {"endpoint": "https://e/a"}})
        changed = datetime(2026, 11, 4, 17, 24, 6, 519261, tzinfo=timezone.utc)
        self.app.on_ring("binary_sensor.klingel", "all", {"state": "off"}, {"state": "on", "last_changed": changed})
        self.assertEqual(RING_ID, self.sent[0][1]["id"])

    def test_only_change_to_on_rings(self):
        self.write_devices({"A": {"endpoint": "https://e/a"}})
        on = {"state": "on", "last_changed": RING_ID}
        self.app.on_ring("binary_sensor.klingel", "all", on, dict(on, attributes={"x": 1}))
        self.app.on_ring("binary_sensor.klingel", "all", on, {"state": "off"})
        self.assertEqual([], self.sent)

    def test_listens_with_attribute_all(self):
        _, entity, kwargs = self.app.listeners[0]
        self.assertEqual("binary_sensor.klingel", entity)
        self.assertEqual("all", kwargs.get("attribute"))

    def test_gone_endpoint_is_removed_other_errors_are_kept(self):
        self.write_devices({
            "Weg": {"endpoint": "https://e/gone"},
            "Kaputt": {"endpoint": "https://e/error"},
            "Gut": {"endpoint": "https://e/ok"},
        })
        self.fail_with["https://e/gone"] = urllib.error.HTTPError("https://e/gone", 410, "Gone", {}, None)
        self.fail_with["https://e/error"] = OSError("timeout")

        self.app._ring(RING_ID)

        self.assertEqual(["https://e/ok"], [ep for ep, _ in self.sent])
        self.assertEqual({"Kaputt", "Gut"}, set(self.read_devices()))

    def test_device_without_endpoint_is_skipped(self):
        self.write_devices({"Alt": {"token": "fcm"}, "Neu": {"endpoint": "https://e/neu"}})
        self.app._ring(RING_ID)
        self.assertEqual(["https://e/neu"], [ep for ep, _ in self.sent])

    def test_test_ring_endpoint_sends_ring_with_id(self):
        self.write_devices({"A": {"endpoint": "https://e/a"}})
        self.assertEqual(200, self.request({"action": "ring"})[1])
        self.wait_for(lambda: self.sent)
        self.assertEqual("doorbell_ring", self.sent[0][1]["type"])
        self.assertIn("id", self.sent[0][1])

    # --- Angenommen --------------------------------------------------------

    def test_answered_notifies_other_devices_with_ring_id(self):
        self.write_devices({"A": {"endpoint": "https://e/a"}, "B": {"endpoint": "https://e/b"}})
        self.assertEqual(200, self.request({"device": "A", "action": "answered", "id": RING_ID})[1])
        self.wait_for(lambda: self.sent)
        self.assertEqual(
            [("https://e/b", {"type": "doorbell_answered", "answered_by": "A", "id": RING_ID})],
            self.sent,
        )

    def test_answered_without_or_with_invalid_id_omits_it(self):
        self.write_devices({"A": {"endpoint": "https://e/a"}, "B": {"endpoint": "https://e/b"}})
        self.request({"device": "A", "action": "answered", "id": 123})
        self.wait_for(lambda: self.sent)
        self.assertNotIn("id", self.sent[0][1])


def run(coroutine):
    """on_request ist async, wartet aber auf nichts – ein Schritt genügt."""
    try:
        coroutine.send(None)
    except StopIteration as done:
        return done.value
    raise AssertionError("Coroutine hat unerwartet gewartet")


if __name__ == "__main__":
    unittest.main()
