# Doorbell Intercom (Android, local, push via ntfy or MQTT)

Native Android app for a Reolink PoE doorbell. When someone rings, the phone
shows a full-screen incoming call with the live camera picture. You can talk
to the visitor and open the door – directly from the phone, locally in your
home network, with no vendor cloud in between.

## 1. Features

- **Incoming call on ring**: a full-screen call screen appears, even on a
  locked or switched-off display. The live picture starts muted; after
  **Accept** you hear the visitor and can talk right away. If the phone is
  locked, Accept asks you to unlock first, so the door cannot be opened from
  the lock screen.
- **Live video (and audio)** straight from the camera over RTSP, also on the
  main screen whenever you open the app. Pinch to zoom, optional aspect-ratio
  preservation.
- **Two-way talk** to the visitor through the camera's speaker (Reolink
  Baichuan protocol, about 1 s latency, similar to the official Reolink app),
  with an adjustable software microphone gain.
- **Door opener button**: calls a webhook URL you configure (for example a
  Home Assistant webhook).
- **Two ways to get the ring**: push via ntfy/UnifiedPush (works from anywhere
  with internet, needs no cloud account) and MQTT (works inside your home
  network even without internet). See section 3.
- **Several phones**: every registered phone rings. When one phone accepts the
  call, the others stop ringing and close the call screen. If one phone
  declines, the others keep ringing.
- **"Answered by" notice** (optional): the other phones show a short
  notification such as "Doorbell answered by: Pixel 10 Pro".
- **Adjustable ringing time**: 10 to 90 seconds, default 30 seconds.
- **Automatic VPN** (optional): away from home, the app can bring up your
  WireGuard tunnel when the doorbell rings and take it down again afterwards.
- **Settings export and import** as a JSON file (the file contains passwords
  in plain text).
- Starts its background service again after a reboot.

## 2. Requirements

- A **Reolink doorbell** (or camera) in your local network with RTSP (port
  554) and Baichuan (port 9000) access. Both are enabled by default on Reolink
  devices. Use the same credentials as in the Reolink app.
- An **Android phone with Android 8.0 or newer**. The app needs the microphone
  and notification permissions. On recent Android versions the full-screen
  notification permission must be allowed so the call screen can appear over
  other apps.
- **Home Assistant** that detects the doorbell press (a binary sensor or an
  automation). It tells the phones when someone rings.
- For the **ntfy push** (section 3): the **ntfy app** on every phone, and the
  **AppDaemon add-on** in Home Assistant.
- For the **MQTT path** (section 3): an **MQTT broker** in your network, for
  example Mosquitto, which Home Assistant can publish to.
- Optional, for the VPN feature: the **WireGuard app** with a tunnel to your
  home network.
- Recommended on every phone: **turn off battery optimisation** for this app
  (Settings → Apps → Battery → Unrestricted). Some manufacturers (Samsung,
  Xiaomi and others) have additional battery managers; allow autostart and
  background activity there too. The ntfy app needs the same exception.

## 3. How the ring reaches the phone

The app supports two ways. They can be used together.

### ntfy (UnifiedPush) – works from anywhere

Home Assistant sends a small HTTP request to an address that belongs to the
phone. The **ntfy app** on the phone receives it and hands it to this app,
which opens the call screen. No Firebase or Google Cloud project and no server
of your own is required.

- Each phone gets its own endpoint URL from ntfy (for example
  `https://ntfy.sh/upAbc123…`). The app reports it to Home Assistant by itself.
- With the public server ntfy.sh, the server operator can see metadata (topic
  and time). The endpoint URL is the secret: whoever knows it can make the
  phone ring. If you do not want that, host ntfy yourself and point the ntfy
  app at your server.
- ntfy from the Play Store keeps a connection to the server. If ntfy is
  stopped by the system (battery optimisation), the server rejects the push
  and nothing rings, so check that exception first if a ring does not arrive.

### MQTT – works in the home network, even without internet

Home Assistant publishes a message on an MQTT topic. The app listens for it in
a background service with a permanent notification.

- To save battery, MQTT is paused while the phone has internet access, because
  the ntfy push is used then. When the phone is in a Wi-Fi network **without
  internet**, the app connects to your local broker after about 10 seconds and
  binds to that Wi-Fi. Without bound Wi-Fi, local traffic would be routed over
  mobile data.
