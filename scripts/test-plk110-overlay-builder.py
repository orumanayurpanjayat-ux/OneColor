#!/usr/bin/env python3
"""Regression checks for the image-bound PLK110 overlay builder."""
from pathlib import Path
import importlib.util
import unittest

ROOT = Path(__file__).resolve().parents[1]
BUILDER = (ROOT / "scripts/build-plk110-overlays.sh").read_text()
WORKFLOW = (ROOT / ".github/workflows/plk110-overlays.yml").read_text()
PATCH_NOTE = (ROOT / "docs/patch-notes/2026-11-13-plk110-coloros-gsi.md").read_text()
PATCH_BUILT_JAR = (ROOT / "scripts/patch-built-jar.sh").read_text()
CRASHRECOVERY_INSPECTOR = (ROOT / "scripts/inspect-plk110-crashrecovery.py").read_text()
INSPECTOR_SPEC = importlib.util.spec_from_file_location(
    "inspect_plk110_crashrecovery", ROOT / "scripts/inspect-plk110-crashrecovery.py"
)
INSPECTOR = importlib.util.module_from_spec(INSPECTOR_SPEC)
INSPECTOR_SPEC.loader.exec_module(INSPECTOR)


class Plk110OverlayBuilderTest(unittest.TestCase):
    def test_builder_is_bound_to_the_supplied_zip_and_system_image(self):
        self.assertIn("VqJvx5SD", BUILDER)
        self.assertIn("e3b19a42f4770e2788903c679e8e2a7116bc28e4b272c7597c5e6c3e2ac4fb73", BUILDER)
        self.assertIn("43593ee631299f710fb091bc7f635627e91bd81fca7a8ab759ee3573cd621314", BUILDER)
        self.assertIn("ZIP_BYTES=3029357770", BUILDER)
        self.assertIn("IMAGE_BYTES=6644899840", BUILDER)

    def test_builder_validates_archive_contents_and_image_before_patching(self):
        self.assertIn('items[0].filename != "system.img"', BUILDER)
        self.assertIn('len(items) != 1', BUILDER)
        self.assertIn('image.seek(1080)', BUILDER)
        self.assertIn('"$DEBUGFS" -R stats "$IMG"', BUILDER)
        self.assertLess(BUILDER.index("IMAGE_SHA"), BUILDER.index('"$ROOT/scripts/make-overlays.sh"'))
        self.assertIn('sh "$ROOT/scripts/make-overlays.sh"', BUILDER)
        self.assertIn("Refusing to reuse an existing output path", BUILDER)

    def test_builder_requires_guest_vold_isolation_outputs(self):
        required = BUILDER.split("for name in ", 1)[1].split("; do", 1)[0]
        self.assertIn("vold_patched", required)
        self.assertIn("service.incremental_patched.so", required)

    def test_unknown_vold_build_reports_full_crashrecovery_hash(self):
        overlay_builder = (ROOT / "scripts/make-overlays.sh").read_text()
        self.assertIn('sha(d) if d else "missing"', overlay_builder)

    def test_pinned_plk_crashrecovery_apex_is_inspected_before_allowlisting(self):
        self.assertIn("inspect-plk110-crashrecovery.py", BUILDER)
        self.assertIn("05d8d921185130d88388b417387bbc0ba74281251b0d5188dae16953b82481d2", CRASHRECOVERY_INSPECTOR)
        self.assertIn("832efa6618bdf7673503706326dd8f4c2d21f8b9bac2c988c410087e14248761", CRASHRECOVERY_INSPECTOR)
        self.assertIn("RescueParty.isDisabled()Z", CRASHRECOVERY_INSPECTOR)
        self.assertIn("RecoverySystem;->rebootPromptAndWipeUserData", CRASHRECOVERY_INSPECTOR)
        self.assertIn("RescueParty;->-$$Nest$smisDisabled()Z", CRASHRECOVERY_INSPECTOR)
        self.assertIn("RescueParty;->executeFactoryReset(", CRASHRECOVERY_INSPECTOR)
        self.assertIn("PLK110 CRASHRECOVERY SAFETY INSPECTION", WORKFLOW)

    def test_rescueparty_method_match_ignores_synthetic_name_containing_signature(self):
        lines = [
            ".method private static lambda$isDisabled()Z",
            "    return-const/4 v0, 0x0",
            ".end method",
            ".method private static isDisabled()Z",
            "    return-const/4 v0, 0x1",
            ".end method",
        ]
        method = INSPECTOR.method_block(lines, "isDisabled()Z", "RescueParty.isDisabled()Z")
        self.assertEqual(method[0], ".method private static isDisabled()Z")

    def test_inspector_reports_guard_callers_and_factory_reset_scheduler(self):
        sample = [
            ".method private static trigger()V",
            "    invoke-static {}, Lcom/android/server/RescueParty;->isDisabled()Z",
            "    new-instance v0, Lcom/android/server/RescueParty$1;",
            "    invoke-direct {v0}, Lcom/android/server/RescueParty$1;-><init>()V",
            ".end method",
            ".method public run()V",
            "    invoke-static {}, Landroid/os/RecoverySystem;->rebootPromptAndWipeUserData()V",
            ".end method",
        ]
        blocks = list(INSPECTOR.method_blocks(sample))
        self.assertEqual(len(blocks), 2)
        self.assertIn("RescueParty;->isDisabled()Z", "\n".join(blocks[0]))
        self.assertIn("RescueParty$1;-><init>", "\n".join(blocks[0]))

    def test_crashrecovery_inspector_covers_the_synthetic_guard_bridge(self):
        sample = [
            ".method private static isDisabled()Z",
            '    const-string v0, "persist.sys.disable_rescue"',
            "    return v0",
            ".end method",
            ".method static bridge synthetic -$$Nest$smisDisabled()Z",
            "    invoke-static {}, Lcom/android/server/RescueParty;->isDisabled()Z",
            ".end method",
            ".method public static onHealthCheckFailed()V",
            "    invoke-static {}, Lcom/android/server/RescueParty;->-$$Nest$smisDisabled()Z",
            "    invoke-static {}, Lcom/android/server/RescueParty;->executeFactoryReset()V",
            ".end method",
        ]
        refs = [
            block for block in INSPECTOR.method_blocks(sample)
            if any(ref in "\n".join(block) for ref in INSPECTOR.RELEVANT_RESCUE_REFS)
        ]
        self.assertEqual(2, len(refs))
        guarded_entry = next(block for block in refs if "RescueParty;->executeFactoryReset(" in "\n".join(block))
        self.assertIn("RescueParty;->-$$Nest$smisDisabled()Z", "\n".join(guarded_entry))

    def test_patch_built_jar_runs_pinned_apktool_jar_through_java(self):
        self.assertIn('run_apktool() { java -jar "$APKTOOL" "$@"; }', PATCH_BUILT_JAR)
        self.assertIn("java\\ -jar\\ *)", PATCH_BUILT_JAR)
        self.assertIn("${APKTOOL#java -jar }", PATCH_BUILT_JAR)
        self.assertIn("run_apktool d -q -f -r", PATCH_BUILT_JAR)
        self.assertIn("run_apktool b -q", PATCH_BUILT_JAR)
        self.assertNotIn("$APKTOOL d -q", PATCH_BUILT_JAR)
        self.assertNotIn("$APKTOOL b -q", PATCH_BUILT_JAR)

    def test_apktool_is_pinned_and_the_action_does_not_upload_the_source_image(self):
        self.assertIn("apktool_3.0.3.jar", WORKFLOW)
        self.assertIn("dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423", WORKFLOW)
        self.assertIn("actions/upload-artifact@v7", WORKFLOW)
        self.assertIn("overlays/**", WORKFLOW)
        self.assertNotIn("system.img", WORKFLOW.split("actions/upload-artifact@v7", 1)[1])

    def test_builder_failure_context_is_published_even_when_patch_build_fails(self):
        self.assertIn("Publish PLK110 builder diagnostics", WORKFLOW)
        self.assertIn("if: always()", WORKFLOW)
        self.assertIn("tail -n 160", WORKFLOW)
        self.assertIn("df -h", WORKFLOW)

    def test_documentation_marks_the_x9_payload_reuse_as_the_previous_state(self):
        self.assertIn("were built from the Find X9 Ultra IN image", PATCH_NOTE)


if __name__ == "__main__":
    unittest.main()
