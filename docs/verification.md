# Prototype verification — 2026-10-23

## Climate setpoint integration — 2026-10-31

- Optional front-left/right setpoints and evaporator parser/UI added. Cabin
  measurement and climate running state remain independent.
- Actual LCD C serializer fixture climate-setpoints.json is1004 bytes. It follows
  native2AD/350 parsing plus the independent processor and produces20/21°C and
  5.5°C evaporator, cabin unknown. JVM tests verify these exact bytes.
- Offline app/test APK builds and lint passed. **16 JVM tests**,0 failures;
  lint0 errors/one pre-existing resource-configuration warning. Version0.2.0/code2.
- Debug APK:30,330,197bytes, SHA-256
  `08ef4ac836e2d069432a51dc38dc6d18ffe8c0881f977d9d0e5d035d99f96ed9`.
  Path:app/build/outputs/apk/debug/app-debug.apk.
- With Samsung A55 DEVICE_SERIAL connected for installation,
  `adb install -r` succeeded and MainActivity opened. Package inspection confirms
  version0.2.0/code2; existing application data/settings are retained.
  New LCD firmware is prepared without upload; physical setpoint verification
  remains pending until matching firmware is installed.
- Evidence:artifacts/climate-build.log,climate-contract-test.log; sibling
  opel_dash/docs/climate.md and climate-tests.log.

## BSI auxiliary battery integration — 2026-10-31

- Added optional independent auxiliary SOC/voltage decoding and Home/details UI.
  Recorded CAN sample `00 8E A3 00 C8 09` decodes to **12.6 V /81.5%** in LCD firmware.
  The 863-byte compact native C-codec fixture is read by the Kotlin contract test.
- Offline debug app/test builds succeeded; **15 JVM tests** passed. Android lint:
  **0 errors, 1 existing ObsoleteSdkInt resource warning**. Version remains0.2.0,
  code2. SHA-256: `146a6ebd6719d1b61554a53aac687f593cf8292f60c2d271a47337f1b2125022`;
  APK **30,329,289 bytes** at `app/build/outputs/apk/debug/app-debug.apk`.
- Installed with `adb install -r` on Samsung A55 DEVICE_SERIAL, preserving saved
  settings. Read-only `LocalConnectionDeviceTest` passed again (1 instrumentation
  test), querying192.168.1.220 and opel-dash.lan through the existing VPN policy.
- The new LCD firmware is built but **has not been installed**. This verifies
  native decoding, the wire contract and phone connectivity/backward compatibility;
  physical auxiliary readings and automatic LCD sleep/wake await firmware testing.
- Evidence: ignored `artifacts/bsi-build.log`, `bsi-contract-test.log`,
  `bsi-device-test.log`; firmware evidence in sibling opel_dash/docs/bsi-verification.md.

## Non-bypassable VPN and installed LCD gateway — 2026-10-31

- Reproduced on the connected Samsung A55, Android 16 / API36: the existing app
  reported `Binding socket to network 1086 failed: EPERM`. The device had a
  Tailscale VPN with `bypassable=false` applying to the app UID. INTERNET and
  ACCESS_NETWORK_STATE were granted; this failure occurred at explicit network
  binding, before gateway UDP exchange.
- `openLocalConnection` excludes VPN entries from physical LAN selection (a VPN
  can also advertise WIFI). Direct Wi-Fi/Ethernet binding remains the normal path.
  On EPERM with an active VPN, close that socket and create a fresh, unbound one
  using Android's system VPN/routing policy. Require a physical LAN and local
  unicast target throughout; preserve saved gateway/session checks. Recent
  diagnostics records the selected path. Other failures retain normal handling.
- Offline Gradle debug app/test APK builds, all **14 JVM tests**, and lint passed
  (**0 errors, 1 existing resource-configuration warning**). Version stays 0.2.0,
  code2. Installed the debug APK over the existing application with `adb install -r`;
  saved settings were retained. The old APK is backed up under ignored artifacts.
- The new opt-in `LocalConnectionDeviceTest` passed on the physical phone with
  **VPN active=true**. It observed the EPERM/system-route fallback and successfully
  queried `info.get` then `state.get` through both **192.168.1.220** and
  **opel-dash.lan**, UDP42840, identity `ecmpilot-30eda0ac2328`. It does not reset
  application settings, change VPN configuration or send any vehicle commands.
- Reopened the ordinary application and inspected its UI: **Connected · Updated
  just now**, SOC/range/outside readings populated, controls unavailable as
  advertised by the current monitor-only firmware. Gateway UDP RX/TX counters
  advanced together and reported no drops in the final sample. This validates
  the connection, not every CAN signal's physical interpretation.
