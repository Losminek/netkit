#!/usr/bin/env python3
"""Export aliasů z ~/.ssh/config do importního souboru NetKit a rozdání veřejného klíče.

Spouštěj na vlastním PC: ``python3 -I tools/netkit_ssh_export.py --help``.
Jen standardní knihovna. Soubor nikdy neobsahuje hesla, klíče ani cesty ke klíčům.
Formát v1 je závazně popsaný v ``prompty/11-c2-import-ssh-profilu.md``.
"""
from __future__ import annotations

import argparse
import base64
import binascii
import glob
import json
import os
import re
import shlex
import shutil
import socket
import subprocess
import sys
import tempfile
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Sequence, TextIO

FORMAT = "netkit-ssh-profiles"
SCHEMA = 1
MAX_PROFILES = 200
MAX_SKIPPED = 200
MAX_FILE_BYTES = 256 * 1024
MAX_ALIAS = 100
MAX_GROUP = 60
MAX_SOURCE = 64
MAX_HOST = 253
MAX_USERNAME = 128
MAX_INCLUDE_DEPTH = 16  # READCONF_MAX_DEPTH v OpenSSH
MAX_PUBKEY_BYTES = 16 * 1024
SSH_G_TIMEOUT = 5.0
COPY_ID_TIMEOUT = 120.0

REASON_PROXY_JUMP = "PROXY_JUMP"
REASON_PROXY_COMMAND = "PROXY_COMMAND"
REASON_MATCH = "MATCH"
REASON_WILDCARD = "WILDCARD"
REASON_MISSING_HOST = "MISSING_HOST"
REASON_UNSUPPORTED = "UNSUPPORTED_OPTION"
REASONS = frozenset({REASON_PROXY_JUMP, REASON_PROXY_COMMAND, REASON_MATCH, REASON_WILDCARD,
                     REASON_MISSING_HOST, REASON_UNSUPPORTED})

FORBIDDEN_HOST_CHARS = frozenset("/\\@?#[]")
WILDCARD_CHARS = frozenset("*?!")
PUBKEY_TYPES = frozenset({"ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384",
                          "ecdsa-sha2-nistp521", "ssh-rsa"})
RECOMMENDED_COMMENT = "netkit@xiaomi15"
GROUP_COMMENT = re.compile(r"^#\s*netkit-group:\s*(.*?)\s*$")
KEYWORD_LINE = re.compile(r"^([^\s=]+)\s*(?:=\s*)?(.*)$")
RESOLVED_KEYS = ("hostname", "user", "port", "proxyjump", "proxycommand")
YES_ANSWERS = frozenset({"y", "yes", "a", "ano"})
# Git hosting přes SSH nemá shell: profil v telefonu ani klíč přes ssh-copy-id nedávají smysl.
GIT_HOSTS = frozenset({"github.com", "ssh.github.com", "gitlab.com", "altssh.gitlab.com", "bitbucket.org",
                       "altssh.bitbucket.org", "codeberg.org", "ssh.dev.azure.com", "vs-ssh.visualstudio.com"})
CAUTION_ALIAS = re.compile(r"(^|[-_.])(audit|prod|production)($|[-_.])", re.IGNORECASE)


class ToolError(Exception):
    """Chyba pro uživatele; text je česky a neobsahuje tajemství."""


# --- validace shodná s SshProfile.valid() v aplikaci -------------------------------------

def utf16_len(value: str) -> int:
    """Délka jako Kotlin String.length (UTF-16 jednotky)."""
    return len(value.encode("utf-16-le")) // 2


def _has_control(value: str) -> bool:
    return any(ord(ch) < 32 or ord(ch) == 127 for ch in value)


def _has_space(value: str) -> bool:
    return any(ch.isspace() for ch in value)


def valid_alias(value: str) -> bool:
    # Aplikace u aliasu odmítá i C1 řídicí znaky 0x80–0x9F (SshProfileImport.validAlias).
    return (1 <= utf16_len(value) <= MAX_ALIAS and value.strip() != "" and not _has_control(value)
            and not any(0x80 <= ord(ch) <= 0x9F for ch in value))


