# Gateway low power

Open **Gateway** and scroll below **Automatic vehicle refresh** to enable automatic sleep and choose how long the
vehicle must stay inactive. Default: enabled, **48 hours**; range 1–720 hours.
The setting belongs to the gateway and works without a connected phone.

The screen shows time remaining until sleep. Each valid BSI radio-control
message restarts the full inactivity period. After a GW restart it waits for
the first valid vehicle sample rather than timing from boot.

Automatic vehicle refresh keeps BSI data recent. If the 12 V battery falls below
the refresh threshold (default 50%), refresh pauses; after the subsequent
inactivity period the gateway can sleep. The low-power and refresh switches
remain independent. Reading status in the phone does not restart the countdown.

Deep sleep turns off Wi-Fi, so phone/web/HA access stops. CAN activity wakes the
gateway and it reconnects after reboot. The phone cannot wake a sleeping gateway.

The app reads `low_power.get` and saves boot-bound, revision-checked
`low_power.set` settings (`rev`, `enabled`, `idle_hours`). It retains edits while
polling, reports conflicting saves and interpolates the server countdown between
replies. No sleep timer or vehicle-control command runs in the phone.
