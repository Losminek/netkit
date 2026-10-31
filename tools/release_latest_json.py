#!/usr/bin/env python3
"""latest.json for a signed NetKit release (D14, D22, D23; schema in docs/distribuce.md §4).

The file is created once, next to the signed APK, and uploaded to the DRAFT release
(tools/sign_release.sh). The app reads it from the web and, as a fallback, from
github.com/<repo>/releases/latest/download/latest.json; the web copies it at build time.
publish.yml checks it again before the web is told about the release.

    python3 -I tools/release_latest_json.py build --version 1.0.0 --apk netkit-1.0.0.apk \\
        --cert <sha256> --out latest.json [--notes-cs …] [--notes-en …] [--released YYYY-MM-DD]
    python3 -I tools/release_latest_json.py check latest.json --version 1.0.0 \\
        --apk netkit-1.0.0.apk --cert <sha256>

Web base and source URL come from gradle.properties (netkitWebBase, netkitSourceUrl), the same
values the app is built with. Standard library only.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
VERSION = re.compile(r"^(0|[1-9]\d{0,4})\.(0|[1-9]\d?)\.(0|[1-9]\d?)$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
GITHUB_REPO = re.compile(r"^https://github\.com/([\w.-]+)/([\w.-]+)$")
# Same rule as androidApp/build.gradle.kts (netkitWebConfigured): reserved names are placeholders.
PLACEHOLDER_TLDS = {"invalid", "test", "example", "localhost", "local"}
MAX_NOTE_CHARS = 280
MIN_SDK = 29
KEYS = {"schema", "versionName", "versionCode", "released", "minSdk", "apkUrl", "pageUrl",
        "sha256", "certSha256", "notes"}


class ReleaseError(Exception):
    """The release data would produce a wrong latest.json; stop."""


def version_code(version: str) -> int:
    """androidApp/build.gradle.kts scheme: major*10000 + minor*100 + patch."""
    match = VERSION.match(version)
    if not match:
        raise ReleaseError(f"verze {version!r} není X.Y.Z (minor a patch 0–99)")
    major, minor, patch = (int(part) for part in match.groups())
    return major * 10000 + minor * 100 + patch


def sha256_hex(value: str) -> str:
    digest = value.strip().lower().replace(":", "")
    if not SHA256.match(digest):
        raise ReleaseError("otisk není SHA-256 (64 hex znaků, dvojtečky povoleny)")
    return digest


def read_properties(path: Path) -> dict[str, str]:
    props: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def web_base(value: str) -> str:
    """https://www.<domain>/netkit without a trailing slash; placeholders are refused."""
    match = re.match(r"^https://([a-z0-9.-]+)(/[\w./-]*)?$", value)
    if not match or value.endswith("/"):
        raise ReleaseError(f"netkitWebBase {value!r} musí být https://host/cesta bez lomítka na konci")
    host = match.group(1)
    if host.rsplit(".", 1)[-1] in PLACEHOLDER_TLDS or "example." in host or "." not in host:
        raise ReleaseError(f"netkitWebBase {value!r} je zástupná hodnota (D17): doplň skutečnou doménu")
    return value


def source_repo(value: str) -> str:
    if not GITHUB_REPO.match(value):
        raise ReleaseError(f"netkitSourceUrl {value!r} není https://github.com/<owner>/<repo>")
    return value


def changelog_note(text: str, version: str) -> str:
    """First bullet of the `## X.Y.Z` section of CHANGELOG.md, as plain text."""
    section = re.search(rf"^## {re.escape(version)}\b.*?$(.*?)(?=^## |\Z)", text, re.M | re.S)
    if section:
        for line in section.group(1).splitlines():
            if line.startswith("- "):
                return line[2:].strip()[:MAX_NOTE_CHARS]
    return ""


def apk_url(source: str, version: str) -> str:
    return f"{source}/releases/download/v{version}/netkit-{version}.apk"


