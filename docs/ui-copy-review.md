# UI wording review — owner approved items 1 and 2 only

Owner decision: implement **1 and 2 only**. Items 3–8 are not approved and remain
unchanged. The changes below do not alter vehicle commands,
capabilities, freshness rules or the connection protocol. English remains the
application language. Keep the explicit BSI status and diagnostic export, which
the owner previously requested.

Items 1 and 2 are implemented in the debug APK installed on the connected phone.

Already authorized separately: selected car/lightning launcher icon; charge-limit
help only beside its Gateway switch, Charging slider 30–100%; outside temperature
in Prepare, no front setpoints in Preconditioning; remove the consumption-history
subtitle. Those changes are not part of this approval list.

| # | Location and current text | Proposed change |
| --- | --- | --- |
| 1 | Doors: “Door and lock readings can have different update times.” | Remove this explanatory footer. Keep the actual lock/door states and stale appearance. |
| 2 | Trips: “kWh/100 km · Trip 1 and Trip 2 are the car's counters, not a dated list of journeys.” | Keep only “kWh/100 km” below the chart. |
| 3 | Preconditioning: “Heat or cool using the vehicle preset.” plus Temperature / Vehicle preset and Duration / Vehicle cycle rows | Replace the paragraph and both informational rows with one sentence: “Uses the car’s temperature settings and automatic heating or cooling cycle.” Keep Start/Stop and the outside-temperature badge. |
| 4 | Schedules: “Switch on to enable all configured plans; off disables them and keeps their times and days. Apply saves to the vehicle.” | “Choose the days and time, then save your plans. Turning schedules off keeps your saved times.” Rename “Apply schedules” to “Save schedules”. |
| 5 | Connection: “Allow experimental local controls” and “Unencrypted lab UDP. Only gateway-advertised, confirmed operations are enabled.” | “Enable vehicle controls” with “Allow this phone to send commands to your car. Use only a trusted local network; the connection is not encrypted.” Update the related monitoring-only message to “Vehicle controls are off. Enable them in Settings.” |
| 6 | Connection: “Local UDP port”; validation refers to UDP | “Port”; validation “Enter a port from 1 to 65535.” Keep its existing value and validation rules. |
| 7 | Connection: raw “Saved gateway: …” identifier and “Gateway identity will be learned again when settings are saved.” | Move the identifier to connection diagnostics. Keep the forget action as “Reset saved gateway” with “The gateway will be identified again when you reconnect.” The stored binding behavior stays the same. |
| 8 | Vehicle details: “Transport profile”, raw request phase/message and always-expanded “Recent diagnostics” | Keep vehicle values and firmware visible. Put protocol/security profile, raw identifiers, request state and connection log behind an expandable “Connection diagnostics” section; keep “Save diagnostics…” available. Do not discard diagnostic data or hide errors when an action fails. |

Likely later follow-up, not included in these proposals: map individual raw
connection/command errors to plain-language messages while retaining the original
codes in exported diagnostics. That needs a separate review of actual error cases.
