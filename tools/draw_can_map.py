"""Generate matching editable SVG and vector PDF CAN maps. Requires reportlab.

Run: python tools/draw_can_map.py
Source paths are resolved from wiring.md's source root. No source images are embedded.
"""
from pathlib import Path
from html import escape
import json
import re
from xml.etree import ElementTree

from reportlab.pdfgen import canvas
from reportlab.lib.colors import HexColor
from reportlab.lib.pagesizes import A0, landscape
from reportlab.pdfbase.pdfmetrics import stringWidth
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "can-map"
OUT.mkdir(parents=True, exist_ok=True)
W, H = 3600, 2546
INK, MUTED, GRID, BG = "#152c3c", "#506778", "#ccdae1", "#f2f6f8"
COL = {"hs1": "#1573a4", "info": "#bd640d", "ev": "#188063",
       "hs2": "#7657a6", "cabin": "#526d82", "adas": "#a64f64", "lamp": "#7e7536"}
WARN = "#a86d09"
FRENCH = {"NR": "Black", "BE": "Blue", "BA": "White", "GR": "Grey",
          "VE": "Green", "MR": "Brown", "RS": "Pink", "VI": "Violet",
          "BG": "Beige", "RG": "Red", "OR": "Orange", "JN": "Yellow",
          "VJ": "Green/Yellow", "ND": "Unspecified"}
SOURCE_ROOT = Path(r"C:\work\bev\service manual\Opel PSA e-Corsa eP2JO 2019-2021 Wiring Diagrams\EWD")
INTER = "General Points/Interconnections/"
SOURCES = {
    "T": ("D3CZV9GG", "Comfort and Convinience/Audio_Navigation_Telematics_Telephone_Multimedia/Independent Telematic Unit/wiring.pdf"),
    "A": ("D3CZV75G / D3CZV75R", INTER + "HS CAN 1 Multiplexed Network Interconnection/wiring 1.pdf"),
    "AP": ("D3CZV75R", INTER + "HS CAN 1 Multiplexed Network Interconnection/principle 1.pdf"),
    "B": ("D3CZV7AG / D3CZV7AR", INTER + "ENT INFO CAN Interconnection/wiring.pdf"),
    "BP": ("D3CZV7AR", INTER + "ENT INFO CAN Interconnection/principle.pdf"),
    "C": ("D3CZV7CG", INTER + "ECAN Network Interconnection/wiring.pdf"),
    "D": ("D3CZV77G", INTER + "HS CAN 2 Multiplexed Network Interconnection/wiring 1.pdf"),
    "D2": ("D3CZV78G", INTER + "HS CAN 2 Multiplexed Network Interconnection/wiring 2.pdf"),
    "D3": ("D3CZV76G", INTER + "HS CAN 2 Multiplexed Network Interconnection/wiring 3.pdf"),
    "E": ("D3CZV7DG / D3CZV7DR", INTER + "Multiplexed Passenger Compartment CAN Interconnection/wiring.pdf"),
    "EP": ("D3CZV7DR", INTER + "Multiplexed Passenger Compartment CAN Interconnection/principle.pdf"),
    "F": ("D3CZV7BG", INTER + "ADAS CAN Interconnection/wiring.pdf"),
    "L": ("D3CZV79G", INTER + "CAN Network Interconnection/wiring.pdf"),
    "P": ("BSI F14 supply classification", "General Points/Matching of Fuses/Allocation of BSI1 Fuses.pdf"),
    "Q": ("D3CZV7FG", "General Points/Sockets_Terminals_Connectors/Diagnostic Socket/wiring.pdf"),
}


def english(code):
    return "/".join(FRENCH.get(part, part + " [unresolved]") for part in code.split("/"))


def connector(code):
    m = re.fullmatch(r"(\d+)V (\w+)", code)
    assert m, code
    return f"{m[1]}-way {english(m[2])} ({code})"


ENDPOINTS = []


