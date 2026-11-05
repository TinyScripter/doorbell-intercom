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

Für verschlüsselte Pushes (empfohlen, siehe unten) braucht es das Paket
"cryptography": in der Konfiguration des AppDaemon-Add-ons unter python_packages
eintragen. Ohne es läuft alles wie bisher, nur unverschlüsselt. AppDaemon lädt diese Datei
selbst automatisch neu (hot reload), sobald sie gespeichert wird. /config ist
aus Sicht von AppDaemon das eigene Verzeichnis des Add-ons
(/addon_configs/a0d7b954_appdaemon).

Endpunkt (in der App unter "Registrierungs-URL" eintragen):

    http://<HA-IP>:5050/api/appdaemon/doorbell_devices?key=<secret>

  POST {"device": "<Gerätename>", "endpoint": "<Endpoint-URL>",
        "p256dh": "<öffentlicher Schlüssel>", "auth": "<Auth-Secret>"}
                                                              Gerät anlegen/aktualisieren; Antwort
                                                              {"status": "ok", "encryption": true|false}
  POST {"device": "<Gerätename>", "action": "unregister"}      Gerät entfernen
  POST {"device": "<Gerätename>", "action": "answered", "id": "<Ring-ID>"}
                                                              Anruf angenommen: alle ANDEREN Geräte hören auf zu klingeln
  POST {"action": "ring"}                                       Test: alle Geräte klingeln lassen (ohne "device")
  GET                                                         Geräteliste (ohne Endpoint-URLs)

Push-Nachrichten an die Handys:

  {"type": "doorbell_ring", "id": "<Ring-ID>"}
  {"type": "doorbell_answered", "answered_by": "<Gerätename>", "id": "<Ring-ID>"}

Verschlüsselung: Meldet das Handy seine Web-Push-Schlüssel (p256dh, auth) mit und ist
"cryptography" installiert, gehen die Nachrichten nach RFC 8291 (aes128gcm) verschlüsselt
raus. Die App lehnt dann unverschlüsselte Pushes ab – wer nur die Endpoint-URL kennt,
kann kein Klingeln mehr vortäuschen und kein "answered" schicken. Ob verschlüsselt wird,
erfährt die App aus der Antwort auf die Registrierung ("encryption").

Die Ring-ID ist last_changed der trigger_entity. Schickt die MQTT-Automation
dieselbe ID mit (siehe README, Abschnitt 4.1), zeigt die App ein Klingeln, das
über beide Wege ankommt, genau einmal – und ein zweites echtes Klingeln kurz
danach trotzdem. "answered" beendet auf den anderen Handys nur dieses Klingeln.

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

import base64
import hashlib
import hmac
import json
import os
import threading
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

from appdaemon.plugins.hass import Hass

try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    WEB_PUSH_ENCRYPTION = True
except ImportError:  # Paket nicht installiert: unverschlüsselt wie bisher
    WEB_PUSH_ENCRYPTION = False

TIMEOUT_SECONDS = 10
ENDPOINT_NAME = "doorbell_devices"
DEFAULT_DEVICES_FILE = "/config/doorbell_devices.json"
MAX_FIELD_LENGTH = 512
# Gleichzeitige Pushes: ein hängender Endpoint soll die anderen Handys nicht aufhalten.
MAX_PARALLEL_PUSHES = 8
# Web Push (RFC 8030): ein Klingeln, das länger als eine Minute unterwegs ist, ist wertlos.
PUSH_TTL_SECONDS = 60
# RFC 8188: Größe eines Datensatzes; die Nachrichten passen immer in einen einzigen.
RECORD_SIZE = 4096


def b64url_decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def _hkdf(salt: bytes, ikm: bytes, info: bytes, length: int) -> bytes:
    """HKDF-SHA-256 (RFC 5869) für length <= 32, also mit nur einem Expand-Block."""
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    return hmac.new(prk, info + b"\x01", hashlib.sha256).digest()[:length]


def parse_web_push_keys(p256dh, auth):
    """Prüft die Schlüssel des Handys; gibt (ua_public, auth_secret) zurück oder None."""
    if not isinstance(p256dh, str) or not isinstance(auth, str):
        return None
    try:
        ua_public = b64url_decode(p256dh)
        auth_secret = b64url_decode(auth)
    except ValueError:
        return None
    if len(ua_public) != 65 or ua_public[0] != 4 or len(auth_secret) != 16:
        return None
    if WEB_PUSH_ENCRYPTION:
        try:
            # Prüft auch, dass der Punkt auf der Kurve liegt (RFC 8291, Abschnitt 7).
            ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), ua_public)
        except ValueError:
            return None
    return ua_public, auth_secret


