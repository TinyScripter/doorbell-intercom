"""AppDaemon-App: schickt beim Klingeln eine Push-Nachricht per UnifiedPush an
alle registrierten Türklingel-Apps – als zweiter Weg neben MQTT.

UnifiedPush braucht kein eigenes Cloud-Projekt und keine Zugangsdaten: jedes
Handy bekommt von seinem Push-Verteiler (z. B. der ntfy-App) eine Endpoint-URL,
und ein einfaches HTTP-POST an diese URL löst den Push aus. Die URL ist das
Geheimnis – wer sie kennt, kann ein Klingeln auslösen.

Die Handys melden ihre Endpoint-URL selbst an einen Endpunkt dieser App (siehe
unten); die Geräteliste liegt in einer JSON-Datei. In Home Assistant muss dafür
nichts angelegt werden (kein input_text, kein Webhook).

Beim AppDaemon-Add-on (a0d7b954_appdaemon) nach
/addon_configs/a0d7b954_appdaemon/apps/ kopieren, dazu diesen Eintrag in
apps.yaml:

    doorbell_push:
      module: doorbell_push
      class: DoorbellPush
      trigger_entity: binary_sensor.klingel_besucher
      # Optional:
      devices_file: /config/doorbell_devices.json   # Standardwert
      secret: ein-langes-zufaelliges-geheimnis      # Der Endpunkt verlangt dann ?key=...

Es werden keine zusätzlichen Python-Pakete benötigt. AppDaemon lädt diese Datei
selbst automatisch neu (hot reload), sobald sie gespeichert wird. /config ist
aus Sicht von AppDaemon das eigene Verzeichnis des Add-ons
(/addon_configs/a0d7b954_appdaemon).

Endpunkt (in der App unter "Registrierungs-URL" eintragen):

    http://<HA-IP>:5050/api/appdaemon/doorbell_devices?key=<secret>

  POST {"device": "<Gerätename>", "endpoint": "<Endpoint-URL>"}  Gerät anlegen/aktualisieren
  POST {"device": "<Gerätename>", "action": "unregister"}      Gerät entfernen
  POST {"device": "<Gerätename>", "action": "answered"}        Anruf angenommen: alle ANDEREN Geräte hören auf zu klingeln
  POST {"action": "ring"}                                       Test: alle Geräte klingeln lassen (ohne "device")
  GET                                                         Geräteliste (ohne Endpoint-URLs)

AppDaemon lässt für registrierte Endpunkte nur GET und POST zu (DELETE wird mit
HTTP 405 abgewiesen), daher läuft auch das Entfernen über POST.

Klingeln testen (alle registrierten Handys, ohne den Auslöser in Home Assistant zu setzen):

    curl -X POST -H "Content-Type: application/json" -d '{"action": "ring"}' \
         "http://<HA-IP>:5050/api/appdaemon/doorbell_devices?key=<secret>"

Beispiele zum Aufräumen alter Handys:

    curl "http://<HA-IP>:5050/api/appdaemon/doorbell_devices?key=<secret>"
    curl -X POST -d '{"device": "Altes Handy", "action": "unregister"}' \
         "http://<HA-IP>:5050/api/appdaemon/doorbell_devices?key=<secret>"

Eine Endpoint-URL, die der Push-Server als nicht mehr vorhanden meldet (HTTP
404/410, App deinstalliert oder abgemeldet), wird beim nächsten Klingeln
automatisch entfernt. Einträge aus der Zeit vor UnifiedPush (mit "token" statt
"endpoint") werden übersprungen, bis das Handy sich neu gemeldet hat. Der
Endpunkt läuft über AppDaemons HTTP-Server – ist dort ein api_key gesetzt, kann
die App ihn nicht mitsenden; dann stattdessen "secret" nutzen.
"""

import hmac
import json
import threading
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime

from appdaemon.plugins.hass import Hass

TIMEOUT_SECONDS = 10
ENDPOINT_NAME = "doorbell_devices"
DEFAULT_DEVICES_FILE = "/config/doorbell_devices.json"
MAX_FIELD_LENGTH = 512


