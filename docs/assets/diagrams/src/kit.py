"""
Diagram toolkit: a tiny SVG scene builder for PayFlo's documentation diagrams.

Handles: the shared dark theme, group panels, icon nodes, routed connectors with labels, legends, sequence
diagrams and entity-relationship cards, and exporting each scene to SVG. Icons are Simple Icons (CC0) paths
loaded from ./icons, tinted with each brand's colour, plus a few hand-drawn generic glyphs.
"""
import os, re, html

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = "'Segoe UI', 'Inter', system-ui, -apple-system, Helvetica, Arial, sans-serif"
MONO = "'Cascadia Code', 'JetBrains Mono', Consolas, 'SFMono-Regular', monospace"

BG = "#0b0f17"
CARD = "#121826"
CARD_EDGE = "#263042"
TEXT = "#e6edf3"
MUTED = "#8b98a9"
LINE = "#7d8aa0"

C = {
    "green": "#3fb950", "blue": "#58a6ff", "red": "#ff7b72", "purple": "#bc8cff", "teal": "#39c5cf",
    "yellow": "#e3b341", "orange": "#f0883e", "pink": "#f778ba", "gray": "#8b98a9",
}

BRAND = {
    "spring": "#6db33f", "springboot": "#6db33f", "kubernetes": "#5b8def", "k3s": "#ffc61c",
    "postgresql": "#6b9bff", "redis": "#ff4438", "react": "#61dafb", "nodedotjs": "#5fa04e",
    "minio": "#e8455f", "firebase": "#ffca28", "stripe": "#8a84ff", "cloudflare": "#f38020",
    "githubactions": "#2f95ff", "github": "#e6edf3", "tailscale": "#e6edf3", "docker": "#2496ed",
    "nginx": "#1fb85a", "vite": "#8f94ff", "typescript": "#3178c6", "oracle": "#f80000",
    "tailwindcss": "#06b6d4", "flyway": "#e0413a", "apachemaven": "#e0413a", "openjdk": "#e6edf3",
}

_icon_cache = {}


def _load_icon(slug):
    if slug not in _icon_cache:
        s = open(os.path.join(HERE, "icons", slug + ".svg"), encoding="utf-8").read()
        _icon_cache[slug] = re.findall(r'<path d="([^"]+)"', s)
    return _icon_cache[slug]