class Drawing:
    def __init__(self):
        self.svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="1189mm" height="841mm" viewBox="0 0 {W} {H}" role="img">',
                    '<title>Corsa-e CAN networks - consolidated engineering map</title>',
                    '<desc>Editable vector redraw. Solid external wiring is sourced; VCU internal continuity is owner-confirmed; possible BSI and ESP internal connections are dashed and unverified.</desc>']
        self.pdf = canvas.Canvas(str(OUT / "ecmpilot-can-map.pdf"), pagesize=landscape(A0), pageCompression=1)
        self.pdf.setTitle("eCMPilot | Corsa-e consolidated CAN map")
        self.pdf.setAuthor("eCMPilot project")
        pw, ph = landscape(A0)
        self.sx, self.sy = pw / W, ph / H
        self.pdf.scale(self.sx, self.sy)
        self.bounds = []

    def rect(self, x, y, w, h, fill="#ffffff", stroke=GRID, radius=8, dash=False):
        self.svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{radius}" fill="{fill or "none"}" stroke="{stroke or "none"}" stroke-width="1.5"' + (' stroke-dasharray="9 6"' if dash else '') + '/>')
        p = self.pdf
        p.saveState()
        p.setLineWidth(1.5)
        if fill: p.setFillColor(HexColor(fill))
        if stroke: p.setStrokeColor(HexColor(stroke))
        if dash: p.setDash(9, 6)
        p.roundRect(x, H-y-h, w, h, radius, stroke=bool(stroke), fill=bool(fill))
        p.restoreState()

    def line(self, pts, color=INK, width=2, dash=False):
        points = " ".join(f"{x},{y}" for x, y in pts)
        self.svg.append(f'<polyline points="{points}" fill="none" stroke="{color}" stroke-width="{width}" stroke-linejoin="round"' + (' stroke-dasharray="9 6"' if dash else '') + '/>')
        p = self.pdf
        p.saveState()
        p.setStrokeColor(HexColor(color)); p.setLineWidth(width)
        if dash: p.setDash(9, 6)
        path = p.beginPath(); path.moveTo(pts[0][0], H-pts[0][1])
        for x, y in pts[1:]: path.lineTo(x, H-y)
        p.drawPath(path); p.restoreState()

    def dot(self, x, y, color=INK, radius=4):
        self.svg.append(f'<circle cx="{x}" cy="{y}" r="{radius}" fill="{color}"/>')
        self.pdf.setFillColor(HexColor(color)); self.pdf.circle(x, H-y, radius, fill=1, stroke=0)

    def text(self, x, y, s, size=16, color=INK, bold=False, maxw=None):
        font = "Helvetica-Bold" if bold else "Helvetica"
        if maxw is not None:
            assert stringWidth(s, font, size) <= maxw, f"Text too wide: {s}"
        assert x >= 0 and y >= 0 and y <= H, s
        self.svg.append(f'<text x="{x}" y="{y}" font-family="Helvetica,Arial,sans-serif" font-size="{size}" font-weight="{700 if bold else 400}" fill="{color}">{escape(s)}</text>')
        self.pdf.setFillColor(HexColor(color)); self.pdf.setFont(font, size); self.pdf.drawString(x, H-y, s)
        self.bounds.append((x, y, stringWidth(s, font, size), size, s))

    def lines(self, x, y, lines, size=16, step=23, **kw):
        for i, s in enumerate(lines): self.text(x, y+i*step, s, size, **kw)

    def pair(self, x1, x2, y, color, dashed=False):
        self.line([(x1, y), (x2, y)], color, 3, dashed)

    def card(self, x, y, w, unit, name, conn, pins, source, color, note=None):
        """Pin tuple: (cavity, printed wire identifier, printed colour code)."""
        self.rect(x, y, w, 88, stroke=color)
        self.text(x+12, y+21, f"{unit} | {name}", 18, bold=True, maxw=w-24)
        self.text(x+12, y+39, connector(conn), 13, MUTED, maxw=w-24)
        for i, (pin, wire, code) in enumerate(pins):
            self.text(x+12, y+59+i*17, f"{pin}: {wire} | {english(code)}", 14, maxw=w-24)
        ENDPOINTS.append(dict(unit=unit, name=name, connector=conn, pins=pins, source=source, note=note))
        return y+44

    def panel(self, x, y, w, h, letter, title, subtitle, color):
        self.rect(x, y, w, h)
        self.rect(x+1, y+1, w-2, 7, fill=color, stroke=None, radius=0)
        self.text(x+22, y+37, f"{letter}  {title}", 25, color, True, maxw=w-44)
        self.text(x+22, y+64, subtitle, 15, MUTED, maxw=w-44)

    def source(self, x, y, key, width):
        code, path = SOURCES[key]
        self.text(x, y, f"Source {key}: {code} | p.1", 13, MUTED, maxw=width)
        target = SOURCE_ROOT / path
        assert target.exists(), target
        self.pdf.linkURL(target.as_uri(), (x, H-y-4, x+width, H-y+14), relative=0, thickness=0)

    def finish(self):
        self.svg.append("</svg>")
        (OUT / "ecmpilot-can-map.svg").write_text("\n".join(self.svg), encoding="utf-8")
        self.pdf.showPage(); self.pdf.save()
        for x, y, w, size, s in self.bounds:
            assert x+w < W-15, f"Outside page: {s}"
        ElementTree.parse(OUT / "ecmpilot-can-map.svg")
        reader = PdfReader(OUT / "ecmpilot-can-map.pdf")
        assert len(reader.pages) == 1
        images = list(reader.pages[0].images)
        assert not images, "PDF must contain vector drawing and text only"
        txt = reader.pages[0].extract_text()
        for term in ("BSI1", "84C4", "1775", "7800", "9034C", "9A35A", "93P5B", "owner-confirmed", "unresolved"):
            assert term in txt, term
        assert len(ENDPOINTS) >= 35
        record = {
            "revision": "2026-10-28 / 2", "vehicle": "Opel Corsa-e P2JO / P2J0",
            "scope": "Consolidated CAN topology, not a complete vehicle harness or VIN-verified installation pinout",
            "sources": {k: {"sheet": v[0], "path": str(SOURCE_ROOT / v[1]), "pages": [1, 2, 3] if k == "P" else [1]} for k, v in SOURCES.items()},
            "owner_evidence": {"U01": "Owner states VCU 1775 internally connects OBC eCAN branch and motor/battery eCAN branch. No measurement record supplied."},
            "unresolved_colour_codes": ["RB", "BB"],
            "endpoints": ENDPOINTS,
            "verification": {"page_count": 1, "embedded_images": len(images), "endpoint_count": len(ENDPOINTS), "text_labels": len(self.bounds)},
        }
        (OUT / "ecmpilot-can-map-data.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
        print(json.dumps(record["verification"]))


d = Drawing()
d.rect(0, 0, W, H, BG, None, 0)
d.text(42, 65, "eCMPilot | Corsa-e CAN networks", 44, bold=True)
d.text(44, 105, "Consolidated vector schematic | English wire colours | P2JO / P2J0 wiring collection | revision 2 / 2026-10-28", 21, MUTED)
d.text(2540, 61, "ENGINEERING REFERENCE", 22, WARN, True)
d.text(2540, 91, "Source-derived wiring; vehicle fitment not yet verified", 17, MUTED)

# Architectural view. Box membership does not create electrical continuity.
d.rect(40, 145, 3520, 455)
d.text(62, 178, "ARCHITECTURE / INTERNAL CONNECTIONS", 18, bold=True)
d.text(62, 203, "Details A-F below. Repeated unit IDs refer to the same ECU. Each coloured network line represents a CAN conductor pair.", 16, MUTED)

d.rect(1230, 230, 570, 300, "#edf3f7", INK)
d.text(1250, 260, "BSI1 | Body systems interface", 24, bold=True)
d.text(1250, 286, "Driver-side lower dashboard / interior fuse assembly", 15, MUTED)
d.text(1250, 315, "HS CAN1", 15, COL["hs1"], True)
d.text(1250, 414, "ENT INFO", 15, COL["info"], True)
d.text(1440, 499, "Dashed internal paths = ?", 16, WARN, True)

# Only same-family candidate ties are indicated; these are not verified BSI connections.
d.line([(1240, 337), (1740, 337), (1740, 370), (1240, 370)], WARN, 2, True)
d.text(1430, 327, "? unverified", 14, WARN)
d.line([(1240, 438), (1740, 438), (1740, 466)], WARN, 2, True)
d.text(1420, 431, "? unverified", 14, WARN)
d.text(1360, 472, "Second ENT INFO port: Yellow 34/36", 14, MUTED)

d.rect(80, 255, 400, 120, stroke=COL["hs1"])
d.lines(96, 284, ["84C4 | Telematics / eCall", "18-way Black: 8/17 = HS CAN1", "18-way Black: 9/18 = ENT INFO"], 18, 28)
d.pair(480, 1230, 337, COL["hs1"])
d.text(630, 323, "A: 60-way Blue / 10,8", 17, COL["hs1"])
d.line([(480, 355), (540, 355), (540, 438), (1230, 438)], COL["info"], 3)
d.text(630, 426, "B: 60-way Blue / 27,29", 17, COL["info"])
d.rect(650, 450, 440, 110, stroke=COL["hs1"])
d.text(665, 474, "C001 | Both OBD diagnostic pairs [Q]", 18, bold=True)
d.lines(665, 497, ["6/14: 9064 Grey / 9065 Brown", "3/8: 9028 Grey / 9029 Brown"], 17, 23)
d.text(665, 544, "BSI 60-way Black: 15/13 and 8/10, respectively", 15, MUTED)
d.line([(1090, 497), (1130, 497), (1130, 370), (1230, 370)], COL["hs1"], 3)
d.line([(1090, 520), (1230, 520)], MUTED, 3)
d.text(1250, 522, "Diag2 8/10: network assignment ?", 14, MUTED)
ENDPOINTS.append(dict(unit="C001", connector="16V NR", pins=[(3,"9028","GR"),(8,"9029","MR")], source="Q", note="Second diagnostic pair; internal network assignment unknown"))
ENDPOINTS.append(dict(unit="BSI1", connector="60V NR", pins=[(8,"9028","GR"),(10,"9029","MR")], source="Q", note="Second OBD diagnostic pair; no join to named CAN networks established"))
d.text(80, 583, "BCM6 = roof SOS console, not 84C4. No CAN pair is drawn through its IC95A connection. [T]", 17, MUTED)

d.rect(2150, 255, 400, 165, stroke=COL["hs1"])
d.text(2167, 283, "7800 | ESP / stability ECU", 21, bold=True)
d.lines(2167, 309, ["46-way Black", "Main bus: 5/19 | VCU branch: 4/18"], 16, 25)
d.line([(2150, 337), (2170, 337), (2170, 357), (2550, 357)], WARN, 2, True)
d.text(2250, 381, "? internal continuity", 15, WARN)
d.pair(1800, 2150, 337, COL["hs1"])
d.text(1840, 322, "A: main HS CAN1", 17, COL["hs1"])
d.pair(2550, 2840, 357, COL["hs1"])
d.text(2570, 339, "A: 9A35Z / 9A36Z", 15, COL["hs1"])

d.rect(2840, 255, 650, 165, stroke=COL["ev"])
d.text(2857, 283, "1775 | Electric drivetrain supervisor / VCU", 21, bold=True)
d.lines(2857, 310, ["HS CAN1 + HS CAN2 interfaces", "eCAN: 120-way Black 36/57 + 70-way Black 35/51"], 17, 25)
d.text(2930, 389, "Internal eCAN tie: owner-confirmed [U01]", 17, COL["ev"], True)
d.lines(1900, 464, ["D: HS CAN2   |   E: passenger-compartment CAN   |   F: ADAS", "C: VCU eCAN feeds motor / battery / OBC. F: local headlamp CAN behind 6606.", "Only the VCU eCAN internal tie is confirmed by owner evidence; BSI/ESP ties remain hypotheses."], 17, 32, color=MUTED)

# A. Preserve all separate HS CAN1 external runs, including the ESP-to-VCU branch.
X, Y, PW = 40, 640, 1130
c = COL["hs1"]
d.panel(X, Y, PW, 1005, "A", "HS CAN1 / telematics + OBD + vehicle", "Separate BSI ports and ESP ports are deliberately not shorted together.", c)

def direct(x, y, w, left, right, color, source):
    lw, rw = 410, w-610-20
    cy = d.card(x+20, y, lw, *left, source, color)
    d.pair(x+20+lw, x+610, cy, color)
    d.card(x+610, y, rw, *right, source, color)

direct(X, 735, PW,
       ("BSI1", "Telematics port", "60V BE", [(10, "9A35A", "VE"), (8, "9A36A", "MR")]),
       ("84C4", "Telematics / eCall", "18V NR", [(8, "9A35A", "VE"), (17, "9A36A", "MR")]), c, "A,T")
direct(X, 835, PW,
       ("BSI1", "OBD port", "60V NR", [(15, "9064", "GR"), (13, "9065", "MR")]),
       ("C001", "Diagnostic socket / OBD", "16V NR", [(6, "9064", "GR"), (14, "9065", "MR")]), c, "A")
direct(X, 935, PW,
       ("BSI1", "Camera port - variant", "35V NR", [(26, "9A35", "RS"), (24, "9A36", "OR")]),
       ("7573", "Driver-assistance camera", "12V BE", [(8, "9A35", "RS"), (9, "9A36", "OR")]), c, "A")

def group(x, y, root, nodes, color, source, step=95):
    rootcy = d.card(x+20, y, 340, *root, source, color)
    spine = x+410
    d.pair(x+360, spine, rootcy, color)
    d.line([(spine, rootcy), (spine, y+(len(nodes)-1)*step+44)], color, 3)
    for i, node in enumerate(nodes):
        cy = d.card(x+460, y+i*step, 650, *node, source, color)
        d.line([(spine, cy), (x+460, cy)], color, 3)
        d.dot(spine, cy, color, 4)

group(X, 1040,
      ("BSI1", "Main vehicle port", "60V NR", [(5, "9A35C", "BG"), (3, "9A36C", "GR")]), [
          ("1603", "Drive selector", "6V BE", [(1, "9A35L", "VE"), (4, "9A36L", "VE")]),
          ("6606", "Headlamp-control ECU", "18V NR", [(7, "9A35G", "MR"), (16, "9A36G", "VE")]),
          ("7126", "Steering ECU", "3V GR", [(2, "9A35F", "RB"), (3, "9A36F", "BE")]),
          ("7500", "Parking ECU - variant", "18V NR", [(8, "9A35Y", "BA"), (9, "9A36Y", "VI")]),
          ("7800", "ESP - main bus port", "46V NR", [(5, "9A35H", "GR"), (19, "9A36H", "GR")]),
      ], c, "A,AP")
d.lines(X+22, 1171, ["Shared splice network:", "E935B / E936B", "E935A / E936A", "E935F / E936", "", "Branch suffixes are preserved.", "Physical splice positions omitted.", "", "ESP 4/18 is a different port:", "see direct VCU run below.", "Internal ESP tie remains ?"], 16, 25, color=MUTED)
direct(X, 1530, PW,
       ("1775", "VCU - HS CAN1", "70V NR", [(53, "9A35Z", "RS/BA"), (37, "9A36Z", "VE/BA")]),
       ("7800", "ESP - VCU branch port", "46V NR", [(4, "9A35Z", "RS/BA"), (18, "9A36Z", "VE/BA")]), c, "A,AP")
d.source(X+22, 1634, "A", PW-44)

# B. Hands-free is on the dashboard-splice network, not the separate yellow BSI port.
X, PW = 1205, 1130
c = COL["info"]
d.panel(X, Y, PW, 1005, "B", "ENT INFO / infotainment + telematics", "E934 / E935 share the dashboard pair; the second BSI run is separate.", c)
group(X, 735,
      ("BSI1", "Dashboard branch", "60V BE", [(27, "9034", "RS"), (29, "9035", "VI")]), [
          ("84C4", "Telematics / eCall", "18V NR", [(9, "9034C", "RS"), (18, "9035C", "VI")]),
          ("8480", "Radio / infotainment", "56V NR", [(10, "9034H", "RS"), (21, "9035H", "VI")]),
          ("0004", "Instrument cluster", "18V BA", [(9, "9034D", "RS"), (7, "9035D", "VI")]),
          ("84C6", "Multifunction panel", "6V NR", [(5, "9034R", "RS"), (4, "9035R", "VI")]),
          ("8240", "Hands-free access - via IC91B", "26V BE", [(21, "9034K", "BA"), (22, "9035K", "VE")]),
      ], c, "B,BP")
d.lines(X+22, 875, ["E934 = 9034 conductors", "E935 = 9035 conductors", "", "8240 path through IC91B:", "22-way Black / pins 14,15", "9034A Pink -> 9034K White", "9035A Violet -> 9035K Green", "", "No external join to the", "60-way Yellow BSI run is", "shown on the source sheets."], 16, 25, color=MUTED)
direct(X, 1250, PW,
       ("BSI1", "Second ENT INFO run", "60V JN", [(34, "9034A", "BE"), (36, "9035A", "BG")]),
       ("7500", "Parking ECU - variant", "26V JN", [(26, "9034A", "BE"), (25, "9035A", "BG")]), c, "B,BP")
d.text(X+22, 1380, "TELEMATICS POWER / GROUND REFERENCE (not CAN)", 18, bold=True)
d.card(X+20, 1400, 410, "BSI1", "F14 / 5 A supply", "16V GR", [(5, "ZE55A", "BG")], "T,P", MUTED)
d.line([(X+430, 1444), (X+610, 1444)], MUTED, 2)
d.text(X+446, 1428, "EZ55", 16, MUTED)
d.card(X+610, 1400, 500, "84C4", "Supply", "18V NR", [(14, "ZE55C", "RS")], "T,P", MUTED)
d.lines(X+22, 1520, ["Supply: +BAT_CSP classification; continuous KL30 availability has not been measured.", "Ground: 84C4 / 18-way Black / pin 16, MD84C Green/Yellow -> EM50B -> body earth MC50B.", "The grey reference line above links only supply pins via EZ55; ground is a separate circuit."], 15, 24, color=MUTED)
d.source(X+22, 1611, "B", PW-44)
d.source(X+22, 1634, "T", PW-44)

# C. Owner-confirmed internal continuity is shown explicitly inside VCU 1775.
X, PW = 2370, 1190
c = COL["ev"]
d.panel(X, Y, PW, 1005, "C", "eCAN / VCU + traction system + OBC", "93P5 / 93P6 | Internal OBC-to-motor/battery branch continuity: owner-confirmed.", c)
d.rect(X+20, 735, 410, 415, "#eff8f3", c)
d.text(X+35, 764, "1775 | VCU", 24, c, True)
d.text(X+35, 791, "Electric drivetrain supervisor", 18)
d.rect(X+35, 815, 380, 88, stroke=c)
d.lines(X+48, 838, [connector("120V NR"), "36: 93P5 | Green", "57: 93P6 | Pink"], 16, 24)
ENDPOINTS.append(dict(unit="1775", connector="120V NR", pins=[(36,"93P5","VE"),(57,"93P6","RS")], source="C"))
d.rect(X+35, 1030, 380, 88, stroke=c)
d.lines(X+48, 1053, [connector("70V NR"), "35: 93P5B | Green", "51: 93P6B | Green"], 16, 24)
ENDPOINTS.append(dict(unit="1775", connector="70V NR", pins=[(35,"93P5B","VE"),(51,"93P6B","VE")], source="C"))
d.line([(X+215, 903), (X+215, 1030)], c, 3)
d.rect(X+42, 938, 360, 48, "#eff8f3", None)
d.text(X+50, 959, "Internal pair connection [U01]", 18, c, True)
d.text(X+50, 979, "owner-confirmed; no measurement record", 14, MUTED)
d.pair(X+415, X+490, 859, c)
d.line([(X+490, 784), (X+490, 1100)], c, 3)
for i, node in enumerate([
    ("70B4", "Park actuator", "4V NR", [(2, "93P5A", "BG"), (3, "93P6A", "RG")]),
    ("1917", "Traction-motor controller", "10V NR", [(2, "93P5C", "JN"), (7, "93P6C", "BG")]),
    ("1778", "Traction-battery interface", "27V NR", [(2, "93P5B", "BA"), (1, "93P6B", "VE")]),
]):
    cy = d.card(X+540, 740+i*140, 625, *node, "C", c)
    d.line([(X+490, cy), (X+540, cy)], c, 3); d.dot(X+490, cy, c, 4)
d.lines(X+535, 1198, ["Shared splices:", "E935J = 93P5 family", "E936J = 93P6 family"], 18, 28, color=MUTED)
d.line([(X+215, 1118), (X+215, 1270)], c, 3)
d.text(X+240, 1222, "OBC branch", 18, c, True)
d.card(X+20, 1270, 410, "1708", "Charger / DC-DC", "32V NR", [("E2", "93P5B", "VE"), ("E1", "93P6B", "VE")], "C", c)
d.lines(X+22, 1400, ["The two externally drawn eCAN runs are joined inside 1775 according to the owner [U01].", "The source sheet alone does not depict this internal continuity. No VCU cross-bus forwarding", "or electrical join to HS CAN1 / HS CAN2 is implied.", "", "93P5B / 93P6B at 1708 and 1778 are distinct external wire runs; identical labels", "must not be used to bypass the VCU branch routing."], 17, 28, color=MUTED)
d.source(X+22, 1634, "C", PW-44)

# D. Baseline is wiring 1; radar and loop alternatives are textually separated.
X, Y, PW = 40, 1680, 1130
c = COL["hs2"]
d.panel(X, Y, PW, 630, "D", "HS CAN2 / 9A37 + 9A38", "Baseline shown: D3CZV77G. Other equipment layouts are listed separately.", c)
group(X, 1770,
      ("BSI1", "Main HS CAN2", "60V NR", [(46, "9A37", "MR"), (44, "9A38", "BG")]), [
          ("1775", "VCU - HS CAN2", "70V NR", [(48, "9A37E", "RG"), (32, "9A38E", "VI")]),
          ("7800", "ESP - HS CAN2", "46V NR", [(11, "9A37A", "BE"), (25, "9A38A", "BA")]),
          ("2524", "Pedestrian sound - via IC02A", "4V NR", [(3, "9A37", "BA"), (2, "9A38", "JN")]),
      ], c, "D", step=92)
d.lines(X+22, 1894, ["Splices E937 / E938", "IC02A: 10-way Black / 4,8", "9A37B Yellow -> 9A37 White", "9A38B White -> 9A38 Yellow"], 15, 23, color=MUTED)
direct(X, 2060, PW,
       ("BSI1", "Camera run - baseline", "35V NR", [(8, "9A37", "VI"), (6, "9A38", "VE")]),
       ("7573", "Camera - HS CAN2", "12V BE", [(4, "9A37", "VI"), (5, "9A38", "VE")]), c, "D")
d.lines(X+22, 2178, ["Alternative D3CZV78G: radar 7571 on main bus via ICY4 / 5,2; radar 6-way Black / 5,4.", "Radar-local 9A37 = Green; 9A38 = Red. Incoming 9A37C = Pink; 9A38C = Beige.", "Alternative D3CZV76G: BSI 35-way Black 8,22 (9091 Pink) / 6,20 (9092 Blue) loop.", "Camera 35-way Black / 6,8 assignments differ in ADAS layout F. Do not combine variants."], 15, 23, color=MUTED)
d.source(X+22, 2297, "D", PW-44)

# E. Airbag belongs to the brown-connector branch via IC91B, not the blue BSI branch.
X, PW = 1205, 1130
c = COL["cabin"]
d.panel(X, Y, PW, 630, "E", "Passenger-compartment CAN / 9038 + 9039", "Two external BSI branches; no internal continuity is asserted.", c)
group(X, 1770,
      ("BSI1", "Blue-connector branch", "60V BE", [(16, "9038F", "BA"), (18, "9039F", "GR")]), [
          ("CV00", "Steering-column controls", "18V NR", [(12, "9038G", "BA"), (13, "9039G", "GR")]),
          ("8080", "Climate-control ECU", "6V NR", [(4, "9038D", "BA"), (5, "9039D", "GR")]),
          ("AE00", "Steering lock", "6V BA", [(4, "9038E", "BA"), (5, "9039E", "GR")]),
      ], c, "E,EP", step=92)
d.lines(X+22, 1900, ["Blue branch:", "E938B / E939A", "", "Brown branch:", "E938A / E939"], 16, 24, color=MUTED)
group(X, 2072,
      ("BSI1", "Brown-connector branch", "60V MR", [(23, "9038J", "BG"), (25, "9039J", "BE")]), [
          ("6570", "Airbag ECU - via IC91B / 19,20", "30V NR", [(30, "9038C", "BE"), (29, "9039C", "VI")]),
          ("6036", "Driver door - via ICV4B / 17,18", "20V JN", [(2, "9038", "OR"), (9, "9039", "BE")]),
      ], c, "E,EP", step=95)
d.lines(X+22, 2200, ["IC91B: 22-way Black", "9038K Beige -> 9038C Blue", "9039K Blue -> 9039C Violet", "ICV4B: 22-way Yellow", "9038L Beige -> 9038 Orange"], 14, 18, color=MUTED)
d.source(X+22, 2297, "E", PW-44)

# F. Separate ADAS runs and a local lighting bus. No connection to VCU/eCall is invented.
X, PW = 2370, 1190
c = COL["adas"]
d.panel(X, Y, PW, 630, "F", "ADAS + local headlamp CAN", "Equipment alternatives. Camera pins overlap with layout D but wire families differ.", c)
direct(X, 1770, PW,
       ("BSI1", "ADAS radar port", "60V NR", [(28, "9A33B", "BB"), (26, "9A34B", "BG/BA")]),
       ("7571", "Radar - via ICY4 / 4,1", "6V NR", [(2, "9A33", "VI"), (1, "9A34", "BG")]), c, "F")
d.text(X+22, 1883, "ICY4 = 10-way Green. RB / BB colour codes are preserved as unresolved, not guessed.", 15, MUTED)
direct(X, 1900, PW,
       ("BSI1", "ADAS camera port", "35V NR", [(6, "9A33", "VI"), (8, "9A34", "MR")]),
       ("7573", "Camera - ADAS layout", "12V BE", [(5, "9A33", "VI"), (4, "9A34", "MR")]), c, "F")
d.source(X+22, 2008, "F", PW-44)
d.line([(X+22, 2025), (X+PW-22, 2025)], GRID, 1)
c = COL["lamp"]
group(X, 2043,
      ("6606", "Local headlamp CAN", "18V NR", [(15, "9A43A", "GR"), (6, "9A44A", "BA")]), [
          ("2610", "Left headlamp", "10V NR", [(2, "9A43B", "RG"), (7, "9A44B", "BG")]),
          ("2615", "Right headlamp", "10V NR", [(2, "9A43C", "MR"), (7, "9A44C", "VE")]),
      ], c, "L", step=100)
d.lines(X+22, 2173, ["Splices E261L / E261H", "6606 HS CAN1 port is in A.", "Internal forwarding is unspecified."], 15, 23, color=MUTED)
d.source(X+22, 2297, "L", PW-44)

# Legend and provenance, printed on the same sheet.
d.rect(40, 2340, 3520, 172)
d.text(62, 2369, "HOW TO READ THIS SHEET", 17, bold=True)
d.lines(62, 2397, ["Solid network line: one CAN pair. Both conductors are listed at each endpoint. Dots: simplified shared splices.", "Solid green inside VCU [U01]: internal eCAN tie reported by the owner. Dashed amber + ?: unverified internal tie.", "Card rows = cavity : printed wire ID | English physical wire colour. Network stroke colours are only visual grouping.", "H/L polarity and bitrate are not assigned. Connector cavity viewing direction is not established."], 16, 26, color=MUTED)
d.text(1510, 2369, "FR COLOUR CODES -> ENGLISH", 17, bold=True)
d.lines(1510, 2397, ["NR Black | BE Blue | BA White | GR Grey | VE Green", "MR Brown | RS Pink | VI Violet | BG Beige | RG Red", "OR Orange | JN Yellow | VJ Green/Yellow | ND Unspecified", "RB / BB: unresolved. Slash colours retain both components."], 16, 26, color=MUTED)
d.text(2495, 2369, "SCOPE / TRACEABILITY", 17, bold=True)
d.lines(2495, 2397, ["Source keys A-F / T / L / Q are resolved in the adjacent README and JSON.", "OBD [Q]: D3CZV7FG. Power: T + BSI fuse allocation [P]. U01: owner, 2026-10-28.", "Not a full harness: LIN, diesel DEPOLLUTION and trailer options omitted.", "Variants are not a single verified vehicle configuration. No CAN commands implied."], 16, 26, color=MUTED)
d.finish()
