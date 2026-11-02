"""Offline tests for tools/update_oui.py (python3 -I -m unittest discover -s tools -p test_update_oui.py).

Only small synthetic CSV files; nothing is downloaded. Names and prefixes are invented.
"""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import update_oui as uo  # noqa: E402

HEADER = "Registry,Assignment,Organization Name,Organization Address\n"


def csv_text(registry: str, *rows: tuple[str, str]) -> str:
    body = "".join(f'{registry},{prefix},"{name}","1 Example Street Exampletown XX 00000 "\n' for prefix, name in rows)
    return HEADER + body


def sources(**overrides: str) -> dict[str, str]:
    base = {
        "MA-L": csv_text("MA-L", ("F40000", "Example Vendor A"), ("F40001", "Example Vendor B"),
                         ("F4AAAA", "IEEE Registration Authority")),
        "MA-M": csv_text("MA-M", ("F40001A", "Example Vendor M")),
        "MA-S": csv_text("MA-S", ("F40001ABC", "Example Vendor S"), ("F40001ABD", "Private")),
    }
    base.update(overrides)
    return base


class BuildTests(unittest.TestCase):
    def test_all_three_registries_sorted_with_header(self) -> None:
        text, counts = uo.build(sources(), "2026-11-02", check_minimum=False)
        lines = text.splitlines()
        self.assertTrue(lines[0].startswith("# IEEE OUI Public Listing"))
        self.assertIn("downloaded 2026-11-02", lines[0])
        self.assertIn("MA-L=2, MA-M=1, MA-S=1, total=4", lines[0])
        self.assertEqual(lines[1:], [
            "F40000\t24\tExample Vendor A",
            "F40001\t24\tExample Vendor B",
            "F40001A\t28\tExample Vendor M",
            "F40001ABC\t36\tExample Vendor S",
        ])
        self.assertEqual(counts, {"MA-L": 2, "MA-M": 1, "MA-S": 1})
        self.assertTrue(text.endswith("\n"))

    def test_addresses_are_not_copied(self) -> None:
        text, _ = uo.build(sources(), "2026-11-02", check_minimum=False)
        self.assertNotIn("Example Street", text)
        self.assertNotIn("Exampletown", text)

    def test_private_and_registration_authority_are_left_out(self) -> None:
        text, _ = uo.build(sources(), "2026-11-02", check_minimum=False)
        self.assertNotIn("Private", text)
        self.assertNotIn("Registration Authority", text)
        self.assertNotIn("F4AAAA", text)

    def test_output_is_deterministic_regardless_of_input_order(self) -> None:
        shuffled = sources(**{"MA-L": csv_text("MA-L", ("F40001", "Example Vendor B"), ("F40000", "Example Vendor A"))})
        first, _ = uo.build(shuffled, "2026-11-02", check_minimum=False)
        second, _ = uo.build(sources(), "2026-11-02", check_minimum=False)
        self.assertEqual(first.splitlines()[1:], second.splitlines()[1:])
        self.assertEqual(first, uo.build(shuffled, "2026-11-02", check_minimum=False)[0])

    def test_duplicate_prefix_keeps_distinct_names_sorted(self) -> None:
        dup = csv_text("MA-L", ("F40000", "Zeta Example"), ("F40000", "Alpha Example"), ("F40000", "Zeta Example"))
        text, counts = uo.build(sources(**{"MA-L": dup}), "2026-11-02", check_minimum=False)
        self.assertIn("F40000\t24\tAlpha Example / Zeta Example\n", text)
        self.assertEqual(counts["MA-L"], 1)

    def test_lowercase_prefix_is_normalized(self) -> None:
        text, _ = uo.build(sources(**{"MA-L": csv_text("MA-L", ("f4000a", "Example Lower"))}), "2026-11-02", check_minimum=False)
        self.assertIn("F4000A\t24\tExample Lower\n", text)

    def test_byte_order_mark_is_accepted(self) -> None:
        text, _ = uo.build(sources(**{"MA-M": "\ufeff" + csv_text("MA-M", ("F40001A", "Example Vendor M"))}),
                           "2026-11-02", check_minimum=False)
        self.assertIn("F40001A\t28\tExample Vendor M\n", text)

    def test_minimum_floor_rejects_a_truncated_download(self) -> None:
        with self.assertRaisesRegex(uo.OuiError, "only 2 entries"):
            uo.build(sources(), "2026-11-02", check_minimum=True)


