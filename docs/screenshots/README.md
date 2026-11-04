# eCMPilot screenshots

Captured on a **physical Samsung Galaxy A55** on **2026-11-04**, using the installed **eCMPilot 0.2.0** Android build. Images are lossless captures of the rendered app, without the phone's notification/status bar. They are not UI mockups.

## Live vehicle

Connected to the installed `ecmpilot-gw` on the owner's Corsa-e. The owner started preconditioning before capture. Home reports **91% SOC**, **262 km estimated range**, **9.0 °C outside**, **87.5% 12 V SOC**, BSI Normal and preconditioning Running. These are vehicle-reported observations at capture time.

The screenshot run only navigated and made passive reads. It did not press Refresh, Start/Stop, Apply, change the charge limit or save gateway/vehicle settings. Independent enabled gateway automation is separate from this capture run.

<table>
<tr>
<td align="center"><img src="live-home.png" width="250" alt="Live home with 91 percent SOC and active preconditioning"><br>Home · preconditioning running</td>
<td align="center"><img src="live-preconditioning.png" width="250" alt="Live preconditioning with reported running status"><br>Preconditioning · reported active</td>
<td align="center"><img src="live-charging.png" width="250" alt="Live charging screen showing unplugged and stored start time"><br>Charging · unplugged, stored start 03:00</td>
</tr>
<tr>
<td align="center"><img src="live-doors.png" width="250" alt="Live doors and locks showing closed panels and locked status"><br>Doors &amp; locks · reported closed/locked</td>
<td align="center"><img src="live-trips.png" width="250" alt="Live vehicle trip counters and consumption slots"><br>Trips · reported counters/history</td>
</tr>
</table>

The live trip screen is a record of the decoded values, not independent validation of each consumption field. The charging page's stored schedule/limit is also separate from whether energy is currently flowing. Do not interpret a screenshot as a guarantee of a vehicle action's physical effect.

## Demo: functions and navigation

These screenshots use the app's **in-process demo gateway**. No demo action opens a network socket or controls the vehicle. Values illustrate the UI; they are not extra measurements from the live session above. The capture restores the phone's original connection/appearance settings afterwards.

<table>
<tr>
<td align="center"><img src="demo-home-light.png" width="250" alt="Demo home in light mode"><br>Home · charging</td>
<td align="center"><img src="demo-charging.png" width="250" alt="Demo charging screen with charge limit slider"><br>Charging · 80% Charge limit</td>
<td align="center"><img src="demo-delayed-charging.png" width="250" alt="Demo delayed charging after simulated confirmation"><br>Delayed charging · confirmed by demo</td>
</tr>
<tr>
<td align="center"><img src="demo-preconditioning.png" width="250" alt="Demo preconditioning with Start action and outside temperature"><br>Preconditioning · ready to start</td>
<td align="center"><img src="demo-schedules.png" width="250" alt="Demo weekly schedule editor with weekday selectors"><br>Schedule editor · 07:30 Mon/Wed/Thu</td>
<td align="center"><img src="demo-doors.png" width="250" alt="Demo overhead car with individual open doors and boot"><br>Doors &amp; locks · three open panels</td>
</tr>
<tr>
<td align="center"><img src="demo-trips.png" width="250" alt="Demo Trip 1 and Trip 2 plus consumption history"><br>Trips &amp; consumption</td>
<td align="center"><img src="demo-details.png" width="250" alt="Demo vehicle measurements and connection diagnostics"><br>Vehicle details</td>
<td align="center"><img src="demo-gateway.png" width="250" alt="Demo gateway automatic refresh periods and charge limit enablement"><br>Gateway · refresh and Charge limit</td>
</tr>
<tr>
<td align="center"><img src="demo-settings.png" width="250" alt="App connection and demo scene settings"><br>App settings · demo source</td>
</tr>
</table>

## Demo: dark mode and failure states

<table>
<tr>
<td align="center"><img src="demo-home-dark.png" width="250" alt="Demo home in dark mode with active preconditioning"><br>Dark Home</td>
<td align="center"><img src="demo-preconditioning-dark.png" width="250" alt="Demo preconditioning in dark mode"><br>Dark preconditioning</td>
<td align="center"><img src="demo-offline.png" width="250" alt="Demo offline gateway without invented vehicle readings"><br>Gateway offline</td>
</tr>
<tr>
<td align="center"><img src="demo-request-error.png" width="250" alt="Demo rejected vehicle request with error explanation"><br>Rejected request · descriptive error</td>
</tr>
</table>

## Reproduce captures

The test source is [GitHubScreenshotsTest.kt](../../app/src/androidTest/java/cz/ecmpilot/GitHubScreenshotsTest.kt). Use an unlocked physical device with both the debug app and test APK installed. Select the intended device explicitly; do not use an arbitrary attached phone.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w -r -e class 'cz.ecmpilot.GitHubScreenshotsTest#captureDocumentationGallery' cz.ecmpilot.test/androidx.test.runner.AndroidJUnitRunner
adb -s DEVICE_SERIAL pull /sdcard/Android/data/cz.ecmpilot/files/ CAPTURE_DIRECTORY
```

On Windows, use `gradlew.bat`. The demo run writes `github-*.png`; these are copied byte-for-byte to this directory with `demo-` filenames. The visible diagnostic tail is filled with demo connections so a real gateway identifier is not published in the details image.

Live capture is a **separate opt-in** method. First configure/connect the app to the intended real gateway yourself, then run:

```sh
adb -s DEVICE_SERIAL shell am instrument -w -r -e class 'cz.ecmpilot.GitHubScreenshotsTest#captureLiveVehicleGallery' -e captureLive true cz.ecmpilot.test/androidx.test.runner.AndroidJUnitRunner
```

It requires an existing UDP connection and only opens Home, Preconditioning, Charging, Doors and Trips. Files are named `github-live-*.png`; copy them as `live-*.png`. Without the opt-in argument the live method is skipped. The app/firmware versions and hardware validation status must be recorded again for future galleries.

[Capture manifest and image hashes](capture-manifest.json). [Back to project](../../README.md).
