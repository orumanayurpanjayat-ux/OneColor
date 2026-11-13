#!/usr/bin/env python3
"""Exercise the fail-closed SurfaceFlinger patch helper from guest_prepare.sh."""
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
GUEST_PREPARE = ROOT / "switcher/assets/guest_prepare.sh"
SOURCE_SHA = "46440a04787d42484b134fd2feed559822daa4a93d9428a3f7078616ab57f93c"
OUTPUT_SHA = "05d5fc761bf3556d97e59b6185686760545ef0b08847633b550ee03475fbfd5a"
PLK_FILE = os.environ.get("ONEOS_PLK110_SURFACEFLINGER")


def patch_function():
    text = GUEST_PREPARE.read_text(encoding="utf-8")
    start = text.index("oneos_patch_binary() {")
    end = text.index("\n}\n", start) + 3
    return text[start:end]


class SurfaceFlingerPatchTest(unittest.TestCase):
    def run_helper(self, source, expected_source_sha, expected_output_sha, offset, patch_bytes):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "surfaceflinger"
            dst = root / "patched"
            src.write_bytes(source)
            busybox_impl = root / "busybox.py"
            busybox_impl.write_text(
                "import hashlib, os, shutil, sys\n"
                "from pathlib import Path\n"
                "name, *args = sys.argv[1:]\n"
                "if name == 'cp': shutil.copyfile(args[0], args[1])\n"
                "elif name == 'rm':\n"
                "    for p in args[1:] if args[0] == '-f' else args:\n"
                "        try: Path(p).unlink()\n"
                "        except FileNotFoundError: pass\n"
                "elif name == 'chmod': os.chmod(args[1], int(args[0], 8))\n"
                "elif name == 'dd':\n"
                "    opts = dict(a.split('=', 1) for a in args if '=' in a)\n"
                "    count = int(opts['count']); payload = sys.stdin.buffer.read(count)[:count]\n"
                "    with open(opts['of'], 'r+b') as f: f.seek(int(opts['seek']) * int(opts['bs'])); f.write(payload)\n"
                "else: raise SystemExit('unsupported fixture command: ' + name)\n",
                encoding="utf-8",
            )
            bb = root / "bb"
            python_exe = sys.executable.replace("\\", "/")
            bb.write_text('#!/bin/sh\nexec "$PYTHON_EXE" "$BB_IMPL" "$@"\n', encoding="utf-8")
            bb.chmod(0o755)
            program = (
                'sha256sum() { "$PYTHON_EXE" -c \'import hashlib,sys; p=sys.argv[1]; print(hashlib.sha256(open(p,"rb").read()).hexdigest(), "  "+p)\' "$1"; }\n'
                + patch_function()
                + '\noneos_patch_binary "$SRC" "$DST" "$SOURCE_SHA" "$OUTPUT_SHA" "$OFFSET" "$PATCH_BYTES"\n'
            )
            result = subprocess.run(
                ["sh", "-ec", program],
                env=dict(
                    os.environ,
                    BB=str(bb),
                    BB_IMPL=str(busybox_impl),
                    PYTHON_EXE=python_exe,
                    SRC=str(src),
                    DST=str(dst),
                    SOURCE_SHA=expected_source_sha,
                    OUTPUT_SHA=expected_output_sha,
                    OFFSET=str(offset),
                    PATCH_BYTES=patch_bytes,
                ),
                capture_output=True,
                text=True,
            )
            output = dst.read_bytes() if dst.exists() else None
            mode = dst.stat().st_mode & 0o777 if dst.exists() else None
            return result, output, mode

    def test_patches_only_after_source_and_output_hashes_match(self):
        source = bytearray(b"PLK fixture: old!")
        offset = source.index(b"old!")
        source_sha = hashlib.sha256(source).hexdigest()
        output = bytearray(source)
        output[offset : offset + 4] = bytes.fromhex("aabbccdd")
        output_sha = hashlib.sha256(output).hexdigest()
        result, patched, mode = self.run_helper(bytes(source), source_sha, output_sha, offset, r"\252\273\314\335")
        self.assertEqual(0, result.returncode, f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}")
        self.assertEqual(bytes(output), patched)
        self.assertEqual(output_sha, hashlib.sha256(patched).hexdigest())
        if os.name != "nt":
            self.assertEqual(0o755, mode)

    def test_refuses_wrong_source_hash_without_creating_output(self):
        result, patched, _ = self.run_helper(b"unmatched image", "0" * 64, "1" * 64, 0, r"\001\002\003\004")
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(patched)

    def test_removes_output_if_the_patch_result_does_not_match(self):
        source = b"unmatched image"
        source_sha = hashlib.sha256(source).hexdigest()
        result, patched, _ = self.run_helper(source, source_sha, "0" * 64, 0, r"\001\002\003\004")
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(patched)

    def test_plk110_hashes_are_pinned_in_the_boot_path(self):
        text = GUEST_PREPARE.read_text(encoding="utf-8")
        self.assertIn(SOURCE_SHA, text)
        self.assertIn(OUTPUT_SHA, text)
        self.assertIn('if [ "$SESSION_SET" = "coloros17-plk110-sultra" ]', text)
        overlays = (ROOT / "scripts/make-overlays.sh").read_text(encoding="utf-8")
        self.assertIn('"e824806ecec5": 0x470888', overlays)

    @unittest.skipUnless(PLK_FILE, "set ONEOS_PLK110_SURFACEFLINGER to test the supplied PLK110 binary")
    def test_supplied_plk110_binary_produces_the_pinned_patch(self):
        source = Path(PLK_FILE).read_bytes()
        self.assertEqual(SOURCE_SHA, hashlib.sha256(source).hexdigest())
        result, patched, _ = self.run_helper(source, SOURCE_SHA, OUTPUT_SHA, 4_655_240, r"\023\000\000\024")
        self.assertEqual(0, result.returncode, f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}")
        self.assertEqual(OUTPUT_SHA, hashlib.sha256(patched).hexdigest())


if __name__ == "__main__":
    unittest.main()
