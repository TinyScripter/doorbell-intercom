# Third-party notices

## Reolink Baichuan protocol (`app/src/main/java/.../baichuan/`)

The Baichuan talk client (login handshake, encryption, XML templates, command
IDs, IMA-ADPCM framing) is a Kotlin port of Python code from:

- **reolink_aio** – https://github.com/starkillerOG/reolink_aio
  (MIT License), in particular the pull request
  https://github.com/starkillerOG/reolink_aio/pull/165 by `fredrsat`
  (branch `add-two-way-audio`, two-way audio / talk).

reolink_aio in turn states that its Baichuan part is based on the work of
@QuantumEntangledAndy and @thirtythreeforty:

- **neolink** – https://github.com/QuantumEntangledAndy/neolink
  (AGPL-3.0). The PR #165 description says its implementation is "based on
  reverse-engineering from the neolink project".

Reolink's Baichuan protocol is proprietary and not officially documented. This
project is not affiliated with Reolink.

### MIT License text (reolink_aio)

Copyright (c) 2023 starkillerOG

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Libraries (Apache License 2.0)

The following libraries are used unmodified as Gradle dependencies and are
licensed under the Apache License, Version 2.0
(https://www.apache.org/licenses/LICENSE-2.0). Their transitive dependencies
bring their own notices, which are part of the respective artifacts.

- AndroidX (core-ktx, appcompat, constraintlayout), The Android Open Source Project
- Material Components for Android, https://github.com/material-components/material-components-android
- AndroidX Media3 (exoplayer, exoplayer-rtsp, ui), https://github.com/androidx/media
- HiveMQ MQTT Client, https://github.com/hivemq/hivemq-mqtt-client
- UnifiedPush Android connector, https://github.com/UnifiedPush/android-connector
- kotlinx.coroutines, https://github.com/Kotlin/kotlinx.coroutines
- ZXing core (QR code generation, all build variants), https://github.com/zxing/zxing
- ZXing Android Embedded (QR code scanning, build variant `free` only), https://github.com/journeyapps/zxing-android-embedded

## Proprietary components (build variant `play` only)

The `play` build variant scans QR codes with the ML Kit Code Scanner
(`com.google.android.gms:play-services-code-scanner`), which runs inside Google
Play Services. It is proprietary software, is not part of this project's source
code and is subject to Google's terms. The `free` build variant does not use it
and does not depend on Google Play Services.

## Icons

The phone handset icon is based on Feather Icons (MIT License),
https://github.com/feathericons/feather.
