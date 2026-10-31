import io
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

import check_contrast as cc

SAMPLE = """
val DarkNetKitColors = NetKitColors(
    background = Color(0xFF000000), surface = Color(0xFF000000), surface2 = Color(0xFF000000),
    text = Color(0xFFFFFFFF), muted = Color(0xFFFFFFFF), dim = Color(0xFFFFFFFF),
    accent = Color(0xFFFFFFFF), accentInk = Color(0xFF000000), accentSoft = Color(0x1AFFFFFF),
    info = Color(0xFFFFFFFF), infoSoft = Color(0x1AFFFFFF),
    violet = Color(0xFFFFFFFF), violetSoft = Color(0x1AFFFFFF),
    ok = Color(0xFFFFFFFF), okSoft = Color(0x1AFFFFFF),
    warn = Color(0xFFFFFFFF), warnSoft = Color(0x1AFFFFFF),
    err = Color(0xFFFFFFFF), errSoft = Color(0x1AFFFFFF),
    terminalBackground = Color(0xFF000000), terminalText = Color(0xFFFFFFFF),
    isDark = true,
)

val LightNetKitColors = NetKitColors(
    background = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFFFFFFF),
    text = Color(0xFF000000), muted = Color(0xFF000000), dim = Color(0xFF000000),
    accent = Color(0xFF000000), accentInk = Color(0xFFFFFFFF), accentSoft = Color(0x1A000000),
    info = Color(0xFF000000), infoSoft = Color(0x1A000000),
    violet = Color(0xFF000000), violetSoft = Color(0x1A000000),
    ok = Color(0xFF000000), okSoft = Color(0x1A000000),
    warn = Color(0xFF000000), warnSoft = Color(0x1A000000),
    err = Color(0xFF000000), errSoft = Color(0x1A000000),
    terminalBackground = Color(0xFF000000), terminalText = Color(0xFFFFFFFF),
    isDark = false,
)
"""


class ContrastMathTest(unittest.TestCase):
    def test_black_on_white_is_21(self):
        self.assertAlmostEqual(cc.contrast((0, 0, 0, 1), (1, 1, 1, 1)), 21.0, places=6)

    def test_ratio_is_symmetric(self):
        a, b = (0.2, 0.4, 0.6, 1.0), (0.9, 0.9, 0.8, 1.0)
        self.assertAlmostEqual(cc.contrast(a, b), cc.contrast(b, a))

    def test_known_wcag_value(self):
        # #777777 on white is the classic 4.48:1 that just fails AA.
        g = 0x77 / 255
        grey = (g, g, g, 1.0)
        self.assertAlmostEqual(cc.contrast(grey, (1, 1, 1, 1)), 4.48, places=2)

    def test_translucent_foreground_is_composited(self):
        half = (0, 0, 0, 0.5)
        mixed = cc.composite(half, (1, 1, 1, 1))
        self.assertAlmostEqual(mixed[0], 0.5)
        self.assertAlmostEqual(cc.contrast(half, (1, 1, 1, 1)), cc.contrast(mixed, (1, 1, 1, 1)))

    def test_translucent_background_is_rejected(self):
        with self.assertRaises(ValueError):
            cc.contrast((0, 0, 0, 1), (1, 1, 1, 0.5))

    def test_ratio_text_truncates_instead_of_rounding(self):
        check = cc.Check("light", "text", "a", "b", 4.4999, 4.5)
        self.assertFalse(check.passed)
        self.assertEqual(check.ratio_text, "4.49")


class ParseTest(unittest.TestCase):
    def test_parses_both_palettes_with_alpha(self):
        palettes = cc.parse_palettes(SAMPLE)
        self.assertEqual(set(palettes), {"dark", "light"})
        self.assertEqual(palettes["dark"]["text"], (1.0, 1.0, 1.0, 1.0))
        self.assertAlmostEqual(palettes["light"]["okSoft"][3], 0x1A / 255)

    def test_missing_palette_is_an_error(self):
        with self.assertRaises(ValueError):
            cc.parse_palettes(SAMPLE.split("val LightNetKitColors")[0])

    def test_extreme_palette_passes_everything(self):
        checks = cc.run_checks(SAMPLE)
        self.assertTrue(checks)
        self.assertTrue(all(c.passed for c in checks), [c for c in checks if not c.passed])

    def test_low_contrast_token_fails_and_main_exits_non_zero(self):
        bad = SAMPLE.replace("muted = Color(0xFF000000)", "muted = Color(0xFFBBBBBB)")
        failed = [c for c in cc.run_checks(bad) if not c.passed]
        self.assertTrue(failed)
        self.assertTrue(all(c.theme == "light" and c.foreground == "muted" for c in failed))
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "Theme.kt"
            path.write_text(bad, encoding="utf-8")
            with redirect_stdout(io.StringIO()) as out:
                self.assertEqual(cc.main([str(path)]), 1)
        self.assertIn("FAIL", out.getvalue())


class RepositoryThemeTest(unittest.TestCase):
    def test_repository_tokens_pass(self):
        with redirect_stdout(io.StringIO()):
            self.assertEqual(cc.main([]), 0)


if __name__ == "__main__":
    unittest.main()
