#!/usr/bin/env python3
"""Regression checks for secondary failures found in the PLK110 session logs."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PREPARE = (ROOT / "switcher/assets/guest_prepare.sh").read_text()
START = (ROOT / "switcher/assets/guest_start.sh").read_text()
ROOT_SCRIPT = (ROOT / "app-android/app/src/full/assets/root/oneos_root.sh").read_text()
PACKAGE_REMOTE = (ROOT / "app-android/packages/remote.txt").read_text()
PACKAGE_SIZES = (ROOT / "app-android/packages/sizes.txt").read_text()


class HostAppSplitTest(unittest.TestCase):
    def test_density_split_does_not_hide_a_later_base_apk(self):
        body = PREPARE.split("\nbind_host_app() {", 1)[1].split("\n}\n", 1)[0]
        function = "bind_host_app() {" + body + "\n}\n"
        busy = r"""#!/usr/bin/env python3
import os,sys,zipfile
cmd=sys.argv[1:]
if cmd[0]=='head':
    with open(cmd[-1],'rb') as f: sys.stdout.buffer.write(f.read(int(cmd[2])))
elif cmd[0]=='stat': print(os.path.getsize(cmd[-1]))
elif cmd[0]=='unzip':
    try:
        with zipfile.ZipFile(cmd[2]) as z: sys.exit(0 if cmd[3] in z.namelist() else 1)
    except Exception: sys.exit(1)
elif cmd[0]=='mount':
    with open(os.environ['CALLS'],'a') as f: f.write(' '.join(cmd[1:])+'\n')
else: sys.exit(2)
"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "CaptivePortalLoginGoogle"
            dst = root / "guest/CaptivePortalLogin"
            src.mkdir()
            dst.mkdir(parents=True)
            # This density split was first alphabetically and rejected as a whole APK.
            (src / "CaptivePortalLoginGoogle-hdpi.apk").write_bytes(b"PK" + b"0" * 49621)
            with zipfile.ZipFile(src / "CaptivePortalLoginGoogle.apk", "w", zipfile.ZIP_STORED) as archive:
                archive.writestr("AndroidManifest.xml", b"manifest")
                archive.writestr("base-padding.bin", b"0" * 70000)
            bb = root / "busybox"
            bb.write_text(busy)
            bb.chmod(0o755)
            env = dict(os.environ, BB=str(bb), CALLS=str(root / "mounts"))
            result = subprocess.run(
                ["/bin/sh", "-ec", function + 'bind_host_app "$1" "$2" com.google.android.captiveportallogin',
                 "sh", str(src), str(dst)],
                env=env, capture_output=True, text=True,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("Host CaptivePortalLoginGoogle ->", result.stdout)
            self.assertNotIn("not a readable base APK", result.stdout)
            self.assertEqual("--bind " + str(src) + " " + str(dst), (root / "mounts").read_text().strip())


class ComposerDiagnosticTest(unittest.TestCase):
    def test_aidl_composer_never_goes_to_hidl_lshal_debug(self):
        body = START.split("    echo \"== the composer's own dump\"", 1)[1].split('\n} > "$CD"', 1)[0]
        self.assertIn("service check android.hardware.graphics.composer3.IComposer/default", body)
        self.assertIn("skipped (AIDL composer; lshal only debugs HIDL services)", body)
        self.assertIn("lshal list -i", body)
        self.assertNotIn("lshal debug android.hardware.graphics.composer3.IComposer/default", body)


class PermissionPatchPackagingTest(unittest.TestCase):
    def test_permission_patch_is_in_both_coloros_staging_sets(self):
        for name in ("SET_COMMON", "SET_COMMON_PLK"):
            value = next(line for line in ROOT_SCRIPT.splitlines() if line.startswith(name + "="))
            self.assertIn("PermissionController_patched.apk", value)

    def test_permission_patch_download_is_hash_and_size_pinned(self):
        digest = "4c3978e8d72c82013726d8e1ea4914c04ebef4fa099339dafd9b53543f402df3"
        source = (
            "url:https://raw.githubusercontent.com/WeWaffles/OneLineage/"
            "10cd368dc4c67f3ec7e9c885e0474233cde8edcd/assets/PermissionController_patched.apk"
        )
        self.assertIn(f"{digest} {source}", PACKAGE_REMOTE)
        self.assertIn(f"{digest} 26008661", PACKAGE_SIZES)
        self.assertIn(digest, (ROOT / "app-android/app/src/full/kotlin/com/fahadali/oneos/session/SetDownloader.kt").read_text())


if __name__ == "__main__":
    unittest.main()
