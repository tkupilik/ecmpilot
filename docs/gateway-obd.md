# Gateway OBD adapter

Open **Gateway → OBD adapter → Find adapters**, select a Bluetooth address and
choose **Save adapter**. The gateway performs the eight-second scan and stores
the selected address and address type in its own nonvolatile settings. The
phone does not connect to Bluetooth and needs no additional Bluetooth permission.
The selection is shared with the gateway web editor and survives a reboot.

Disconnect other OBD applications before use. After an explicit or automatic
vehicle refresh, the gateway briefly connects to the saved adapter, samples its
HSCAN messages and releases Bluetooth. Opening this screen or Vehicle details
only reads cached data; it does not wake the vehicle or start a snapshot.

## Supported adapters

The current gateway supports the BLE ELM327 UART family with service **FFF0**,
notifications **FFF1**, writes **FFF2**. Scanning filters by the advertised
service, not by the device name. Connection verifies the GATT layout and the ELM
identification response. Saving an address is not security pairing: there is no
PIN, bonding, or support for other BLE UART profiles yet.

Android Dash currently uses **Bluetooth Classic/SPP**, including selection from
Android's paired devices. The ESP32-S3 gateway cannot use that transport. An
adapter must expose the supported BLE interface to work with this gateway,
even if it also works with Android Dash over Classic.

## Vehicle details

**HSCAN / OBD** contains 27 readings grouped into traction battery,
powertrain, and heating/cooling: energy, SOC, pack voltage/current, battery and
cell measurements, SOH, insulation, coolant and component temperatures, heater
and evaporator setpoints, airflow, compressor current and charging time.

The readings were checked against vehicle observations on 2026-11-13. Current
is an unsigned magnitude, and compressor current is not a measured heating-power value. OBD SOC
is shown separately from the main INFO CAN SOC. Missing values show `-`; retained
observations turn grey after five minutes, using their own original ages.
Temperatures use one decimal place; cell voltages retain three. Home shows the
average battery temperature above the outside temperature, with its own age.

## Local protocol

The gateway advertises `elm327` and `obd` in `info.fields`.

| Operation | Arguments | Response |
| --- | --- | --- |
| `elm327.get` | `{}` | Compact `elm327` configuration, scan state and results |
| `elm327.scan` | `generation` from the last get | Updated `elm327`; scan completes asynchronously |
| `elm327.set` | `rev`, `address`, `address_type` (`public`/`random`) | Saved `elm327` configuration |
| `obd.get` | `page`: 0–4 | `obd: {page, pages:5, readings:{...}}` |

Mutations require the current gateway/boot identities. Scan uses compare-and-start
on the operation generation; retransmission cannot restart a completed scan.
Repeating the same saved configuration with the immediately previous revision
returns the saved result without another flash write. Conflicting settings are
rejected. A busy adapter cannot be reconfigured.

The compact scan list uses tuples `[address, address_type_number, rssi_dbm]`, with
type 0 for public and 1 for random. At most six results are returned. Each OBD
page carries at most six readings as `{value, age_ms}`, with null for unknowns.
Pages merge by key, keep independent source ages and clear on gateway restart.
They are cached observations, not an atomic simultaneous measurement. All
responses fit the existing 1024-byte UDP limit.