class DoorbellPush(Hass):

    def initialize(self):
        if "trigger_entity" not in self.args:
            self.log("trigger_entity fehlt in apps.yaml", level="ERROR")
            return

        self._devices_path = self.args.get("devices_file", DEFAULT_DEVICES_FILE)
        self._secret = self.args.get("secret")
        # on_ring läuft in einem Worker-Thread, der Endpunkt im Event-Loop:
        # der Zugriff auf die Geräte-Datei muss daher gesperrt werden.
        self._lock = threading.Lock()

        # Callbacks erst hier registrieren, nicht in __init__: die
        # Plugin-Verbindung steht erst nach dem Start von initialize().
        self.register_endpoint(self.on_request, ENDPOINT_NAME)
        self.listen_state(self.on_ring, self.args["trigger_entity"], new="on")
        self.log(f"{len(self._load_devices())} Gerät(e) registriert")

    # --- Geräteliste -------------------------------------------------------

    def _load_devices(self) -> dict:
        with self._lock:
            return self._read_devices()

    def _read_devices(self) -> dict:
        try:
            with open(self._devices_path, encoding="utf-8") as f:
                devices = json.load(f)
            return devices if isinstance(devices, dict) else {}
        except FileNotFoundError:
            return {}
        except (OSError, ValueError) as e:
            self.log(f"{self._devices_path} nicht lesbar: {e}", level="ERROR")
            return {}

    def _write_devices(self, devices: dict):
        with open(self._devices_path, "w", encoding="utf-8") as f:
            json.dump(devices, f, indent=2, ensure_ascii=False)

    # --- HTTP-Endpunkt -----------------------------------------------------

    async def on_request(self, data, kwargs):
        # AppDaemon ruft Endpunkte als callback(data, kwargs) auf: bei POST ist "data" der
        # bereits geparste JSON-Body, bei GET die Query-Parameter; das eigentliche
        # aiohttp-Request-Objekt steckt in kwargs["request"].
        request = kwargs["request"]
        if self._secret and not hmac.compare_digest(
            request.query.get("key", ""), str(self._secret)
        ):
            return {"error": "unauthorized"}, 401

        try:
            if request.method == "POST":
                return self._handle_post(data)
            if request.method == "GET":
                return self._list()
        except OSError as e:
            self.log(f"Geräteliste konnte nicht gespeichert werden: {e}", level="ERROR")
            return {"error": "storage"}, 500
        return {"error": "method not allowed"}, 405

    def _handle_post(self, body):
        if isinstance(body, dict) and body.get("action") == "ring":
            # Im Hintergrund: der Endpunkt läuft im Event-Loop, HTTP-Aufrufe blockieren.
            self.log("Test-Klingeln über den Endpunkt ausgelöst")
            threading.Thread(target=self.on_ring, args=(None, None, None, None), daemon=True).start()
            return {"status": "ok"}, 200
        try:
            device = str(body["device"]).strip()
            action = body.get("action")
            endpoint = None if action in ("unregister", "answered") else str(body["endpoint"]).strip()
        except (ValueError, KeyError, TypeError, AttributeError):
            return {"error": "device und endpoint erwartet"}, 400
        if not device or len(device) > MAX_FIELD_LENGTH:
            return {"error": "device erwartet"}, 400
        if action == "unregister":
            return self._unregister(device)
        if action == "answered":
            # Im Hintergrund: der Endpunkt läuft im Event-Loop, HTTP-Aufrufe blockieren.
            threading.Thread(target=self._notify_answered, args=(device,), daemon=True).start()
            return {"status": "ok"}, 200
        if not self._is_http_url(endpoint) or len(endpoint) > MAX_FIELD_LENGTH:
            return {"error": "endpoint (http/https-URL) erwartet"}, 400

        with self._lock:
            devices = self._read_devices()
            is_new = device not in devices
            devices[device] = {
                "endpoint": endpoint,
                "last_seen": datetime.now().isoformat(timespec="seconds"),
            }
            self._write_devices(devices)
        self.log(f"Gerät \"{device}\" {'registriert' if is_new else 'aktualisiert'}")
        return {"status": "ok"}, 200

    @staticmethod
    def _is_http_url(value) -> bool:
        return isinstance(value, str) and urllib.parse.urlparse(value).scheme in ("http", "https")

    def _unregister(self, device: str):
        removed = self._remove_device(device)
        if not removed:
            return {"error": "unbekanntes Gerät"}, 404
        self.log(f"Gerät \"{device}\" entfernt")
        return {"status": "ok"}, 200

    def _list(self):
        devices = self._load_devices()
        return {
            "devices": [
                {"device": name, "last_seen": info.get("last_seen"), "endpoint_end": info.get("endpoint", "")[-6:]}
                for name, info in sorted(devices.items())
            ]
        }, 200

    def _remove_device(self, device: str) -> bool:
        with self._lock:
            devices = self._read_devices()
            if device not in devices:
                return False
            del devices[device]
            self._write_devices(devices)
            return True

    # --- Klingeln ----------------------------------------------------------

    def on_ring(self, entity, attribute, old, new, **kwargs):
        self._push({"type": "doorbell_ring"})

    def _notify_answered(self, answered_by: str):
        """Sagt allen anderen Geräten, dass jemand angenommen hat – sie hören auf zu klingeln."""
        self.log(f"\"{answered_by}\" hat angenommen")
        self._push({"type": "doorbell_answered", "answered_by": answered_by}, exclude=answered_by)

    def _push(self, data: dict, exclude: str = None):
        devices = self._load_devices()
        if exclude is not None:
            devices.pop(exclude, None)
        if not devices:
            self.log("Kein Gerät für den Push vorhanden", level="WARNING")
            return

        # Jedes Gerät einzeln: eine ungültige Endpoint-URL darf die anderen nicht aufhalten.
        for name, info in devices.items():
            endpoint = info.get("endpoint")
            if not self._is_http_url(endpoint):
                self.log(f"\"{name}\" hat noch keine Endpoint-URL – App muss sich neu melden", level="WARNING")
                continue
            try:
                self._send(endpoint, data)
                self.log(f"Push {data['type']} an \"{name}\" gesendet")
            except urllib.error.HTTPError as e:
                if e.code in (404, 410):
                    # Endpoint beim Push-Server nicht mehr vorhanden: App deinstalliert oder abgemeldet.
                    self.log(f"Endpoint von \"{name}\" ungültig – Gerät wird entfernt", level="WARNING")
                    self._remove_device(name)
                else:
                    detail = e.read().decode(errors="replace")
                    self.log(f"HTTP {e.code} für \"{name}\": {detail}", level="ERROR")
            except (OSError, ValueError) as e:
                self.log(f"Push an \"{name}\" fehlgeschlagen: {e}", level="ERROR")

    def _send(self, endpoint: str, data: dict):
        request = urllib.request.Request(
            endpoint,
            data=json.dumps(data).encode(),
            headers={
                "Content-Type": "application/json",
                # Nur für ntfy (bei anderen Servern wirkungslos): höchste Priorität weckt
                # das Handy auch im Ruhemodus, und ein Klingeln, das erst später
                # zugestellt werden könnte, ist wertlos – dann lieber nicht speichern.
                "Priority": "urgent",
                "Cache": "no",
            },
        )
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS):
            pass