def valid_group(value: str) -> bool:
    return 1 <= utf16_len(value) <= MAX_GROUP and value.strip() != "" and not _has_control(value)


def valid_host(value: str) -> bool:
    return (1 <= utf16_len(value) <= MAX_HOST and not _has_space(value) and not _has_control(value)
            and not any(ch in FORBIDDEN_HOST_CHARS for ch in value))


def valid_username(value: str) -> bool:
    return 1 <= utf16_len(value) <= MAX_USERNAME and not _has_space(value) and not _has_control(value)


def parse_port(value: str | None) -> int | None:
    if value is None or not value.isascii() or not value.isdigit():
        return None
    port = int(value)
    return port if 1 <= port <= 65535 else None


# --- čtení ssh_config (objevování aliasů a minimální vlastní resolver) -------------------

def split_args(text: str) -> list[str]:
    """Rozdělí argumenty jako OpenSSH argv_split: uvozovky, escapování, '#' na začátku slova."""
    args: list[str] = []
    i, n = 0, len(text)
    while i < n:
        while i < n and text[i] in " \t":
            i += 1
        if i >= n or text[i] == "#":
            break
        token: list[str] = []
        quote: str | None = None
        while i < n:
            ch = text[i]
            if quote is None and ch in " \t":
                break
            if ch == "\\" and i + 1 < n and text[i + 1] in "\\\"' ":
                token.append(text[i + 1])
                i += 2
                continue
            if quote is None and ch in "\"'":
                quote = ch
            elif quote is not None and ch == quote:
                quote = None
            else:
                token.append(ch)
            i += 1
        if quote is not None:
            raise ValueError("neukončené uvozovky")
        args.append("".join(token))
    return args


def is_wildcard(pattern: str) -> bool:
    return any(ch in WILDCARD_CHARS for ch in pattern)


def _pattern_regex(pattern: str) -> re.Pattern[str]:
    parts = [".*" if ch == "*" else "." if ch == "?" else re.escape(ch) for ch in pattern]
    return re.compile("".join(parts), re.DOTALL)


def host_patterns_match(patterns: Sequence[str], alias: str) -> bool:
    """Sémantika Host řádku: negace vyřadí celý řádek, jinak stačí jedna pozitivní shoda."""
    name = alias.lower()
    matched = False
    for raw in patterns:
        negated = raw.startswith("!")
        pattern = (raw[1:] if negated else raw).lower()
        if _pattern_regex(pattern).fullmatch(name):
            if negated:
                return False
            matched = True
    return matched


MATCH_BLOCK: tuple[str, ...] = ("\0match",)


@dataclass(frozen=True)
class HostEntry:
    pattern: str
    group: str | None
    source_file: Path
    included: bool


@dataclass(frozen=True)
class ConfigLine:
    conditions: tuple[tuple[str, ...], ...]
    keyword: str
    args: tuple[str, ...]


@dataclass
class ParsedConfig:
    hosts: list[HostEntry] = field(default_factory=list)
    lines: list[ConfigLine] = field(default_factory=list)
    match_files: set[Path] = field(default_factory=set)
    warnings: list[str] = field(default_factory=list)


def _expand_include(arg: str, home: Path, ssh_dir: Path) -> list[Path]:
    if arg == "~" or arg.startswith("~/"):
        arg = str(home) + arg[1:]
    path = arg if os.path.isabs(arg) else str(ssh_dir / arg)
    return [Path(p) for p in sorted(glob.glob(path))]


def parse_config(config: Path, home: Path, ssh_dir: Path) -> ParsedConfig:
    parsed = ParsedConfig()
    if not config.is_file():
        raise ToolError(f"Konfigurační soubor {config} neexistuje nebo není soubor.")
    _parse_file(config, home, ssh_dir, parsed, (), (), included=False)
    return parsed


