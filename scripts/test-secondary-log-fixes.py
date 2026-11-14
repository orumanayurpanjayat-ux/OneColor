#!/usr/bin/env python3
"""Regression checks for secondary failures found in the PLK110 session logs."""
from pathlib import Path
import os
import shutil
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
APP_BUILD = (ROOT / "app-android/app/build.gradle.kts").read_text()
SWITCH = (ROOT / "switcher/assets/oneos_switch.sh").read_text()
ZYGOTE_LAUNCHER = (ROOT / "switcher/native/zygote_launcher.c").read_text()
GUEST_ROOT_SOURCE = (ROOT / "switcher/native/guest_root.c").read_text()
BUILD_WORKFLOW = (ROOT / ".github/workflows/build.yml").read_text()


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
        self.assertIn(f'permissionPatchSource = "{source}"', APP_BUILD)
        self.assertIn("Regex.escape(permissionPatchSource)", APP_BUILD)


class ZygoteFailureCaptureTest(unittest.TestCase):
    def _startup_decision(self):
        body = SWITCH.split("            startup_stuck=0", 1)[1].split(
            "            if [ \\$startup_stuck -eq 1 ] || [ \\$dead -ge 30 ]; then", 1
        )[0]
        return ("startup_stuck=0" + body).replace(r'\"', '"').replace(r"\$", "$")

    def test_live_app_process_gets_bounded_startup_grace(self):
        body = SWITCH.split("            startup_stuck=0", 1)[1].split("            # Nothing restarts", 1)[0]
        self.assertIn("app_process_wait=0", SWITCH)
        self.assertIn('case \\"\\$zygote_exe\\" in', body)
        self.assertIn('*/app_process64|*/app_process32)', body)
        self.assertIn(r"app_process_wait=\$((app_process_wait + 10))", body)
        self.assertIn(r"[ \$app_process_wait -ge \$startup_limit ] && startup_stuck=1", body)
        self.assertIn("startup_limit=60", body)
        self.assertIn("jit_abort_retry=1", body)
        self.assertIn("dead=0; app_process_wait=0", body)
        self.assertIn("app_process64 pid \\$zygote_pid stayed alive without zygote registration", body)
        self.assertIn("live app_process startup state", body)
        self.assertIn("/proc/\\$zygote_pid/wchan", body)
        self.assertIn(r'for c in \$($BB pgrep -P \"$TAKEOVER_PID\" 2>/dev/null); do', body)
        self.assertIn("child_exe=\\$(readlink /proc/\\$c/exe", body)
        self.assertIn('case \\"\\$child_exe\\" in', body)
        self.assertIn("takeover.log", body)
        self.assertIn("zygote_exec.log", body)
        self.assertIn("Stopped the stalled guest app_process pid", body)
        self.assertIn('readlink /proc/\\$zygote_pid/ns/mnt', body)

    def test_guest_start_records_launcher_exit_status(self):
        launch = START.split('echo "[Pivoted Root] Launching guest Zygote & SystemServer via zygote_exec..."', 1)[1]
        non_root = launch.split("exec /data/adb/oneos/bin/su --launch", 1)[1]
        self.assertIn("/data/zygote_exec &\nZYGOTE_PID=$!\nwait \"$ZYGOTE_PID\"", non_root)
        self.assertIn('[ "$ZYGOTE_EXIT" = 134 ]', non_root)
        self.assertIn('ONEOS_GUEST_JIT_ZYGOTE=1 /data/zygote_exec &', non_root)
        self.assertIn("guest zygote launcher pid $ZYGOTE_PID exited with status $ZYGOTE_EXIT", non_root)
        self.assertIn('exit "$ZYGOTE_EXIT"', non_root)
        self.assertNotIn("exec /data/zygote_exec", non_root)
        if not shutil.which("bash"):
            self.skipTest("bash is required to exercise the launcher fallback")
        with tempfile.TemporaryDirectory() as temp:
            launcher = Path(temp) / "zygote_exec"
            attempts = Path(temp) / "attempts"
            jit_env = Path(temp) / "jit_env"
            launcher.write_text(
                "#!/bin/sh\n"
                f"n=$(cat '{attempts}' 2>/dev/null || echo 0)\n"
                f"n=$((n + 1)); echo $n > '{attempts}'\n"
                f"echo ${'{'}ONEOS_GUEST_JIT_ZYGOTE:-0{'}'} > '{jit_env}'\n"
                'case "${FAIL_MODE:-abort}:$n" in abort:1) exit 134;; exit:1) exit 23;; esac\n'
                "exit 0\n"
            )
            launcher.chmod(0o755)
            root_start = launch.index("if [ -f /data/oneos_root/enabled ]; then")
            non_root_body = launch[launch.index("fi\n", root_start) + 3:]
            tail = non_root_body.replace("/data/zygote_exec", str(launcher))
            result = subprocess.run(
                ["bash", "-c", 'pidof() { return 1; }\n' + tail],
                env=dict(os.environ, ONEOS_GUEST_JIT_ABORT_FALLBACK="1", ONEOS_GUEST_JIT_ZYGOTE="0"),
                capture_output=True, text=True,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual("2", attempts.read_text().strip())
            self.assertEqual("1", jit_env.read_text().strip())
            self.assertIn("retrying once with -Xforcejitzygote", result.stdout)

            attempts.unlink()
            result = subprocess.run(
                ["bash", "-c", 'pidof() { return 1; }\n' + tail],
                env=dict(os.environ, ONEOS_GUEST_JIT_ABORT_FALLBACK="1", ONEOS_GUEST_JIT_ZYGOTE="0", FAIL_MODE="exit"),
                capture_output=True, text=True,
            )
            self.assertEqual(23, result.returncode, result.stdout + result.stderr)
            self.assertEqual("1", attempts.read_text().strip())
            self.assertNotIn("retrying once with -Xforcejitzygote", result.stdout)

    def test_kernel_su_launcher_reports_guest_process_exit_status(self):
        launch = GUEST_ROOT_SOURCE.split("static int launch_once", 1)[1].split(
            "int main(int argc", 1
        )[0]
        self.assertIn('log_child_status("app_process",pid,w)', launch)
        self.assertIn('log_child_status("app_process launcher",pid,w)', launch)
        self.assertIn('log_child_status("zygisk injector",injector,result)', launch)
        self.assertIn("WIFEXITED(status)", GUEST_ROOT_SOURCE)
        self.assertIn("WIFSIGNALED(status)", GUEST_ROOT_SOURCE)
        self.assertIn("child_signal!=SIGABRT", GUEST_ROOT_SOURCE)
        self.assertIn('strcmp(fallback,"0")', GUEST_ROOT_SOURCE)
        self.assertIn('setenv("ONEOS_GUEST_JIT_ZYGOTE","1",1)', GUEST_ROOT_SOURCE)
        self.assertIn("ONEOS_GUEST_JIT_ABORT_FALLBACK=1", START)
        self.assertIn("if [ -f /system/framework/oplus-services.jar ]", START)
        self.assertIn('getenv("ONEOS_GUEST_JIT_ABORT_FALLBACK")', GUEST_ROOT_SOURCE)
        self.assertIn("-o switcher/assets/guest_root switcher/native/guest_root.c", BUILD_WORKFLOW)

    def test_live_app_process_is_not_returned_early_and_timeout_is_bounded(self):
        if not shutil.which("bash"):
            self.skipTest("bash is required to exercise the Android shell decision")
        decision = self._startup_decision().replace("$STAGE", "/tmp/stage").replace("$TAKEOVER_PID", "777")
        prefix = r'''\
pidof() { [ "$MOCK_ZYGOTE" = 1 ] && [ "$1" = zygote64 ]; }
busybox() { "$@"; }
pgrep() { [ "$1" = -P ] && [ -n "$MOCK_CHILD_PID" ] && printf '%s\n' "$MOCK_CHILD_PID"; }
readlink() {
    case "$1" in
        /proc/777/exe) printf '%s\n' "$MOCK_EXE" ;;
        /proc/778/exe) printf '%s\n' "$MOCK_CHILD_EXE" ;;
        /proc/777/ns/mnt) printf '%s\n' 'mnt:[42]' ;;
        *) command readlink "$@" ;;
    esac
}
cat() {
    case "$1" in
        /tmp/stage/guest_mntns) printf '%s\n' 'mnt:[42]' ;;
        *) command cat "$@" ;;
    esac
}
dead=0; app_process_wait=0; jit_abort_retry=0; BB=busybox
'''
        live = prefix + (decision + "\n") * 12 + 'printf "%s:%s:%s\\n" "$dead" "$app_process_wait" "$startup_stuck"\n'
        result = subprocess.run(["bash", "-c", live], env=dict(os.environ, MOCK_EXE="/system/bin/app_process64"), capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("0:120:1", result.stdout.strip())

        child_live = prefix + (decision + "\n") * 12 + 'printf "%s:%s:%s\\n" "$dead" "$app_process_wait" "$startup_stuck"\n'
        result = subprocess.run(
            ["bash", "-c", child_live],
            env=dict(os.environ, MOCK_EXE="/data/adb/oneos/bin/su", MOCK_CHILD_PID="778", MOCK_CHILD_EXE="/system/bin/app_process64"),
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("0:120:1", result.stdout.strip())

        exited = prefix + (decision + "\n") * 3 + 'printf "%s:%s:%s\\n" "$dead" "$app_process_wait" "$startup_stuck"\n'
        result = subprocess.run(["bash", "-c", exited], env=dict(os.environ, MOCK_EXE=""), capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("30:0:0", result.stdout.strip())

        registered = prefix + decision + '\nprintf "%s:%s:%s\\n" "$dead" "$app_process_wait" "$startup_stuck"\n'
        result = subprocess.run(
            ["bash", "-c", registered],
            env=dict(os.environ, MOCK_EXE="/system/bin/app_process64", MOCK_ZYGOTE="1"),
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("0:0:0", result.stdout.strip())

    def test_missing_zygote_snapshot_is_saved_before_safe_return(self):
        body = SWITCH.split("            startup_stuck=0", 1)[1].split("            # Nothing restarts", 1)[0]
        self.assertIn("zygote_failure.log", body)
        self.assertIn("timeout 10 logcat -b all -d -t 3000", body)
        self.assertIn("timeout 5 dmesg -T", body)
        self.assertLess(body.index("zygote_failure.log"), body.index("exec /system/bin/sh $STAGE/return_to_oneui.sh"))

    def test_zygote_output_is_saved_and_collected_without_following_links(self):
        self.assertIn('#define ZYGOTE_LOG_PATH "/data/zygote_exec.log"', ZYGOTE_LAUNCHER)
        self.assertIn("O_APPEND | O_CLOEXEC | O_NOFOLLOW", ZYGOTE_LAUNCHER)
        self.assertIn("fstat(log_fd, &st)", ZYGOTE_LAUNCHER)
        self.assertIn("S_ISREG(st.st_mode)", ZYGOTE_LAUNCHER)
        self.assertIn("dup3(log_fd, STDOUT_FILENO, 0)", ZYGOTE_LAUNCHER)
        self.assertIn("dup3(log_fd, STDERR_FILENO, 0)", ZYGOTE_LAUNCHER)
        self.assertIn('execve("/system/bin/app_process64", argv, envp)', ZYGOTE_LAUNCHER)
        self.assertIn('perror("zygote_launcher: execve /system/bin/app_process64")', ZYGOTE_LAUNCHER)
        self.assertIn('ZYGOTE_LOG=/data/zygote_exec.log', START)
        launch = START.split('echo "[Pivoted Root] Launching guest Zygote & SystemServer via zygote_exec..."', 1)[1]
        self.assertLess(launch.index('ZYGOTE_LOG=/data/zygote_exec.log'), launch.index('if [ -f /data/oneos_root/enabled ]'))
        self.assertIn('for f in "$S"/data/*.log', (ROOT / "app-android/app/src/full/assets/logs/collect.list").read_text())


class GuestClassPathNamespaceTest(unittest.TestCase):
    def test_system_ext_classpaths_use_the_linker_permitted_mount_alias(self):
        if not shutil.which("bash"):
            self.skipTest("bash is required to exercise the Android shell helper")
        normalize_call = 'normalize_guest_classpath_file "$CLASSPATH_FILE"'
        self.assertIn(normalize_call, START)
        self.assertLess(START.index(normalize_call), START.index('done < "$CLASSPATH_FILE"'))
        function = START.split("normalize_guest_classpath_file() {", 1)[1].split("\n}\n", 1)[0]
        with tempfile.TemporaryDirectory() as temp:
            classpath = Path(temp) / "classpath"
            classpath.write_text(
                "export BOOTCLASSPATH /system/framework/framework.jar:/system/system_ext/framework/ext.jar\n"
                "export SYSTEMSERVERCLASSPATH /system/system_ext/framework/services.jar\n"
            )
            result = subprocess.run(
                ["bash", "-c", "normalize_guest_classpath_file() {" + function + "\n}\n"
                 + 'normalize_guest_classpath_file "$1"', "bash", str(classpath)],
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(
                "export BOOTCLASSPATH /system/framework/framework.jar:/system_ext/framework/ext.jar\n"
                "export SYSTEMSERVERCLASSPATH /system_ext/framework/services.jar\n",
                classpath.read_text(),
            )

            # A second pass is a no-op and must leave an already-canonical
            # classpath untouched.
            before = classpath.read_bytes()
            result = subprocess.run(
                ["bash", "-c", "normalize_guest_classpath_file() {" + function + "\n}\n"
                 + 'normalize_guest_classpath_file "$1"', "bash", str(classpath)],
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(before, classpath.read_bytes())


class OplusNetdIsolationTest(unittest.TestCase):
    def _run_helper(self, lib_dir, stage, fake_bb, abi="lib64"):
        function = PREPARE.split("hide_oplus_netd_extension() {", 1)[1].split("\n}\n", 1)[0]
        script = "hide_oplus_netd_extension() {" + function + "\n}\n"
        script += 'hide_oplus_netd_extension "$1" "$2"\n'
        return subprocess.run(
            ["bash", "-c", script, "bash", str(lib_dir), abi],
            env=dict(os.environ, STAGE=str(stage), BB=str(fake_bb)),
            capture_output=True,
            text=True,
        )

    def test_isolation_is_applied_after_final_system_ext_mounts(self):
        addons_prepare = PREPARE.index('fold8_coloros.sh" addons-prepare')
        final_system_ext_mount = PREPARE.index('if [ "$SEXT_MOUNT" = 1 ]; then', addons_prepare)
        isolation = PREPARE.index("# ColorOS's Oplus netd extension registers")
        mounts_ready = PREPARE.index('echo "=== ALL MOUNTS PREPARED SUCCESSFULLY')
        self.assertLess(final_system_ext_mount, isolation)
        self.assertLess(isolation, mounts_ready)
        self.assertIn('NETD_SYSTEM_EXT="$GUEST_SYSTEM_EXT"', PREPARE[isolation:mounts_ready])
        self.assertIn('[ "$SEXT_MOUNT" = 1 ] && NETD_SYSTEM_EXT="$ROOTFS/system_ext"', PREPARE[isolation:mounts_ready])

    def test_oplus_netd_is_whiteouted_only_in_a_private_read_only_guest_view(self):
        if not shutil.which("bash"):
            self.skipTest("bash is required to exercise the Android shell helper")
        self.assertIn('if [ -f "$ROOTFS/system/framework/oplus-services.jar" ]; then', PREPARE)
        self.assertIn('for abi in lib64 lib; do', PREPARE)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            stage = root / "stage"
            lib_dir = root / "guest/system_ext/lib64"
            lib_dir.mkdir(parents=True)
            netd_so = lib_dir / "liboplusNetd.so"
            netd_so.write_bytes(b"base image library")
            calls = root / "mount-calls"
            fake_bb = root / "busybox"
            fake_bb.write_text(
                "#!/bin/sh\n"
                f"printf '%s\\n' \"$*\" >> '{calls}'\n"
            )
            fake_bb.chmod(0o755)

            result = self._run_helper(lib_dir, stage, fake_bb)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            commands = calls.read_text().splitlines()
            tmpfs_mount = next(command for command in commands if command.startswith("mount -t tmpfs "))
            overlay = tmpfs_mount.split()[-1]
            self.assertTrue(overlay.startswith(str(stage / "tmp" / "oneos_oplus_netd_lib64_")))
            self.assertIn(f"mount -t tmpfs -o size=128k,mode=755 tmpfs {overlay}", commands)
            self.assertIn(f"mount --bind {lib_dir} {overlay}/lower", commands)
            self.assertIn(f"mknod {overlay}/upper/liboplusNetd.so c 0 0", commands)
            self.assertIn(
                f"mount -t overlay overlay -o ro,lowerdir={overlay}/lower,upperdir={overlay}/upper,workdir={overlay}/work {lib_dir}",
                commands,
            )
            self.assertIn("mount --make-private " + overlay, commands)
            self.assertIn("mount --make-private " + overlay + "/lower", commands)
            self.assertEqual(b"base image library", netd_so.read_bytes())

    def test_symlinked_oplus_netd_is_refused_before_any_mount(self):
        if not shutil.which("bash"):
            self.skipTest("bash is required to exercise the Android shell helper")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            stage = root / "stage"
            lib_dir = root / "guest/system_ext/lib"
            lib_dir.mkdir(parents=True)
            outside = root / "outside.so"
            outside.write_bytes(b"keep")
            netd_so = lib_dir / "liboplusNetd.so"
            try:
                netd_so.symlink_to(outside)
            except OSError as error:
                self.skipTest(f"symlinks are unavailable: {error}")
            calls = root / "mount-calls"
            fake_bb = root / "busybox"
            fake_bb.write_text("#!/bin/sh\nprintf '%s\\n' \"$*\" >> '" + str(calls) + "'\n")
            fake_bb.chmod(0o755)

            result = self._run_helper(lib_dir, stage, fake_bb, "lib")
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(calls.exists())
            self.assertEqual(b"keep", outside.read_bytes())


if __name__ == "__main__":
    unittest.main()
