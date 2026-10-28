# Development and simulator testing

The Android app is a native Compose implementation. Its initial source is in-process demo data. No ESP32 or vehicle is needed to try the UI.

## Automated checks

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\dev.ps1 check
python -m unittest discover -s tools -p 'test_*.py' -v
```

The JVM checks cover strict/duplicate JSON parsing, invalid encoding/size/values, freshness, snapshot ordering and boot changes, capability/security gating, non-optimistic demo acknowledgement, actual loopback UDP retry bytes, unrelated/oversized responses and gateway identity mismatches. The standard-library Python tests cover simulator deduplication, expiry, boot checks, command generations, stop supersession, unknown results and client-scoped results.

The helper disables persistent Kotlin compilation daemons and limits Gradle workers. `--no-daemon` uses only a single-use Gradle process. Build outputs, local SDK configuration and development device files stay ignored.

## ESP32 varstore backend

Use firmware 0.3.0 from `ecmpilot-gw` and Android 0.2.0. Seed its HTTP varstore with the gateway repository's `tools/udp_bench.py`, connect the app to its LAN IP/42840, opt into experimental local controls and Refresh. Confirm TEST is shown. A phone request updates gateway `request.*` signals but does not change observations; force matching climate/mode/time readback in the web tool to complete it. No CAN device is needed. Test timeout and stale/invalid values too. This physical phone-to-board path has not been exercised in the workstation checks.

JVM tests decode the actual C codec's compact response fixtures under `app/src/test/resources/gateway`, in addition to checking explicit test-backend capability gating.

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

In the app choose Local gateway, grant local-network access if required, and opt into experimental local controls. The simulator identifies as `simulated-corsa`; forget the saved gateway before changing from it to a different gateway identity. Android sockets are bound to an available local Wi-Fi/Ethernet network, so a phone's mobile-data default route is not used for gateway communication. Public/multicast addresses are rejected by this local-only client.

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

## Implementation limits

- UDP schema v0, simulator and local client are implemented; firmware/CAN integration and real-car command support are not.
- Current security profile is explicit unauthenticated `lab-plain`; device pairing and authenticated transport remain unimplemented.
- Runtime controls require capability verification/confirmation flags, an opted-in live connection and fresh awake vehicle/domain observations. The app checks these again immediately before submission; the firmware must enforce its own current prerequisites, expiry, deduplication and domain-generation rules.
- Adjustable temperature/duration, charge targets/current limits, pause/resume, discovery, MQTT, Wi-Fi provisioning and Android Auto are not implemented.
- A native deferred setting is a start time in the gateway-reported timezone, not a guaranteed finish time. Its persistence/recurrence semantics must be verified in the future firmware before advertising that capability.
- Device/network failures produce unconfirmed results; this is not a guarantee of exactly-once physical effects. Lost gateway result history is not a reason to send the operation again automatically.