def _parse_file(path: Path, home: Path, ssh_dir: Path, parsed: ParsedConfig,
                outer: tuple[tuple[str, ...], ...], stack: tuple[Path, ...], included: bool) -> None:
    real = Path(os.path.realpath(path))
    if real in stack:
        parsed.warnings.append(f"Cyklický Include {path} přeskočen.")
        return
    if len(stack) >= MAX_INCLUDE_DEPTH:
        parsed.warnings.append(f"Include {path} překročil hloubku {MAX_INCLUDE_DEPTH}, přeskočen.")
        return
    try:
        text = real.read_text(encoding="utf-8-sig")
    except (OSError, UnicodeDecodeError) as error:
        parsed.warnings.append(f"Soubor {path} nelze přečíst ({type(error).__name__}), přeskočen.")
        return
    stack = stack + (real,)
    conditions = outer
    previous = ""
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        above, previous = previous, line
        if not line or line.startswith("#"):
            continue
        match = KEYWORD_LINE.match(line)
        if match is None:
            continue
        keyword = match.group(1).lower()
        try:
            args = split_args(match.group(2))
        except ValueError:
            parsed.warnings.append(f"{path}:{number}: neukončené uvozovky, řádek přeskočen.")
            continue
        if keyword == "host":
            conditions = outer + (tuple(args),)
            group = _group_from_comment(above, path, number, parsed)
            if group is None and included:
                stem = path.stem.strip()[:MAX_GROUP]
                group = stem if valid_group(stem) else None
            for pattern in args:
                parsed.hosts.append(HostEntry(pattern, group, real, included))
        elif keyword == "match":
            conditions = outer + (MATCH_BLOCK,)
            parsed.match_files.add(real)
        elif keyword == "include":
            for arg in args:
                for target in _expand_include(arg, home, ssh_dir):
                    _parse_file(target, home, ssh_dir, parsed, conditions, stack, included=True)
        else:
            parsed.lines.append(ConfigLine(conditions, keyword, tuple(args)))


def _group_from_comment(above: str, path: Path, number: int, parsed: ParsedConfig) -> str | None:
    match = GROUP_COMMENT.match(above)
    if match is None:
        return None
    value = match.group(1)
    if valid_group(value):
        return value
    parsed.warnings.append(f"{path}:{number}: neplatná netkit-group (1–{MAX_GROUP} znaků), ignorována.")
    return None


# --- resolvery -----------------------------------------------------------------------------

@dataclass(frozen=True)
class Resolved:
    hostname: str | None
    user: str | None
    port: str | None
    proxyjump: str | None = None
    proxycommand: str | None = None


@dataclass(frozen=True)
class SshGResult:
    returncode: int
    stdout: str
    stderr: str = ""


SshGRunner = Callable[[str, Path | None], SshGResult]
CopyRunner = Callable[[Sequence[str], float], int]
Resolver = Callable[[str], Resolved | str]


def ssh_g_argv(ssh: str, alias: str, config: Path | None) -> list[str]:
    argv = [ssh, "-G"]
    if config is not None:
        argv += ["-F", str(config)]
    return argv + ["--", alias]


def minimal_env(home: Path) -> dict[str, str]:
    env = {"PATH": "/usr/bin:/bin", "HOME": str(home), "LANG": "C", "LC_ALL": "C"}
    for name in ("USER", "LOGNAME"):
        value = os.environ.get(name)
        if value:
            env[name] = value
    return env


def make_ssh_g_runner(ssh: str, home: Path) -> SshGRunner:
    def run(alias: str, config: Path | None) -> SshGResult:
        try:
            done = subprocess.run(ssh_g_argv(ssh, alias, config), stdin=subprocess.DEVNULL,
                                  capture_output=True, text=True, timeout=SSH_G_TIMEOUT,
                                  env=minimal_env(home), check=False)
        except subprocess.TimeoutExpired:
            return SshGResult(124, "", "timeout")
        except OSError as error:
            return SshGResult(127, "", type(error).__name__)
        return SshGResult(done.returncode, done.stdout, done.stderr)
    return run


def parse_ssh_g(output: str) -> Resolved:
    values: dict[str, str] = {}
    for line in output.splitlines():
        key, _, value = line.partition(" ")
        key = key.lower()
        if key in RESOLVED_KEYS and key not in values:
            values[key] = value.strip()
    return Resolved(values.get("hostname"), values.get("user") or None, values.get("port"),
                    values.get("proxyjump"), values.get("proxycommand"))