- If no ntfy registration URL is configured, MQTT stays connected permanently.
- With the switch **MQTT fallback** in the settings turned off, there is no
  background service and no permanent notification; the ring comes via ntfy
  only.

## 4. Home Assistant setup

### 4.1 MQTT

Extend your existing doorbell automation with an MQTT publish action:

```yaml
action:
  - service: mqtt.publish
    data:
      topic: "doorbell/ring"
      payload: "ring"
      qos: 1
```

The topic must match the **Topic** field in the app settings.

### 4.2 ntfy push via AppDaemon

1. Install the **AppDaemon** add-on in Home Assistant. The app described here
   needs no additional Python packages.
2. Copy `tools/homeassistant/appdaemon/doorbell_push.py` to the add-on's `apps`
   folder (`/addon_configs/a0d7b954_appdaemon/apps/` for the official add-on).
3. Add this to `apps.yaml` (the same example is at the top of the script):

   ```yaml
   doorbell_push:
     module: doorbell_push
     class: DoorbellPush
     trigger_entity: binary_sensor.doorbell_visitor   # your doorbell sensor
     # Optional:
     devices_file: /config/doorbell_devices.json   # default
     secret: a-long-random-secret                  # endpoint then requires ?key=...
   ```

   `trigger_entity` is the binary sensor that turns `on` when someone rings.
4. Install the **ntfy app** on every phone (Play Store or F-Droid).
5. In the app: **Settings → Push settings**, enter the **registration URL**

   ```
   http://<HA-IP>:5050/api/appdaemon/doorbell_devices?key=<secret>
   ```

   and a **device name** (default: the phone model with a random suffix; the
   name is not part of exported settings). Leave the page to save. The app registers with ntfy
   (Android asks which distributor to use if several are installed) and reports
   its endpoint to Home Assistant at start and after saving. **Report now**
   ("Jetzt melden") does it immediately and shows the result in a short pop-up.

`notify.mobile_app` does not replace this: it goes to the Home Assistant
Companion app, not to this app.

The device list is stored in `doorbell_devices.json` in the AppDaemon folder and
contains the endpoint URLs in plain text. Treat it like a password.

#### Test the ring

Without pressing the real doorbell, send this request to make all registered
phones ring:

```
curl -X POST -H "Content-Type: application/json" -d '{"action": "ring"}' \
     "http://<HA-IP>:5050/api/appdaemon/doorbell_devices?key=<secret>"
```

For a single phone, use `python3 tools/homeassistant/send_doorbell_push.py
--endpoint <endpoint URL>`. The endpoint URL is in `doorbell_devices.json`.
The AppDaemon log shows one line per phone that was pushed to.

#### Several phones

Every phone enters the same registration URL and its own device name. When the
doorbell rings, all registered phones are pushed. When one phone accepts, the
others are told to stop ringing.

#### Removing phones

- **On the phone:** Settings → Push settings → **Unregister** ("Abmelden"). As long as a URL is
  entered, the app registers again on the next start; clear the URL or
  uninstall the app to stay unregistered.
- **From Home Assistant** (for example for a lost phone):

  ```
  curl "http://<HA-IP>:5050/api/appdaemon/doorbell_devices?key=<secret>"
  curl -X POST -H "Content-Type: application/json" \
       -d '{"device": "Old phone", "action": "unregister"}' \
       "http://<HA-IP>:5050/api/appdaemon/doorbell_devices?key=<secret>"
  ```

  The first call lists the devices (endpoint URLs are shortened). AppDaemon only
  allows GET and POST on endpoints, so removing also works with POST.
- **Automatically:** if the push server reports an endpoint as gone (HTTP 404 or
  410), AppDaemon removes the device on the next ring.

### 4.3 Door opener (optional)

The **Open door** button sends an HTTP POST to the **webhook URL** in the
settings. Create a webhook automation in Home Assistant that opens the door, for
example:

```yaml
trigger:
  - platform: webhook
    webhook_id: open_front_door
    allowed_methods: [POST]
    local_only: true
action:
  - service: lock.unlock          # or switch.turn_on, button.press, ...
    target:
      entity_id: lock.front_door
```

and enter `http://<HA-IP>:8123/api/webhook/open_front_door` in the app.

