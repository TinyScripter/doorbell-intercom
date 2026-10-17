#!/usr/bin/env python3
"""Schickt ein Klingel-Push per UnifiedPush an die App (zum manuellen Testen).

Nutzt nur die Python-Standardbibliothek.

Aufruf:
    python3 send_doorbell_push.py --endpoint <Endpoint-URL des Handys>

--endpoint: UnifiedPush-Endpoint-URL des Handys (z. B. https://ntfy.sh/upAbc123).
    Im Normalbetrieb verwaltet die AppDaemon-App (appdaemon/doorbell_push.py) die
    Endpoint-URLs aller Handys selbst; sie liegen in deren Geräte-Datei
    (doorbell_devices.json). Die URL ist das Geheimnis – nicht weitergeben.

Exit-Code 0 bei Erfolg, sonst 1 mit Fehlermeldung auf stderr.
"""

import argparse
import json
import sys
import urllib.error
import urllib.request

TIMEOUT_SECONDS = 10


def send_ring(endpoint: str) -> int:
    request = urllib.request.Request(
        endpoint,
        data=json.dumps({"type": "doorbell_ring"}).encode(),
        headers={
            "Content-Type": "application/json",
            # Nur für ntfy: weckt das Handy auch im Ruhemodus, nichts zwischenspeichern.
            "Priority": "urgent",
            "Cache": "no",
        },
    )
    with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        return response.status


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--endpoint", required=True, help="UnifiedPush-Endpoint-URL des Handys")
    args = parser.parse_args()

    endpoint = args.endpoint.strip()
    if not endpoint.startswith(("http://", "https://")):
        print("Endpoint muss eine http(s)-URL sein", file=sys.stderr)
        return 1

    try:
        status = send_ring(endpoint)
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")
        print(f"HTTP {e.code} von {e.url}: {detail}", file=sys.stderr)
        return 1
    except (OSError, ValueError) as e:
        print(f"Fehler: {e}", file=sys.stderr)
        return 1

    print(f"Gesendet: HTTP {status}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