def encrypt_web_push(plaintext: bytes, ua_public: bytes, auth_secret: bytes,
                     as_private=None, salt: bytes = None) -> bytes:
    """Verschlüsselt nach RFC 8291 (Content-Encoding aes128gcm, ein Datensatz).

    as_private und salt nur für Tests mit festen Werten; sonst für jede Nachricht neu.
    """
    as_private = as_private or ec.generate_private_key(ec.SECP256R1())
    salt = salt or os.urandom(16)
    ua_key = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), ua_public)
    as_public = as_private.public_key().public_bytes(
        serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint
    )
    ecdh_secret = as_private.exchange(ec.ECDH(), ua_key)
    ikm = _hkdf(auth_secret, ecdh_secret, b"WebPush: info\x00" + ua_public + as_public, 32)
    cek = _hkdf(salt, ikm, b"Content-Encoding: aes128gcm\x00", 16)
    nonce = _hkdf(salt, ikm, b"Content-Encoding: nonce\x00", 12)
    # 0x02 = Ende des letzten Datensatzes, ohne weiteres Padding.
    ciphertext = AESGCM(cek).encrypt(nonce, plaintext + b"\x02", None)
    header = salt + RECORD_SIZE.to_bytes(4, "big") + bytes([len(as_public)]) + as_public
    return header + ciphertext


