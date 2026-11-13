#!/usr/bin/env python3
"""Regression checks for guarded ColorOS boot-hang smali patches."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PATCHER_PATH = ROOT / "switcher/patches/oplus_boot_hangs.py"
SPEC = importlib.util.spec_from_file_location("oplus_boot_hangs", PATCHER_PATH)
PATCHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PATCHER)


class OplusBootHangsTest(unittest.TestCase):
    def run_game_gpu(self, registers):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            smali = root / "smali/com/android/server/app/features/GameGpuControlPanel.smali"
            smali.parent.mkdir(parents=True)
            smali.write_text(
                ".class public Lcom/android/server/app/features/GameGpuControlPanel;\n"
                + PATCHER.GAME_HEADER + "\n"
                + f"    {registers}\n"
                + '    .param p1, "context"\n'
                + "    .prologue\n"
                + "    invoke-static {}, Lcom/oplus/qspm/Manager;->waitForHal()V\n"
                + "    return-void\n"
                + ".end method\n"
            )
            changed = PATCHER.fix_game_gpu(str(root))
            return changed, smali.read_text()

    def test_does_not_assume_locals_count(self):
        for registers in (".locals 2", ".locals 5", ".registers 5", ".registers 9"):
            with self.subTest(registers=registers):
                changed, output = self.run_game_gpu(registers)
                self.assertEqual(changed, "smali")
                self.assertIn(f"    {registers}\n", output)
                self.assertIn(
                    "    # OneOS: no QSPM HAL on this vendor; the wait for it hung android.display.\n"
                    "    return-void\n",
                    output,
                )
                self.assertLess(output.index("return-void"), output.index("waitForHal"))

    def test_keeps_method_annotations_before_the_entry_guard(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            smali = root / "smali/com/android/server/app/features/GameGpuControlPanel.smali"
            smali.parent.mkdir(parents=True)
            smali.write_text(
                PATCHER.GAME_HEADER + "\n"
                "    .locals 2\n"
                "    .annotation runtime Ldalvik/annotation/Example;\n"
                '        value = "kept"\n'
                "    .end annotation\n"
                "    invoke-static {}, Lcom/oplus/qspm/Manager;->waitForHal()V\n"
                "    return-void\n"
                ".end method\n"
            )
            PATCHER.fix_game_gpu(str(root))
            output = smali.read_text()
            self.assertLess(output.index(".end annotation"), output.index("return-void"))
            self.assertLess(output.index("return-void"), output.index("waitForHal"))

    def test_signature_mismatch_fails_closed_with_candidates(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            smali = root / "smali/com/android/server/app/features/GameGpuControlPanel.smali"
            smali.parent.mkdir(parents=True)
            smali.write_text(
                ".method public onBootCompleted(Landroid/content/Context;)V\n"
                "    .locals 2\n"
                "    return-void\n"
                ".end method\n"
            )
            with self.assertRaisesRegex(SystemExit, "onBootCompleted signature mismatch"):
                PATCHER.fix_game_gpu(str(root))

    def test_missing_register_directive_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            smali = root / "smali/com/android/server/app/features/GameGpuControlPanel.smali"
            smali.parent.mkdir(parents=True)
            smali.write_text(
                PATCHER.GAME_HEADER + "\n"
                "    invoke-static {}, Lcom/oplus/qspm/Manager;->waitForHal()V\n"
                ".end method\n"
            )
            with self.assertRaisesRegex(SystemExit, "one .locals or .registers directive"):
                PATCHER.fix_game_gpu(str(root))

    def test_entry_guard_is_idempotent(self):
        _, output = self.run_game_gpu(".locals 2")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            smali = root / "smali/com/android/server/app/features/GameGpuControlPanel.smali"
            smali.parent.mkdir(parents=True)
            smali.write_text(output)
            PATCHER.fix_game_gpu(str(root))
            self.assertEqual(smali.read_text().count("return-void"), 2)
            self.assertEqual(smali.read_text().count("no QSPM HAL"), 1)


if __name__ == "__main__":
    unittest.main()
