# Home, vehicle status and consumption

Home keeps the current header: settings icon, gateway/BSI status, then a separate
line for the age of the SOC observation, followed by the vehicle name and explicit
Refresh action. Both status lines always occupy one line with ellipsis instead
of wrapping, so changes in age or BSI text cannot shift the content below them.
The compact vehicle panel contains SOC, a small battery bar, estimated range
and outside temperature, with `12V SOC …%` at the bottom right. The auxiliary SOC
uses its own observation age and shows unavailable when missing. A large vehicle illustration is intentionally omitted
from Home to leave room for the status shortcuts and navigation.

Three shortcuts open screens; clicking a status never sends a vehicle command:

- **Preconditioning:** grey when off/unknown; a green rotating fan only for a
  fresh reported active state. A stale active state remains visible, grey and
  stationary, with a last-known label.
- **Charging:** grey for inactive/unknown, green for fresh charging, blue for a
  fresh delayed mode. Explicit disconnected status takes precedence over a
  stored delayed schedule. State and schedule timestamps remain independent.
- **Doors & locks:** reported locked/superlocked, unlocked or partial-unlock
  state. Opens the selected silver hatchback illustration viewed from above,
  front at the top, with independent CAN-driven aperture overlays.

The row menu contains **Trips & consumption** and **Vehicle details**. Charging
and Preconditioning open through their status shortcuts above it. Charging holds SOC/charge information, the delayed-charging
switch, always-visible time editor and Apply. The switch track is transparent
when off and blue when on. SOC uses a continuous track without a progress-widget
gap or end marker. Dark surfaces use a neutral charcoal palette shared with the
Connection/setup sheet; green is reserved for status accents.

Preconditioning groups the outside temperature into a compact badge beside the
Prepare heading; the badge fades with stale data. Front left/right setpoints are
omitted from this screen (still available in Vehicle details). It holds the explicit Start/Stop action and four weekly
program editors with times, weekdays, individual activation and a master switch.
Disabled plans are collapsed to a header/summary; enabled plans expand. Edit or
Set up can temporarily open a disabled plan without enabling it. Switching it
off collapses it again. The charging time editor remains always visible.
The master enables all configured slots or disables all slots while keeping
their times and weekdays. Apply schedules explicitly saves changes and waits
for BSI readback; editing does not start immediate preconditioning. Slots are
vehicle observations, not phone alarms. Missing/older gateway support is shown
without pretending a local draft is installed in the car. The captured plan
matches 07:30 Monday/Wednesday/Thursday, disabled. See the GW
[program contract](../../ecmpilot-gw/docs/preconditioning-programs.md).

Both dialogs use the shared Close action without a duplicate
Cancel button; pending requests still follow the existing confirmation or
30-second timeout. Navigation remains usable when the gateway/vehicle is asleep.

## Doors and locks

The generic left-hand-drive hatchback has separate front-left, front-right,
rear-left, rear-right and boot indications. Fresh open panels are amber; closed
panels are neutral. Unknown panels have a neutral marker and say Unavailable.
An unknown value is never converted to closed. Text labels accompany the picture.
Cached open shapes remain drawn when old, but grey; lock and aperture ages are
separate. The transparent artwork works on both light and dark surfaces; its
source/prompt notes are in [vehicle-top.md](assets/vehicle-top.md). Neither opening
this screen nor reconnecting wakes the vehicle.

GW source layouts: `236` for apertures and `276` for locking. Existing standby
captures contain an unlocked -> locked transition. The sampled apertures are all
closed; opening each physical door still requires vehicle validation. There is
no bonnet/window indication or lock/unlock command without a supported source.

## Trips and consumption

- Trip 1 and Trip 2: electric average consumption from `31A`, distance and mean
  speed from `2A1` / `261`.
- Instantaneous consumption from `31A`, shown unavailable for sentinel values.
- Six vehicle consumption-history slots from `39A`. Their interval duration and
  chronological direction have not been established: slots are numbered 1..6,
  with no invented dates, durations or "last six journeys" claim.

The driving capture supplies 21.5 / 20.7 kWh/100 km, 164 / 2232 km and 72 / 88 km/h.
These are source-supported decoded candidates, awaiting a comparison with the
car's trip pages. Legacy fuel-consumption fields are not relabelled as electric
consumption. There is no reset-trip command or locally fabricated trip archive.

Vehicle details now lists SOC/range, outside temperature, odometer, auxiliary
12 V voltage/SOC, and the two front setpoints, followed by gateway diagnostics.
Per-reading age captions are omitted here; old values are indicated by grey text.
Unsupported HV voltage/current, measured cabin temperature and pack-temperature
placeholders have been removed from this INFO-oriented screen. The protocol
retains those optional legacy fields for compatible gateways.

## Transport and compatibility

The GW advertises `vehicle_details` in `info.fields`. The repository then polls
the **passive** `vehicle.details.get` operation every two seconds, pausing these
extra reads while a vehicle command is pending. This has no CAN transmission
side effects. The separate reply stays within the existing UDP v0 1024-byte
limit; it does not expand the regular `state.get` datagram.

The reply is cached independently of the regular status. Original CAN ages
continue advancing between datagrams. Boot changes clear both caches; old
sequence numbers cannot overwrite newer details. A gateway without this
capability is never sent the new operation and the new screens show unavailable
values. Door and trip demo data run entirely inside the existing in-process
demo gateway; the additional **Doors open** scene opens three panels.

See the GW [decoder and wire details](../../ecmpilot-gw/docs/vehicle-details.md).

## Display freshness

All displayed measurements and reported vehicle states stay visually fresh for
five minutes (300,000 ms) after the vehicle observation. After that the cached
value remains visible in grey. Missing/invalid values remain unavailable. Loss
of gateway connection still dims its cached data immediately. This presentation
policy is separate from the existing shorter vehicle-control freshness checks;
commands, network timeouts and CAN signal ages are unchanged.

## Shared dialog gestures

Every modal uses the shared SheetFrame. Status-bar clearance is applied outside
the sheet's offset-dependent inset consumption; content keeps only bottom and
horizontal safe-drawing insets. This prevents a wrapped sheet's height and
Expanded anchor from feeding back into each other during upward drags. IME
padding is provided once by ModalBottomSheet. Inner scrolling and swipe-down
dismissal remain available.

Swipe dismissal requires56dp travel. The fast-fling shortcut is disabled at the
owner's request: speed alone cannot dismiss a sheet below that distance.
Longer downward pulls, Close, Back and outside taps retain normal dismissal.
The same saved SheetState policy applies to all six
dialogs and preserves the earlier stable height/inset handling.

## Vehicle action feedback

Every vehicle action (refresh, charging, preconditioning and plan edits) replaces
the Refresh label with an accessible indeterminate spinner while preparing,
sending or awaiting vehicle confirmation. Its minimum button width remains
fixed. No action produces an inline command card or success popup; successful
completion immediately restores the enabled Refresh button. The existing
receipt cleanup may run later without any visible success notification.
Rejected/failed/unconfirmed results open a descriptive dismissible error dialog,
including early connection/preflight failures without a gateway receipt. Existing
saved receipts and vehicle command sequencing are unchanged.

Vehicle details labels the traction battery percentage simply **SOC**.
