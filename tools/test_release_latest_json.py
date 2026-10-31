"""Offline tests for tools/release_latest_json.py (python3 -I -m unittest discover -s tools -p test_release_latest_json.py)."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import release_latest_json as rl  # noqa: E402

SHA = "0123456789abcdef" * 4
CERT = "fedcba9876543210" * 4
BASE = "https://www.firma.cz/netkit"
SOURCE = "https://github.com/Losminek/netkit"


def sample(**overrides: object) -> dict[str, object]:
    latest = rl.build("1.0.0", SHA, CERT, BASE, SOURCE, "2026-11-20", "První veřejné vydání.", "")
    latest.update(overrides)
    return latest


class BuildTests(unittest.TestCase):
    def test_schema_and_urls(self) -> None:
        latest = sample()
        self.assertEqual(set(latest), rl.KEYS)
        self.assertEqual(latest["versionCode"], 10000)
        self.assertEqual(latest["pageUrl"], BASE)  # no trailing slash: the web uses trailingSlash 'never'
        self.assertEqual(latest["apkUrl"], f"{SOURCE}/releases/download/v1.0.0/netkit-1.0.0.apk")
        self.assertEqual(latest["notes"], {"cs": "První veřejné vydání.", "en": "První veřejné vydání."})

    def test_version_scheme_matches_gradle(self) -> None:
        self.assertEqual(rl.version_code("0.7.0"), 700)
        self.assertEqual(rl.version_code("1.2.3"), 10203)
        for bad in ("1.0", "1.0.0-dev", "1.100.0", "v1.0.0", "01.0.0"):
            with self.assertRaises(rl.ReleaseError):
                rl.version_code(bad)

    def test_placeholder_and_malformed_web_base_refused(self) -> None:
        for bad in ("https://www.example.invalid/netkit", "https://www.daniel.example/netkit",
                    "https://example.com/netkit", "http://www.firma.cz/netkit", "https://www.firma.cz/netkit/",
                    "https://localhost/netkit", "https://user@www.firma.cz/netkit"):
            with self.assertRaises(rl.ReleaseError, msg=bad):
                rl.web_base(bad)

    def test_source_must_be_github_repo(self) -> None:
        for bad in ("https://gitlab.com/a/b", "https://github.com/a", "https://github.com/a/b/"):
            with self.assertRaises(rl.ReleaseError, msg=bad):
                rl.source_repo(bad)

    def test_fingerprint_forms(self) -> None:
        colon = ":".join(CERT[i:i + 2] for i in range(0, 64, 2)).upper()
        self.assertEqual(rl.sha256_hex(colon), CERT)
        with self.assertRaises(rl.ReleaseError):
            rl.sha256_hex("NEVYPLNĚNO")

    def test_note_must_be_single_short_line(self) -> None:
        with self.assertRaises(rl.ReleaseError):
            rl.build("1.0.0", SHA, CERT, BASE, SOURCE, "2026-11-20", "a\nb", "")
        with self.assertRaises(rl.ReleaseError):
            rl.build("1.0.0", SHA, CERT, BASE, SOURCE, "2026-11-20", "x" * 281, "")

    def test_changelog_note(self) -> None:
        text = "# Changelog\n\n## 1.0.0 — vydání (2026-11-20)\n\nÚvod.\n\n- První odrážka.\n- Druhá.\n\n## 0.7.0\n\n- Stará.\n"
        self.assertEqual(rl.changelog_note(text, "1.0.0"), "První odrážka.")
        self.assertEqual(rl.changelog_note(text, "0.7.0"), "Stará.")
        self.assertEqual(rl.changelog_note(text, "2.0.0"), "")


class CheckTests(unittest.TestCase):
    def test_accepts_matching_release(self) -> None:
        rl.check(sample(), "1.0.0", SHA, CERT, BASE, SOURCE)

    def test_rejects_mismatch(self) -> None:
        cases = {
            "versionName": "1.0.1", "versionCode": 10001, "sha256": "aa" * 32, "certSha256": "bb" * 32,
            "pageUrl": "https://www.jiny.cz/netkit", "apkUrl": "https://github.com/evil/netkit/x.apk",
            "minSdk": 30, "notes": {"cs": "x"},
        }
        for key, value in cases.items():
            with self.assertRaises(rl.ReleaseError, msg=key):
                rl.check(sample(**{key: value}), "1.0.0", SHA, CERT, BASE, SOURCE)
        rl.check(sample(minSdk=31), "1.0.0", SHA, CERT, BASE, SOURCE, min_sdk=31)
        with self.assertRaises(rl.ReleaseError):
            rl.check(sample(minSdk=29.0), "1.0.0", SHA, CERT, BASE, SOURCE)
        extra = sample()
        extra["sample"] = True
        with self.assertRaises(rl.ReleaseError):
            rl.check(extra, "1.0.0", SHA, CERT, BASE, SOURCE)


class CliTests(unittest.TestCase):
    def test_build_then_check_round_trip(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            props = root / "gradle.properties"
            props.write_text(f"netkitWebBase={BASE}\nnetkitSourceUrl={SOURCE}\n", encoding="utf-8")
            changelog = root / "CHANGELOG.md"
            changelog.write_text("## 1.0.0\n\n- Poznámka.\n", encoding="utf-8")
            apk = root / "netkit-1.0.0.apk"
            apk.write_bytes(b"not really an apk")
            out = root / "latest.json"
            common = ["--gradle-properties", str(props)]
            self.assertEqual(rl.main([*common, "build", "--version", "1.0.0", "--apk", str(apk), "--cert", CERT,
                                      "--out", str(out), "--changelog", str(changelog)]), 0)
            latest = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(latest["notes"]["cs"], "Poznámka.")
            self.assertEqual(rl.main([*common, "check", str(out), "--version", "1.0.0", "--apk", str(apk),
                                      "--cert", CERT]), 0)
            apk.write_bytes(b"tampered")
            self.assertEqual(rl.main([*common, "check", str(out), "--version", "1.0.0", "--apk", str(apk),
                                      "--cert", CERT]), 1)

    def test_placeholder_properties_fail_build(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            props = root / "gradle.properties"
            props.write_text(f"netkitWebBase=https://www.example.invalid/netkit\nnetkitSourceUrl={SOURCE}\n",
                             encoding="utf-8")
            apk = root / "a.apk"
            apk.write_bytes(b"x")
            self.assertEqual(rl.main(["--gradle-properties", str(props), "build", "--version", "1.0.0", "--apk",
                                      str(apk), "--cert", CERT, "--out", str(root / "l.json"), "--notes-cs", "x"]), 1)


if __name__ == "__main__":
    unittest.main()