class DoorbellPush(Hass):

    def initialize(self):
        if "trigger_entity" not in self.args:
            self.log("trigger_entity fehlt in apps.yaml", level="ERROR")
            return

        self._devices_path = self.args.get("devices_file", DEFAULT_DEVICES_FILE)
        self._secret = self.args.get("secret")
        if not self._secret:
            # Ohne Geheimnis kann jeder im Netz Geräte eintragen (und so erfahren, wann es
            # klingelt), fremde Geräte abmelden oder per "answered" stummschalten.
            self.log(
                "Kein 'secret' in apps.yaml gesetzt – der Endpunkt ist für jeden im Netz offen. "
                "Bitte ein langes, zufälliges secret eintragen.",
                level="WARNING",
            )
        if not WEB_PUSH_ENCRYPTION:
            self.log(
                "Paket 'cryptography' fehlt – Pushes gehen unverschlüsselt raus. Bitte in der "
                "Add-on-Konfiguration unter python_packages eintragen.",
                level="WARNING",
            )
        # on_ring läuft in einem Worker-Thread, der Endpunkt im Event-Loop:
        # der Zugriff auf die Geräte-Datei muss daher gesperrt werden.
        self._lock = threading.Lock()

        # Callbacks erst hier registrieren, nicht in __init__: die
        # Plugin-Verbindung steht erst nach dem Start von initialize().
        self.register_endpoint(self.on_request, ENDPOINT_NAME)
        # attribute="all": der Callback bekommt den ganzen Zustand samt last_changed (Ring-ID).
        self.listen_state(self.on_ring, self.args["trigger_entity"], attribute="all")
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
        # Erst in eine temporäre Datei, dann ersetzen: bricht das Schreiben ab (Neustart,
        # volle Platte), bleibt die alte Liste erhalten statt einer halben Datei, die beim
        # nächsten Lesen als leer gälte – dann wären alle Geräte still verschwunden.
        tmp_path = self._devices_path + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(devices, f, indent=2, ensure_ascii=False)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_path, self._devices_path)

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
            ring_id = datetime.now(timezone.utc).isoformat()
            threading.Thread(target=self._ring, args=(ring_id,), daemon=True).start()
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
            ring_id = body.get("id")
            if not isinstance(ring_id, str) or len(ring_id) > MAX_FIELD_LENGTH:
                ring_id = None
            # Im Hintergrund: der Endpunkt läuft im Event-Loop, HTTP-Aufrufe blockieren.
            threading.Thread(target=self._notify_answered, args=(device, ring_id), daemon=True).start()
            return {"status": "ok"}, 200
        if not self._is_http_url(endpoint) or len(endpoint) > MAX_FIELD_LENGTH:
            return {"error": "endpoint (http/https-URL) erwartet"}, 400

        entry = {
            "endpoint": endpoint,
            "last_seen": datetime.now().isoformat(timespec="seconds"),
        }
        p256dh, auth = body.get("p256dh"), body.get("auth")
        if parse_web_push_keys(p256dh, auth) is not None:
            entry["keys"] = {"p256dh": p256dh, "auth": auth}
        elif p256dh is not None or auth is not None:
            # Trotzdem registrieren: ein Klingeln ohne Verschlüsselung ist besser als keines.
            self.log(f"Ungültige Web-Push-Schlüssel von \"{device}\" – unverschlüsselt", level="WARNING")
        encrypted = WEB_PUSH_ENCRYPTION and "keys" in entry

        with self._lock:
            devices = self._read_devices()
            is_new = device not in devices
            devices[device] = entry
            self._write_devices(devices)
        self.log(
            f"Gerät \"{device}\" {'registriert' if is_new else 'aktualisiert'}"
            f" ({'verschlüsselt' if encrypted else 'unverschlüsselt'})"
        )
        # Die App lehnt unverschlüsselte Pushes nur ab, wenn hier "encryption": true kam.
        return {"status": "ok", "encryption": encrypted}, 200

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
                {
                    "device": name,
                    "last_seen": info.get("last_seen"),
                    "endpoint_end": info.get("endpoint", "")[-6:],
                    "encrypted": WEB_PUSH_ENCRYPTION and "keys" in info,
                }
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
        # Mit attribute="all" kommt jede Änderung, auch reine Attribut-Änderungen:
        # nur der Wechsel nach "on" ist ein Klingeln.
        if not isinstance(new, dict) or new.get("state") != "on":
            return
        if isinstance(old, dict) and old.get("state") == "on":
            return
        self._ring(new.get("last_changed"))

    def _ring(self, ring_id):
        data = {"type": "doorbell_ring"}
        if ring_id:
            # Je nach AppDaemon-Version String oder datetime; isoformat() entspricht dem
            # Template in der MQTT-Automation.
            data["id"] = ring_id.isoformat() if hasattr(ring_id, "isoformat") else str(ring_id)
        self._push(data)

    def _notify_answered(self, answered_by: str, ring_id: str = None):
        """Sagt allen anderen Geräten, dass jemand angenommen hat – sie hören auf zu klingeln."""
        self.log(f"\"{answered_by}\" hat angenommen")
        data = {"type": "doorbell_answered", "answered_by": answered_by}
        if ring_id:
            data["id"] = ring_id
        self._push(data, exclude=answered_by)

    def _push(self, data: dict, exclude: str = None):
        devices = self._load_devices()
        if exclude is not None:
            devices.pop(exclude, None)
        if not devices:
            self.log("Kein Gerät für den Push vorhanden", level="WARNING")
            return

        # Alle Geräte gleichzeitig: nacheinander würde ein hängender Endpoint (bis zu
        # TIMEOUT_SECONDS) das Klingeln auf allen folgenden Handys verzögern.
        with ThreadPoolExecutor(max_workers=min(MAX_PARALLEL_PUSHES, len(devices))) as pool:
            for name, info in devices.items():
                pool.submit(self._push_device, name, info, data)

    def _push_device(self, name: str, info: dict, data: dict):
        # Jedes Gerät einzeln: eine ungültige Endpoint-URL darf die anderen nicht aufhalten.
        endpoint = info.get("endpoint")
        if not self._is_http_url(endpoint):
            self.log(f"\"{name}\" hat noch keine Endpoint-URL – App muss sich neu melden", level="WARNING")
            return
        keys = info.get("keys") if WEB_PUSH_ENCRYPTION else None
        try:
            self._send(endpoint, data, keys)
            self.log(f"Push {data['type']} an \"{name}\" gesendet{'' if keys else ' (unverschlüsselt)'}")
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
        except Exception as e:  # noqa: BLE001 – im Worker-Thread ginge der Fehler sonst unbemerkt verloren
            self.log(f"Push an \"{name}\" fehlgeschlagen (unerwartet): {e!r}", level="ERROR")

    def _send(self, endpoint: str, data: dict, keys: dict = None):
        body = json.dumps(data).encode()
        headers = {
            "Content-Type": "application/json",
            # Nur für ntfy (bei anderen Servern wirkungslos): höchste Priorität weckt
            # das Handy auch im Ruhemodus, und ein Klingeln, das erst später
            # zugestellt werden könnte, ist wertlos – dann lieber nicht speichern.
            "Priority": "urgent",
            "Cache": "no",
            "TTL": str(PUSH_TTL_SECONDS),
        }
        parsed = parse_web_push_keys(keys.get("p256dh"), keys.get("auth")) if keys else None
        if parsed is not None:
            body = encrypt_web_push(body, *parsed)
            headers["Content-Type"] = "application/octet-stream"
            headers["Content-Encoding"] = "aes128gcm"
        request = urllib.request.Request(endpoint, data=body, headers=headers)
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS):
            pass
