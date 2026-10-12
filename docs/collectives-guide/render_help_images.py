#!/usr/bin/env python3
"""Render small, synthetic help illustrations using original app vector icons.

These are labelled orientation illustrations, not screenshots or captures of a
real user session. No device, account, biometric protection or app flags change.
"""

from pathlib import Path
from html import escape
import subprocess
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ASSETS = Path(__file__).resolve().parent / "help-images"
DRAWABLES = ROOT / "app/src/main/res/drawable"
ANDROID = "{http://schemas.android.com/apk/res/android}"
INK, SECONDARY = "#1D1726", "#62586B"
CORAL, CORAL_TEXT = "#FD4B5A", "#BF243A"


def icon(name, x, y, size=28, tint=INK):
    root = ET.parse(DRAWABLES / f"{name}.xml").getroot()
    paths = []
    for path in root.findall("path"):
        fill = path.get(ANDROID + "fillColor", "#00000000")
        invisible = fill == "@android:color/transparent" or (
            fill.startswith("#") and len(fill) == 9 and fill[1:3] == "00"
        )
        attrs = {"d": path.get(ANDROID + "pathData"), "fill": "none" if invisible else tint}
        if path.get(ANDROID + "strokeWidth"):
            attrs.update(stroke=tint, **{
                "stroke-width": path.get(ANDROID + "strokeWidth"),
                "stroke-linecap": path.get(ANDROID + "strokeLineCap", "butt"),
                "stroke-linejoin": path.get(ANDROID + "strokeLineJoin", "miter"),
            })
        paths.append("<path " + " ".join(f'{key}="{escape(value)}"' for key, value in attrs.items()) + "/>")
    return f'<g transform="translate({x} {y}) scale({size / 24})">{"".join(paths)}</g>'


def text(value, x, y, size=18, color=INK, weight=400, anchor="start"):
    return (f'<text x="{x}" y="{y}" font-family="DejaVu Sans" font-size="{size}" '
            f'fill="{color}" font-weight="{weight}" text-anchor="{anchor}">{escape(value)}</text>')


def rect(x, y, width, height, radius=14, fill="white", stroke="none"):
    return f'<rect x="{x}" y="{y}" width="{width}" height="{height}" rx="{radius}" fill="{fill}" stroke="{stroke}"/>'


def pill(value, x, y, width, selected=False):
    return rect(x, y, width, 44, 22, CORAL if selected else "white", "none" if selected else "#B6B0BB") + text(
        value, x + width / 2, y + 29, 17, "white" if selected else SECONDARY, 600, "middle")


def render(name, height, content):
    ASSETS.mkdir(exist_ok=True)
    svg = ASSETS / f"{name}.svg"
    png = ASSETS / f"{name}.png"
    svg.write_text(f'<svg xmlns="http://www.w3.org/2000/svg" width="450" height="{height}" viewBox="0 0 450 {height}">'
                   + rect(0, 0, 450, height, 16, "#FCF9FD") + content + "</svg>\n", encoding="utf-8")
    renderer = shutil.which("magick") or "convert"
    subprocess.run([renderer, "-background", "none", str(svg), str(png)], check=True)
    print(f"{png.name}: 450 x {height}")


def main():
    render("app-hilfe-symbole", 126, "".join(
        icon(name, center - 16, 23, 32) + text(label, center, 89, 16, weight=600, anchor="middle")
        for name, label, center in [
            ("ic_contacts", "Kontakte", 78),
            ("ic_notice_bell", "Neu für mich", 225),
            ("ic_settings", "Einstellungen", 372),
        ]))
    render("app-hilfe-favorit", 104,
           rect(12, 14, 426, 76, 18) + rect(28, 33, 40, 40, 12, "#E3F3EF")
           + icon("ic_app_mail", 36, 41, 24, "#08756D")
           + text("Zimbra", 86, 59, 21, weight=600)
           + icon("ic_contact_star", 390, 39, 28, CORAL))
    render("app-hilfe-nachrichten", 132,
           text("Wie viel Text möchtest du sehen?", 18, 34, 19, weight=600)
           + pill("Standard", 16, 56, 123, True)
           + pill("Ausführlich", 149, 56, 142)
           + pill("Diskret", 301, 56, 133))
    render("app-hilfe-kalender", 186,
           text("Zimbra-Termine im", 18, 31, 20, weight=600)
           + text("Handy-Kalender anzeigen", 18, 57, 20, weight=600)
           + rect(364, 24, 68, 36, 18, CORAL)
           + '<circle cx="414" cy="42" r="14" fill="white"/>'
           + text("Zeitraum", 18, 97, 18, weight=600)
           + pill("1 Tag", 16, 113, 90) + pill("3 Tage", 116, 113, 90)
           + pill("1 Woche", 216, 113, 105, True) + pill("2 Wochen", 331, 113, 103))
    render("app-hilfe-problem-melden", 152,
           text("‹ Portal", 18, 39, 18, CORAL_TEXT, 600)
           + text("ML Dokumente", 116, 39, 19, weight=600)
           + icon("ic_more_vert", 403, 16, 28)
           + rect(163, 63, 271, 66, 10, "white", "#D9D3DE")
           + text("Problem melden", 181, 104, 19, weight=600))


if __name__ == "__main__":
    main()
