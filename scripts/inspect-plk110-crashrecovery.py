#!/usr/bin/env python3
"""Inspect the pinned PLK110 crash-recovery guard and reset call chain.

The guest's vold is renamed only when the image's RescueParty guard makes its
factory-reset path safe. This inspection keeps the exact source APEX available
in the CI log before that APEX is added to the binary allowlist.
"""
import hashlib
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

EXPECTED_APEX_SHA = "05d8d921185130d88388b417387bbc0ba74281251b0d5188dae16953b82481d2"
EXPECTED_JAR_SHA = "832efa6618bdf7673503706326dd8f4c2d21f8b9bac2c988c410087e14248761"
RELEVANT_RESCUE_REFS = (
    "RescueParty;->isDisabled()Z",
    "RescueParty;->-$$Nest$smisDisabled()Z",
    "RescueParty;->-$$Nest$smexecuteRescueLevel(",
    "RescueParty;->executeFactoryReset(",
    "RescueParty$1;-><init>",
)


def run(command):
    return subprocess.run(command, check=True, text=True, capture_output=True)


def dump(debugfs, image, source_path, destination):
    result = run([debugfs, "-R", f"dump {source_path} {destination}", str(image)])
    if not destination.is_file() or destination.stat().st_size == 0:
        raise SystemExit(f"debugfs could not dump {source_path}: {result.stderr.strip()}")


def method_block(lines, signature, label):
    starts = [i for i, line in enumerate(lines)
              if line.startswith(".method ") and line.split()[-1] == signature]
    if len(starts) != 1:
        raise SystemExit(f"expected one {label} method, found {len(starts)}")
    start = starts[0]
    try:
        end = lines.index(".end method", start + 1)
    except ValueError:
        raise SystemExit(f"{label} method has no .end method")
    return lines[start:end + 1]


def method_blocks(lines):
    start = None
    for index, line in enumerate(lines):
        if line.startswith(".method "):
            start = index
        elif start is not None and line == ".end method":
            yield lines[start:index + 1]
            start = None


