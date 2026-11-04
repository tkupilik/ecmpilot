# Consolidated Corsa-e CAN map

[Editable SVG](ecmpilot-can-map.svg) | [Vector PDF](ecmpilot-can-map.pdf) | [Endpoint/source manifest](ecmpilot-can-map-data.json) | [Engineering notes](../../wiring.md)

Revision 2, 2026-10-28. One A0 landscape sheet combines an architecture view and six detailed network panels. The SVG contains editable text and vector primitives. The PDF contains vector paths and selectable text, with no embedded images. Colours of the network lines identify networks; the actual conductor colours are written in English at each endpoint. The original French connector codes are retained in parentheses for searching the source sheets.

Revision 2 adds the second OBD diagnostic pair to the architecture view: C001 pins 3/8, wires 9028 Grey / 9029 Brown, to BSI1 60-way Black pins 8/10. It is drawn as a separate grey pair bundle with unknown internal network assignment. It is not labelled HS CAN2, ENT INFO or eCAN. The first pair, OBD 6/14, remains detailed in panel A.

## Drawing conventions and limits

- Each coloured network line represents a **two-conductor CAN pair**, not one wire. The endpoint card lists both cavities, wire identifiers and physical colours. These cards are logical port descriptions, not connector-face drawings. CAN-H/L polarity is not assigned.
- Solid external network connections are transcribed from the cited OEM sheets. Dots represent shared splice connectivity. Multiple splice stages are simplified; their physical order and location are not reproduced.
- Solid internal eCAN continuity inside **VCU 1775** is marked **U01 / owner-confirmed**. On 2026-10-28 the owner stated that the OBC branch and motor/battery branch are connected inside the VCU. No measurement record was supplied. The OEM sheet alone does not expose this internal join.
- Amber dashed internal paths with **?** inside BSI and ESP are **hypotheses**, not verified connections. Separate BSI ports are preserved. ESP 7800 main-bus pins 5/19 are kept separate from VCU-branch pins 4/18. No electrical continuity or transparent forwarding is established by the box outline.
- The same ECU may occur in several panels. This does not imply that its different CAN interfaces are electrically joined. The VCU eCAN connection U01 is the explicit exception.
- Alternative equipment layouts are labelled. In particular the camera uses BSI 35-way Black pins 6/8 differently in the HS CAN2 and ADAS sheets. Parking ECU 7500 also has alternative network assignments. These alternatives must not be treated as one fitted vehicle configuration.
- **RB** and **BB** colour codes are retained as **unresolved**. Their English equivalents have not been established from the reviewed material. All other displayed colour names follow the legend in `wiring.md`.
- The grey eCall power reference is separate from CAN. F14 is classified `+BAT_CSP`; unconditional battery availability during sleep is not established. Ground is stated separately, with no connection to the supply line implied.
- The drawing covers the CAN categories relevant to this investigation. It omits LIN, diesel DEPOLLUTION, trailer options, termination details and most non-CAN harness circuits. It is not a full harness drawing or a vehicle-verified installation pinout.

## Important topology checks

1. **HS CAN1:** BSI main port reaches ESP 7800 pins 5/19. VCU 1775 reaches a separate ESP pair, pins 4/18. The source does not disclose ESP internal continuity.
2. **ENT INFO:** hands-free ECU 8240 is on the dashboard splice network through IC91B pins 14/15. It is not on the separately drawn BSI 60-way Yellow parking-ECU run.
3. **Passenger-compartment CAN:** airbag ECU 6570 is on the BSI Brown-connector branch through IC91B pins 19/20. Steering controls, climate ECU and steering lock are on the Blue-connector branch.
4. **eCAN:** the externally separate OBC and motor/battery runs are connected within VCU 1775 according to U01. This is not a join to the VCU HS CAN1 or HS CAN2 interfaces.

## Source register

All paths below are relative to:

```text
C:\work\bev\service manual\Opel PSA e-Corsa eP2JO 2019-2021 Wiring Diagrams\EWD
```

| Key | Source sheet | Relative source path | Pages used |
| --- | --- | --- | --- |
| T | D3CZV9GG | `Comfort and Convinience/Audio_Navigation_Telematics_Telephone_Multimedia/Independent Telematic Unit/wiring.pdf` | 1 |
| A / AP | D3CZV75G / D3CZV75R | `General Points/Interconnections/HS CAN 1 Multiplexed Network Interconnection/wiring 1.pdf` and `principle 1.pdf` | 1 each |
| B / BP | D3CZV7AG / D3CZV7AR | `General Points/Interconnections/ENT INFO CAN Interconnection/wiring.pdf` and `principle.pdf` | 1 each |
| C | D3CZV7CG | `General Points/Interconnections/ECAN Network Interconnection/wiring.pdf` | 1 |
| D | D3CZV77G | `General Points/Interconnections/HS CAN 2 Multiplexed Network Interconnection/wiring 1.pdf` | 1 |
| D2 | D3CZV78G | Same directory, `wiring 2.pdf` | 1 |
| D3 | D3CZV76G | Same directory, `wiring 3.pdf` | 1 |
| E / EP | D3CZV7DG / D3CZV7DR | `General Points/Interconnections/Multiplexed Passenger Compartment CAN Interconnection/wiring.pdf` and `principle.pdf` | 1 each |
| F | D3CZV7BG | `General Points/Interconnections/ADAS CAN Interconnection/wiring.pdf` | 1 |
| L | D3CZV79G | `General Points/Interconnections/CAN Network Interconnection/wiring.pdf` | 1 |
| P | BSI fuse allocation | `General Points/Matching of Fuses/Allocation of BSI1 Fuses.pdf` | 1-3 |
| Q | D3CZV7FG | `General Points/Sockets_Terminals_Connectors/Diagnostic Socket/wiring.pdf` | 1 |
| U01 | Owner evidence, 2026-10-28 | VCU internal eCAN branch connection; statement in project conversation | Not an OEM sheet |

The source collection includes equipment alternatives. Its nominal vehicle/year does not prove fitment on the owner's car. `wiring.md` records the remaining questions about BSI routing, OBD visibility, supply behaviour and physical access. The manifest retains absolute source paths and the port records used to draw the sheet. PDF source captions link to the local source PDFs.

## Regenerate and verify

From the repository root, using Python with `reportlab` and `pypdf`:

```text
python tools/draw_can_map.py
```

The generator emits SVG, PDF and the JSON manifest. It checks card text width, page bounds, SVG syntax, expected PDF text, single-page output and absence of embedded raster images. It does not automatically establish electrical correctness. The output was rendered with Poppler and all six detail panels, architecture and legend were visually inspected against the relevant source pages. The revision 2 architecture and legend edits were rendered and inspected again.
