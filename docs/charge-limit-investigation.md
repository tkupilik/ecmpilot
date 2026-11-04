# Charging target level is not a verified SOC percentage limit

The pinned PSA-RE INFO source contains a target-level field at byte0 bits4..3
in BSI status `160`, local request `2DB` and remote request `35B` (all DLC2).
The `160` / `2DB` source enums label raw0 as `10`, raw1 as `20`, raw2 as `FULL`
and raw3 unused. The remote `35B` source only assigns raw2=`FULL` and raw3 unused.
No percentage unit or general 0..100% encoding is defined by these fields.

Across the twelve nonempty INFO captures in the GW logs, all 1,653 `160` status
frames have target code0. Both captured `35B` requests also use code0. These
captures span different actual SOC levels and immediate/delayed schedule changes;
none records selecting another charge target or stopping at a chosen SOC.
There is no observed `2DB` command in those files. The existing GW mode/time
encoder preserves these target bits rather than treating zero as 10%.

Consequently the app does not add an active percentage slider based on this
unverified enum. A controllable SOC limit needs a documented or experimentally
confirmed encoding, request sequence, status readback and evidence that charging
actually stops at the selected level. This does not prove that the car has no
such capability; it records what the current source and captures establish.

See the GW's `PSA_telematic.md` charge-schedule payload analysis and
`dbc/opel_corsa_e_info.source.json` entries160/2DB/35B, pinned PSA-RE revision
`67f7581e7622410efdbb29c7eda3b86982b9b450`. No new CAN transmission is performed.