GLYPHS = {
    "browser": '<rect x="2" y="4" width="20" height="14" rx="2" fill="none" stroke="{c}" stroke-width="1.8"/>'
               '<path d="M2 8h20" stroke="{c}" stroke-width="1.8"/><circle cx="5" cy="6" r=".8" fill="{c}"/>'
               '<circle cx="7.5" cy="6" r=".8" fill="{c}"/><path d="M8 21h8M12 18v3" stroke="{c}" stroke-width="1.8"/>',
    "user": '<circle cx="12" cy="8" r="4" fill="none" stroke="{c}" stroke-width="1.8"/>'
            '<path d="M4 21c0-4.4 3.6-7 8-7s8 2.6 8 7" fill="none" stroke="{c}" stroke-width="1.8"/>',
    "db": '<ellipse cx="12" cy="5.5" rx="8" ry="3" fill="none" stroke="{c}" stroke-width="1.8"/>'
          '<path d="M4 5.5v13c0 1.7 3.6 3 8 3s8-1.3 8-3v-13M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3" fill="none" stroke="{c}" stroke-width="1.8"/>',
    "gear": '<circle cx="12" cy="12" r="3.2" fill="none" stroke="{c}" stroke-width="1.8"/>'
            '<path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1" stroke="{c}" stroke-width="1.8" stroke-linecap="round"/>',
    "compass": '<circle cx="12" cy="12" r="9" fill="none" stroke="{c}" stroke-width="1.8"/>'
               '<path d="M15.5 8.5l-2 5-5 2 2-5z" fill="{c}"/>',
    "router": '<path d="M4 7h11M11 3l4 4-4 4M20 17H9M13 13l-4 4 4 4" fill="none" stroke="{c}" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>',
    "sparkle": '<path d="M12 2l2.2 6.3L20.5 10l-6.3 2.2L12 18.5l-2.2-6.3L3.5 10l6.3-1.7z" fill="{c}"/>'
               '<path d="M19 15l.9 2.3 2.1.7-2.1.8L19 21l-.9-2.2-2.1-.8 2.1-.7z" fill="{c}"/>',
    "clock": '<circle cx="12" cy="12" r="9" fill="none" stroke="{c}" stroke-width="1.8"/>'
             '<path d="M12 7v5l3.5 2" fill="none" stroke="{c}" stroke-width="1.8" stroke-linecap="round"/>',
    "lock": '<rect x="4.5" y="10" width="15" height="11" rx="2" fill="none" stroke="{c}" stroke-width="1.8"/>'
            '<path d="M8 10V7a4 4 0 0 1 8 0v3" fill="none" stroke="{c}" stroke-width="1.8"/>',
    "box": '<path d="M12 2.5l8.5 4.7v9.6L12 21.5l-8.5-4.7V7.2z" fill="none" stroke="{c}" stroke-width="1.8" stroke-linejoin="round"/>'
           '<path d="M3.5 7.2L12 12l8.5-4.8M12 12v9.5" fill="none" stroke="{c}" stroke-width="1.8"/>',
    "bucket": '<path d="M4 6h16l-2 14H6z" fill="none" stroke="{c}" stroke-width="1.8" stroke-linejoin="round"/>'
              '<ellipse cx="12" cy="6" rx="8" ry="2.2" fill="none" stroke="{c}" stroke-width="1.8"/>',
    "cloud": '<path d="M7 18h10.5a4 4 0 0 0 .5-8 6 6 0 0 0-11.3-1.5A4.8 4.8 0 0 0 7 18z" fill="none" stroke="{c}" stroke-width="1.8" stroke-linejoin="round"/>',
    "pod": '<rect x="3" y="3" width="18" height="18" rx="4" fill="none" stroke="{c}" stroke-width="1.8"/>'
           '<path d="M8 9h8M8 12h8M8 15h5" stroke="{c}" stroke-width="1.8" stroke-linecap="round"/>',
    "check": '<circle cx="12" cy="12" r="9" fill="none" stroke="{c}" stroke-width="1.8"/>'
             '<path d="M7.5 12.5l3 3 6-6.5" fill="none" stroke="{c}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    "undo": '<path d="M9 7L4 12l5 5M4 12h10a6 6 0 0 1 0 12" fill="none" stroke="{c}" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" transform="translate(0,-4)"/>',
    "key": '<circle cx="8" cy="12" r="4" fill="none" stroke="{c}" stroke-width="1.8"/>'
           '<path d="M12 12h9M18 12v3M21 12v2" stroke="{c}" stroke-width="1.8" stroke-linecap="round"/>',
    "code": '<path d="M8 7l-5 5 5 5M16 7l5 5-5 5M14 4l-4 16" fill="none" stroke="{c}" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>',
    "shield": '<path d="M12 2.5l8 3v6c0 5-3.4 8.6-8 10-4.6-1.4-8-5-8-10v-6z" fill="none" stroke="{c}" stroke-width="1.8" stroke-linejoin="round"/>',
    "layers": '<path d="M12 3l9 5-9 5-9-5z M3 13l9 5 9-5" fill="none" stroke="{c}" stroke-width="1.8" stroke-linejoin="round"/>',
    "flag": '<path d="M5 21V4M5 4h11l-2 4 2 4H5" fill="none" stroke="{c}" stroke-width="1.8" stroke-linejoin="round"/>',
    "terminal": '<rect x="2.5" y="4" width="19" height="16" rx="2" fill="none" stroke="{c}" stroke-width="1.8"/>'
                '<path d="M6.5 9l3 3-3 3M11.5 15h5" fill="none" stroke="{c}" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>',
    "stream": '<rect x="2.5" y="4" width="19" height="4" rx="1.5" fill="none" stroke="{c}" stroke-width="1.8"/>'
              '<rect x="2.5" y="10" width="19" height="4" rx="1.5" fill="none" stroke="{c}" stroke-width="1.8"/>'
              '<rect x="2.5" y="16" width="19" height="4" rx="1.5" fill="none" stroke="{c}" stroke-width="1.8"/>'
              '<path d="M6 6h2M6 12h5M6 18h8" stroke="{c}" stroke-width="1.8" stroke-linecap="round"/>',
    "card": '<rect x="2.5" y="5" width="19" height="14" rx="2.5" fill="none" stroke="{c}" stroke-width="1.8"/>'
            '<path d="M2.5 9.5h19" stroke="{c}" stroke-width="2.4"/><path d="M6 15h4" stroke="{c}" stroke-width="1.8" stroke-linecap="round"/>',
    "bank": '<path d="M3 9.5L12 4l9 5.5z" fill="none" stroke="{c}" stroke-width="1.8" stroke-linejoin="round"/>'
            '<path d="M5.5 10.5v7M9.8 10.5v7M14.2 10.5v7M18.5 10.5v7M3 20h18" stroke="{c}" stroke-width="1.8" stroke-linecap="round"/>',
    "webhook": '<circle cx="12" cy="6.5" r="2.6" fill="none" stroke="{c}" stroke-width="1.8"/>'
               '<circle cx="5.5" cy="17.5" r="2.6" fill="none" stroke="{c}" stroke-width="1.8"/>'
               '<circle cx="18.5" cy="17.5" r="2.6" fill="none" stroke="{c}" stroke-width="1.8"/>'
               '<path d="M10.6 8.8L6.9 15M8.1 17.5h7.8M13.4 8.8l3.7 6.2" stroke="{c}" stroke-width="1.8" stroke-linecap="round"/>',
}


