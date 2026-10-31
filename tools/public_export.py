"""Strict manifest copying and redacted grep gate for the public source export.

The reviewed baseline is data, never an automatic acceptance of new findings.
Only file paths, line numbers and finding types are emitted on failure.
"""
from __future__ import annotations

import hashlib
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys

# Grep is intentionally broad: false positives require a reviewed file fingerprint.
PATTERN = (
    r'/home/|[[:alnum:]_.+-]+@[[:alnum:]_.-]+|'
    r'([0-9]{1,3}\.){3}[0-9]{1,3}|'
    r'([[:xdigit:]]{2}:){5}[[:xdigit:]]{2}|'
    r'password|token|secret|BEGIN .*PRIVATE KEY|https?://|'
    r'hostname|hostName|ssid|bssid|[[:alnum:]_-]+\.(local|lan|internal)'
)
ALLOWED_BINARY = {
    'gradle/wrapper/gradle-wrapper.jar',
    'androidApp/src/main/res/font/manrope.ttf',
    'androidApp/src/main/res/font/jetbrains_mono.ttf',
    # Brand graphics rendered from the SVGs next to them; checked 2026-10-31: no text/EXIF chunks.
    'docs/public/grafika/feature-graphic.png',
    'docs/public/grafika/ikona-512.png',
}
FORBIDDEN_PARTS = {'.git', 'artifacts', 'build', '.gradle', '.kotlin', '__pycache__',
                   '.idea', '.vscode', '.codex', '.claude', 'prace', 'prompty'}
FORBIDDEN_NAMES = {'keystore.properties', 'local.properties', 'secrets.properties', '.env'}
FORBIDDEN_SUFFIXES = {'.jks', '.keystore', '.p12', '.pem', '.key', '.apk', '.aab', '.pyc'}


def fail(message: str) -> None:
    raise ValueError(message)


def safe_relative(value: str) -> Path:
    path = PurePosixPath(value)
    if not value or path.is_absolute() or any(x in {'', '.', '..'} for x in value.split('/')):
        fail('Invalid relative path in manifest')
    if any(ord(c) < 32 or c in ':\\' for c in value):
        fail('Unsupported filename in manifest')
    if (set(path.parts) & FORBIDDEN_PARTS or path.name in FORBIDDEN_NAMES
            or path.name.startswith('.env.') or path.suffix in FORBIDDEN_SUFFIXES):
        fail('Forbidden local file in manifest')
    return Path(value)


def entries(root: Path) -> list[tuple[Path, Path]]:
    result: list[tuple[Path, Path]] = []
    destinations: set[Path] = set()
    policies: set[str] = set()
    for line in (root / 'tools/public_export.manifest').read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        fields = line.split('\t')
        if len(fields) == 2 and fields[0] == 'exclude' and fields[1] in {'prace/', 'prompty/'}:
            policies.add(fields[1])
            continue
        if len(fields) != 3 or fields[0] != 'file':
            fail('Invalid manifest record')
        src, dst = (safe_relative(x) for x in fields[1:])
        if dst in destinations:
            fail('Duplicate export destination')
        destinations.add(dst)
        result.append((src, dst))
    if policies != {'prace/', 'prompty/'}:
        fail('Manifest must explicitly exclude internal work and prompts')
    return result


