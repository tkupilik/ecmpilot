# Changelog

Notable changes to eCMPilot will be documented here.

## [Unreleased]

- Prefer Wi-Fi/UDP and start BLE discovery only when the gateway is unreachable
  over the network. While using BLE, probe UDP in parallel every five seconds and
  return automatically, releasing BLE at a request boundary. Preserve the original
  request/correlation identity when an exchange changes transport. Add foreground gateway discovery, Android
  Bluetooth permissions and a Bluetooth connection status label. Automatic
  discovery and phone-to-GW connection were confirmed on hardware on 2026-11-13.

- Explain the phone/GW/optional ELM connections for everyday use, including
  Wi-Fi priority and Bluetooth fallback. Keep planned dashboard UDS services
  distinct from the implemented phone transport and HSCAN snapshots.

- Show average battery temperature above outside temperature on Home; format
  all temperatures with one decimal place. Keep cell voltages at three decimals.
- Remove candidate labels from the vehicle-validated HSCAN readings.

- Add gateway-owned BLE OBD adapter discovery and selection in Gateway settings.
- Show 27 HSCAN/OBD observations in Vehicle details, preserving each
  reading's age, three decimal places for cell voltages, and stale-value shading.
- Add bounded, paged OBD transport and retry-safe adapter configuration, with an
  in-process demo for discovery, saving and cached observations.

## [0.3.0] - 2026-11-11

First signed Google Play distribution build, with an AAB upload artifact and a
separate release APK. Package `cz.ecmpilot`, version code 3.

### Added

- Local gateway discovery over mDNS, with manual hostname/IP fallback.
- Weekly charging pause schedules with up to eight high-tariff periods,
  overnight intervals and gateway-managed resume after its own pause.
- Vehicle date and time on Home, synchronized from BSI through the gateway.
- Charge limit controls in Charging, alongside gateway automatic refresh and
  inactivity sleep configuration.
- Updated physical-phone demo gallery and English installation/feature guides.

### Changed

- Native delayed-charging switches save immediately; valid time edits save
  after a short pause without an Apply button. Progress remains in the dialog.
- Charge limit sits above Schedules, with a persistent grey slider when off;
  its Gateway section follows automatic deep sleep.
- Home presents battery/range, preconditioning, charging and doors/locks, with
  separate Trips, Vehicle details and Gateway screens.

### Fixed

- Completed command errors no longer reopen after restarting the app.
- Correlated UDP retries preserve action identity and avoid duplicate vehicle
  commands during packet loss or delayed replies.
- Stable modal-sheet scrolling and a 100 px dismissal threshold without the
  short-fling dismissal shortcut.

## Development history before 0.3.0

- Add gateway-owned **Charging → Schedules**: eight weekly high-tariff pause
  periods, explicit Apply/Reload, overnight intervals and persistent pause undo.
- Show vehicle date/time on Home, synchronized from BSI via GW with no timezone
  or DST conversion. The GW keeps counting while BSI sleeps.
- Save native delayed-charging switches immediately and valid time edits after
  an 800 ms pause; remove their Apply button and keep the dialog open with progress.
- Put Charge limit directly above Schedules, with an immediate enable switch
  and an always-visible disabled slider when off. Move its Gateway section below
  automatic deep sleep.
- Add local mDNS gateway discovery and discard completed persisted receipts so
  old errors do not reopen after app restart; pending recovery remains supported.
- Refresh the physical-phone demo gallery for these controls and retain the
  dated live-vehicle gallery separately.

- Replace the launcher battery icon with the selected ivory car/amber lightning
  design on charcoal, with separate adaptive background and monochrome artwork.
- Move Charge limit help to Gateway settings and offer 30–100% on its Charging
  slider. Simplify Preconditioning by moving outside temperature beside Prepare
  and removing front setpoints. Remove the technical consumption-history subtitle.
- Apply reviewed wording items 1 and 2 only: remove the doors/locks timing
  explanation and keep only units below consumption history. Other proposed text
  changes remain unapproved.
- Add gateway Charge limit enable (dependent on automatic refresh) and a saved
  target slider in Charging, default 80%. Show the gateway observer status and
  explain the stored delayed-charging start time; no phone-side timer or direct
  vehicle command is sent when editing settings.
- Explain automatic refresh in everyday language and show its status beside the
  gateway address at the top of the Gateway sheet.

- Move automatic refresh settings from the phone Connection sheet into a new
  Gateway row/sheet in the Home menu. Keep connection and appearance under the
  Settings gear.

- Correlate complete operations with a session-scoped uint32 counter, including
  preflight, retries, receipt lookup and diagnostics. Match logical receipts
  independently of their original message ID and persist pending correlation.
- Tolerate isolated UDP loss and multi-second RTT: retry reads, use 5/6 s reply
  budgets and a 15 s offline grace period, retain the socket/data on a transient
  timeout and poll ambiguous original actions within their existing 35 s window.

- Add gateway-owned automatic refresh settings in Connection: enable, standby
  and charging periods (60/5 min defaults) and minimum 12 V SOC (50%). Apply uses
  persistent revision-checked GW settings; polling and connection remain passive.

### Changed

- Show progress for every vehicle action in the Refresh button, return silently
  to Refresh on success, and display failures/unconfirmed results only in an
  error popup. Remove inline command cards. Use a 100 px shared sheet drag threshold
  with the velocity shortcut disabled. Rename Driver SOC to SOC
  in Vehicle details.