def esc(s):
    return html.escape(str(s), quote=True)


def text_w(s, size, bold=False, mono=False):
    k = 0.60 if mono else (0.575 if bold else 0.54)
    return len(str(s)) * size * k


def icon(name, x, y, size, color=None):
    """Draw icon `name` with its top-left at (x, y), `size` px square."""
    sc = size / 24.0
    if name in GLYPHS:
        col = color or TEXT
        return f'<g transform="translate({x:.1f},{y:.1f}) scale({sc:.4f})">{GLYPHS[name].format(c=col)}</g>'
    col = color or BRAND.get(name, TEXT)
    paths = "".join(f'<path d="{d}"/>' for d in _load_icon(name))
    return f'<g transform="translate({x:.1f},{y:.1f}) scale({sc:.4f})" fill="{col}">{paths}</g>'


def hex_rgba(h, a):
    h = h.lstrip("#")
    return f"rgba({int(h[0:2],16)},{int(h[2:4],16)},{int(h[4:6],16)},{a})"


class Node:
    def __init__(self, x, y, w, h):
        self.x, self.y, self.w, self.h = x, y, w, h

    @property
    def cx(self): return self.x + self.w / 2

    @property
    def cy(self): return self.y + self.h / 2

    def l(self, dy=0): return (self.x, self.cy + dy)

    def r(self, dy=0): return (self.x + self.w, self.cy + dy)

    def t(self, dx=0): return (self.cx + dx, self.y)

    def b(self, dx=0): return (self.cx + dx, self.y + self.h)