## 5. Setting up the app

The app's interface is currently in German. Button and field names below are
translated, with the German label in brackets where it matters.

The start screen shows the live picture of the camera and a **Talk** button
("Sprechen"). Tap the three dots in the top right corner for the settings
("Einstellungen"). The settings page lists these sub-pages:

- **Camera**: IP address, user name and password (used for video over RTSP,
  port 554, and for talking over Baichuan, port 9000). Change the RTSP path if
  it differs from `h264Preview_01_main` (for example `h264Preview_01_sub` for
  the substream with less bandwidth). Turn on **keep aspect ratio** if the
  picture looks stretched. The microphone gain raises the volume of your voice
  at the doorbell.
- **Doorbell** ("Klingel"): how long the phone rings (10–90 s), the ringtone
  and whether to ring in silent mode.
- **Push settings** ("Push-Einstellungen"): registration URL and device name
  (section 4.2), the MQTT fallback (broker address, port, optional
  credentials, topic) and whether to be notified when another phone answers.
- **More connections** ("Weitere Verbindungen"): the door opener webhook URL
  from section 4.3 and the VPN (WireGuard) switch and tunnel name (below).
- **About / Licenses** ("Über / Lizenzen"): the app's license (GPLv3) and
  third-party notices.

The settings page also has **Backup** ("Sicherung") to export and import all
settings as a file.

There is no Save button: changes in a sub-page are saved when you leave it
(arrow in the top left or back gesture), and a short message confirms it. Then
grant the microphone and notification permissions when asked, and turn off
battery optimisation for the app.

### VPN over WireGuard (optional)

Away from your Wi-Fi, the app can bring up your WireGuard tunnel to the home
network by itself:

- **When the doorbell rings** and **when you open the app**: if the camera is not
  reachable (test on port 554) and no VPN is running, the app starts the tunnel
  and shows the live picture afterwards.
- **When you hang up or leave the app**, it takes the tunnel down again, but only
  if it started it. A VPN that was running before stays on. As a safety net, an
  alarm disconnects the tunnel after about 15 minutes at the latest in case the
  system ended the app in between (Android may delay this by a few minutes).

One-time setup:

1. In the WireGuard app, enable **Allow remote control apps** under Settings.
   Without this option WireGuard silently ignores the commands.
2. Set the battery for WireGuard to **Unrestricted** (Settings → Apps → WireGuard
   → Battery). Without this exception Android forbids the background WireGuard
   app to start its VPN service and the tunnel never comes up at a ring.
3. Allow the app to control WireGuard: when you turn the switch on or save, the
   app asks for the WireGuard permission `CONTROL_TUNNELS` (WireGuard must be
   installed). Confirm it.

Then in the app settings (card **VPN (WireGuard)**) turn on **Bring up VPN
automatically on ring** and enter the **tunnel name exactly as it is called in
WireGuard** (case-sensitive).

Notes:

- The tunnel's `AllowedIPs` must include the network of the camera, and ports
  554 (RTSP) and 9000 (Baichuan) must be reachable through the tunnel.
- Android allows only one VPN at a time; another running VPN is replaced by the
  WireGuard tunnel.
- Away from home, the live picture appears about 1–2 s later because the tunnel
  has to come up first (it waits at most 10 s, then the live picture reports a
  connection error).

## 6. Known pitfalls

- **Baichuan protocol details**: Reolink's Baichuan protocol is not officially
  documented. The implementation in `baichuan/` is a port of `reolink_aio`
  (MIT), which itself builds on `neolink` (AGPL-3.0); see
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). It may break with future
  firmware updates.
- **RTSP path varies by camera model**: `h264Preview_01_main` is the usual main
  stream path on Reolink cameras but can differ; it can be changed in the
  settings.
- **Doze and background killing**: some Android vendors have aggressive battery
  managers; set an exception for autostart and background activity there.