def ssh_g_resolver(runner: SshGRunner, config: Path | None, warnings: list[str]) -> Resolver:
    def resolve(alias: str) -> Resolved | str:
        result = runner(alias, config)
        if result.returncode != 0:
            detail = result.stderr.strip().splitlines()[-1:] or [f"kód {result.returncode}"]
            warnings.append(f"ssh -G {alias} selhalo: {detail[0][:200]}")
            return REASON_UNSUPPORTED
        return parse_ssh_g(result.stdout)
    return resolve


def _expand_hostname(value: str, alias: str) -> str | None:
    out: list[str] = []
    i = 0
    while i < len(value):
        if value[i] == "%":
            nxt = value[i + 1:i + 2]
            if nxt == "h":
                out.append(alias)
            elif nxt == "%":
                out.append("%")
            else:
                return None
            i += 2
            continue
        out.append(value[i])
        i += 1
    return "".join(out)


def fallback_resolver(parsed: ParsedConfig, warnings: list[str]) -> Resolver:
    """První shoda vyhrává, Host * jako výchozí hodnoty; Match bloky se nevyhodnocují."""
    def resolve(alias: str) -> Resolved | str:
        values: dict[str, str] = {}
        for line in parsed.lines:
            if line.keyword not in RESOLVED_KEYS or line.keyword in values or not line.args:
                continue
            if all(cond is not MATCH_BLOCK and host_patterns_match(cond, alias) for cond in line.conditions):
                values[line.keyword] = " ".join(line.args) if line.keyword == "proxycommand" else line.args[0]
        hostname = _expand_hostname(values.get("hostname", alias), alias)
        if hostname is None:
            warnings.append(f"{alias}: HostName obsahuje nepodporovaný %-token.")
            return REASON_UNSUPPORTED
        return Resolved(hostname, values.get("user"), values.get("port", "22"),
                        values.get("proxyjump"), values.get("proxycommand"))
    return resolve


# --- sestavení exportu ---------------------------------------------------------------------

@dataclass(frozen=True)
class Profile:
    alias: str
    group: str | None
    host: str
    port: int
    username: str

    def to_json(self) -> dict[str, object]:
        item: dict[str, object] = {"alias": self.alias}
        if self.group is not None:
            item["group"] = self.group
        item.update({"host": self.host, "port": self.port, "username": self.username})
        return item


@dataclass(frozen=True)
class Skipped:
    alias: str
    reason: str

    def to_json(self) -> dict[str, object]:
        return {"alias": self.alias, "reason": self.reason}


@dataclass
class Collection:
    profiles: list[Profile] = field(default_factory=list)
    skipped: list[Skipped] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    unknown_only: list[str] = field(default_factory=list)


def _not_none(value: str | None) -> str | None:
    return None if value is None or value.lower() == "none" or value == "" else value


def build_profile(alias: str, group: str | None, resolved: Resolved, default_user: str | None,
                  warnings: list[str]) -> Profile | Skipped:
    if _not_none(resolved.proxyjump) is not None:
        return Skipped(alias, REASON_PROXY_JUMP)
    if _not_none(resolved.proxycommand) is not None:
        return Skipped(alias, REASON_PROXY_COMMAND)
    host = (resolved.hostname or "").strip()
    if not host:
        return Skipped(alias, REASON_MISSING_HOST)
    if not valid_host(host):
        warnings.append(f"{alias}: neplatný host (mezery, řídicí znaky, /\\@?#[] nebo > {MAX_HOST} znaků).")
        return Skipped(alias, REASON_UNSUPPORTED)
    if host.lower().rstrip(".") in GIT_HOSTS:
        warnings.append(f"{alias}: git hosting ({host}) vynechán, v telefonu ani pro klíč nemá smysl.")
        return Skipped(alias, REASON_UNSUPPORTED)
    port = parse_port(resolved.port)
    if port is None:
        warnings.append(f"{alias}: neplatný port {resolved.port!r} (povoleno 1–65535).")
        return Skipped(alias, REASON_UNSUPPORTED)
    username = resolved.user or default_user
    if username is None:
        warnings.append(f"{alias}: chybí uživatel; doplň User v configu nebo použij --default-user.")
        return Skipped(alias, REASON_UNSUPPORTED)
    if not valid_username(username):
        warnings.append(f"{alias}: neplatné uživatelské jméno (1–{MAX_USERNAME} znaků bez mezer).")
        return Skipped(alias, REASON_UNSUPPORTED)
    return Profile(alias, group, host, port, username)