class Scene:
    def __init__(self, w, h, title=None, subtitle=None, grid=True):
        self.w, self.h = w, h
        self.back, self.mid, self.front = [], [], []
        self.markers = {}
        self.title, self.subtitle, self.grid = title, subtitle, grid

    def _marker(self, color):
        mid = "a" + color.lstrip("#")
        self.markers[mid] = color
        return mid

    def group(self, x, y, w, h, label, color, glyph=None, sub=None, dashed=False):
        dash = ' stroke-dasharray="6 5"' if dashed else ""
        self.back.append(
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="14" fill="{hex_rgba(color, 0.055)}" '
            f'stroke="{hex_rgba(color, 0.55)}" stroke-width="1.4"{dash}/>')
        cw = text_w(label, 11.5, True) * 1.08 + len(label) * 0.9 + (30 if glyph else 16) + 10
        self.back.append(f'<rect x="{x+12}" y="{y-11}" width="{cw:.0f}" height="22" rx="6" fill="{BG}" '
                         f'stroke="{hex_rgba(color, 0.7)}" stroke-width="1.1"/>')
        tx = x + 20
        if glyph:
            self.back.append(icon(glyph, x + 19, y - 7, 14, color))
            tx += 20
        self.back.append(f'<text x="{tx}" y="{y+4}" font-family="{FONT}" font-size="11.5" font-weight="700" '
                         f'letter-spacing="0.9" fill="{color}">{esc(label.upper())}</text>')
        if sub:
            self.back.append(f'<rect x="{x+12+cw+4:.0f}" y="{y-8}" width="{text_w(sub,11)+12:.0f}" height="16" fill="{BG}"/>')
            self.back.append(f'<text x="{x+12+cw+10:.0f}" y="{y+4}" font-family="{FONT}" font-size="11" '
                             f'fill="{MUTED}">{esc(sub)}</text>')

    def node(self, x, y, label, ic=None, sub=None, w=None, h=None, accent=None, icolor=None, badge=None,
             layout="stack", mono_sub=False):
        """Card with an icon. layout='stack' puts the icon above the text; 'row' puts it on the left."""
        lsize, ssize = 13.5, 11
        if layout == "row":
            tw = max(text_w(label, lsize, True), text_w(sub or "", ssize, mono=mono_sub))
            w = w or int(tw + (62 if ic else 30))
            h = h or (54 if sub else 42)
        else:
            tw = max(text_w(label, lsize, True), text_w(sub or "", ssize, mono=mono_sub))
            w = w or int(max(tw + 28, 110))
            h = h or (92 if sub else 76)
        stroke = hex_rgba(accent, 0.6) if accent else CARD_EDGE
        self.mid.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="11" fill="{CARD}" stroke="{stroke}" '
                        f'stroke-width="1.3" filter="url(#sh)"/>')
        if accent:
            self.mid.append(f'<rect x="{x+1}" y="{y+1}" width="{w-2}" height="3" rx="1.5" fill="{hex_rgba(accent,0.85)}"/>')
        subfont = MONO if mono_sub else FONT
        if layout == "row":
            tx = x + 14
            if ic:
                self.mid.append(icon(ic, x + 13, y + h / 2 - 13, 26, icolor))
                tx = x + 50
            ty = y + h / 2 + (-3 if sub else 5)
            self.mid.append(f'<text x="{tx}" y="{ty:.1f}" font-family="{FONT}" font-size="{lsize}" font-weight="600" '
                            f'fill="{TEXT}">{esc(label)}</text>')
            if sub:
                self.mid.append(f'<text x="{tx}" y="{ty+16:.1f}" font-family="{subfont}" font-size="{ssize}" '
                                f'fill="{MUTED}">{esc(sub)}</text>')
        else:
            cx = x + w / 2
            if ic:
                self.mid.append(icon(ic, cx - 15, y + 13, 30, icolor))
            ly = y + (60 if ic else 30)
            self.mid.append(f'<text x="{cx:.1f}" y="{ly}" text-anchor="middle" font-family="{FONT}" font-size="{lsize}" '
                            f'font-weight="600" fill="{TEXT}">{esc(label)}</text>')
            if sub:
                self.mid.append(f'<text x="{cx:.1f}" y="{ly+16}" text-anchor="middle" font-family="{subfont}" '
                                f'font-size="{ssize}" fill="{MUTED}">{esc(sub)}</text>')
        if badge:
            bw = text_w(badge, 9.5, True) + 12
            self.mid.append(f'<rect x="{x+w-bw-8:.1f}" y="{y+8}" width="{bw:.1f}" height="16" rx="8" '
                            f'fill="{hex_rgba(accent or C["gray"],0.18)}"/>')
            self.mid.append(f'<text x="{x+w-bw/2-8:.1f}" y="{y+19.5}" text-anchor="middle" font-family="{FONT}" '
                            f'font-size="9.5" font-weight="700" fill="{accent or MUTED}">{esc(badge)}</text>')
        return Node(x, y, w, h)

    def pill(self, x, y, s, color=MUTED, size=10.5, mono=False, anchor="middle", fill=None):
        tw = text_w(s, size, mono=mono)
        bx = x - tw / 2 - 7 if anchor == "middle" else (x - 7 if anchor == "start" else x - tw - 7)
        f = MONO if mono else FONT
        self.front.append(f'<rect x="{bx:.1f}" y="{y-9:.1f}" width="{tw+14:.1f}" height="18" rx="9" fill="{fill or BG}" '
                          f'stroke="{hex_rgba(color,0.35)}" stroke-width="1"/>')
        self.front.append(f'<text x="{bx+7+tw/2:.1f}" y="{y+3.6:.1f}" text-anchor="middle" font-family="{f}" '
                          f'font-size="{size}" fill="{color}">{esc(s)}</text>')

    def edge(self, pts, label=None, color=LINE, dashed=False, at=None, both=False, width=1.6, mono=False,
             lcolor=None, radius=9, arrow=True):
        """Polyline through `pts` with rounded bends and an arrowhead at the end."""
        d = f"M{pts[0][0]:.1f},{pts[0][1]:.1f}"
        for i in range(1, len(pts) - 1):
            (x0, y0), (x1, y1), (x2, y2) = pts[i - 1], pts[i], pts[i + 1]
            def toward(ax, ay, bx, by, r):
                dx, dy = bx - ax, by - ay
                L = max((dx * dx + dy * dy) ** 0.5, 1e-6)
                r = min(r, L / 2)
                return ax + dx / L * r, ay + dy / L * r
            p1 = toward(x1, y1, x0, y0, radius)
            p2 = toward(x1, y1, x2, y2, radius)
            d += f" L{p1[0]:.1f},{p1[1]:.1f} Q{x1:.1f},{y1:.1f} {p2[0]:.1f},{p2[1]:.1f}"
        d += f" L{pts[-1][0]:.1f},{pts[-1][1]:.1f}"
        mid = self._marker(color)
        dash = ' stroke-dasharray="6 5"' if dashed else ""
        start = f' marker-start="url(#s{mid[1:]})"' if both else ""
        if both:
            self.markers["s" + mid[1:]] = color
        self.mid.insert(0, f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{width}"{dash} '
                           f'stroke-linecap="round" stroke-linejoin="round"' + (f' marker-end="url(#{mid})"' if arrow else '') + f'{start}/>')
        if label:
            if at is None:
                best, bi = -1, 0
                for i in range(len(pts) - 1):
                    L = abs(pts[i + 1][0] - pts[i][0]) + abs(pts[i + 1][1] - pts[i][1])
                    if L > best:
                        best, bi = L, i
                at = ((pts[bi][0] + pts[bi + 1][0]) / 2, (pts[bi][1] + pts[bi + 1][1]) / 2)
            self.pill(at[0], at[1], label, lcolor or (color if color != LINE else MUTED), mono=mono)

    def listbox(self, x, y, title, lines, color, w=None, mono=True):
        """A small panel listing endpoints or steps; returns its Node."""
        f = MONO if mono else FONT
        w = w or int(max([text_w(l, 11, mono=mono) for l in lines] + [text_w(title, 11, True)]) + 30)
        h = 34 + 19 * len(lines)
        self.front.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="9" fill="{BG}" stroke="{hex_rgba(color,0.55)}" stroke-width="1.1"/>')
        self.front.append(f'<text x="{x+12}" y="{y+20}" font-family="{FONT}" font-size="11" font-weight="700" letter-spacing="0.5" fill="{color}">{esc(title)}</text>')
        for i, l in enumerate(lines):
            self.front.append(f'<text x="{x+12}" y="{y+40+19*i}" font-family="{f}" font-size="11" fill="{TEXT}">{esc(l)}</text>')
        return Node(x, y, w, h)

    def text(self, x, y, s, size=12, color=MUTED, anchor="start", bold=False, mono=False, layer="front"):
        f = MONO if mono else FONT
        wgt = ' font-weight="700"' if bold else ""
        getattr(self, layer).append(f'<text x="{x}" y="{y}" text-anchor="{anchor}" font-family="{f}" font-size="{size}"'
                                    f'{wgt} fill="{color}">{esc(s)}</text>')

    def raw(self, s, layer="mid"):
        getattr(self, layer).append(s)

    def legend(self, x, y, items):
        """items: list of (kind, color, label) where kind is 'solid' or 'dashed'."""
        cx = x
        for kind, color, label in items:
            dash = ' stroke-dasharray="6 5"' if kind == "dashed" else ""
            mid = self._marker(color)
            self.front.append(f'<path d="M{cx},{y} L{cx+34},{y}" stroke="{color}" stroke-width="1.6"{dash} '
                              f'marker-end="url(#{mid})"/>')
            self.front.append(f'<text x="{cx+44}" y="{y+4}" font-family="{FONT}" font-size="11.5" fill="{MUTED}">{esc(label)}</text>')
            cx += 44 + text_w(label, 11.5) + 34

    def svg(self):
        defs = ['<filter id="sh" x="-10%" y="-10%" width="120%" height="130%"><feDropShadow dx="0" dy="2" stdDeviation="3" '
                'flood-color="#000" flood-opacity="0.45"/></filter>',
                '<pattern id="dots" width="22" height="22" patternUnits="userSpaceOnUse">'
                '<circle cx="1.2" cy="1.2" r="1.1" fill="#1a2232"/></pattern>']
        for mid, color in self.markers.items():
            if mid.startswith("s"):
                defs.append(f'<marker id="{mid}" viewBox="0 0 10 10" refX="1" refY="5" markerWidth="7" markerHeight="7" '
                            f'orient="auto"><path d="M10,1 L1,5 L10,9 z" fill="{color}"/></marker>')
            else:
                defs.append(f'<marker id="{mid}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" '
                            f'orient="auto"><path d="M0,1 L9,5 L0,9 z" fill="{color}"/></marker>')
        head = []
        if self.title:
            head.append(f'<text x="36" y="46" font-family="{FONT}" font-size="22" font-weight="700" fill="{TEXT}">{esc(self.title)}</text>')
        if self.subtitle:
            head.append(f'<text x="36" y="70" font-family="{FONT}" font-size="13" fill="{MUTED}">{esc(self.subtitle)}</text>')
        grid = f'<rect width="{self.w}" height="{self.h}" fill="url(#dots)"/>' if self.grid else ""
        body = "\n".join(self.back + self.mid + self.front)
        return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" viewBox="0 0 {self.w} {self.h}">'
                f'<defs>{"".join(defs)}</defs>'
                f'<rect width="{self.w}" height="{self.h}" rx="18" fill="{BG}"/>{grid}'
                f'{"".join(head)}\n{body}</svg>')

    def save(self, path):
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(self.svg())


