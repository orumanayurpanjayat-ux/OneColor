#!/usr/bin/env python3
"""Print the pinned PLK110 crash-recovery guard methods for safety review.

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
        run(["java", "-jar", str(apktool), "d", "-q", "-f", "-r", "-o", str(decoded), str(jar)])

        rescue_files = list(decoded.rglob("RescueParty.smali"))
        if len(rescue_files) != 1:
            raise SystemExit(f"expected one RescueParty.smali in crashrecovery APEX, found {len(rescue_files)}")
        rescue_lines = rescue_files[0].read_text(encoding="utf-8").splitlines()
        disabled = method_block(rescue_lines, "isDisabled()Z", "RescueParty.isDisabled()Z")

        call_sites = []
        scheduler_methods = []
        for source in decoded.rglob("*.smali"):
            lines = source.read_text(encoding="utf-8").splitlines()
            for block in method_blocks(lines):
                text = "\n".join(block)
                if "RescueParty;->isDisabled()Z" in text or "RescueParty$1;-><init>" in text:
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

        print("===== BEGIN PLK110 CRASHRECOVERY SAFETY INSPECTION =====")
        print(f"AOSP crashrecovery APEX SHA-256: {digest}")
        print(f"service-crashrecovery.jar SHA-256: {hashlib.sha256(jar.read_bytes()).hexdigest()}")
        print(f"Source: {rescue_files[0].relative_to(decoded)}")
        print("RescueParty.isDisabled()Z:")
        print("\n".join(disabled))
        for source, block in scheduler_methods:
            print(f"RescueParty guard or factory-reset scheduler: {source}")
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