def collect(parsed: ParsedConfig, resolver: Resolver, default_user: str | None,
            only: set[str] | None, fallback: bool) -> Collection:
    result = Collection(warnings=list(parsed.warnings))
    seen: set[str] = set()
    for entry in parsed.hosts:
        alias = entry.pattern
        if alias in seen or alias == "*" or (only is not None and alias not in only):
            continue
        seen.add(alias)
        if not valid_alias(alias):
            result.warnings.append(f"Host {alias[:40]!r}… nesplňuje pravidla aliasu (1–{MAX_ALIAS} znaků), ignorován.")
            continue
        if is_wildcard(alias):
            result.skipped.append(Skipped(alias, REASON_WILDCARD))
            continue
        if alias.startswith("-"):
            result.skipped.append(Skipped(alias, REASON_UNSUPPORTED))
            continue
        if fallback and entry.source_file in parsed.match_files:
            result.skipped.append(Skipped(alias, REASON_MATCH))
            continue
        resolved = resolver(alias)
        if isinstance(resolved, str):
            result.skipped.append(Skipped(alias, resolved))
            continue
        item = build_profile(alias, entry.group, resolved, default_user, result.warnings)
        if isinstance(item, Profile):
            result.profiles.append(item)
        else:
            result.skipped.append(item)
    if only is not None:
        result.unknown_only = sorted(only - seen)
    return result


def build_document(collection: Collection, source: str) -> dict[str, object]:
    if len(collection.profiles) > MAX_PROFILES:
        raise ToolError(f"Nalezeno {len(collection.profiles)} profilů, aplikace přijme nejvýše {MAX_PROFILES}. "
                        "Vyber aliasy přes --only a,b,c a exportuj po částech.")
    skipped = collection.skipped[:MAX_SKIPPED]
    if len(collection.skipped) > MAX_SKIPPED:
        collection.warnings.append(f"Seznam skipped zkrácen na {MAX_SKIPPED} položek (limit aplikace).")
    document: dict[str, object] = {
        "format": FORMAT,
        "schema": SCHEMA,
        "source": source,
        "profiles": [p.to_json() for p in collection.profiles],
        "skipped": [s.to_json() for s in skipped],
    }
    return document


def encode_document(document: dict[str, object]) -> bytes:
    data = (json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if len(data) > MAX_FILE_BYTES:
        raise ToolError(f"Výsledný soubor má {len(data)} B, limit aplikace je {MAX_FILE_BYTES} B. Použij --only.")
    return data


def default_source(hostname: str) -> str:
    cleaned = "".join(ch for ch in hostname if ord(ch) >= 32 and ord(ch) != 127).strip()
    return cleaned[:MAX_SOURCE] or "pc"


# --- zápis výstupu -------------------------------------------------------------------------

def find_git_worktree(directory: Path) -> Path | None:
    for candidate in (directory, *directory.parents):
        if (candidate / ".git").exists():
            return candidate
    return None


def write_private(path: Path, data: bytes, force: bool) -> Path:
    target = Path(os.path.abspath(path))
    parent = Path(os.path.realpath(target.parent))
    if not parent.is_dir():
        raise ToolError(f"Adresář {target.parent} neexistuje.")
    if target.is_dir():
        raise ToolError(f"{target} je adresář.")
    repo = find_git_worktree(parent)
    if repo is not None and not force:
        raise ToolError(f"Výstup {target} leží v git repozitáři {repo}; soubor obsahuje názvy hostů a "
                        "uživatelů a nesmí skončit v commitu. Zvol --out mimo repozitář, nebo potvrď --force.")
    fd, tmp = tempfile.mkstemp(prefix=".netkit-ssh-profiles.", suffix=".tmp", dir=parent)
    try:
        with os.fdopen(fd, "wb") as handle:
            os.fchmod(handle.fileno(), 0o600)
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, parent / target.name)
    except BaseException:
        try:
            os.unlink(tmp)
        except FileNotFoundError:
            pass
        raise
    return parent / target.name