class Sequence:
    """Participants across the top, lifelines down, numbered messages, notes, loop/alt frames."""

    def __init__(self, title, subtitle, participants, width=None, col=190, top=110):
        self.parts = participants
        self.col, self.top = col, top
        self.x0 = 60 + col / 2
        self.xs = {p["id"]: self.x0 + i * col for i, p in enumerate(participants)}
        self.width = width or int(self.x0 * 2 + (len(participants) - 1) * col)
        self.items, self.y = [], top + 122
        self.title, self.subtitle = title, subtitle
        self.n = 0
        self.frames = []

    def msg(self, a, b, label, ret=False, color=None, gap=46):
        self.n += 1
        self.items.append(("msg", a, b, label, ret, color, self.y, self.n))
        self.y += gap

    def note(self, over, label, color=C["yellow"], gap=50):
        self.items.append(("note", over, label, color, self.y))
        self.y += gap

    def frame_start(self, kind, label, color=C["purple"]):
        self.frames.append((kind, label, color, self.y - 6))
        self.y += 42

    def frame_end(self):
        kind, label, color, y0 = self.frames.pop()
        self.items.append(("frame", kind, label, color, y0, self.y - 12))
        self.y += 14

    def render(self):
        H = int(self.y + 40)
        s = Scene(self.width, H, self.title, self.subtitle)
        for p in self.parts:
            x = self.xs[p["id"]]
            w = self.col - 26
            s.raw(f'<path d="M{x},{self.top+84} L{x},{H-30}" stroke="{hex_rgba(p.get("color", C["gray"]),0.45)}" '
                  f'stroke-width="1.3" stroke-dasharray="4 6"/>', "back")
            s.node(x - w / 2, self.top, p["label"], p.get("icon"), p.get("sub"), w=w, h=84, accent=p.get("color"),
                   icolor=p.get("icolor"))
        for it in self.items:
            if it[0] == "frame":
                _, kind, label, color, y0, y1 = it
                xs = list(self.xs.values())
                x0, x1 = min(xs) - self.col / 2 + 18, max(xs) + self.col / 2 - 18
                s.raw(f'<rect x="{x0}" y="{y0}" width="{x1-x0}" height="{y1-y0}" rx="10" fill="{hex_rgba(color,0.05)}" '
                      f'stroke="{hex_rgba(color,0.5)}" stroke-width="1.2" stroke-dasharray="5 4"/>', "back")
                t = f"{kind.upper()}  {label}"
                s.raw(f'<rect x="{x0}" y="{y0}" width="{text_w(t,10.5,True)+18:.0f}" height="20" rx="6" fill="{hex_rgba(color,0.18)}"/>', "back")
                s.text(x0 + 9, y0 + 14, t, 10.5, color, bold=True, layer="back")
        for it in self.items:
            if it[0] == "msg":
                _, a, b, label, ret, color, y, n = it
                xa, xb = self.xs[a], self.xs[b]
                col = color or (C["gray"] if ret else C["blue"])
                if a == b:
                    d = -1 if xa >= max(self.xs.values()) else 1
                    s.edge([(xa + 4 * d, y - 8), (xa + 46 * d, y - 8), (xa + 46 * d, y + 10), (xa + 6 * d, y + 10)],
                           color=col, dashed=ret, radius=6)
                    tx, anchor = xa + 56 * d, ("start" if d > 0 else "end")
                    ty = y + 5
                    cxn = xa - 14 * d
                    s.raw(f'<circle cx="{cxn}" cy="{y}" r="9" fill="{hex_rgba(col,0.2)}" stroke="{col}" stroke-width="1"/>'
                          f'<text x="{cxn}" y="{y+3.6}" text-anchor="middle" font-family="{FONT}" font-size="9.5" '
                          f'font-weight="700" fill="{col}">{n}</text>', "front")
                else:
                    sign = 1 if xb > xa else -1
                    s.edge([(xa + 5 * sign, y), (xb - 6 * sign, y)], color=col, dashed=ret)
                    tx, anchor, ty = (xa + xb) / 2, "middle", y - 9
                num = f'<circle cx="{xa + (-14 if xb>=xa else 14)}" cy="{y}" r="9" fill="{hex_rgba(col,0.2)}" stroke="{col}" stroke-width="1"/>' \
                      f'<text x="{xa + (-14 if xb>=xa else 14)}" y="{y+3.6}" text-anchor="middle" font-family="{FONT}" font-size="9.5" font-weight="700" fill="{col}">{n}</text>'
                if a != b:
                    s.raw(num, "front")
                tw = text_w(label, 11.5)
                if anchor == "middle":
                    s.raw(f'<rect x="{tx-tw/2-5:.1f}" y="{ty-12:.1f}" width="{tw+10:.1f}" height="16" rx="4" fill="{BG}" opacity="0.85"/>', "front")
                s.text(tx, ty, label, 11.5, TEXT if not ret else MUTED, anchor=anchor)
            elif it[0] == "note":
                _, over, label, color, y = it
                xs = [self.xs[o] for o in over]
                tw = text_w(label, 11.5)
                x0 = min(xs) - 70 if len(xs) > 1 else xs[0] - tw / 2 - 16
                x1 = max(xs) + 70 if len(xs) > 1 else xs[0] + tw / 2 + 16
                x0, x1 = min(x0, (x0 + x1) / 2 - tw / 2 - 16), max(x1, (x0 + x1) / 2 + tw / 2 + 16)
                s.raw(f'<rect x="{x0:.1f}" y="{y-16}" width="{x1-x0:.1f}" height="30" rx="8" fill="{hex_rgba(color,0.12)}" '
                      f'stroke="{hex_rgba(color,0.55)}" stroke-width="1.1"/>', "front")
                s.text((x0 + x1) / 2, y + 4, label, 11.5, color, anchor="middle")
        return s


