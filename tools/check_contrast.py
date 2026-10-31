#!/usr/bin/env python3
"""WCAG contrast check of the NetKit colour tokens (docs/design.md §2).

Parses ``DarkNetKitColors`` and ``LightNetKitColors`` from ``Theme.kt`` (literals in the form
``name = Color(0xAARRGGBB)``), prints a table and exits non-zero when a required ratio fails.
No third-party dependencies; run with ``python3 -I tools/check_contrast.py``.
"""

from __future__ import annotations

import argparse
import math
import re
import sys
from dataclasses import dataclass
from pathlib import Path

THEME_KT = (
    Path(__file__).resolve().parent.parent
    / "androidApp/src/main/kotlin/cz/netkit/app/ui/theme/Theme.kt"
)

TEXT_MIN = 4.5
GRAPHIC_MIN = 3.0
SEMANTIC = ("accent", "info", "violet", "ok", "warn", "err")
SOFT = {
    "accent": "accentSoft",
    "info": "infoSoft",
    "violet": "violetSoft",
    "ok": "okSoft",
    "warn": "warnSoft",
    "err": "errSoft",
}

_PALETTE = re.compile(r"val\s+(Dark|Light)NetKitColors\s*=\s*NetKitColors\((.*?)\n\)", re.S)
_COLOR_LITERAL = re.compile(r"(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)")

RGBA = tuple[float, float, float, float]


@dataclass(frozen=True)
class Check:
    theme: str
    role: str
    foreground: str
    background: str
    ratio: float
    minimum: float

    @property
    def passed(self) -> bool:
        # WCAG thresholds are not rounded; the table truncates (see ``ratio_text``).
        return self.ratio >= self.minimum

    @property
    def ratio_text(self) -> str:
        """Ratio truncated to two decimals, so a failing 4.499 never prints as 4.50."""
        return f"{math.floor(self.ratio * 100) / 100:.2f}"


def parse_palettes(source: str) -> dict[str, dict[str, RGBA]]:
    palettes: dict[str, dict[str, RGBA]] = {}
    for name, body in _PALETTE.findall(source):
        colors: dict[str, RGBA] = {}
        for token, argb in _COLOR_LITERAL.findall(body):
            value = int(argb, 16)
            a = (value >> 24) & 0xFF
            r = (value >> 16) & 0xFF
            g = (value >> 8) & 0xFF
            b = value & 0xFF
            colors[token] = (r / 255, g / 255, b / 255, a / 255)
        palettes[name.lower()] = colors
    if set(palettes) != {"dark", "light"}:
        raise ValueError("Theme.kt must define DarkNetKitColors and LightNetKitColors")
    return palettes


def composite(fg: RGBA, bg: RGBA) -> RGBA:
    """Source-over of ``fg`` on an opaque ``bg``."""
    a = fg[3]
    return (
        fg[0] * a + bg[0] * (1 - a),
        fg[1] * a + bg[1] * (1 - a),
        fg[2] * a + bg[2] * (1 - a),
        1.0,
    )


def _linear(channel: float) -> float:
    return channel / 12.92 if channel <= 0.04045 else ((channel + 0.055) / 1.055) ** 2.4


def luminance(color: RGBA) -> float:
    r, g, b, _ = color
    return 0.2126 * _linear(r) + 0.7152 * _linear(g) + 0.0722 * _linear(b)


def contrast(fg: RGBA, bg: RGBA) -> float:
    if bg[3] < 1.0:
        raise ValueError("background must be opaque")
    fg = composite(fg, bg) if fg[3] < 1.0 else fg
    high, low = sorted((luminance(fg), luminance(bg)), reverse=True)
    return (high + 0.05) / (low + 0.05)


def checks_for(theme: str, t: dict[str, RGBA]) -> list[Check]:
    out: list[Check] = []

    def add(role: str, fg: str, bg: str, minimum: float, bg_color: RGBA | None = None) -> None:
        out.append(Check(theme, role, fg, bg, contrast(t[fg], bg_color or t[bg]), minimum))

    for fg in ("text", "muted"):
        for bg in ("surface", "background", "surface2"):
            add("text", fg, bg, TEXT_MIN)
    for bg in ("surface", "background"):
        add("dim", "dim", bg, GRAPHIC_MIN)
    for fg in SEMANTIC:
        add("text", fg, "surface", TEXT_MIN)
    for fg in SEMANTIC:
        add("chart line", fg, "surface", GRAPHIC_MIN)
    for fg in SEMANTIC:
        # Tag / tonal button: semantic text on its soft fill laid over a card.
        soft = SOFT[fg]
        fill = composite(t[soft], t["surface"])
        add("tag text", fg, f"{soft}/surface", TEXT_MIN, fill)
    add("button", "accentInk", "accent", TEXT_MIN)
    add("terminal", "terminalText", "terminalBackground", TEXT_MIN)
    return out


def run_checks(source: str) -> list[Check]:
    palettes = parse_palettes(source)
    return [c for theme in ("dark", "light") for c in checks_for(theme, palettes[theme])]


def format_table(checks: list[Check]) -> str:
    rows = [("theme", "role", "foreground", "background", "ratio", "min", "result")]
    for c in checks:
        rows.append(
            (c.theme, c.role, c.foreground, c.background, c.ratio_text, f"{c.minimum:.1f}", "PASS" if c.passed else "FAIL")
        )
    widths = [max(len(r[i]) for r in rows) for i in range(len(rows[0]))]
    return "\n".join("  ".join(cell.ljust(widths[i]) for i, cell in enumerate(r)).rstrip() for r in rows)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="WCAG contrast check of the NetKit colour tokens.")
    parser.add_argument("theme", nargs="?", type=Path, default=THEME_KT, help="path to Theme.kt")
    args = parser.parse_args(argv)
    checks = run_checks(args.theme.read_text(encoding="utf-8"))
    print(format_table(checks))
    failed = [c for c in checks if not c.passed]
    print(f"\n{len(checks) - len(failed)}/{len(checks)} PASS")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