# --- veřejný klíč ----------------------------------------------------------------------------

@dataclass(frozen=True)
class PublicKey:
    path: Path
    key_type: str
    comment: str


def load_public_key(path: Path) -> PublicKey:
    if path.suffix != ".pub":
        raise ToolError("Soubor klíče musí mít příponu .pub (ssh-copy-id jinak hledá jiný soubor).")
    try:
        with path.open("rb") as handle:
            raw = handle.read(MAX_PUBKEY_BYTES + 1)
    except OSError as error:
        raise ToolError(f"Soubor {path} nelze přečíst ({type(error).__name__}).") from None
    if b"PRIVATE KEY" in raw:
        raise ToolError("Soubor obsahuje PRIVÁTNÍ klíč. Předej jen veřejnou část (.pub) a privátní klíč "
                        "nikam nekopíruj; pokud unikl, vygeneruj v aplikaci nový.")
    if len(raw) > MAX_PUBKEY_BYTES:
        raise ToolError(f"Soubor je větší než {MAX_PUBKEY_BYTES} B, nejde o jeden veřejný klíč.")
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        raise ToolError("Soubor klíče není platné UTF-8.") from None
    lines = [line for line in text.splitlines() if line.strip()]
    if len(lines) != 1:
        raise ToolError(f"Soubor musí obsahovat právě jeden řádek s veřejným klíčem (nalezeno {len(lines)}).")
    parts = lines[0].split()
    if len(parts) < 2 or parts[0] not in PUBKEY_TYPES:
        raise ToolError("Nepodporovaný formát; čekám 'ssh-ed25519|ecdsa-sha2-nistp256/384/521|ssh-rsa "
                        "<base64> [komentář]' bez voleb authorized_keys.")
    try:
        blob = base64.b64decode(parts[1], validate=True)
    except (binascii.Error, ValueError):
        raise ToolError("Data klíče nejsou platné base64.") from None
    if len(blob) < 4:
        raise ToolError("Data klíče jsou zkrácená.")
    length = int.from_bytes(blob[:4], "big")
    if blob[4:4 + length] != parts[0].encode("ascii"):
        raise ToolError("Typ klíče v datech nesouhlasí s uvedeným typem.")
    return PublicKey(path, parts[0], " ".join(parts[2:]))


def copy_id_argv(key: Path, alias: str, config: Path | None) -> list[str]:
    argv = ["ssh-copy-id", "-f", "-i", str(key)]
    if config is not None:
        argv += ["-F", str(config)]
    return argv + [alias]


def default_copy_runner(argv: Sequence[str], timeout: float) -> int:
    # Bez capture: dědí TTY, aby fungovalo zadání hesla a 2FA.
    return subprocess.run(list(argv), timeout=timeout, check=False).returncode


# --- CLI -------------------------------------------------------------------------------------

@dataclass
class Environment:
    home: Path
    ssh_dir: Path
    hostname: str
    ssh_runner: SshGRunner | None
    copy_runner: CopyRunner
    input_fn: Callable[[str], str]
    stdout: TextIO
    stderr: TextIO


def default_environment() -> Environment:
    home = Path.home()
    ssh = shutil.which("ssh")
    return Environment(home=home, ssh_dir=home / ".ssh", hostname=socket.gethostname(),
                       ssh_runner=make_ssh_g_runner(ssh, home) if ssh else None,
                       copy_runner=default_copy_runner, input_fn=input,
                       stdout=sys.stdout, stderr=sys.stderr)


def parse_only(value: str) -> set[str]:
    names = {name.strip() for name in value.split(",") if name.strip()}
    if not names:
        raise argparse.ArgumentTypeError("--only potřebuje aspoň jeden alias")
    return names