class ER:
    def __init__(self, w, h, title, subtitle):
        self.s = Scene(w, h, title, subtitle)
        self.ents = {}

    def entity(self, key, x, y, name, cols, color, w=250, note=None):
        rowh, headh = 22, 38
        h = headh + rowh * len(cols) + 10 + (20 if note else 0)
        s = self.s
        s.raw(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="11" fill="{CARD}" stroke="{hex_rgba(color,0.6)}" '
              f'stroke-width="1.3" filter="url(#sh)"/>')
        s.raw(f'<path d="M{x},{y+11} a11,11 0 0 1 11,-11 h{w-22} a11,11 0 0 1 11,11 v{headh-11} h-{w} z" fill="{hex_rgba(color,0.16)}"/>')
        s.raw(icon("db", x + 12, y + 10, 18, color))
        s.raw(f'<text x="{x+38}" y="{y+24}" font-family="{MONO}" font-size="13" font-weight="700" fill="{TEXT}">{esc(name)}</text>')
        for i, c in enumerate(cols):
            name_, typ, tag = (list(c) + [None, None])[:3]
            ry = y + headh + 16 + i * rowh
            if i % 2 == 1:
                s.raw(f'<rect x="{x+1}" y="{ry-15}" width="{w-2}" height="{rowh}" fill="#ffffff" opacity="0.025"/>')
            tx = x + 14
            if tag:
                tc = {"PK": C["yellow"], "FK": C["blue"], "UK": C["teal"], "ID": C["purple"], "PK FK": C["yellow"]}.get(tag, MUTED)
                tw = text_w(tag, 8.5, True) + 8
                s.raw(f'<rect x="{tx}" y="{ry-11}" width="{tw:.0f}" height="14" rx="4" fill="{hex_rgba(tc,0.18)}"/>')
                s.raw(f'<text x="{tx+tw/2:.1f}" y="{ry-1}" text-anchor="middle" font-family="{FONT}" font-size="8.5" font-weight="700" fill="{tc}">{esc(tag)}</text>')
            s.raw(f'<text x="{x+50}" y="{ry}" font-family="{MONO}" font-size="11.5" fill="{TEXT}">{esc(name_)}</text>')
            s.raw(f'<text x="{x+w-12}" y="{ry}" text-anchor="end" font-family="{MONO}" font-size="10.5" fill="{MUTED}">{esc(typ or "")}</text>')
        if note:
            s.raw(f'<text x="{x+14}" y="{y+h-10}" font-family="{FONT}" font-size="10.5" font-style="italic" fill="{MUTED}">{esc(note)}</text>')
        n = Node(x, y, w, h)
        self.ents[key] = n
        return n

    def _end(self, p, direction, kind, color):
        """Crow's-foot marker at point p; direction is the unit vector pointing *into* the entity."""
        x, y = p
        dx, dy = direction
        px, py = -dy, dx
        out = []
        def ln(ax, ay, bx, by):
            out.append(f'<path d="M{ax:.1f},{ay:.1f} L{bx:.1f},{by:.1f}" stroke="{color}" stroke-width="1.6"/>')
        if kind in ("many", "zmany"):
            bx_, by_ = x - dx * 14, y - dy * 14
            ln(bx_, by_, x + px * 8, y + py * 8)
            ln(bx_, by_, x - px * 8, y - py * 8)
            ln(bx_, by_, x, y)
        if kind in ("one", "zone"):
            ln(x - dx * 8 + px * 7, y - dy * 8 + py * 7, x - dx * 8 - px * 7, y - dy * 8 - py * 7)
        if kind == "one":
            ln(x - dx * 13 + px * 7, y - dy * 13 + py * 7, x - dx * 13 - px * 7, y - dy * 13 - py * 7)
        if kind in ("zone", "zmany"):
            cx, cy = x - dx * 22, y - dy * 22
            out.append(f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="4.5" fill="{BG}" stroke="{color}" stroke-width="1.5"/>')
        return "".join(out)

    def rel(self, pts, a_kind, b_kind, label=None, color=C["gray"], dashed=False, at=None):
        s = self.s
        d = "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in pts)
        dash = ' stroke-dasharray="6 5"' if dashed else ""
        s.mid.insert(0, f'<path d="{d}" fill="none" stroke="{color}" stroke-width="1.6"{dash}/>')
        def unit(p, q):
            dx, dy = q[0] - p[0], q[1] - p[1]
            L = max((dx * dx + dy * dy) ** 0.5, 1e-6)
            return dx / L, dy / L
        s.front.append(self._end(pts[0], unit(pts[1], pts[0]), a_kind, color))
        s.front.append(self._end(pts[-1], unit(pts[-2], pts[-1]), b_kind, color))
        if label:
            if at is None:
                i = len(pts) // 2 - (1 if len(pts) % 2 == 0 else 0)
                at = ((pts[i][0] + pts[i + 1][0]) / 2, (pts[i][1] + pts[i + 1][1]) / 2) if len(pts) > 1 else pts[0]
            s.pill(at[0], at[1], label, color if color != C["gray"] else MUTED)
