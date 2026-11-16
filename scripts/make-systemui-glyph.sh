#!/bin/sh
# Build the guest fingerprint-glyph SystemUI APK from a stock Infinity-X
# SystemUI.apk. Infinity-X only: the guest's own UDFPS overlay is created empty
# there, and other images draw their own glyph.
# Usage: [APKTOOL="java -Xmx5g -jar apktool.jar"] scripts/make-systemui-glyph.sh [stock.apk] [out.apk]
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
patch_file="$root/switcher/patches/systemui-guest-glyph/systemui-guest-glyph.patch"
tested=6a99684e831f9635738189d39667debe0f8b798012fa4eb622a7cb87ad9b1ddf
stock=${1:-}
out=${2:-SystemUI_glyph.apk}
apktool=${APKTOOL:-apktool}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

if [ -z "$stock" ]; then
    stock="$work/SystemUI.apk"
    echo "Pulling SystemUI.apk from the guest; it must be unpatched." >&2
    adb exec-out su -c 'cat /system/system_ext/priv-app/SystemUI/SystemUI.apk' > "$stock"
fi
[ -f "$stock" ] || { echo "no such APK: $stock" >&2; exit 1; }

got=$(sha256sum "$stock" | cut -d' ' -f1)
if [ "$got" != "$tested" ] && [ "${ONEOS_GLYPH_ALLOW_OTHER:-0}" != 1 ]; then
    echo "SystemUI.apk sha256 is $got" >&2
    echo "tested build is $tested; set ONEOS_GLYPH_ALLOW_OTHER=1 to try anyway" >&2
    exit 1
fi

$apktool d -f --no-res -o "$work/dec" "$stock" >/dev/null
patch -p1 -d "$work/dec" < "$patch_file"

# The full decode is too big for one apktool build on this machine; only
# classes2.dex changes, and smali_classes2 is all that project needs.
mkdir -p "$work/proj"
awk 'BEGIN{s=0} /^unknownFiles:/{s=1;next} s==1 && /^[^ #-]/{s=0} s==0{print}' \
    "$work/dec/apktool.yml" > "$work/proj/apktool.yml"
cp -a "$work/dec/smali_classes2" "$work/proj/"
$apktool b "$work/proj" -o "$work/rebuilt.apk" >/dev/null

unzip -o -q "$work/rebuilt.apk" classes2.dex -d "$work"
python3 "$root/scripts/apk-replace-dex.py" "$stock" classes2.dex "$work/classes2.dex" "$out"
sha256sum "$out"
