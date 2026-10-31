"""Testy netkit_ssh_export.py: jen syntetické configy v dočasných adresářích, žádné skutečné ssh."""
from __future__ import annotations

import base64
import io
import json
import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path
from typing import Sequence
from unittest.mock import Mock, patch

import netkit_ssh_export as tool
from netkit_ssh_export import Environment, SshGResult

ROOT_KEYS = {"format", "schema", "source", "profiles", "skipped"}
PROFILE_KEYS = {"alias", "group", "host", "port", "username"}
# Syntetický marker skládaný z částí; nejde o žádný skutečný klíč.
PRIVATE_MARKER = "-----BEGIN OPENSSH " + "PRIVATE" + " KEY-----"


def fake_pubkey(comment: str = "netkit@xiaomi15", key_type: str = "ssh-ed25519") -> str:
    name = key_type.encode()
    blob = len(name).to_bytes(4, "big") + name + (32).to_bytes(4, "big") + bytes(32)
    return f"{key_type} {base64.b64encode(blob).decode()} {comment}".rstrip() + "\n"


class FakeSsh:
    """Náhrada `ssh -G`: alias -> výstup; neznámý alias dostane hostname=alias, user=tester."""

    def __init__(self, outputs: dict[str, str] | None = None, failing: set[str] | None = None) -> None:
        self.outputs = outputs or {}
        self.failing = failing or set()
        self.calls: list[tuple[str, Path | None]] = []

    def __call__(self, alias: str, config: Path | None) -> SshGResult:
        self.calls.append((alias, config))
        if alias in self.failing:
            return SshGResult(255, "", "Bad configuration option: foo")
        default = f"user tester\nhostname {alias}\nport 22\nidentityfile ~/.ssh/id_secret\n"
        return SshGResult(0, self.outputs.get(alias, default))


class Sandbox:
    def __init__(self, directory: str) -> None:
        self.root = Path(directory)
        self.home = self.root / "home"
        self.ssh_dir = self.home / ".ssh"
        self.ssh_dir.mkdir(parents=True)
        self.out_dir = self.root / "out"
        self.out_dir.mkdir()
        self.stdout = io.StringIO()
        self.stderr = io.StringIO()

    def write(self, relative: str, text: str) -> Path:
        path = self.ssh_dir / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def env(self, runner: tool.SshGRunner | None, copy_runner: tool.CopyRunner | None = None,
            input_fn: Mock | None = None) -> Environment:
        return Environment(home=self.home, ssh_dir=self.ssh_dir, hostname="build-host",
                           ssh_runner=runner, copy_runner=copy_runner or Mock(return_value=0),
                           input_fn=input_fn or Mock(side_effect=AssertionError("input nečekán")),
                           stdout=self.stdout, stderr=self.stderr)

    def export(self, runner: tool.SshGRunner | None, *extra: str) -> tuple[int, dict[str, object] | None]:
        out = self.out_dir / "profiles.json"
        code = tool.main(["export", "--out", str(out), *extra], self.env(runner))
        return code, (json.loads(out.read_text()) if out.exists() else None)


def profiles(document: dict[str, object] | None) -> dict[str, dict[str, object]]:
    assert document is not None
    items = document["profiles"]
    assert isinstance(items, list)
    return {str(p["alias"]): p for p in items}


def skipped(document: dict[str, object] | None) -> list[tuple[str, str]]:
    assert document is not None
    items = document["skipped"]
    assert isinstance(items, list)
    return [(str(s["alias"]), str(s["reason"])) for s in items]


