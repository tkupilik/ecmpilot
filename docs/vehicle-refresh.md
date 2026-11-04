# Passive connection and explicit vehicle refresh

Opening the app, saving connection settings and periodic polling only read GW
RAM observations. They never issue vehicle.refresh. The header shows connection
status, actual last-reported BSI CAN PowerManagement phase and
`Last vehicle data N min ago`, derived from SOC age. Below it, the vehicle-name
row contains Refresh; Settings is a gear
icon in the title row. Old readings are grey. Cached observations retain CAN receipt age, not gateway
response age. The GW cache lasts through vehicle sleep and network reconnects,
but not an ESP restart/deep-sleep reboot. CAN receipt age cannot prove the age of
the battery measurement inside the source ECU.

Battery and charging share one card. Observed deferred mode uses a horizontal
switch, stored start time and blue fresh SOC. The switch is grey for disconnected,
unknown or stale state, green for fresh active charging and blue for fresh deferred
mode. A fresh disconnected indication takes priority and disables the switch.
Changing the switch or applying the
charging sheet requests a setting; it is only displayed as changed after vehicle
readback. Unknown charging flow/cable/current remain unknown even with a known
schedule. The stored time is available in both modes when supplied by the GW.

Refresh vehicle is explicit and works with stale/missing vehicle data. The
informational warning paragraph has been removed from the card. Refresh can
wake the car and activate charging. It is only enabled when the GW
advertises vehicle.refresh. Existing lab-control opt-in still gates charging;
the experimental backend enables only explicitly advertised functions, including immediate preconditioning. Settings Save & connect uses
passive reconnect rather than this action.

The GW implements `control_backend: info-can-experimental`. `vehicle.refresh`
uses empty args, current boot, command/client ID, domain_rev0 and a short deadline.
It has the existing accepted/succeeded/failed/unknown lifecycle and idempotent
retries. Read-only state/result queries cannot wake the car. There is no automatic
refresh-on-poll/background timer, and ambiguous writes are not replayed on boot.

For explicit Refresh or charge mode requests the GW waits for BSI Normal, waking
through the configured telematics frame only if needed. It sends394/10 once;
charge mode adds35B after the observed~460ms preamble and waits for matching new
BSI160. The exact immediate-charge behavior still needs vehicle validation. Immediate preconditioning uses its own31B/11B sequence with no394 preamble; see the GW preconditioning document. The
app never sends raw CAN and never marks driver acceptance as physical success.

Optional `charge.mode_age_ms` describes schedule freshness separately from
charge-flow `age_ms`; `charge.schedule_available` controls experimental schedule
availability. Old replies fall back to their existing group stamp. The protocol
stays v0/1024bytes, and the Android version is unchanged.

Validation: gateway native C tests check actual capture schedule samples,
deduplication, passive reads and TX policy. Android JVM tests check independent
ages, capability gating, stale/asleep control behavior and demo Refresh. No
firmware/phone deployment or vehicle command is part of workstation validation.

## Header and confirmation update, 2026-11-03

Optional `state.bsi` contains `power_management` (CAN 036 raw codes 0-4) and
independent `age_ms`. The header displays its last reported phase rather than
deriving it from awake/parked or replacing it with a freshness label. An older
gateway without this object displays BSI unknown. Silence does not infer sleep.
The age shown in the header still comes exclusively from SOC. Climate setpoints
are retained with their original timestamps by the updated GW; aged numeric
values remain visible in grey. Explicit non-numeric/invalid values remain unknown.

Successful command confirmations disappear after three seconds. A different/new
command cancels that timer; errors and unconfirmed results remain until dismissed.
The UI uses Start/Stop preconditioning and Delayed charging, following
[Opel's English FAQ](https://www.opel.ie/tools/FAQ.html). These are UI labels;
protocol operations and the `deferred` mode value remain unchanged.

## Immediate preconditioning

The INFO experimental backend can advertise climate.set for Start and Stop.
The existing explicit-control opt-in is required. Missing/stale/asleep vehicle
observations do not disable an advertised request: the GW handles wake-up and
preserves fresh program data before sending. The app creates commands only from
button actions and shows the reported climate state without optimistic changes.

The button stays disabled while a command is pending, until confirmation,
failure or the GW30-second timeout. There is no fixed10-second cooldown.
The app polls the existing receipt and never creates a new command to retry an
ambiguous result. Start success requires the BSI active report; Stop requires
inactive. Successful confirmations still auto-dismiss after3 seconds.
Actual cabin temperature remains unavailable; no setpoint/outside proxy is shown.
