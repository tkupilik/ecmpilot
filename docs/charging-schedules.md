# Charging schedules

Open **Charging → Schedules** to choose the times when you do **not** want the
car to charge, for example during a high electricity tariff. The gateway runs
the schedule; the phone does not need to stay connected.

## Set up high-tariff periods

1. Turn on **Schedules**. It is off by default.
2. Add up to eight periods. Choose **From**, **To**, weekdays and the individual
   period's enable switch.
3. Press **Apply** to save the periods to the gateway. **Reload** discards unsaved
   edits and reads the gateway's settings. Disabled periods keep their settings.

A period can cross midnight, such as 22:00–01:00; its selected days refer to
the day it starts. Overlapping or touching periods count as one continuous pause.
Equal start/end times and periods without selected days cannot be saved.
Turning Schedules off collapses the editors without erasing the saved periods.

## What the gateway does

At the beginning of a high-tariff period, the gateway checks the last reported
cable connection. If the cable is known to be connected, it requests delayed
charging. Unknown or disconnected cable status skips the period without waking
the car. Plugging in later during that skipped period does not trigger a pause.

At the end, the gateway turns delayed charging off **if it made the pause**.
This undo can wake the car even if it has since been unplugged. Disabling the
feature or removing its active period also undoes its own pause. Saved pause
tracking survives a gateway restart; uncertain acknowledgements are handled by
the gateway rather than repeatedly issuing unlimited wake requests.

The scheduler preserves the car's native delayed-charging start time. It does
not resolve conflicts with that setting or continuously enforce the paused
mode. The car may start at its native stored time inside a high-tariff period;
choose both settings accordingly. A mode confirmation is separate from the
reported physical charging state.

## Check the vehicle clock

Home shows **Vehicle time · dd.MM.yyyy · HH:mm** below the connection and data-age
lines. All charging periods use this date and time, including weekdays and
overnight boundaries. They do not follow the phone's clock.

The gateway synchronizes from BSI's INFO CAN `0x276` message and continues counting
with its own monotonic timer between messages, including while BSI sleeps.
There is no NTP, timezone offset or automatic summer/winter adjustment. The app
only displays and advances the gateway's clock between replies. Check the date
and time against the vehicle before relying on scheduled transitions.

After a gateway restart, a valid CAN clock sample is required before timed
operations run. With the schedule enabled and a known connected cable, or while
the gateway owes a resume after its own pause, automatic deep sleep is inhibited.

## Three separate controls

| Control | Purpose | Saving |
| --- | --- | --- |
| Delayed charging | Native vehicle mode and one stored start time | Switch sends immediately; a valid time sends after 800 ms without typing |
| Charge limit | GW pauses at the chosen SOC after updated data arrives; requires automatic refresh | Switch and slider save immediately |
| Schedules | GW pauses during weekly high-tariff periods | Edit periods, then Apply |

The Charge limit section sits directly above Schedules. Its slider stays visible
but grey and inactive when the limiter is off. Gateway also exposes its enable
switch after the automatic deep-sleep section, with the feature explanation.

The current implementation is covered by native gateway checks, Android clock
tests and physical-phone demo UI tests. Vehicle validation of the new timed
transitions remains separate. For wire format, persistence and bounded retries,
see the [GW schedule reference](https://github.com/tkupilik/ecmpilot-gw/blob/master/docs/off-peak-charging.md) and
the app's [UDP protocol](protocol/udp-v0.md).
