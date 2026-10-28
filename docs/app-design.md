# Android control app: first design

Design proposal, 2026-10-23. The initial native implementation now lives in `app/`: Home, Climate/Charging sheets, connection settings, demo scenes and the experimental UDP client. Vehicle controls remain unverified on hardware; see [testing and implementation limits](testing.md). This document records the product direction, including later features. The [interactive mockup](mockups/control-app.html) remains a separate simulated design reference and never opens a network connection. The [static companion wireframe](mockups/screens.svg) shows Home and both sheets. See [UDP contract](protocol/udp-v0.md) and [vehicle wiring research](../wiring.md).

## Product direction

A small phone app for answering three questions: **How is the car doing? Can I prepare the cabin? What is charging doing?** One home screen, two control sheets and a connection/details screen. Start with one vehicle and one known local network. MQTT remains a future firmware integration, not a dependency of the app. Android Auto is outside this app's initial scope; `android_dash` already serves the driving dashboard use case.

Use Kotlin, Jetpack Compose and Material 3, following the successful phone-side patterns in `android_dash`: a shared state repository, explicit demo/live modes, freshness-aware values and readable errors. The initial implementation supports API 28 and later, targets API 37, and uses its own application ID, `cz.ecmpilot`.

English UI copy, system light/dark themes, a restrained amber accent, large numbers and 48 dp touch targets. Display connection/freshness as text as well as colour. Avoid account creation, a map, decorative vehicle animations and a dense instrument panel in the first version.

## What comparable apps offer

Official documentation checked on 2026-10-23; exact availability depends on model, equipment and region. These are product references, not proof of capabilities on our car.