class DiscoveryTests(unittest.TestCase):
    def test_include_glob_relative_path_and_cycle(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            box.write("config", "Include conf.d/*.conf\nHost top\n  HostName 10.0.0.9\n")
            box.write("conf.d/homelab.conf", "Host pve nas\n")
            box.write("conf.d/work.conf", "Include ~/.ssh/config\nInclude conf.d/work.conf\nHost sw1\n")
            box.write("conf.d/ignored.txt", "Host notincluded\n")
            fake = FakeSsh()
            code, document = box.export(fake)
            self.assertEqual(code, 0)
            found = profiles(document)
            self.assertEqual(list(found), ["pve", "nas", "sw1", "top"])
            self.assertEqual(found["pve"]["group"], "homelab")
            self.assertEqual(found["sw1"]["group"], "work")
            self.assertNotIn("group", found["top"])
            self.assertIn("Cyklický Include", box.stderr.getvalue())
            self.assertEqual([alias for alias, _ in fake.calls], ["pve", "nas", "sw1", "top"])

    def test_include_depth_limit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            box.write("config", "Include d0\n")
            for level in range(20):
                box.write(f"d{level}", f"Include d{level + 1}\nHost h{level}\n")
            code, document = box.export(FakeSsh())
            self.assertEqual(code, 0)
            self.assertEqual(len(profiles(document)), tool.MAX_INCLUDE_DEPTH - 1)
            self.assertIn("hloubku", box.stderr.getvalue())

    def test_wildcards_are_skipped_once_and_host_star_is_silent(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            box.write("config", "Host *\n  User x\nHost *.lan foo !bar\nHost *.lan\nHost r?\n")
            code, document = box.export(FakeSsh())
            self.assertEqual(code, 0)
            self.assertEqual(list(profiles(document)), ["foo"])
            self.assertEqual(skipped(document), [("*.lan", "WILDCARD"), ("!bar", "WILDCARD"), ("r?", "WILDCARD")])

    def test_group_comment_beats_include_stem(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            long_group = "x" * 61
            box.write("config", "Include homelab.conf\n# netkit-group: Hlavní\nHost main\n")
            box.write("homelab.conf", "# netkit-group: Síť doma\nHost a b\n\n# netkit-group: Ztracená\n\nHost c\n"
                                      f"# netkit-group: {long_group}\nHost d\n")
            code, document = box.export(FakeSsh())
            self.assertEqual(code, 0)
            found = profiles(document)
            self.assertEqual(found["a"]["group"], "Síť doma")
            self.assertEqual(found["b"]["group"], "Síť doma")
            self.assertEqual(found["c"]["group"], "homelab")
            self.assertEqual(found["d"]["group"], "homelab")
            self.assertEqual(found["main"]["group"], "Hlavní")
            self.assertIn("neplatná netkit-group", box.stderr.getvalue())

    def test_split_args_like_openssh(self) -> None:
        self.assertEqual(tool.split_args('a "b c" d # komentář'), ["a", "b c", "d"])
        self.assertEqual(tool.split_args("x#y"), ["x#y"])
        with self.assertRaises(ValueError):
            tool.split_args('"neukončené')


class ResolutionTests(unittest.TestCase):
    def test_proxy_jump_and_proxy_command_are_skipped(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            box.write("config", "Host jump cmd plain nojump\n")
            fake = FakeSsh({
                "jump": "user u\nhostname 10.0.0.2\nport 22\nproxyjump bastion\n",
                "cmd": "user u\nhostname 10.0.0.3\nport 22\nproxycommand nc %h %p\n",
                "nojump": "user u\nhostname 10.0.0.4\nport 22\nproxyjump none\n",
            })
            code, document = box.export(fake)
            self.assertEqual(code, 0)
            self.assertEqual(sorted(profiles(document)), ["nojump", "plain"])
            self.assertEqual(skipped(document), [("jump", "PROXY_JUMP"), ("cmd", "PROXY_COMMAND")])

    def test_missing_user_with_and_without_default_user(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            box.write("config", "Host nouser\n")
            fake = FakeSsh({"nouser": "hostname 10.0.0.5\nport 2222\n"})
            code, document = box.export(fake)
            self.assertEqual(code, 0)
            self.assertEqual(skipped(document), [("nouser", "UNSUPPORTED_OPTION")])
            code, document = box.export(fake, "--default-user", "admin")
            self.assertEqual(code, 0)
            self.assertEqual(profiles(document)["nouser"],
                             {"alias": "nouser", "host": "10.0.0.5", "port": 2222, "username": "admin"})

    def test_invalid_host_characters_and_missing_host(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            box.write("config", "Host at slash space empty ok\n")
            fake = FakeSsh({
                "at": "user u\nhostname a@b\nport 22\n",
                "slash": "user u\nhostname a/b\nport 22\n",
                "space": "user u\nhostname a b\nport 22\n",
                "empty": "user u\nhostname \nport 22\n",
                "ok": "user u\nhostname fe80--1.example\nport 22\n",
            })
            code, document = box.export(fake)
            self.assertEqual(code, 0)
            self.assertEqual(list(profiles(document)), ["ok"])
            self.assertEqual(skipped(document), [("at", "UNSUPPORTED_OPTION"), ("slash", "UNSUPPORTED_OPTION"),
                                                 ("space", "UNSUPPORTED_OPTION"), ("empty", "MISSING_HOST")])

    def test_host_rules_match_app(self) -> None:
        self.assertTrue(tool.valid_host("a" * 253))
        self.assertFalse(tool.valid_host("a" * 254))
        for bad in ("a\tb", "a\x7fb", "[::1]", "h#x", "h?x", "h\\x", "a b", ""):
            self.assertFalse(tool.valid_host(bad), bad)
        self.assertTrue(tool.valid_username("u" * 128))
        self.assertFalse(tool.valid_username("u" * 129))
        self.assertFalse(tool.valid_username("a b"))
        self.assertFalse(tool.valid_alias("a" * 101))
        self.assertFalse(tool.valid_alias("a\x01"))
        self.assertTrue(tool.valid_alias("a" * 100))

    def test_port_bounds(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            box.write("config", "Host p0 p1 pmax pover pword\n")
            fake = FakeSsh({name: f"user u\nhostname h\nport {port}\n" for name, port in
                            {"p0": "0", "p1": "1", "pmax": "65535", "pover": "65536", "pword": "ssh"}.items()})
            code, document = box.export(fake)
            self.assertEqual(code, 0)
            found = profiles(document)
            self.assertEqual((found["p1"]["port"], found["pmax"]["port"]), (1, 65535))
            self.assertEqual(skipped(document), [("p0", "UNSUPPORTED_OPTION"), ("pover", "UNSUPPORTED_OPTION"),
                                                 ("pword", "UNSUPPORTED_OPTION")])

    def test_ssh_failure_is_skipped_with_warning(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            box.write("config", "Host broken good\n")
            code, document = box.export(FakeSsh(failing={"broken"}))
            self.assertEqual(code, 0)
            self.assertEqual(skipped(document), [("broken", "UNSUPPORTED_OPTION")])
            self.assertIn("ssh -G broken selhalo", box.stderr.getvalue())

    def test_explicit_config_is_passed_with_dash_f_only_when_given(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            config = box.write("config", "Host a\n")
            fake = FakeSsh()
            box.export(fake)
            box.export(fake, "--config", str(config))
            self.assertEqual(fake.calls, [("a", None), ("a", config)])
            self.assertEqual(tool.ssh_g_argv("ssh", "a", None), ["ssh", "-G", "--", "a"])
            self.assertEqual(tool.ssh_g_argv("ssh", "a", config), ["ssh", "-G", "-F", str(config), "--", "a"])
            self.assertEqual(set(tool.minimal_env(box.home)) - {"USER", "LOGNAME"}, {"PATH", "HOME", "LANG", "LC_ALL"})

    def test_alias_with_leading_dash_is_never_passed_to_ssh(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            box.write("config", "Host -oProxyCommand=x ok\n")
            fake = FakeSsh()
            code, document = box.export(fake)
            self.assertEqual(code, 0)
            self.assertEqual(skipped(document), [("-oProxyCommand=x", "UNSUPPORTED_OPTION")])
            self.assertEqual(fake.calls, [("ok", None)])


class FallbackTests(unittest.TestCase):
    def test_fallback_first_match_wins_with_host_star_defaults_and_match(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            box.write("config", "Include extra.conf\nHost pve\n  HostName 10.0.0.10\n  Port 2200\n"
                                "Host pve\n  HostName 10.9.9.9\nHost jump\n  ProxyJump pve\n"
                                "Host alias-only\nHost *\n  User root\n  Port 22\n")
            box.write("extra.conf", "Host m1\n  HostName 192.0.2.1\nMatch user x\n  Port 1\n")
            code, document = box.export(None)
            self.assertEqual(code, 0)
            found = profiles(document)
            self.assertEqual(found["pve"], {"alias": "pve", "host": "10.0.0.10", "port": 2200, "username": "root"})
            self.assertEqual(found["alias-only"]["host"], "alias-only")
            self.assertEqual(skipped(document), [("m1", "MATCH"), ("jump", "PROXY_JUMP")])
            self.assertIn("ssh není dostupný", box.stderr.getvalue())

    def test_fallback_without_user_needs_default_user(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            box.write("config", "Host a\n  HostName %h.lan\n")
            code, document = box.export(None)
            self.assertEqual((code, skipped(document)), (0, [("a", "UNSUPPORTED_OPTION")]))
            code, document = box.export(None, "--default-user", "tester")
            self.assertEqual(profiles(document)["a"]["host"], "a.lan")

    def test_match_reason_only_in_fallback(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            box.write("config", "Host m\nMatch all\n  User x\n")
            code, document = box.export(FakeSsh())
            self.assertEqual((code, list(profiles(document)), skipped(document)), (0, ["m"], []))


class OutputTests(unittest.TestCase):
    def test_output_has_exactly_allowed_keys_and_no_secrets(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            box.write("config", "# netkit-group: Lab\nHost a\n  IdentityFile ~/.ssh/id_secret\nHost b *.x\n")
            code, document = box.export(FakeSsh())
            self.assertEqual(code, 0)
            assert document is not None
            self.assertEqual(set(document), ROOT_KEYS)
            self.assertEqual((document["format"], document["schema"], document["source"]),
                             ("netkit-ssh-profiles", 1, "build-host"))
            for item in profiles(document).values():
                self.assertLessEqual(set(item), PROFILE_KEYS)
                self.assertLessEqual({"alias", "host", "port", "username"}, set(item))
            self.assertEqual(profiles(document)["a"]["group"], "Lab")
            for item in document["skipped"]:  # type: ignore[union-attr]
                self.assertEqual(set(item), {"alias", "reason"})
                self.assertIn(item["reason"], tool.REASONS)
            raw = (box.out_dir / "profiles.json").read_text()
            self.assertNotIn("id_secret", raw)
            self.assertNotIn("identity", raw.lower())

    def test_source_option_is_truncated(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            box.write("config", "Host a\n")
            _code, document = box.export(FakeSsh(), "--source", "s" * 80)
            assert document is not None
            self.assertEqual(document["source"], "s" * 64)

    def test_more_than_200_profiles_fails_and_only_filters(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            box.write("config", "".join(f"Host h{i}\n" for i in range(201)))
            code, document = box.export(FakeSsh())
            self.assertEqual(code, 2)
            self.assertIsNone(document)
            self.assertIn("--only", box.stderr.getvalue())
            code, document = box.export(FakeSsh(), "--only", "h1,h200,missing")
            self.assertEqual(code, 0)
            self.assertEqual(list(profiles(document)), ["h1", "h200"])
            self.assertIn("missing", box.stderr.getvalue())

    def test_exactly_200_profiles_is_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            box.write("config", "".join(f"Host h{i}\n" for i in range(200)))
            code, document = box.export(FakeSsh())
            self.assertEqual((code, len(profiles(document))), (0, 200))

    def test_file_mode_is_0600_even_when_overwriting(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            box.write("config", "Host a\n")
            out = box.out_dir / "profiles.json"
            out.write_text("{}")
            os.chmod(out, 0o644)
            code, _document = box.export(FakeSsh())
            self.assertEqual(code, 0)
            self.assertEqual(stat.S_IMODE(out.stat().st_mode), 0o600)
            self.assertEqual([p.name for p in box.out_dir.iterdir()], ["profiles.json"])

    def test_refuses_inside_git_worktree_without_force(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            box.write("config", "Host a\n")
            repo = box.root / "repo"
            (repo / "sub").mkdir(parents=True)
            (repo / ".git").write_text("gitdir: /elsewhere\n")  # worktree má .git jako soubor
            out = repo / "sub" / "p.json"
            code = tool.main(["--out", str(out)], box.env(FakeSsh()))
            self.assertEqual(code, 2)
            self.assertFalse(out.exists())
            self.assertIn("git repozitáři", box.stderr.getvalue())
            code = tool.main(["--out", str(out), "--force"], box.env(FakeSsh()))
            self.assertEqual(code, 0)
            self.assertTrue(out.exists())

    def test_default_output_path_is_in_home(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            box = Sandbox(directory)
            box.write("config", "Host a\n")
            self.assertEqual(tool.main([], box.env(FakeSsh())), 0)
            self.assertTrue((box.home / "netkit-ssh-profiles.json").exists())


class InstallPubkeyTests(unittest.TestCase):
    def setUp(self) -> None:
        self._directory = tempfile.TemporaryDirectory()
        self.box = Sandbox(self._directory.name)
        self.box.write("config", "Host a b jump\n")
        self.fake = FakeSsh({"jump": "user u\nhostname j\nport 22\nproxyjump a\n"})
        self.key = self.box.root / "netkit.pub"
        self.key.write_text(fake_pubkey())

    def tearDown(self) -> None:
        self._directory.cleanup()

    def install(self, *extra: str, copy_runner: Mock | None = None, input_fn: Mock | None = None) -> int:
        env = self.box.env(self.fake, copy_runner=copy_runner, input_fn=input_fn)
        return tool.main(["install-pubkey", "--key", str(self.key), *extra], env)

    def test_rejects_private_key(self) -> None:
        self.key.write_text(PRIVATE_MARKER + "\nAAAA\n")
        runner = Mock(return_value=0)
        self.assertEqual(self.install("--all", "--yes", copy_runner=runner), 2)
        self.assertIn("PRIVÁTNÍ", self.box.stderr.getvalue())
        runner.assert_not_called()

    def test_rejects_multiline_and_bad_type_and_bad_suffix(self) -> None:
        self.key.write_text(fake_pubkey() + fake_pubkey("druhy"))
        self.assertEqual(self.install("--all", "--yes"), 2)
        self.assertIn("právě jeden řádek", self.box.stderr.getvalue())
        self.key.write_text(fake_pubkey(key_type="ssh-dss"))
        self.assertEqual(self.install("--all", "--yes"), 2)
        self.key.write_text("ssh-ed25519 !!!nebase64 x\n")
        self.assertEqual(self.install("--all", "--yes"), 2)
        other = self.box.root / "netkit"
        other.write_text(fake_pubkey())
        self.key = other
        self.assertEqual(self.install("--all", "--yes"), 2)
        self.assertIn(".pub", self.box.stderr.getvalue())

    def test_accepts_ecdsa_and_rsa_and_recommends_comment(self) -> None:
        for key_type in ("ecdsa-sha2-nistp256", "ssh-rsa"):
            self.key.write_text(fake_pubkey("tester@pc", key_type))
            self.assertEqual(self.install("--all", "--dry-run"), 0)
        self.assertIn("netkit@xiaomi15", self.box.stderr.getvalue())

    def test_dry_run_prints_argv_without_executing(self) -> None:
        runner = Mock(return_value=0)
        self.assertEqual(self.install("--all", "--dry-run", copy_runner=runner), 0)
        runner.assert_not_called()
        output = self.box.stdout.getvalue()
        self.assertIn(f"DRY-RUN: ssh-copy-id -f -i {self.key} a", output)
        self.assertIn(f"DRY-RUN: ssh-copy-id -f -i {self.key} b", output)
        self.assertNotIn("-i " + str(self.key) + " jump", output)
        self.assertIn("SKIPPED  jump (PROXY_JUMP)", output)

    def test_yes_runs_all_without_prompt(self) -> None:
        calls: list[tuple[list[str], float]] = []

        def runner(argv: Sequence[str], timeout: float) -> int:
            calls.append((list(argv), timeout))
            return 0
        env = self.box.env(self.fake, copy_runner=runner)
        self.assertEqual(tool.main(["install-pubkey", "--key", str(self.key), "--all", "--yes"], env), 0)
        self.assertEqual(calls, [(["ssh-copy-id", "-f", "-i", str(self.key), "a"], 120.0),
                                 (["ssh-copy-id", "-f", "-i", str(self.key), "b"], 120.0)])

    def test_interactive_prompt_controls_each_host(self) -> None:
        runner = Mock(return_value=0)
        answers = Mock(side_effect=["y", ""])
        self.assertEqual(self.install("--only", "a,b", copy_runner=runner, input_fn=answers), 0)
        self.assertEqual(answers.call_args_list[0].args[0], "Přidat klíč na a (tester@a:22)? [y/N] ")
        runner.assert_called_once_with(["ssh-copy-id", "-f", "-i", str(self.key), "a"], 120.0)
        self.assertIn("SKIPPED  b (nepotvrzeno)", self.box.stdout.getvalue())

    def test_failure_and_timeout_give_nonzero_exit(self) -> None:
        runner = Mock(side_effect=[1, subprocess.TimeoutExpired("ssh-copy-id", 120)])
        self.assertEqual(self.install("--all", "--yes", copy_runner=runner), 1)
        output = self.box.stdout.getvalue()
        self.assertIn("FAIL     a (kód 1)", output)
        self.assertIn("FAIL     b (timeout 120 s)", output)

    def test_unknown_only_alias_aborts_before_any_action(self) -> None:
        runner = Mock(return_value=0)
        self.assertEqual(self.install("--only", "a,typo", "--yes", copy_runner=runner), 2)
        runner.assert_not_called()

    def test_only_or_all_is_required(self) -> None:
        with self.assertRaises(SystemExit):
            with patch("sys.stderr", io.StringIO()):
                self.install("--yes")




class GitHostingAndCautionTests(unittest.TestCase):
    def setUp(self) -> None:
        self._directory = tempfile.TemporaryDirectory()
        self.box = Sandbox(self._directory.name)
        self.box.write("config", "Host gh pve box-audit apklub-prod\n")
        self.fake = FakeSsh({"gh": "user git\nhostname GitHub.com\nport 22\n"})
        self.key = self.box.root / "netkit.pub"
        self.key.write_text(fake_pubkey())

    def tearDown(self) -> None:
        self._directory.cleanup()

    def install(self, *extra: str) -> int:
        env = self.box.env(self.fake, copy_runner=Mock(return_value=0))
        return tool.main(["install-pubkey", "--key", str(self.key), *extra], env)

    def test_export_skips_git_hosting(self) -> None:
        code, document = self.box.export(self.fake)
        self.assertEqual(code, 0)
        self.assertNotIn("gh", profiles(document))
        self.assertIn(("gh", "UNSUPPORTED_OPTION"), skipped(document))
        self.assertIn("git hosting", self.box.stderr.getvalue())

    def test_install_all_skips_git_hosting_and_warns_on_audit_and_prod(self) -> None:
        self.assertEqual(self.install("--all", "--dry-run"), 0)
        output = self.box.stdout.getvalue()
        self.assertNotIn(f"-i {self.key} gh", output)
        self.assertIn(f"-i {self.key} pve", output)
        warning = self.box.stderr.getvalue()
        self.assertIn("box-audit, apklub-prod", warning)
        self.assertIn("/user ssh-keys", warning)

    def test_install_only_does_not_repeat_caution(self) -> None:
        self.assertEqual(self.install("--only", "box-audit", "--dry-run"), 0)
        self.assertNotIn("Pozor:", self.box.stderr.getvalue())

    def test_caution_pattern_matches_whole_words_only(self) -> None:
        for alias in ("proxmox-audit", "apklub-prod", "prod", "web.production"):
            self.assertIsNotNone(tool.CAUTION_ALIAS.search(alias), alias)
        for alias in ("product", "auditorium", "pve"):
            self.assertIsNone(tool.CAUTION_ALIAS.search(alias), alias)


class AliasControlCharactersTest(unittest.TestCase):
    """Revize M2 #5: alias musí projít stejnou kontrolou jako SshProfileImport.validAlias."""

    def test_c1_control_characters_are_rejected_like_the_app(self) -> None:
        self.assertFalse(tool.valid_alias("nas\u0085"))
        self.assertFalse(tool.valid_alias("\u009fnas"))
        self.assertTrue(tool.valid_alias("nas-é"))
        self.assertTrue(tool.valid_alias("nas x"))


if __name__ == "__main__":
    unittest.main()
