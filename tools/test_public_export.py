"""Regression tests for export boundaries and the redacted privacy gate."""
from __future__ import annotations

from contextlib import redirect_stderr
import hashlib
import io
from pathlib import Path
import tempfile
import unittest

import public_export as export


class ExportTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix='netkit-export-test-')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / 'source'
        (self.root / 'tools').mkdir(parents=True)
        (self.root / 'README.md').write_text('Example project\n')
        self.manifest = self.root / 'tools/public_export.manifest'
        self.manifest.write_text('exclude\tprace/\nexclude\tprompty/\nfile\tREADME.md\tREADME.md\n')
        self.dest = self.base / 'export'

    def test_current_worktree_and_no_unlisted_files(self) -> None:
        (self.root / '.env').write_text('SYNTHETIC_FIXTURE=value')
        (self.root / 'README.md').write_text('changed working tree\n')
        export.prepare(self.root, self.dest)
        self.assertEqual((self.dest / 'README.md').read_text(), 'changed working tree\n')
        self.assertEqual(list(self.dest.iterdir()), [self.dest / 'README.md'])

    def test_inside_repo_parent_and_nonempty_refused(self) -> None:
        for target in [self.root, self.root / 'child', self.base]:
            with self.assertRaises(ValueError):
                export.prepare(self.root, target)
        self.dest.mkdir()
        (self.dest / 'sentinel').write_text('keep')
        with self.assertRaises(ValueError):
            export.prepare(self.root, self.dest)
        self.assertEqual((self.dest / 'sentinel').read_text(), 'keep')

    def test_symlink_target_and_source_refused(self) -> None:
        self.dest.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(ValueError):
            export.prepare(self.root, self.dest)
        (self.root / 'alias').symlink_to(self.root / 'README.md')
        self.manifest.write_text('exclude\tprace/\nexclude\tprompty/\nfile\talias\tREADME.md\n')
        with self.assertRaises(ValueError):
            export.prepare(self.root, self.base / 'other')

    def test_manifest_forbidden_paths_and_duplicate_destinations(self) -> None:
        for path in ['../escape', '/tmp/absolute', '.env', 'nested/key.jks', 'prace/report.md',
                     'artifacts/image.png', '.git/config', 'local.properties']:
            with self.subTest(path=path), self.assertRaises(ValueError):
                export.safe_relative(path)
        self.manifest.write_text(self.manifest.read_text() + 'file\tREADME.md\tREADME.md\n')
        with self.assertRaises(ValueError):
            export.prepare(self.root, self.dest)
        self.assertFalse(self.dest.exists())

    def test_grep_gate_accepts_reviewed_fixture_rejects_changed_context(self) -> None:
        export.prepare(self.root, self.dest)
        (self.dest / 'tools').mkdir()
        baseline = self.dest / 'tools/public_export.reviewed.tsv'
        readme = self.dest / 'README.md'
        readme.write_text('Test address 192.168.50.1\n')
        digest = hashlib.sha256(readme.read_bytes()).hexdigest()
        baseline.write_text(f'README.md\t{digest}\tsynthetic\n')
        export.scan(self.dest)
        readme.write_text('Customer address 192.168.50.1\n')
        errors = io.StringIO()
        with redirect_stderr(errors), self.assertRaises(ValueError):
            export.scan(self.dest)
        self.assertIn('README.md:1:', errors.getvalue())
        self.assertNotIn('192.168.50.1', errors.getvalue())
        self.assertNotIn('Customer', errors.getvalue())

    def test_new_file_without_regex_match_is_rejected(self) -> None:
        export.prepare(self.root, self.dest)
        (self.dest / 'tools').mkdir()
        readme = self.dest / 'README.md'
        digest = hashlib.sha256(readme.read_bytes()).hexdigest()
        (self.dest / 'tools/public_export.reviewed.tsv').write_text(f'README.md\t{digest}\tdocumentation\n')
        (self.dest / 'new.txt').write_text('new data')
        with redirect_stderr(io.StringIO()), self.assertRaises(ValueError):
            export.scan(self.dest)

    def test_unexpected_binary_is_refused(self) -> None:
        export.prepare(self.root, self.dest)
        (self.dest / 'unknown.bin').write_bytes(b'\xff\x00')
        with self.assertRaises(ValueError):
            export.findings(self.dest)


if __name__ == '__main__':
    unittest.main()