def _add_help(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("-h", "--help", action="help", help="zobrazí tuto nápovědu a skončí")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="netkit_ssh_export.py", add_help=False,
        description="Převede aliasy z ~/.ssh/config na importní soubor profilů NetKit "
                    "a volitelně rozdá veřejný klíč telefonu. Bez podpříkazu se spustí export.")
    _add_help(parser)
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--config", type=Path, help="cesta k ssh configu (výchozí ~/.ssh/config)")
    common.add_argument("--default-user", help="uživatel pro aliasy bez User (ssh -G doplní místní login sám, uplatní se hlavně ve vlastním resolveru)")
    sub = parser.add_subparsers(dest="command", metavar="{export,install-pubkey}")

    export = sub.add_parser("export", parents=[common], add_help=False, help="vytvoří importní JSON pro aplikaci",
                            description="Vytvoří importní JSON (formát netkit-ssh-profiles v1). "
                                        "Neobsahuje hesla, klíče ani cesty ke klíčům.")
    _add_help(export)
    export.add_argument("--only", type=parse_only, help="jen vybrané aliasy, oddělené čárkou")
    export.add_argument("--out", type=Path, help="výstupní soubor (výchozí ~/netkit-ssh-profiles.json)")
    export.add_argument("--source", help="název zdroje v souboru (výchozí hostname PC, max. 64 znaků)")
    export.add_argument("--force", action="store_true", help="dovolí zápis do git repozitáře")

    install = sub.add_parser("install-pubkey", parents=[common], add_help=False,
                             help="nainstaluje veřejný klíč přes ssh-copy-id",
                             description="Přidá veřejný klíč telefonu do authorized_keys vybraných hostů "
                                         "přes ssh-copy-id (hesla/2FA zadáváš v terminálu).")
    _add_help(install)
    install.add_argument("--key", type=Path, required=True, help="veřejný klíč z aplikace (soubor .pub)")
    target = install.add_mutually_exclusive_group(required=True)
    target.add_argument("--only", type=parse_only, help="jen vybrané aliasy, oddělené čárkou")
    target.add_argument("--all", action="store_true", help="všechny exportovatelné aliasy")
    install.add_argument("--yes", action="store_true", help="neptat se u každého hosta")
    install.add_argument("--dry-run", action="store_true", help="jen vypíše příkazy, nic nespustí")
    return parser


def _discover(args: argparse.Namespace, env: Environment) -> tuple[Path, Collection]:
    explicit: Path | None = args.config
    config = explicit if explicit is not None else env.ssh_dir / "config"
    parsed = parse_config(config, env.home, env.ssh_dir)
    warnings: list[str] = []
    if env.ssh_runner is None:
        env.stderr.write("Upozornění: příkaz ssh není dostupný, použije se zjednodušený vlastní resolver "
                         "(první shoda vyhrává, Match bloky se nevyhodnocují).\n")
        resolver = fallback_resolver(parsed, warnings)
    else:
        resolver = ssh_g_resolver(env.ssh_runner, explicit, warnings)
    collection = collect(parsed, resolver, args.default_user, args.only, env.ssh_runner is None)
    collection.warnings.extend(warnings)
    return config, collection


def _print_warnings(collection: Collection, env: Environment) -> None:
    for warning in collection.warnings:
        env.stderr.write(f"Upozornění: {warning}\n")
    if collection.unknown_only:
        env.stderr.write(f"Upozornění: aliasy z --only nenalezeny: {', '.join(collection.unknown_only)}\n")


def run_export(args: argparse.Namespace, env: Environment) -> int:
    config, collection = _discover(args, env)
    source = default_source(args.source if args.source is not None else env.hostname)
    document = build_document(collection, source)
    data = encode_document(document)
    out: Path = args.out if args.out is not None else env.home / "netkit-ssh-profiles.json"
    written = write_private(out, data, args.force)
    _print_warnings(collection, env)
    reasons = Counter(s.reason for s in collection.skipped)
    env.stderr.write(f"Config: {config}\nExportováno profilů: {len(collection.profiles)} -> {written} (0600)\n"
                     f"Přeskočeno: {len(collection.skipped)}\n")
    for reason, count in sorted(reasons.items()):
        names = ", ".join(s.alias for s in collection.skipped if s.reason == reason)
        env.stderr.write(f"  {reason}: {count} ({names})\n")
    env.stderr.write("Soubor obsahuje názvy hostů a uživatelů: přenes ho kabelem nebo na vlastní NAS "
                     "a po importu ho smaž.\n")
    return 0


