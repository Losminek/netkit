#!/usr/bin/env python3
"""Offline IEEE OUI list for the Wi-Fi vendor line.

Downloads the three public IEEE registries (MA-L 24 bit, MA-M 28 bit, MA-S 36 bit), keeps only the
assignment prefix and the organization name (addresses are dropped) and writes a sorted, compact
text asset the app parses in `shared` (`cz.netkit.shared.wifi.OuiDatabase`):

    # comment: date of download, entry counts, source
    <prefix hex, upper case><TAB><bits: 24|28|36><TAB><organization name>

Run by hand before a release; CI never downloads anything:

    python3 -I tools/update_oui.py [--output PATH] [--from-dir DIR] [--date YYYY-MM-DD]

`--from-dir` reads oui.csv, mam.csv and oui36.csv from DIR instead of downloading. Downloads go to a
fresh temporary directory that is removed afterwards. The CSV files are untrusted data: they are only
parsed as CSV with strict header, registry and prefix checks; nothing in them is executed.

Deterministic rules (documented in docs/zavislosti.md):
- names are NFKC-normalized, control/format characters (tabs, zero-width, bidi) removed, whitespace
  collapsed, at most MAX_NAME_CHARS characters;
- "Private" entries (no published owner) and MA-L blocks of "IEEE Registration Authority" (containers
  of MA-M/MA-S, never a device vendor) are left out, so the lookup falls back to "unknown";
- a prefix listed more than once (historical IEEE data) keeps all distinct names, sorted, joined by
  " / ".
Standard library only.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import io
import os
import re
import shutil
import sys
import tempfile
import unicodedata
import urllib.parse
import urllib.request
from dataclasses import dataclass
from http.client import HTTPMessage
from typing import IO
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUTPUT = ROOT / "androidApp/src/main/assets/oui/ieee-oui.tsv"
HEADER = ["Registry", "Assignment", "Organization Name", "Organization Address"]
MAX_NAME_CHARS = 120
MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024
DOWNLOAD_TIMEOUT_S = 60
USER_AGENT = "NetKit-update_oui/1 (+https://github.com/Losminek/netkit)"
SKIPPED_NAMES = {"private", "ieee registration authority"}


@dataclass(frozen=True)
class Registry:
    key: str
    file_name: str
    url: str
    bits: int
    # A sanity floor for a real download; a truncated or replaced file fails instead of shipping.
    min_entries: int

    @property
    def hex_digits(self) -> int:
        return self.bits // 4


REGISTRIES = (
    Registry("MA-L", "oui.csv", "https://standards-oui.ieee.org/oui/oui.csv", 24, 30_000),
    Registry("MA-M", "mam.csv", "https://standards-oui.ieee.org/oui28/mam.csv", 28, 4_000),
    Registry("MA-S", "oui36.csv", "https://standards-oui.ieee.org/oui36/oui36.csv", 36, 4_000),
)


class OuiError(Exception):
    """Invalid input; nothing is written."""


def clean_name(raw: str) -> str:
    """Display form of an organization name; empty when nothing printable is left."""
    text = unicodedata.normalize("NFKC", raw)
    chars = []
    for char in text:
        category = unicodedata.category(char)
        if category.startswith("Z") or char in "\t\n\r\f\v":
            chars.append(" ")
        elif category.startswith("C"):
            continue  # Cc control, Cf format (zero-width, bidi), Co/Cn/Cs
        else:
            chars.append(char)
    name = " ".join("".join(chars).split())
    if len(name) > MAX_NAME_CHARS:
        name = name[: MAX_NAME_CHARS - 1].rstrip() + "…"
    return name


def parse_registry(text: str, registry: Registry) -> dict[str, set[str]]:
    """prefix -> distinct names of one registry CSV; any structural problem raises OuiError."""
    prefix_re = re.compile(rf"^[0-9A-F]{{{registry.hex_digits}}}$")
    reader = csv.reader(io.StringIO(text.removeprefix("\ufeff"), newline=""), strict=True)
    try:
        header = next(reader, None)
        if header is None or [cell.strip() for cell in header] != HEADER:
            raise OuiError(f"{registry.file_name}: unexpected header {header!r}")
        entries: dict[str, set[str]] = {}
        for row in reader:
            line = reader.line_num
            if not row:
                continue
            if len(row) != len(HEADER):
                raise OuiError(f"{registry.file_name}:{line}: expected {len(HEADER)} columns, got {len(row)}")
            if row[0].strip() != registry.key:
                raise OuiError(f"{registry.file_name}:{line}: registry {row[0]!r}, expected {registry.key}")
            prefix = row[1].strip().upper()
            if not prefix_re.match(prefix):
                raise OuiError(f"{registry.file_name}:{line}: invalid {registry.key} prefix {row[1]!r}")
            name = clean_name(row[2])
            if not name:
                raise OuiError(f"{registry.file_name}:{line}: empty organization name")
            if name.casefold() in SKIPPED_NAMES:
                continue
            entries.setdefault(prefix, set()).add(name)
    except csv.Error as error:
        raise OuiError(f"{registry.file_name}: malformed CSV: {error}") from error
    return entries


def render(tables: dict[str, dict[str, set[str]]], date: str) -> str:
    """The asset text: one comment line, then entries sorted by bits and prefix."""
    lines = []
    counts = []
    for registry in REGISTRIES:
        entries = tables[registry.key]
        counts.append(f"{registry.key}={len(entries)}")
        for prefix in sorted(entries):
            lines.append(f"{prefix}\t{registry.bits}\t{' / '.join(sorted(entries[prefix]))}")
    total = sum(len(tables[registry.key]) for registry in REGISTRIES)
    comment = (f"# IEEE OUI Public Listing (MA-L, MA-M, MA-S), downloaded {date}; entries {', '.join(counts)}, "
               f"total={total}; prefix<TAB>bits<TAB>organization; source https://standards-oui.ieee.org/; "
               "generated by tools/update_oui.py")
    return "\n".join([comment, *lines]) + "\n"


def build(sources: dict[str, str], date: str, check_minimum: bool) -> tuple[str, dict[str, int]]:
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date):
        raise OuiError(f"invalid date {date!r}")
    tables = {}
    for registry in REGISTRIES:
        if registry.key not in sources:
            raise OuiError(f"missing {registry.file_name}")
        tables[registry.key] = parse_registry(sources[registry.key], registry)
        if check_minimum and len(tables[registry.key]) < registry.min_entries:
            raise OuiError(f"{registry.file_name}: only {len(tables[registry.key])} entries, "
                           f"expected at least {registry.min_entries}")
    return render(tables, date), {key: len(value) for key, value in tables.items()}


IEEE_HOST = "standards-oui.ieee.org"


def is_ieee_url(url: str) -> bool:
    parts = urllib.parse.urlsplit(url)
    return parts.scheme == "https" and parts.hostname == IEEE_HOST and parts.port in (None, 443) \
        and not parts.username and not parts.password


class IeeeOnlyRedirects(urllib.request.HTTPRedirectHandler):
    """Follows a redirect only to https://standards-oui.ieee.org/; any other target is refused
    before a request is sent there."""

    def redirect_request(self, req: urllib.request.Request, fp: IO[bytes], code: int, msg: str,
                         headers: HTTPMessage, newurl: str) -> urllib.request.Request | None:
        if not is_ieee_url(newurl):
            raise OuiError(f"{req.full_url}: refusing redirect to {newurl}")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(registry: Registry, directory: Path) -> Path:
    """HTTPS GET with certificate checks (urllib default context), a timeout and a size cap."""
    if not is_ieee_url(registry.url):
        raise OuiError(f"refusing URL {registry.url}")
    target = directory / registry.file_name
    request = urllib.request.Request(registry.url, headers={"User-Agent": USER_AGENT})
    opener = urllib.request.build_opener(IeeeOnlyRedirects)
    with opener.open(request, timeout=DOWNLOAD_TIMEOUT_S) as response, target.open("wb") as out:
        if response.status != 200:
            raise OuiError(f"{registry.url}: HTTP {response.status}")
        if not is_ieee_url(response.geturl()):
            raise OuiError(f"{registry.url}: redirected to {response.geturl()}")
        size = 0
        while chunk := response.read(64 * 1024):
            size += len(chunk)
            if size > MAX_DOWNLOAD_BYTES:
                raise OuiError(f"{registry.url}: larger than {MAX_DOWNLOAD_BYTES} bytes")
            out.write(chunk)
    return target


def read_sources(directory: Path) -> dict[str, str]:
    sources = {}
    for registry in REGISTRIES:
        path = directory / registry.file_name
        if path.stat().st_size > MAX_DOWNLOAD_BYTES:
            raise OuiError(f"{path}: larger than {MAX_DOWNLOAD_BYTES} bytes")
        try:
            sources[registry.key] = path.read_text(encoding="utf-8")
        except UnicodeDecodeError as error:
            raise OuiError(f"{path}: not UTF-8: {error}") from error
    return sources


def write_atomic(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".ieee-oui-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as out:
            out.write(text)
        os.chmod(temporary, 0o644)  # mkstemp creates 0600; the asset is an ordinary repo file
        os.replace(temporary, path)
    except BaseException:
        Path(temporary).unlink(missing_ok=True)
        raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0] if __doc__ else None)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--from-dir", type=Path, help="read the three CSV files from this directory")
    parser.add_argument("--date", default=dt.datetime.now(dt.timezone.utc).date().isoformat(),
                        help="download date written to the header (default: today, UTC)")
    parser.add_argument("--no-minimum", action="store_true", help="skip the entry-count sanity floor")
    args = parser.parse_args(argv)
    try:
        if args.from_dir is not None:
            sources = read_sources(args.from_dir)
        else:
            directory = Path(tempfile.mkdtemp(prefix="netkit-oui-"))
            try:
                for registry in REGISTRIES:
                    download(registry, directory)
                sources = read_sources(directory)
            finally:
                shutil.rmtree(directory, ignore_errors=True)
        text, counts = build(sources, args.date, check_minimum=not args.no_minimum)
        write_atomic(args.output, text)
    except (OuiError, OSError) as error:
        print(f"update_oui: {error}", file=sys.stderr)
        return 1
    size = args.output.stat().st_size
    print(f"update_oui: {args.output} {size} B, {', '.join(f'{k}={v}' for k, v in counts.items())}, "
          f"total={sum(counts.values())}, date {args.date}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