- Independent LCD diagnostics: TCP23 accepted the SavvyCAN-style startup sequence,
  bus parameters (one listen-only 125 kbit/s bus), time sync and repeated keepalives
  in a 12-second session. Initial physical CAN count was zero while the car slept.
  After the vehicle was woken and SavvyCAN was connected, device diagnostics reported
  CAN_RX_COUNT=19065, GVRET_SENT=14728 and zero bus errors. No LCD firmware edits
  were needed. The final LCD sample still had DASH_DEMO_ENABLED=1; phone readings
  therefore reflected local replay until that setting is disabled.

APK installed at the VPN-only checkpoint: `app/build/outputs/apk/debug/app-debug.apk`, **30,096,321
bytes**, SHA-256 `0a17c22a43691b12491eef603766c6aa46a3bfb1191f0c5251ad406c55d81447`.
Ignored evidence: `artifacts/vpn-build.log`, `vpn-device-test.log`,
`vpn-device-routing.log` and `vpn-phone-window.xml`. The non-VPN direct-bind path
was not retested by toggling the user's VPN during this session.

## ESP32 varstore backend integration — 2026-10-27

Android 0.2.0 / gateway firmware 0.3.0. Offline Gradle `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` succeeded. All 14 JVM tests passed (11 codec/model and 3 UDP transport), including decoding fixtures generated by the gateway's real C codec. Explicit `varstore-test` capabilities enable only advertised test operations and retain lab-control opt-in; unknown/unverified ordinary backends remain disabled. Lint reported zero errors and three dependency-version warnings.

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`, 30091309 bytes, SHA-256 `3e53112604ae7747719d5b481928e1a99ad14d10d612a38ee52a022482190dba`. It was not installed during this change. The updated app and firmware have not yet been tested together on the physical phone/ESP. The gateway repository records its native codec/varstore/loopback tests and firmware image validation separately.


Version 0.1.0 (`cz.ecmpilot`, version code 1), debug build. Minimum API 28; target/compile API 37. Kotlin / Compose / Material 3, Android Gradle Plugin 9.2.1, Gradle 9.4.1, Android Studio JBR.

## Results

| Check | Result |
| --- | --- |
| Android debug APK and instrumentation APK | Built successfully |
| JVM protocol and socket tests | 12 passed, 0 failures |
| Python gateway simulator tests | 10 passed, 0 failures |
| Android lint | 0 errors, 6 warnings in the local workspace |
| Native UI / integration tests | 5 passed, 0 failures, 0 skipped on Android 17 / API 37 x86_64 |
| PowerShell development helper | Syntax parsed successfully |

The lint warnings concern five pinned dependency versions with newer releases available and an empty, obsolete local resource directory left after moving the launcher icon. Empty directories are not part of the Git source tree. Dependencies are deliberately pinned to the working toolchain; update notices are not suppressed.

The isolated emulator used a 1080 × 2400 display at 420 dpi. Native tests covered the Home screen, climate start and deferred charging, asleep/partial-data states, rejected and unconfirmed commands, dark appearance, and configuring a live local UDP connection through the settings UI. Screenshots were captured from the actual native app, with simulated vehicle readings.

## Lost acknowledgement integration case

The foreground Python simulator was bound to `127.0.0.1:42840` with `--drop-first-command-reply`. The Android emulator connected to `10.0.2.2:42840`, with local-network permission granted and experimental controls enabled.

The simulator recorded this sequence for one request ID:

```text
climate.set -> accepted | executions=0
Dropped first write reply after recording acceptance
climate.set -> accepted | executions=0
command.get -> accepted | executions=0
command.get -> accepted | executions=0
command.get -> succeeded | executions=1
```

The app displayed success only after the matching successful result, then observed climate state `on`. This confirms retry/deduplication behavior between this client and this simulator; it is not a guarantee of exactly-once physical vehicle actions.

## Artifact

`app/build/outputs/apk/debug/app-debug.apk`

SHA-256:

```text
44e4369bec4b844d1be5e269c8b90c297d110893f2a782c27c97406eff4ca279
```

This is a debug-signed development APK, not a production or Play Store release. No release key, account, cloud service or firmware is required for demo mode. See [testing.md](testing.md) for reproduction and simulator setup.

## Not verified

- Physical phones, other Android API levels, OEM battery-management behavior and LAN router variations.
- ESP32 hardware, electrical installation, CAN signals, real Corsa-e commands and gateway power/sleep behavior.
- Authenticated pairing/transport, which is not implemented in the experimental `lab-plain` profile.
- Play Store packaging/signing/publication and MQTT/Home Assistant integration.

The app's protocol and UI are ready for a compatible gateway implementation. Simulated capability flags do not establish vehicle compatibility.