def run_install(args: argparse.Namespace, env: Environment) -> int:
    key = load_public_key(args.key)
    if not key.comment.startswith("netkit@"):
        env.stderr.write(f"Doporučení: dej klíči komentář jako {RECOMMENDED_COMMENT}, ať jde na serveru "
                         "v authorized_keys snadno najít a odvolat.\n")
    _config, collection = _discover(args, env)
    if collection.unknown_only:
        raise ToolError(f"Neznámé aliasy v --only: {', '.join(collection.unknown_only)}")
    _print_warnings(collection, env)
    if args.only is None:
        cautious = [p.alias for p in collection.profiles if CAUTION_ALIAS.search(p.alias)]
        if cautious:
            env.stderr.write("Pozor: tyto aliasy vypadají jako omezené (audit) nebo produkční účty; klíč telefonu "
                             f"tam dej jen vědomě, jinak použij --only: {', '.join(cautious)}\n")
    env.stderr.write("RouterOS a jiné prvky bez shellu ssh-copy-id nepodporují; klíč tam přidej ručně "
                     "(MikroTik: /user ssh-keys).\n")
    if not args.dry_run:
        env.stderr.write("Upozornění: ssh-copy-id -f nekontroluje, zda klíč na serveru už je; opakované "
                         "spuštění přidá do authorized_keys duplicitní řádek. Opakuj jen s --only.\n")
    explicit: Path | None = args.config
    results: list[tuple[str, str, str]] = [(s.alias, "SKIPPED", s.reason) for s in collection.skipped
                                           if args.only is not None or s.reason != REASON_WILDCARD]
    for profile in collection.profiles:
        argv = copy_id_argv(key.path, profile.alias, explicit)
        if args.dry_run:
            env.stdout.write(f"DRY-RUN: {shlex.join(argv)}\n")
            results.append((profile.alias, "DRY-RUN", ""))
            continue
        if not args.yes:
            try:
                answer = env.input_fn(f"Přidat klíč na {profile.alias} "
                                      f"({profile.username}@{profile.host}:{profile.port})? [y/N] ")
            except EOFError:
                answer = ""
            if answer.strip().lower() not in YES_ANSWERS:
                results.append((profile.alias, "SKIPPED", "nepotvrzeno"))
                continue
        try:
            code = env.copy_runner(argv, COPY_ID_TIMEOUT)
        except subprocess.TimeoutExpired:
            results.append((profile.alias, "FAIL", f"timeout {int(COPY_ID_TIMEOUT)} s"))
            continue
        except OSError as error:
            results.append((profile.alias, "FAIL", f"ssh-copy-id nelze spustit ({type(error).__name__})"))
            continue
        results.append((profile.alias, "OK", "") if code == 0 else (profile.alias, "FAIL", f"kód {code}"))
    env.stdout.write("Výsledek:\n")
    for alias, status, detail in results:
        env.stdout.write(f"  {status:8} {alias}{f' ({detail})' if detail else ''}\n")
    return 1 if any(status == "FAIL" for _, status, _ in results) else 0


def main(argv: Sequence[str] | None = None, env: Environment | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    if not arguments or arguments[0] not in {"export", "install-pubkey", "-h", "--help"}:
        arguments.insert(0, "export")
    environment = env if env is not None else default_environment()
    args = build_parser().parse_args(arguments)
    try:
        if args.command == "install-pubkey":
            return run_install(args, environment)
        return run_export(args, environment)
    except ToolError as error:
        environment.stderr.write(f"Chyba: {error}\n")
        return 2
    except KeyboardInterrupt:
        environment.stderr.write("Přerušeno.\n")
        return 130


if __name__ == "__main__":
    sys.exit(main())