- **Ring does not arrive via ntfy**: make sure the ntfy app is running, is exempt
  from battery optimisation, and has a subscription for the app (it appears in
  the ntfy app's list). The push server answers HTTP 507 ("without
  previously active subscriber") when ntfy is not connected.

## 7. Building from source

Requirements: a recent Android Studio and JDK 17.

1. Open this folder in Android Studio (**File → Open**). It creates the Gradle
   wrapper (`gradlew`) on first open; otherwise run `gradle wrapper
   --gradle-version 8.7` once.
2. Build and run the `app` module. The **debug** build installs next to the
   release build (application ID suffix `.debug`, red name suffix and a red
   icon with a hammer). Their settings are separate.
3. For a **release** build, put the signing values in `~/.gradle/gradle.properties`
   (not in the repository): `releaseStoreFile`, `releaseStorePassword`,
   `intercomKeyAlias`, `intercomKeyPassword`.

The push uses the UnifiedPush connector library
(`org.unifiedpush.android:connector` from Maven Central). No Google Services
plugin or `google-services.json` is needed.

## 8. Architecture

- **Video (+ audio if the camera offers it in the RTSP stream)**: directly over
  RTSP from the camera (`DirectRtspClient.kt`, Google's official `media3`/ExoPlayer
  RTSP module – an open standard, no reverse engineering).
- **Talk (speaking to the doorbell)**: a direct TCP client speaking Reolink's
  proprietary Baichuan protocol (`baichuan/` package) – login, talk session,
  IMA-ADPCM audio frames. Latency about 1 s, comparable to the official Reolink
  app. Details and sources are in the comments of `baichuan/BaichuanTalkClient.kt`.
- **Ring trigger**: started by Home Assistant. With internet the ring comes via
  ntfy/UnifiedPush (`DoorbellPushService.kt`) and MQTT is paused to save battery.
  In a Wi-Fi network without internet the app connects to the local MQTT broker
  after about 10 s and binds to that Wi-Fi (`DoorbellForegroundService.kt`).
  Both ways end in `IncomingCallNotifier`, which shows the call screen once
  (a ring that arrives on both ways within 15 s is ignored as a duplicate).
- **Push registration**: the app reports its UnifiedPush endpoint to the AppDaemon
  app (`PushRegistrar.kt`); AppDaemon (`tools/homeassistant/appdaemon/doorbell_push.py`)
  stores the device list and sends the HTTP POST to every endpoint.
- **VPN (optional)**: the app starts and stops the WireGuard tunnel by intent
  (`WireGuardTunnel.kt`).

### Project structure

```
app/src/main/java/de/kleinabersmart/doorbellintercom/
  AppConfig.kt                   Settings (SharedPreferences)
  MainActivity.kt                Start screen: live picture + talk
  SettingsActivity.kt            Settings screen
  DoorbellForegroundService.kt   Watches Wi-Fi, MQTT without internet, triggers the call screen
  IncomingCallActivity.kt        Full-screen call UI with accept/decline
  IncomingCallNotifier.kt        Shows/ends the call (ringing time, answered elsewhere)
  DirectRtspClient.kt            Video directly over RTSP (ExoPlayer/media3)
  MqttClientWrapper.kt           MQTT client wrapper
  DoorbellPushService.kt         Push receiver (UnifiedPush)
  PushRegistrar.kt               Registers the endpoint with Home Assistant (AppDaemon)
  WireGuardTunnel.kt             Brings the WireGuard tunnel up/down on demand (intent to the WireGuard app)
  BootReceiver.kt                Restarts the service after a reboot
  WindowInsetsExt.kt             Helpers for edge-to-edge insets
  baichuan/
    BaichuanTalkClient.kt        Baichuan TCP client: login, talk session
    BaichuanTalkSession.kt       Connects microphone capture to the client
    BaichuanCrypto.kt            AES/XOR encryption of the protocol
    BaichuanAdpcm.kt             IMA-ADPCM encoder + BcMedia framing
tools/homeassistant/
  appdaemon/doorbell_push.py     AppDaemon app: device list, registration endpoint, push
  send_doorbell_push.py          Test script: ring a single endpoint
```

## 9. License

This project is free software, licensed under the **GNU General Public License
version 3** (GPLv3); see [LICENSE](LICENSE).

It contains code ported from [reolink_aio](https://github.com/starkillerOG/reolink_aio)
(MIT License) and uses third-party libraries under the Apache License 2.0
(AndroidX, Material Components, media3, HiveMQ MQTT client, UnifiedPush
connector, kotlinx.coroutines). Copyright notices and license texts are in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and in the app under
**Settings → About / Licenses**.

This project is not affiliated with Reolink.