class InvalidInputTests(unittest.TestCase):
    def assert_invalid(self, pattern: str, **override: str) -> None:
        with self.assertRaisesRegex(uo.OuiError, pattern):
            uo.build(sources(**override), "2026-11-02", check_minimum=False)

    def test_bad_header(self) -> None:
        self.assert_invalid("unexpected header", **{"MA-L": "Registry,Assignment,Name\nMA-L,F40000,X\n"})
        self.assert_invalid("unexpected header", **{"MA-S": ""})

    def test_wrong_registry_in_file(self) -> None:
        self.assert_invalid("expected MA-M", **{"MA-M": csv_text("MA-L", ("F40001A", "Example"))})

    def test_prefix_length_and_alphabet(self) -> None:
        self.assert_invalid("invalid MA-L prefix", **{"MA-L": csv_text("MA-L", ("F4000", "Example"))})
        self.assert_invalid("invalid MA-M prefix", **{"MA-M": csv_text("MA-M", ("F40001", "Example"))})
        self.assert_invalid("invalid MA-S prefix", **{"MA-S": csv_text("MA-S", ("F40001ABG", "Example"))})
        self.assert_invalid("invalid MA-L prefix", **{"MA-L": csv_text("MA-L", ("F4:00:00", "Example"))})

    def test_column_count(self) -> None:
        self.assert_invalid("expected 4 columns", **{"MA-L": HEADER + "MA-L,F40000,Example\n"})

    def test_empty_name(self) -> None:
        self.assert_invalid("empty organization name", **{"MA-L": csv_text("MA-L", ("F40000", " \u200b "))})

    def test_malformed_quoting(self) -> None:
        self.assert_invalid("malformed CSV", **{"MA-L": HEADER + 'MA-L,F40000,"Exam"ple",addr\n'})

    def test_missing_registry_and_bad_date(self) -> None:
        partial = sources()
        del partial["MA-S"]
        with self.assertRaisesRegex(uo.OuiError, "missing oui36.csv"):
            uo.build(partial, "2026-11-02", check_minimum=False)
        with self.assertRaisesRegex(uo.OuiError, "invalid date"):
            uo.build(sources(), "02.11.2026", check_minimum=False)


class CleanNameTests(unittest.TestCase):
    def test_control_format_and_wide_characters(self) -> None:
        self.assertEqual(uo.clean_name("\t Example Vendor Co.\t"), "Example Vendor Co.")
        self.assertEqual(uo.clean_name("Example\u200bVendor"), "ExampleVendor")
        self.assertEqual(uo.clean_name("Example\u202eVendor"), "ExampleVendor")  # bidi override removed
        self.assertEqual(uo.clean_name("Example\uff08Beijing\uff09Co.\uff0cLtd"), "Example(Beijing)Co.,Ltd")
        self.assertEqual(uo.clean_name("Bühler Example AG"), "Bühler Example AG")
        self.assertEqual(uo.clean_name("Line\nbreak\r\nname"), "Line break name")

    def test_long_name_is_capped(self) -> None:
        name = uo.clean_name("X" * 500)
        self.assertEqual(len(name), uo.MAX_NAME_CHARS)
        self.assertTrue(name.endswith("…"))


class CommandLineTests(unittest.TestCase):
    def test_from_dir_writes_the_asset(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            for registry in uo.REGISTRIES:
                (directory / registry.file_name).write_text(sources()[registry.key], encoding="utf-8")
            output = directory / "out/ieee-oui.tsv"
            self.assertEqual(uo.main(["--from-dir", str(directory), "--output", str(output), "--date", "2026-11-02",
                                      "--no-minimum"]), 0)
            self.assertEqual(output.read_text(encoding="utf-8"), uo.build(sources(), "2026-11-02", False)[0])

    def test_invalid_input_writes_nothing(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            for registry in uo.REGISTRIES:
                (directory / registry.file_name).write_text("bad\n", encoding="utf-8")
            output = directory / "ieee-oui.tsv"
            self.assertEqual(uo.main(["--from-dir", str(directory), "--output", str(output), "--no-minimum"]), 1)
            self.assertFalse(output.exists())
            self.assertEqual(sorted(p.name for p in directory.iterdir()), ["mam.csv", "oui.csv", "oui36.csv"])

    def test_only_ieee_https_urls_are_allowed(self) -> None:
        self.assertTrue(uo.is_ieee_url("https://standards-oui.ieee.org/oui/oui.csv"))
        for url in ("http://standards-oui.ieee.org/oui/oui.csv", "https://standards-oui.ieee.org.example.invalid/x",
                    "https://example.invalid/oui.csv", "https://user@standards-oui.ieee.org/x",
                    "https://standards-oui.ieee.org:8443/x", "ftp://standards-oui.ieee.org/x"):
            self.assertFalse(uo.is_ieee_url(url), url)

    def test_redirect_to_foreign_host_is_refused(self) -> None:
        import http.client
        import io
        import urllib.request
        handler = uo.IeeeOnlyRedirects()
        headers = http.client.HTTPMessage()
        request = urllib.request.Request("https://standards-oui.ieee.org/oui/oui.csv")
        with self.assertRaisesRegex(uo.OuiError, "refusing redirect"):
            handler.redirect_request(request, io.BytesIO(), 302, "Found", headers, "https://example.invalid/oui.csv")
        same = handler.redirect_request(request, io.BytesIO(), 302, "Found", headers,
                                       "https://standards-oui.ieee.org/oui/oui2.csv")
        self.assertIsNotNone(same)
        self.assertEqual(same.full_url if same else None, "https://standards-oui.ieee.org/oui/oui2.csv")

    def test_download_refuses_foreign_url(self) -> None:
        foreign = uo.Registry("MA-L", "oui.csv", "https://example.invalid/oui.csv", 24, 0)
        with tempfile.TemporaryDirectory() as raw, self.assertRaisesRegex(uo.OuiError, "refusing URL"):
            uo.download(foreign, Path(raw))


if __name__ == "__main__":
    unittest.main()
