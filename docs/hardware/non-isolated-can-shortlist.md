# Non-isolated CAN gateway shortlist

Reviewed 2026-10-23, after the Waveshare CAN-2CH-U power review.

## Requirements

- Assembled, programmable ESP32 kit; no custom PCB or collection of loose modules.
- **Non-isolated CAN**, avoiding the permanently powered isolation converter found on the Waveshare candidate.
- Onboard switching conversion from the vehicle's nominal 12 V supply; ordinary charging voltage around 14.4 V must also be within the board specification.
- Parked input current target: at most 10 mA from 12 V. Report Wi-Fi-reachable idle separately from deep sleep with Wi-Fi disconnected.
- Local Wi-Fi and eCMPilot's application protocol; MQTT is a future option. Generic UDP-to-raw-CAN support is not compatibility with the eCMPilot request/status contract.
- One CAN channel is sufficient for an initial endpoint; two remain useful for investigating the two eCall bus connections. These are development candidates, not verified vehicle integrations.

Removing isolation does not itself establish low consumption. Input converter quiescent current, USB bridges, LEDs, transceiver mode controls and Wi-Fi duty all matter.

## 1. CANipulator V2 — most interesting local candidate to qualify

[Seller, Fusion Tech](https://www.tindie.com/products/fusion/canipulator-automotive-dual-can-esp32-interface/) and [manufacturer documentation](https://github.com/TechOverflow/CANipulator-docs).

- Seller is in Brno, Czechia and states shipping from Czechia. It is an assembled board with optional antenna, two prewired Molex leads and a Hammond enclosure.
- The **current listing is V2, ESP32-C5**, whereas older reviews describe V1, ESP32-C6. Do not mix their pinouts or power assumptions.
- Two CAN interfaces, external U.FL antenna, native USB programming. Optional CAN-FD transceivers also support Classical CAN.
- V2 documentation exposes CAN0/CAN1 silent and shutdown controls through an I2C expander. This is materially better for software power management than the hardwired mode pins on the Waveshare board.
- Seller description gives 4–18 V input; the V2 PDF instead gives 5–36 V and typical 10–15 V. Both include nominal 12 V / 14.4 V, but the conflicting limits need clarification for the exact delivered revision.
- Snapshot of seller HTML: USD 62.40 base, CAN-FD option +2.50; standard-CAN option unavailable, CAN-FD option showed two units. Optional PCB antenna +2.50, pair of leads +5.00, enclosure +6.00. Thus a kit with those options totals USD 78.40 before shipping/tax. Inventory and prices are a snapshot, not a reservation.

**Unresolved:** the public PDFs are product/pinout documents, not full circuit schematics. Their illustrations are consistent with a shared-ground, non-isolated design, but isolation and exact buck topology/part number have not been established by circuit tracing. No board deep-sleep or associated-Wi-Fi current is specified. The approximately 100 mA operating figure lacks enough test conditions to translate into a 12 V parked current. Confirm non-isolation, switching regulator and input-current measurements before calling this a requirements-compliant purchase.

V2 controls are expander outputs; check retained state and expander supply current across ESP sleep. V1 direct-GPIO sleep advice cannot simply be copied to V2.

## 2. MeatPi WiCAN-USB — strongest published sleep claim, incomplete circuit evidence

[Product](https://www.meatpi.com/products/wican/), [hardware documentation](https://docs.meatpi.com/wican-usb/getting-started/hardware/), [sleep behavior](https://meatpihq.github.io/wican-fw/config/sleep-mode/), [firmware](https://github.com/meatpiHQ/wican-fw).

- ESP32-C3, one Classical CAN interface, enclosure and screw terminals for VIN/GND/CANL/CANH; 7.5–36 V input and USB programming.
- Manufacturer price USD 42 before shipping/tax. Mouser part `392-WICAN-USB-C3` provides a distribution route to Czechia. Mouser regional/search stock snapshots disagree, so no guaranteed current inventory is claimed.
- Manufacturer advertises **under 1 mA in sleep**. This is a manufacturer claim, not an independently measured result in this project.
- Existing firmware supports station Wi-Fi, TCP/UDP and CAN tooling; useful as research firmware, but it does not implement eCMPilot's application contract.
- Manufacturer identifies CAN standby control on GPIO6, RX GPIO3 and TX GPIO0 in [discussion #57](https://github.com/meatpiHQ/wican-fw/discussions/57), and says USB uses the same pins.

**Important limitation:** stock sleep follows battery voltage and turns Wi-Fi/BLE off; wake occurs when voltage rises again. This does not provide immediate remote heating control while parked. Firmware must use an associated power-save mode, or a deliberate periodic wake/command queue design, and that mode's consumption still needs measurement.

**Unresolved:** a full schematic for the original USB model was not found in the inspected public repository. Its `sch/` directory contains a **WiCAN PRO** schematic, which must not be substituted as proof for WiCAN-USB. Exact power-converter topology and absence of isolation need confirmation before accepting the hardware against these requirements. Do not assume the sleep claim proves either circuit detail.

## 3. LILYGO T-CAN485 — verified non-isolated buck design, input-rating problem

[Manufacturer product](https://lilygo.cc/en-pl/products/t-can485), [repository](https://github.com/Xinyuan-LilyGO/T-CAN485), [inspected schematic](https://github.com/Xinyuan-LilyGO/T-CAN485/blob/arduino-esp32-libs_v3.0.1/project/T-CAN485.pdf).

- ESP32, one native CAN interface, CAN/power screw terminals and USB programming. Czech-facing marketplace listings exist, but a local warehouse and supplied PCB revision were not verified.
- Inspected five-page schematic dated 2026-01-23 shows **MT1471 buck directly to 3.3 V**, not a linear input regulator. CAN is **SN65HVD231**, directly connected to ESP TX/RX, without a digital isolator or isolated power module.
- CAN mode control is GPIO23; the RS485 5 V boost is controlled by GPIO16. RS485 shutdown is GPIO19. This permits disabling unused functions in firmware.
- The SN65HVD231 sleep mode disables **both transmitter and receiver**. It cannot detect CAN wake traffic in that state; see [TI's device documentation](https://www.ti.com/product/SN65HVD231/part-details/SN65HVD231DR). An active receiver/timer or another wake source would be needed.
- Keep the SD slot empty for initial power tests; setting the RGB LED black is not equivalent to removing its standby consumption. Check USB-bridge powering on the actual assembly.

**Blocking issue for direct vehicle wiring:** the manufacturer's complete-board rating is **5–12 V**. A 12 V vehicle reaches higher charging voltages. A regulator component's wider range does not override the board rating; the schematic also shows 16 V input capacitors. This is a useful low-cost bench candidate, but not a qualified direct-to-car recommendation without written revision-specific input limits or additional power hardware, which the current brief seeks to avoid.

No measured whole-board sleep or Wi-Fi-reachable input current was located. A plausible sub-10-mA sleep design must not be reported as a verified specification.

## 4. Autosport Labs ESP32-CAN-X2 — assembled dual-CAN fallback

[Manufacturer store](https://www.autosportlabs.com/product/esp32-can-x2-dual-can-bus-automotive-grade-development-board/) and [technical guide](https://wiki.autosportlabs.com/ESP32-CAN-X2).

ESP32-S3, two Classical CAN interfaces (TWAI plus MCP2515), nominal VIN 6–20 V and a quoted 40 V maximum, with supplied power/CAN leads. Manufacturer listing showed USD 54.95 and in-stock/backorder availability. No Czech stock was established.

The public material inspected does not provide a usable complete power schematic or parked-current specification. Isolation status, regulator topology and transceiver low-power control still need circuit evidence. The published 105 mA/330 mW operating figures do not have sufficient supply/mode detail to establish 12 V idle current. Keep this as a secondary inquiry rather than presenting it as a proven low-power option. Use its designated VIN connection for vehicle power.

## Other researched boards

| Board | Finding |
| --- | --- |
| LILYGO T-2CAN | Isolated SGND/DGND architecture; fails the new no-isolation requirement ([manufacturer](https://wiki.lilygo.cc/products/industrial-series/t-2can/)). |
| M5 Atomic CAN Base | Built-in isolated DC/DC and CA-IS3050G; fails the new requirement ([manufacturer](https://docs.m5stack.com/en/atom/Atomic%20CAN%20Base)). |
| SK Pang original ESP32 CAN board | Published schematic shows linear NCV4264 + MIC5504 power path; fails the switching-supply preference. Do not generalize this to their newer boards ([schematic](https://cdn.shopify.com/s/files/1/0563/2029/5107/files/ESP32_CAN_rev_B.pdf?v=1620032162)). |
| Voltlog CanLite rev D | Excellent circuit visibility: LMR14006 directly to 3.3 V and non-isolated SN65HVD231. However, the seller page currently marks it **retired**, so it is not a ready-to-order recommendation. CAN Rs is hardwired through 100 kOhm to ground rather than MCU-controlled ([design](https://github.com/voltlog/CanLite), [seller](https://www.tindie.com/products/voltlog/canlite-esp32-can-development-board/)). |
| RejsaCAN v3/v6 | Published non-isolated designs with LMR14006 buck and CAN mode controls; v6 uses ESP32-C6 and two native CAN channels. No regular retail stock established. The README's historical sleep figure of roughly 3 mA plus 4 mA LED at 14 V is not revision-specific evidence for v6 or an associated-Wi-Fi claim ([author](https://github.com/MagnusThome/RejsaCAN-ESP32)). |
| Wio ESP32 CAN Dev Kit | Manufacturer listing currently says out of stock; not a practical immediate purchase ([store](https://www.seeedstudio.com/Wio-ESP32-CAN-Dev-Kit-p-5466.html)). |

## Recommendation

Prioritize **CANipulator V2** for local hardware qualification, then **WiCAN-USB** for its packaged form and documented offline sleep behavior. Neither has yet passed the complete evidence gate of non-isolation + verified switching power + at most 10 mA with the required wake/reachability behavior. LILYGO T-CAN485 is the clearest verified non-isolated buck circuit among the inexpensive readily listed kits, but its stated input limit prevents an unconditional direct-vehicle recommendation.

For a useful parked gateway, the decisive measurement is current from **12 V with Wi-Fi associated and UDP commands still working**, alongside a separate offline sleep measurement. The board should meet the budget without transmitting CAN keep-alives that prevent the vehicle itself from sleeping. No hardware purchase or vendor contact was performed.
