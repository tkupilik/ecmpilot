# Changelog

Notable changes to eCMPilot will be documented here.

## [Unreleased]

### Added

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
