#!/usr/bin/env python3
"""Regression checks for the image-bound PLK110 overlay builder."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BUILDER = (ROOT / "scripts/build-plk110-overlays.sh").read_text()
WORKFLOW = (ROOT / ".github/workflows/plk110-overlays.yml").read_text()
PATCH_NOTE = (ROOT / "docs/patch-notes/2026-11-13-plk110-coloros-gsi.md").read_text()


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