def inspect(image, debugfs, apktool):
    with tempfile.TemporaryDirectory(prefix="oneos-plk110-rescueparty-") as temporary:
        work = Path(temporary)
        apex = work / "crashrecovery-aosp.apex"
        payload = work / "apex_payload.img"
        jar = work / "service-crashrecovery.jar"
        decoded = work / "decoded"

        dump(debugfs, image, "/system/apex/com.android.crashrecovery.apex", apex)
        digest = hashlib.sha256(apex.read_bytes()).hexdigest()
        if digest != EXPECTED_APEX_SHA:
            raise SystemExit(f"PLK110 crashrecovery APEX hash changed: {digest}")

        with zipfile.ZipFile(apex) as archive:
            matches = [item for item in archive.infolist() if item.filename == "apex_payload.img"]
            if len(matches) != 1 or matches[0].flag_bits & 1:
                raise SystemExit("PLK110 crashrecovery APEX must have one unencrypted apex_payload.img")
            with archive.open(matches[0]) as source, payload.open("xb") as output:
                shutil.copyfileobj(source, output, 1024 * 1024)

        with payload.open("rb") as stream:
            stream.seek(1080)
            if stream.read(2) != b"\x53\xef":
                raise SystemExit("PLK110 crashrecovery APEX payload is not the expected ext filesystem")
        dump(debugfs, payload, "/javalib/service-crashrecovery.jar", jar)
        jar_digest = hashlib.sha256(jar.read_bytes()).hexdigest()
        if jar_digest != EXPECTED_JAR_SHA:
            raise SystemExit(f"PLK110 service-crashrecovery.jar hash changed: {jar_digest}")
        run(["java", "-jar", str(apktool), "d", "-q", "-f", "-r", "-o", str(decoded), str(jar)])

        rescue_files = list(decoded.rglob("RescueParty.smali"))
        if len(rescue_files) != 1:
            raise SystemExit(f"expected one RescueParty.smali in crashrecovery APEX, found {len(rescue_files)}")
        rescue_lines = rescue_files[0].read_text(encoding="utf-8").splitlines()
        disabled = method_block(rescue_lines, "isDisabled()Z", "RescueParty.isDisabled()Z")
        disabled_text = "\n".join(disabled)
        for prop in (
            "persist.sys.enable_rescue",
            "persist.device_config.configuration.disable_rescue_party",
            "persist.sys.disable_rescue",
        ):
            if f'"{prop}"' not in disabled_text:
                raise SystemExit(f"RescueParty.isDisabled()Z does not check {prop}")
        if "Landroid/os/Build;->TYPE:Ljava/lang/String;" not in disabled_text or '"userdebug"' not in disabled_text or '"eng"' not in disabled_text:
            raise SystemExit("RescueParty.isDisabled()Z no longer checks eng/userdebug build types")

        call_sites = []
        scheduler_methods = []
        for source in decoded.rglob("*.smali"):
            lines = source.read_text(encoding="utf-8").splitlines()
            for block in method_blocks(lines):
                text = "\n".join(block)
                if any(ref in text for ref in RELEVANT_RESCUE_REFS):
                    scheduler_methods.append((source.relative_to(decoded), block))
            for index, line in enumerate(lines):
                if "RecoverySystem;->rebootPromptAndWipeUserData" not in line:
                    continue
                starts = [i for i in range(index) if lines[i].startswith(".method ")]
                if not starts:
                    raise SystemExit(f"factory-reset call has no method header in {source}")
                start = starts[-1]
                end = lines.index(".end method", index)
                call_sites.append((source.relative_to(decoded), lines[start:end + 1]))
        if not call_sites:
            raise SystemExit("no RescueParty factory-reset call reaches RecoverySystem.rebootPromptAndWipeUserData")
        if not any("RescueParty;->isDisabled()Z" in "\n".join(block) for _, block in scheduler_methods):
            raise SystemExit("no crash-recovery method invokes RescueParty.isDisabled()Z")
        if not any("RescueParty$1;-><init>" in "\n".join(block) for _, block in scheduler_methods):
            raise SystemExit("no crash-recovery method schedules the factory-reset runnable")
        reset_callers = [
            (source, block) for source, block in scheduler_methods
            if "RescueParty;->executeFactoryReset(" in "\n".join(block)
        ]
        if len(reset_callers) != 1 or "executeRescueLevelInternal(" not in "\n".join(reset_callers[0][1]):
            raise SystemExit("factory-reset scheduling no longer has the inspected RescueParty level-7 caller")
        mitigation_callers = []
        for source in decoded.rglob("*.smali"):
            lines = source.read_text(encoding="utf-8").splitlines()
            for block in method_blocks(lines):
                text = "\n".join(block)
                if "RescueParty;->-$$Nest$smexecuteRescueLevel(" not in text:
                    continue
                # The private synthetic bridge itself is expected to forward
                # the call. Every non-bridge entry point must guard it first.
                if "bridge synthetic" in block[0]:
                    continue
                guard = text.find("RescueParty;->-$$Nest$smisDisabled()Z")
                mitigation = text.find("RescueParty;->-$$Nest$smexecuteRescueLevel(")
                move_result = text.find("move-result", guard)
                branch = min(
                    (position for position in (
                        text.find("if-eqz", move_result), text.find("if-nez", move_result),
                        text.find("if-eq", move_result), text.find("if-ne", move_result),
                    ) if position >= 0),
                    default=-1,
                )
                early_return = min(
                    (position for position in (
                        text.find("return-void", move_result), text.find("return ", move_result),
                        text.find("return\n", move_result),
                    ) if position >= 0),
                    default=-1,
                )
                if guard < 0 or move_result < guard or branch < move_result or early_return < branch or early_return > mitigation:
                    raise SystemExit(
                        f"{source.relative_to(decoded)}: rescue mitigation is not guarded by isDisabled before scheduling"
                    )
                mitigation_callers.append((source.relative_to(decoded), block))
        if len(mitigation_callers) < 2:
            raise SystemExit("expected guarded boot-loop and health-check RescueParty mitigation entry points")

        print("===== BEGIN PLK110 CRASHRECOVERY SAFETY INSPECTION =====")
        print(f"AOSP crashrecovery APEX SHA-256: {digest}")
        print(f"service-crashrecovery.jar SHA-256: {jar_digest}")
        print(f"Source: {rescue_files[0].relative_to(decoded)}")
        print("RescueParty.isDisabled()Z:")
        print("\n".join(disabled))
        for source, block in scheduler_methods:
            print(f"RescueParty guard or factory-reset scheduler: {source}")
            print("\n".join(block))
        for source, block in reset_callers:
            print(f"RescueParty executeFactoryReset caller: {source}")
            print("\n".join(block))
        for source, block in mitigation_callers:
            print(f"Guarded RescueParty mitigation entry: {source}")
            print("\n".join(block))
        for source, block in call_sites:
            print(f"Factory-reset call site: {source}")
            print("\n".join(block))
        print("===== END PLK110 CRASHRECOVERY SAFETY INSPECTION =====")


def main(argv):
    if len(argv) != 4:
        raise SystemExit("usage: inspect-plk110-crashrecovery.py <system.img> <debugfs> <apktool.jar>")
    inspect(Path(argv[1]), argv[2], Path(argv[3]))


if __name__ == "__main__":
    main(sys.argv)
