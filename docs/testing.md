# Development and simulator testing

The Android app is a native Compose implementation. Its initial source is in-process demo data. No ESP32 or vehicle is needed to try the UI.

## Automated checks

`ObdProtocolTest` covers adapter scan/save retransmission, strict paged HSCAN
parsing, individual observation ages, missing values and cache clearing on boot
change. `ObdUiTest` provides a demo-only adapter selection and Vehicle details
scenario. On 2026-11-13, the Samsung A55 passed this scenario, covering scan,
selection/save, all three detail groups and three-decimal cell voltage. Original
connection settings and the temporary keep-awake setting were restored. Evidence:
`artifacts/obd-device-test.log` and `artifacts/obd-*-demo.png`. The same run passed
82 JVM tests and Android lint; the companion firmware passed 391 host tests.
The updated scenario checks Home's average battery temperature, one-decimal
temperature formatting, three-decimal cell voltages and removal of candidate
labels. Evidence: `artifacts/temperature-device-test.log` and
`artifacts/temperature-obd-*-demo.png`.

[UDP correlation and recovery](udp-correlation.md) adds actual loopback tests for
lost replies, 3.1 s delay, incorrect correlation and a late old reply after a
read timeout. These tests need no emulator, phone or vehicle.

See [automatic refresh](auto-refresh.md) for the gateway-owned timer settings and
demo-only editor checks. The shared sheet dismissal threshold is now 100 physical
pixels, with velocity-based dismissal disabled; gesture test distances use pixels.

