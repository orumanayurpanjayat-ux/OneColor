#!/usr/bin/env bash
# Rebuild PLK110's image-derived OneOS overlays from the exact GSI supplied by
# the owner. Never reuse Find X9 Ultra framework jars or APEX payloads here.
set -Eeuo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ZIP_URL='https://pixeldrain.com/api/file/VqJvx5SD?download'
ZIP_SHA='e3b19a42f4770e2788903c679e8e2a7116bc28e4b272c7597c5e6c3e2ac4fb73'
ZIP_BYTES=3029357770
IMAGE_SHA='43593ee631299f710fb091bc7f635627e91bd81fca7a8ab759ee3573cd621314'
IMAGE_BYTES=6644899840
APKTOOL_SHA='dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423'

OUT=${1:-"$ROOT/out/overlays_plk110"}
APKTOOL=${APKTOOL:-"$ROOT/mysticgsi/tools/apktool/apktool.jar"}
DEBUGFS=${DEBUGFS:-$(command -v debugfs || true)}

[[ -n "$DEBUGFS" && -x "$DEBUGFS" ]] || { echo 'PLK110 overlay build requires debugfs.' >&2; exit 2; }
[[ -f "$APKTOOL" && ! -L "$APKTOOL" ]] || { echo 'PLK110 overlay build requires the pinned apktool 3.0.3 jar in APKTOOL.' >&2; exit 2; }
[[ "$(sha256sum "$APKTOOL" | awk '{print $1}')" = "$APKTOOL_SHA" ]] || {
    echo 'apktool does not match the pinned 3.0.3 build.' >&2
    exit 2
}
command -v java >/dev/null 2>&1 || { echo 'PLK110 overlay build requires Java.' >&2; exit 2; }
command -v curl >/dev/null 2>&1 || { echo 'PLK110 overlay build requires curl.' >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo 'PLK110 overlay build requires Python 3.' >&2; exit 2; }
command -v sha256sum >/dev/null 2>&1 || { echo 'PLK110 overlay build requires sha256sum.' >&2; exit 2; }

if [[ -e "$OUT" || -L "$OUT" ]]; then
    echo "Refusing to reuse an existing output path: $OUT" >&2
    exit 2
fi
mkdir -p "$(dirname "$OUT")"
OUT_PARENT=$(cd "$(dirname "$OUT")" && pwd)
OUT="$OUT_PARENT/$(basename "$OUT")"

BUILD=$(mktemp -d "${TMPDIR:-/tmp}/oneos-plk110-build.XXXXXX")
trap 'rm -rf -- "$BUILD"' EXIT
ZIP="$BUILD/plk110.zip"
IMG="$BUILD/system.img"

echo "Downloading the pinned PLK110 archive"
curl --fail --location --retry 3 --retry-all-errors --retry-delay 5 \
    --output "$ZIP" "$ZIP_URL"
[[ "$(stat -c %s "$ZIP")" = "$ZIP_BYTES" ]] || { echo 'PLK110 ZIP size differs from the pinned archive.' >&2; exit 2; }
printf '%s  %s\n' "$ZIP_SHA" "$ZIP" | sha256sum --check --status || {
    echo 'PLK110 ZIP SHA-256 differs from the pinned archive.' >&2
    exit 2
}

# Accept exactly the owner's single system.img member and stream it to a new
# file. The archive is never extracted by pathname, so it cannot write outside
# the temporary build directory.
python3 - "$ZIP" "$IMG" "$IMAGE_BYTES" <<'PYEOF'
import shutil
import sys
import zipfile

archive_path, image_path, expected_size = sys.argv[1:]
expected_size = int(expected_size)
with zipfile.ZipFile(archive_path) as archive:
    items = archive.infolist()
    if len(items) != 1 or items[0].filename != "system.img":
        raise SystemExit("PLK110 archive must contain exactly one system.img")
    item = items[0]
    if item.file_size != expected_size or item.flag_bits & 1:
        raise SystemExit("PLK110 system.img size or encryption flag differs")
    with archive.open(item) as source, open(image_path, "xb") as output:
        shutil.copyfileobj(source, output, 1024 * 1024)
PYEOF
rm -f -- "$ZIP"

[[ "$(stat -c %s "$IMG")" = "$IMAGE_BYTES" ]] || { echo 'PLK110 system.img size differs from the pin.' >&2; exit 2; }
printf '%s  %s\n' "$IMAGE_SHA" "$IMG" | sha256sum --check --status || {
    echo 'PLK110 system.img SHA-256 differs from the pinned image.' >&2
    exit 2
}
python3 - "$IMG" <<'PYEOF'
import sys

with open(sys.argv[1], "rb") as image:
    image.seek(1080)
    if image.read(2) != b"\x53\xef":
        raise SystemExit("PLK110 system.img is not an ext4 filesystem image")
PYEOF
"$DEBUGFS" -R stats "$IMG" >/dev/null 2>&1 || { echo 'debugfs could not read the PLK110 system image.' >&2; exit 2; }

echo "Building image-aware overlays from PLK110 system.img ($IMAGE_SHA)"
APKTOOL="$APKTOOL" DEBUGFS="$DEBUGFS" "$ROOT/scripts/make-overlays.sh" "$IMG" "$OUT"

# These files are required for this set's guest startup and cross-system
# safeguards. Optional camera and UI overlays are recorded in the manifest.
for name in services_patched.jar oplus-services_patched.jar framework_patched.jar guards.txt art_payload.img i18n_payload.img runtime_payload.img sdkext_payload.img; do
    [[ -s "$OUT/$name" ]] || { echo "PLK110 patch build did not produce required $name" >&2; exit 3; }
done

(
    cd "$OUT"
    find . -maxdepth 1 -type f ! -name 'plk110-overlays.sha256' -print0 |
        sort -z | xargs -0 -r sha256sum > plk110-overlays.sha256
)

echo "PLK110 overlays built in $OUT"
du -sh "$OUT"
cat "$OUT/plk110-overlays.sha256"
