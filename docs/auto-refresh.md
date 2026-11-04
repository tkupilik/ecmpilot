# Gateway automatic refresh settings

The **Charge limit** switch requires automatic refresh. Enable it here and Apply;
the **Charging** sheet then offers a target slider (default 80%, selectable range 30–100%).
The feature explanation appears only beside its enable switch in Gateway settings.
Releasing the slider saves the gateway configuration, not a direct vehicle action.
Turning off refresh also disables the limiter. The gateway uses fresh CAN data
to request delayed charging at the target, retaining the car's scheduled start
time; charging may resume at that time. It never automatically starts charging.

The **Gateway** row in the Home menu opens the gateway settings sheet, including
the **Automatic vehicle refresh** editor when the connected gateway advertises
`auto_refresh`. Phone connection/appearance remain under the Settings gear.
The gateway editor has its own Apply/Reload. Defaults are disabled, standby 60 min, charging 5 min and
minimum 12 V SOC 50%. Periods accept 1–1440 whole minutes, SOC 0–100%.

The gateway stores settings and runs the timer; the phone does not need to stay
connected. The timer uses last known charging state, clamps to a shorter period
without restarting elapsed time, pauses below the auxiliary SOC limit and permits
refresh when SOC is unknown. The whole refresh sequence pauses the timer; its
completion, including a manual refresh or failure, starts the next full interval.

`auto_refresh.get` reads settings/status. `auto_refresh.set` sends the current
gateway identity, boot and the full desired settings with their base `rev`.
Identical retries are idempotent; conflicting edits from another client require
Reload. Saving is separate from vehicle command receipts/spinner. Connection,
polling and settings save never directly request vehicle wake. No settings write
is sent to an old gateway that does not advertise this capability.

The editor preserves unsaved changes while polling. It configures the active
gateway, discards queued saves on disconnect, and
checks the captured identity/boot before sending. A save with an unconfirmed
result requires Reload to inspect actual gateway settings.

Unit tests cover configuration parsing, snapshot ordering/reboot, bounds and
in-process demo save/idempotence/conflicts without waking a vehicle. The compiled
`AutoRefreshUiTest` covers Apply/Reload in demo mode only. Demo stores settings
for that simulated connection and never runs automatic CAN/network requests.