`OffPeakChargingTest` covers schedule configuration, production-reply parsing,
maximum packet size and vehicle-clock rollover without phone timezone/DST rules.
`ChargeScheduleUiTest` exercises the editor and Home clock on the physical phone
in demo mode. `VehicleActionFeedbackTest` covers automatic delayed-mode/time saves,
invalid time suppression, progress and failures; `AutoRefreshUiTest` covers the
shared limiter switches and disabled slider. `GitHubScreenshotsTest` reproduces
the [dated screenshot gallery](screenshots/README.md), including the new controls.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\dev.ps1 check
python -m unittest discover -s tools -p 'test_*.py' -v
```

The JVM checks cover strict/duplicate JSON parsing, invalid encoding/size/values, freshness, snapshot ordering and boot changes, capability/security gating, non-optimistic demo acknowledgement, actual loopback UDP retry bytes, unrelated/oversized responses and gateway identity mismatches. The standard-library Python tests cover simulator deduplication, expiry, boot checks, command generations, stop supersession, unknown results and client-scoped results.

The helper disables persistent Kotlin compilation daemons and limits Gradle workers. `--no-daemon` uses only a single-use Gradle process. Build outputs, local SDK configuration and development device files stay ignored.

## ESP32 varstore backend

Use firmware 0.3.0 from `ecmpilot-gw` and Android 0.2.0. Seed its HTTP varstore with the gateway repository's `tools/udp_bench.py`, connect the app to its LAN IP/42840, opt into experimental local controls and Refresh. Confirm TEST is shown. A phone request updates gateway `request.*` signals but does not change observations; force matching climate/mode/time readback in the web tool to complete it. No CAN device is needed. In this backend Refresh is a simulated explicit action; actual INFO control is described in [vehicle refresh](vehicle-refresh.md). Test timeout and stale/invalid values too. This physical phone-to-board path has not been exercised in the workstation checks.

JVM tests decode the actual C codec's compact response fixtures under `app/src/test/resources/gateway`, in addition to checking explicit test-backend capability gating.
`aux-battery.json` is an 863-byte compact `state.get` reply produced by the LCD
firmware's native C serializer after decoding BSI396 sample `00 8E A3 00 C8 09`.
It reports auxiliary 12.6 V/81.5%, independent of traction data. Keep the fixture
compact so it obeys the production 1024-byte datagram cap. Tests cover missing,
stale and out-of-range auxiliary readings and compatibility with older replies.

## In-process demo scenes

Settings → Demo provides charging, climate running, asleep, offline, partial data, monitoring-only, rejected-command and unconfirmed-result cases. Start/stop climate and immediate/deferred charging demonstrate request/acceptance/vehicle confirmation separately. They do not transmit to the network.

Closing/backgrounding the activity stops normal polling after a five-second grace period; unsent UI actions are discarded immediately. Foreground return re-reads capabilities/state and queries a saved ambiguous command ID. Telemetry is retained only in memory in this version; after process recreation, missing readings stay unavailable until received again.

## UDP simulator

Python 3, standard library only. Run it in a foreground terminal and stop with Ctrl+C:

```powershell
python tools\gateway_simulator.py
```

Default bind: `127.0.0.1:42840`. For a standard Android emulator, configure the app with **10.0.2.2 / 42840**, which addresses the host machine. A physical phone needs the development computer's LAN address and an explicit simulator LAN bind:

```powershell
python tools\gateway_simulator.py --bind 192.168.1.80 --port 42840
```

Use the computer's actual local IP. Windows Firewall must allow the chosen local UDP traffic. The simulator cannot access CAN hardware or the car; its reported verified capabilities apply to simulated functions only.

In the app choose Local gateway, grant local-network access if required, and opt into experimental local controls. The simulator identifies as `simulated-corsa`; forget the saved gateway before changing from it to a different gateway identity. Android sockets normally bind to physical Wi-Fi/Ethernet. With an active VPN, an `EPERM` rejection of that bind selects a fresh socket with Android's normal VPN/routing policy. Physical LAN presence and local-unicast address validation are still required. VPN restrictions can still prevent delivery. Recent diagnostics records the selected route.

Useful reproducible cases:

```powershell
python tools\gateway_simulator.py --drop-first-command-reply --max-runtime 120
python tools\gateway_simulator.py --scenario readonly
python tools\gateway_simulator.py --scenario asleep
python tools\gateway_simulator.py --scenario unconfirmed
```

`--max-runtime` automatically shuts down a test instance; otherwise Ctrl+C is required. The simulator prints command phases and executed workflow count so a lost-ack retry can be checked for a single execution. Only use one instance per bind address/port. There is no installed service or scheduled task.

## Android instrumentation

`PilotAppTest` exercises native screens and simulator communication on an Android 17 emulator. It resets only this app's settings/demo scenario, never another app's state. The UDP integration case is opt-in via instrumentation arguments; without them it performs no UDP write test.

With an emulator running and a simulator on host port 42840:

```powershell
.\gradlew.bat --no-daemon :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.udpHost=10.0.2.2' '-Pandroid.testInstrumentationRunnerArguments.udpPort=42840'
```

The tests write screenshots into this app's external files directory. Gradle's connected-test cleanup may uninstall the app and remove that directory. To retain screenshots for inspection, install both generated APKs and run the desired test methods directly before pulling the files (use your emulator's serial):

```powershell
adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w -r -e class 'cz.ecmpilot.PilotAppTest#a_demoControlAndSheets,cz.ecmpilot.PilotAppTest#d_darkTheme' cz.ecmpilot.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5580 pull /sdcard/Android/data/cz.ecmpilot/files/. artifacts/screens
```

The local development helper's `check` command deliberately runs host JVM tests/lint only, without starting or modifying any Android device.

### Read-only gateway check with a VPN

`LocalConnectionDeviceTest` does not use the UI test's settings reset. It exercises
the actual connection factory and protocol under the phone's current VPN policy,
querying only `info.get` and `state.get`. It does not change VPN/settings or send
vehicle commands. Without an explicit `gatewayHosts` argument it skips networking.
After building/installing the debug and androidTest APKs manually, run:

```powershell
adb -s YOUR_DEVICE shell am instrument -w -r -e class cz.ecmpilot.LocalConnectionDeviceTest -e gatewayHosts '192.168.1.220,opel-dash.lan' cz.ecmpilot.test/androidx.test.runner.AndroidJUnitRunner
```

Test both VPN active (including non-bypassable mode) and VPN inactive. On active
VPNs rejecting explicit LAN binding, `PilotNetworkTest` logs the system-route
fallback; normal Wi-Fi logs the direct local binding. Check gateway identity and
both replies for the hostname and numeric address. Run these opt-in checks only
against the intended gateway; unlike `PilotAppTest`, they preserve saved settings.

## Implementation limits

- UDP schema v0, simulator and local client are implemented; firmware/CAN integration and real-car command support are not.
- Current security profile is explicit unauthenticated `lab-plain`; device pairing and authenticated transport remain unimplemented.
- Runtime controls require capability verification/confirmation flags, an opted-in live connection and fresh awake vehicle/domain observations. The app checks these again immediately before submission; the firmware must enforce its own current prerequisites, expiry, deduplication and domain-generation rules.
- Adjustable temperature/duration, charge targets/current limits, pause/resume, discovery, MQTT, Wi-Fi provisioning and Android Auto are not implemented.
- A native deferred setting is a start time in the gateway-reported timezone, not a guaranteed finish time. Its persistence/recurrence semantics must be verified in the future firmware before advertising that capability.
- Device/network failures produce unconfirmed results; this is not a guarantee of exactly-once physical effects. Lost gateway result history is not a reason to send the operation again automatically.

climate-setpoints.json is a1004-byte compact response from the actual LCD C codec,
after native2AD/350 decoding and VehicleDataTick:20°C left,21°C right,5.5°C
evaporator, cabin unknown. JVM tests decode this fixture without prettifying it
beyond the1024-byte protocol cap.