| Reference | Relevant features | What to borrow |
| --- | --- | --- |
| [Tesla Model 3 mobile app](https://www.tesla.com/ownersmanual/model3/en_gb/GUID-F6E2CD5E-F226-4167-AC48-BD021D1FFDAB.html) | Battery/charging information, range, cabin climate, odometer; additional access/location features. | Clear vehicle summary and direct climate/charging access. Leave access controls and location out of our first scope. |
| [Tesla scheduled departure](https://www.tesla.com/support/summer-driving-tips) | Coordination of charging and cabin preparation around departure. | A useful later direction once autonomous scheduling is verified. |
| [Volvo Cars app climate](https://www.volvocars.com/al/support/topic/774a8deab8a41d22c0a8015126b9f36c/) | Home-screen climate shortcut and climate timers. | Immediate action first; schedules behind a secondary entry. |
| [MyOpel e-Remote FAQ](https://www.opel.ie/tools/FAQ.html) | SOC/range, immediate/deferred charging and cabin preconditioning. The documented cabin preset is fixed rather than a freely selectable temperature. | Base the initial controls on native preconditioning and charge mode, not an assumed HVAC thermostat or universal charge-limit slider. |

The proposal below is our design inference from those patterns. Manufacturer-specific timing, battery thresholds and charging/HVAC interactions must be established on the target car, not copied from another app or market FAQ.

## Home screen

```text
┌─────────────────────────────────────┐
│ eCMPilot                       [⚙]  │
│ Opel Corsa-e                        │
│ ● Connected · Updated just now       │
│                                     │
│ BATTERY                             │
│ 68 %                 218 km          │
│ █████████████░░░░░░   Est. range      │
│ AC charging · 7.2 kW                 │
│ About 2 h 15 min remaining           │
│                                     │
│ CLIMATE                             │
│ Cabin 16°           Outside 9°       │
│ Off · Vehicle preset                │
│ [          Start climate          ] │
│                                     │
│ CHARGING                            │
│ Charging now        Cable connected │
│ [        Charging options         ] │
│                                     │
│ Battery temperature       24–27 °C  │
│ Odometer                  42,810 km │
│ [         Vehicle details         ] │
└─────────────────────────────────────┘
```

All numbers above are illustrative. There is no implied fixed battery capacity or range conversion.

| Element | Behaviour |
| --- | --- |
| Vehicle header | User-chosen name; connection status opens connection details. Settings is always reachable, including offline. |
| Battery hero | Display/driver SOC, not silently substituted raw cell SOC. State the source in details. Use a single progress bar, no fake target marker. |
| Range | Vehicle-reported estimate if verified. Otherwise show `—`/`Unavailable`; do not calculate SOC × an arbitrary full range. |
| Charging summary | Cable state, actual charging state and battery-side charging power if available. State the power measurement point in details; it is not automatically mains input power. |
| Completion time | Vehicle-reported remaining time when available. Any later calculated estimate needs an explicit estimated label and its own validation. |
| Climate card | Cabin and outside temperatures, observed climate state, one start/stop action. A battery temperature must never appear under `Cabin`. |
| Charging card | Observed mode/state plus `Charging options`. No stop/pause or target-SOC slider unless the firmware advertises a separately verified capability. |
| Secondary readings | Battery min/max temperature and odometer. Distinguish odometer from remaining range; local `0x552` odometer scaling still needs resolution. |
| Details | Voltage/current, battery temperatures, last observation ages, source availability and recent command outcomes. Keep CAN IDs and firmware details in diagnostics. |

Core values may be visible as `—` before their first observation. Optional sections that the selected vehicle cannot provide are omitted or explained in details. The mockup includes an explicit scenario for unavailable cabin/range/odometer data.

## Climate sheet

Entry: `Start climate` on Home. Present a bottom sheet rather than a separate navigation destination.

```text
Climate                          [×]
Cabin 16°C · Outside 9°C

Prepare the cabin
Heat or cool using the vehicle preset.

Temperature          Vehicle preset
Duration             Vehicle cycle

[Cancel]              [Start climate]
```

- The base operation is native **cabin preconditioning**. It is not a direct compressor/heater CAN toggle and does not promise independent traction-battery preconditioning.
- No editable temperature or duration in the base capability. If later verified, `climate.target_temperature` and `climate.timed_run` add their own fields. Do not assume a 15-minute stop can be guaranteed by a phone timer.
- On submission, close the sheet and show `Sending…`, then `Waiting for vehicle…` after gateway acceptance. Change to `Climate running` only when a fresh vehicle observation confirms the requested state. A success toast is supplementary, not the only record.
- When climate is running, use `Stop climate` only if verified stop support is present. The stop action is direct, without a second confirmation; show its pending state and outcome just like start.
- If a fresh prerequisite is known to prevent operation, show its reason next to the disabled action. Unknown prerequisites are not fabricated into a green ready indicator. The gateway checks again before acting.
- A future `Departure time` sheet is a separate feature, enabled only when the car or gateway can execute it autonomously. Closing the app must not cancel a committed schedule.

## Charging sheet

Entry: `Charging options`. Focus the first version on **Now / Later**.

```text
Charging                         [×]
Charging now · Cable connected

Mode                  [Now] [Later]
Start time            23:00       ← Later only
Clock                 Vehicle local time

The saved setting works without your phone.

[Cancel]                      [Apply]
```

That last statement is shown only for an acknowledged, persistent native schedule capability, not for a plan that still lives only on the phone.

| Control | Initial design / capability condition |
| --- | --- |
| Now | Request native immediate charging mode. If the cable/EVSE is unavailable, distinguish a saved mode from actual charging; do not claim current is flowing. |
| Later + time | Native deferred start. Show the gateway/car timezone and clarify that this is a start time, not a guaranteed completion/departure time. Confirm readback of the saved mode/time. |
| Pause/resume | Separate future capability. Deferred charging is not presented as a universally equivalent pause operation. |
| Target SOC | Separate future capability. If implemented by a gateway workaround rather than an OEM target, disclose the distinction and behaviour on gateway failure before presenting it as supported. |
| AC current limit | Separate future capability. Do not imply the car can alter the EVSE's advertised limit. |

The mockup's deferred mode is an example of a future verified capability. It is not evidence that our current CAN integration supports it.

## Connection, state and command outcomes

| Situation | User-visible behaviour |
| --- | --- |
| First launch | `Add gateway`: manual host/IP + local UDP port, optional vehicle nickname; `Test connection`. Offer clearly separate `Try demo`. Provisioning the ESP32's Wi-Fi is a separate hardware setup task. |
| Connected | `Connected · Updated just now`; each displayed value retains its actual observation age. A new Wi-Fi reply does not make old CAN data fresh. |
| Gateway connected, vehicle asleep | `Gateway connected · Vehicle asleep`, only when sleep is actually established. Otherwise `No recent vehicle data`. Retain visibly aged readings. Ordinary status polling must not wake the car. |
| Stale data | Grey/aged values and `Last vehicle data 2 min ago`; commands need fresh applicable prerequisites or a verified wake-and-act workflow. |
| Offline | Last known values are clearly marked; no offline command queue. Reconnecting never sends a previously unsent action automatically. |
| Accepted command | `Waiting for vehicle…`; disable conflicting actions but allow an explicitly supported stop to supersede start through the gateway's command ordering. |
| Rejected | Specific cause, e.g. `Charging cable not connected`, `Vehicle moving`, `Function unavailable`. Preserve the observed state. |
| Timed out / reply lost | `Result unconfirmed` plus last known state. Refresh/query the original command ID; never turn an ambiguous timeout into a new automatic start request. |
| Gateway restarted | Re-read state/capabilities; pending result becomes unknown. No replay of the old command into the new boot. |
| App backgrounded | Stop regular polling after a short grace period. The gateway/car finishes an already accepted operation. On return, read the original command result and current state. No permanent Android background service in the first version. |

Gateway reachability, vehicle-data freshness and command confirmation are three different states in the app model. A received UDP acknowledgement proves only that the gateway received/accepted the request, not that the car obeyed.

## Initial data and capability contract

The CAN integration is still research. These are desired fields, not a claim that every field is decoded on the eCall tap.

| Priority | Data / action | Current design constraint |
| --- | --- | --- |
| First | Driver SOC, battery min/max temperature, cable/charging state, gateway status | Verify broadcasts on the chosen physical tap; known UDS meanings help comparison but are not broadcast decoders. |
| First control | Cabin preconditioning start; stop if available | Verify the complete native request, prerequisites and a confirmation signal. |
| First charging control | Immediate/deferred mode + deferred start time | Verify mode/time readback and actual vehicle behaviour separately. |
| Useful next | Cabin/outside temperature, range, odometer, charging power/ETA | Independent availability; no guessed fallbacks. |
| Later | Native schedules, adjustable target temperature, charge target/current, pause/resume | Capability-specific research and testing. |

Capabilities include **readable fields and writable operations**, not a single blanket `eCMP supported` flag. Unsupported operations are not sent. Include operating limits and confirmation support when known. A command that cannot yet be confirmed remains an experimental function, not an ordinary supported control.

## Communication and basic security decision

Adopt **local unicast UDP as the working transport proposal**. Use two application message families: `request` and `status`; the latter includes command receipt/outcome. Their full draft is in [udp-v0.md](protocol/udp-v0.md). These are UDP application messages, not two vehicle CAN frames. The gateway may need an entire native CAN exchange to fulfil one request.

Local Wi-Fi removes the need for an internet service, but does not itself authorize each device on that network to control the car. For UI/simulator work, authentication is not a current implementation task. Reserve a pairing/authentication boundary now and plan one-time local pairing with a device-specific secret before normal shared-LAN vehicle control. A phone account, public certificate service or cloud login is unnecessary for that purpose. The UDP draft distinguishes this recommendation from the current unauthenticated lab profile.

## MQTT seam

UI → vehicle repository → command/state interface → UDP adapter. The firmware's same command handler and state model can later acquire an MQTT adapter. No broker settings, topics or MQTT UI in the first version. Later MQTT transport must preserve command IDs, expiry, result correlation and reported-versus-requested state; retained commands must not cause old actions to execute.

## Suggested first implementation slice

1. Compose Home + Climate/Charging sheets against an in-process fake repository; reproduce all mockup scenarios.
2. UDP simulator on a development machine, then the same contract on ESP32; no CAN writes initially.
3. Read-only real CAN values and measured sleep/reconnect behaviour.
4. One verified climate operation, its vehicle confirmation and basic local pairing/authentication; then verified charging-mode control.
5. Autonomous schedules and MQTT only after the above works reliably.

The first usability pass should verify large-text layout, TalkBack labels, light/dark contrast, stale/offline states and an ambiguous command outcome. Transport checks should deliberately lose, duplicate and reorder packets; the protocol draft lists concrete cases.

Review performed for this sketch: document links and payload examples are checked statically, JavaScript syntax is checked, and the separate SVG wireframe was rendered and visually inspected. Interactive browser/layout validation remains outstanding because this environment blocked opening local HTML files; no browser interaction test is claimed.