def prepare(root: Path, requested: Path) -> None:
    root = root.resolve(strict=True)
    target = requested.resolve()
    if target == root or root in target.parents or target in root.parents:
        fail('Destination must be outside the repository and its ancestors')
    # A target in any other existing repository is also unsafe (including worktrees).
    for parent in (target, *target.parents):
        if (parent / '.git').is_file() or (parent / '.git/HEAD').is_file():
            fail('Destination belongs to an existing repository')
    if requested.is_symlink():
        fail('Destination must not be a symlink')
    if target.exists() and (not target.is_dir() or any(target.iterdir())):
        fail('Destination must be an empty directory')
    planned = entries(root)
    # Validate everything before creating the destination. No symlinks at any level.
    for src, _ in planned:
        candidate = root / src
        if any(part.is_symlink() for part in (candidate, *candidate.parents)):
            fail('Source symlink refused')
        if not candidate.is_file():
            fail(f'Missing manifest file: {src}')
    target.mkdir(parents=True, exist_ok=True)
    for src, dst in planned:
        destination = target / dst
        destination.parent.mkdir(parents=True, exist_ok=True)
        # Exclusive creation: never overwrite, even if another process created a file.
        with (root / src).open('rb') as incoming, destination.open('xb') as outgoing:
            shutil.copyfileobj(incoming, outgoing)
        destination.chmod(0o755 if os.access(root / src, os.X_OK) else 0o644)


def findings(root: Path) -> list[tuple[str, int, str]]:
    result: list[tuple[str, int, str]] = []
    for path in sorted(root.rglob('*')):
        relative = path.relative_to(root)
        if '.git' in relative.parts:
            continue
        if path.is_symlink():
            fail('Symlink in export')
        if not path.is_file():
            continue
        safe_relative(relative.as_posix())
        raw = path.read_bytes()
        if relative.as_posix() in ALLOWED_BINARY:
            result.append((relative.as_posix(), 0, hashlib.sha256(raw).hexdigest()))
            continue
        try:
            raw.decode('utf-8')
        except UnicodeError:
            fail(f'Unexpected binary: {relative}')
        # Baseline hashes cannot contain matched plaintext and are not scanned recursively.
        if relative.as_posix() == 'tools/public_export.reviewed.tsv':
            continue
        proc = subprocess.run(['grep', '-nEi', '--', PATTERN, str(path)],
                              capture_output=True, check=False)
        if proc.returncode not in (0, 1):
            fail(f'grep failed: {relative}')
        for line in proc.stdout.splitlines():
            number, content = line.split(b':', 1)
            result.append((relative.as_posix(), int(number), hashlib.sha256(content).hexdigest()))
    return result


def scan(root: Path) -> None:
    baseline: set[tuple[str, str]] = set()
    for line in (root / 'tools/public_export.reviewed.tsv').read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        fields = line.split('\t')
        if (len(fields) != 3 or not re.fullmatch('[0-9a-f]{64}', fields[1])
                or fields[2] not in {'synthetic', 'documentation', 'code', 'upstream-license', 'public-service', 'binary'}):
            fail('Invalid reviewed finding record')
        safe_relative(fields[0])
        baseline.add((fields[0], fields[1]))
    candidates = findings(root)  # Always run grep, including unchanged files.
    locations: dict[str, list[int]] = {}
    for path, number, _ in candidates:
        locations.setdefault(path, []).append(number)
    rejected = False
    for file in sorted(root.rglob('*')):
        relative = file.relative_to(root).as_posix()
        if '.git' in file.relative_to(root).parts or not file.is_file():
            continue
        if relative == 'tools/public_export.reviewed.tsv':
            continue
        if (relative, hashlib.sha256(file.read_bytes()).hexdigest()) not in baseline:
            for number in locations.get(relative, [0]):
                print(f'{relative}:{number}: unreviewed privacy/secret candidate', file=sys.stderr)
            rejected = True
    if rejected:
        fail('Export refused: review findings; never automatically refresh the baseline')


def main() -> int:
    try:
        if len(sys.argv) == 4 and sys.argv[1] == 'prepare':
            prepare(Path(sys.argv[2]), Path(sys.argv[3]))
        elif len(sys.argv) == 3 and sys.argv[1] == 'scan':
            scan(Path(sys.argv[2]))
        else:
            fail('Usage: public_export.py prepare SOURCE DEST | scan EXPORT')
    except (ValueError, OSError) as error:
        # OSError may contain a private absolute path; never echo it.
        print(str(error) if isinstance(error, ValueError) else 'Export filesystem operation failed', file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())