- Stabilize all shared modal sheets after upward drags by separating status-bar
  clearance from offset-dependent content insets and removing duplicate IME
  padding. Collapse disabled preconditioning plans to compact headers while
  retaining explicit Edit/Set up access.
- Add four vehicle-native preconditioning plan editors and a master activation
  switch, with passive readback, explicit Apply, capability gating and conflict
  checks against edits made by another client.
- Use the selected top-view hatchback artwork for Doors & locks. Neutralize the
  dark palette, make the delayed switch transparent off/blue on, and use a
  continuous SOC bar. Keep the charging time editor always visible.
- Replace the dense Home cards with a compact SOC/range panel, preconditioning,
  charging and door/lock status shortcuts, and Trips/Details navigation rows.
- Add a top-view doors/boot screen, preserving unknown and stale states.
  Add Trips & consumption for the two vehicle counters and six history slots.
- Read capability-gated passive vehicle details separately from the 1 KiB status
  envelope; remove unsupported INFO HV/cabin/pack-temperature placeholders.

- Enable advertised experimental INFO preconditioning Start/Stop. Let the gateway
  wake BSI with stale/asleep observations, keep the button disabled until the
  report or30-second timeout, and display only the BSI-reported state. Preserve
  the existing explicit-control opt-in; no fixed10-second lockout is used.

- Show the actual BSI CAN PowerManagement phase and move last vehicle data age
  from the battery card into the connection row. Remove the refresh warning
  paragraph and dismiss successful confirmations automatically after 3 seconds.
- Use Start/Stop preconditioning and Delayed charging in the English UI,
  following terminology in Opel's English e-Remote Control FAQ.
- Keep startup, settings reconnect and polling passive. Add explicit Refresh
  vehicle with gateway command receipts.
- Combine battery/charging into one card with grey stale data, SOC observation age,
  delayed-mode switch/time and blue fresh SOC for delayed charging.
- Support the experimental INFO CAN backend and independent schedule freshness,
  without treating schedule mode as proof of current charging power/flow.

### Fixed

- Handle Android `Network.bindSocket` EPERM with an active non-bypassable VPN:
  retry using a fresh socket with the system VPN/routing policy, preserving the
  physical LAN requirement, local address checks and gateway identity validation.
  Exclude VPN entries from physical Wi-Fi/Ethernet selection and log the selected
  route in Recent diagnostics. Add opt-in read-only device verification.

### Added

- Optional front-left/right selected climate temperatures and evaporator reading,
  independent of measured cabin temperature and climate running state. Native
  LCD C-codec fixture verifies the combined2AD/350 mapping in Kotlin.

- Separate 12 V battery SOC and voltage on Home and Vehicle details, from optional
  aged `aux_battery_soc_pct` / `aux_battery_voltage_v` readings. HV telemetry keeps
  its own fields. Native LCD C-codec fixture and JVM tests verify the binding.

- Android 0.2.0 support for the explicit ESP32 `varstore-test` UDP backend: TEST label, lab opt-in, advertised test-only operations and distinct confirmation messages.
- C-generated gateway response fixtures decoded by JVM tests; normal unverified/unknown backends remain disabled. Refresh now reloads capabilities, including a changed test-mode setting.

- Non-isolated CAN gateway requirement and replacement-kit shortlist, including CANipulator V2, WiCAN-USB, input-voltage limits, low-power controls and unresolved parked-current evidence.
- Waveshare ESP32-S3-CAN-2CH-U schematic and power review, including fixed isolation losses, explicit consumption assumptions, firmware limitations and a bench measurement plan for the 10 mA parked-current target.
- Native Android 0.1.0 prototype with Compose Home, Climate/Charging sheets, connection settings, local-network permission handling, theme selection and diagnostic export.
- In-process demo scenes plus a local UDP client with capability gating, freshness, same-ID retries, gateway identity/boot tracking and saved command-result recovery.
- Standalone standard-library UDP gateway simulator, host protocol/transport tests and native Android UI/integration tests.
- Android control-app design with Home, Climate/Charging sheets, comparable-app references, capability-dependent features and stale/offline/command-result behaviour.
- Standalone interactive HTML mockup using simulated values, including dark mode and unconfirmed/rejected command scenarios.
- UDP v0 design with request/status messages, command correlation, expiry, deduplication and a future local pairing/authentication boundary; MQTT deferred behind a transport-independent interface.
- Cross-check of the existing Android UDS profile against PSA-RE, with eCAN/diagnostic naming clarification and references to the sibling Android project's complete DID catalog.
- PSA-RE reference in the README for vehicle architecture and CAN research.
- Vehicle wiring investigation with consolidated 84C4 eCall connector tables, CAN architecture diagrams, gateway access candidates, power/ground routing, source references, and public remote-command research leads.
- Initial README describing the Android app's goals and planned local network architecture.
- Planned ESP32 CAN gateway and MQTT integration with Home Assistant.
- Proposed separation of Android and firmware repositories, linked by a versioned protocol contract.
- Development roadmap, initial Corsa-e focus, and open hardware and protocol decisions.
- Planned open-source distribution and paid Google Play build with equivalent functionality.
- BSD-2-Clause license for permissive reuse, modification, and redistribution, including commercial and closed-source use.

A development APK can be built from the Android prototype. No production application or firmware release is available yet.