def build(version: str, apk_sha: str, cert: str, base: str, source: str, released: str,
          notes_cs: str, notes_en: str, min_sdk: int = MIN_SDK) -> dict[str, Any]:
    dt.date.fromisoformat(released)
    if min_sdk < MIN_SDK:
        raise ReleaseError(f"minSdk {min_sdk} je pod {MIN_SDK}")
    for note in (notes_cs, notes_en):
        if len(note) > MAX_NOTE_CHARS or any(ord(c) < 32 for c in note):
            raise ReleaseError(f"poznámka musí být jeden řádek do {MAX_NOTE_CHARS} znaků")
    return {
        "schema": 1,
        "versionName": version,
        "versionCode": version_code(version),
        "released": released,
        "minSdk": min_sdk,
        "apkUrl": apk_url(source_repo(source), version),
        "pageUrl": web_base(base),
        "sha256": sha256_hex(apk_sha),
        "certSha256": sha256_hex(cert),
        "notes": {"cs": notes_cs, "en": notes_en or notes_cs},
    }


def check(latest: dict[str, Any], version: str, apk_sha: str, cert: str, base: str, source: str,
          min_sdk: int = MIN_SDK) -> None:
    """Fail unless latest.json describes exactly this signed APK (incl. its minSdk) on this web and repo."""
    if set(latest) != KEYS:
        raise ReleaseError(f"latest.json má jiná pole: {sorted(set(latest) ^ KEYS)}")
    expected = build(version, apk_sha, cert, base, source, str(latest.get("released", "")), "", "", min_sdk)
    for key in ("schema", "versionName", "versionCode", "minSdk", "apkUrl", "pageUrl", "sha256", "certSha256"):
        if latest[key] != expected[key] or type(latest[key]) is not type(expected[key]):
            raise ReleaseError(f"latest.json {key} = {latest[key]!r}, očekáváno {expected[key]!r}")
    notes = latest["notes"]
    if not isinstance(notes, dict) or set(notes) != {"cs", "en"} or not all(isinstance(v, str) for v in notes.values()):
        raise ReleaseError("latest.json notes musí být {\"cs\": text, \"en\": text}")


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="NetKit: latest.json pro podepsaný release")
    parser.add_argument("--gradle-properties", type=Path, default=ROOT / "gradle.properties")
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("build", "check"):
        command = sub.add_parser(name)
        if name == "check":
            command.add_argument("latest", type=Path)
        command.add_argument("--version", required=True)
        command.add_argument("--apk", type=Path, required=True)
        command.add_argument("--cert", required=True, help="SHA-256 otisk podpisového certifikátu")
        command.add_argument("--min-sdk", type=int, default=MIN_SDK, help="minSdk z aapt2 badging APK")
    build_cmd = sub.choices["build"]
    build_cmd.add_argument("--out", type=Path, required=True)
    build_cmd.add_argument("--released", default=dt.date.today().isoformat())
    build_cmd.add_argument("--notes-cs", help="výchozí: první odrážka verze v CHANGELOG.md")
    build_cmd.add_argument("--notes-en", help="výchozí: stejný text jako --notes-cs")
    build_cmd.add_argument("--changelog", type=Path, default=ROOT / "CHANGELOG.md")
    args = parser.parse_args(argv)
    try:
        props = read_properties(args.gradle_properties)
        base, source = props.get("netkitWebBase", ""), props.get("netkitSourceUrl", "")
        apk_sha = file_sha256(args.apk)
        if args.command == "build":
            notes_cs = args.notes_cs if args.notes_cs is not None else changelog_note(
                args.changelog.read_text(encoding="utf-8"), args.version)
            latest = build(args.version, apk_sha, args.cert, base, source, args.released,
                           notes_cs, args.notes_en or "", args.min_sdk)
            args.out.write_text(json.dumps(latest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(f"latest.json: {latest['versionName']} ({latest['versionCode']}) → {args.out}")
        else:
            check(json.loads(args.latest.read_text(encoding="utf-8")), args.version, apk_sha,
                  args.cert, base, source, args.min_sdk)
            print(f"ok: {args.latest} odpovídá netkit-{args.version}.apk")
    except (ReleaseError, OSError, ValueError, KeyError) as error:
        print(f"release_latest_json: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
