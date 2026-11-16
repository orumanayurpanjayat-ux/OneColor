# Infinity-X keyguard fingerprint glyph

Infinity-X's SystemUI starts its own UDFPS overlay but never draws a glyph in
it: `UdfpsControllerOverlay` is built with a null
`UdfpsAnimationViewController`, so `updateDimensions()` leaves the overlay
empty. The sensor still works — a touch authenticates and the phone unlocks —
but the lock screen shows nothing to press.

This patch adds the glyph. The image's `NotificationShadeWindowView` gets one
`addView` in `onAttachedToWindow` for a `OneOSGlyphView`, which paints a white
circle at the sensor and is shown only while the keyguard reports locked.

**Infinity-X only.** The block in `guest_prepare.sh` is gated on a
`ro.infinity.*` property in the image's `system/build.prop`; ColorOS, HyperOS,
Nothing, OxygenOS and the Pixels draw their own glyph and are untouched. The
APK is only ever packed into the Infinity-X set.

## What it changes

`smali_classes2/com/android/systemui/shade/NotificationShadeWindowView.smali`:

- `.locals 1` becomes `.locals 4` in `onAttachedToWindow`.
- After `setWillNotDraw(false)`, `findViewById(0x0e05ee01)` checks for the view
  (the id the glyph sets on itself), and adds a new `OneOSGlyphView` with
  `FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)` when it is absent.

New `smali_classes2/com/oneos/glyph/`:

- `OneOSGlyphView` — a `View` that draws a 196 px circle on a 1440x3120 screen
  (white, alpha 200, stroke 12% of the radius) centred at 50% width and 78%
  height, which is where the ultrasonic sensor sits on the S25 Ultra. It listens
  to `KeyguardManager.addKeyguardLockedStateListener`: `VISIBLE` while locked,
  `INVISIBLE` while unlocked. It re-`bringToFront()`s itself on global layout so
  it stays above the shade's own children.
- `OneOSGlyphView$1`, `OneOSGlyphView$2` — the lock-state listener and the
  layout listener.

The overlay is not used: it is a separate window that sits outside the keyguard
scene, and it is what the image already leaves empty. The glyph lives in the
shade window, the same window the keyguard draws in.

## Build

```sh
scripts/make-systemui-glyph.sh [stock.apk] [SystemUI_glyph.apk]
```

The script pulls the guest's `SystemUI.apk` when no path is given, refuses a
sha256 it was not tested against (`ONEOS_GLYPH_ALLOW_OTHER=1` overrides it),
decodes without resources, applies
`systemui-guest-glyph.patch`, rebuilds **only** `smali_classes2` (a full decode
does not fit in memory here), and splices the new `classes2.dex` back with
`scripts/apk-replace-dex.py` so the signing block and every other entry stay as
they were.

The guest's SystemUI has precompiled `SystemUI.odex`/`.vdex`, so ART checks the
APK's dex checksums against them; the changed `classes2.dex` makes it JIT from
the APK instead. That costs one slower first start of SystemUI and nothing else.

## Deploy

`SystemUI_glyph.apk` is a file of the `infinityx` package set
(`app-android/packages/infinityx.sha256`), so the app fetches it by sha256 on
install and puts it in the stage. `guest_prepare.sh` bind-mounts it over the
image's SystemUI:

```sh
mount --bind "$STAGE/SystemUI_glyph.apk" "$GUEST_SYSTEM_EXT/priv-app/SystemUI/SystemUI.apk"
```

`ONEOS_GUEST_GLYPH=0` leaves the image's own SystemUI alone.

To redo it by hand on a running guest, write into the staged file (never
`adb push` onto a bind-mounted path — that replaces the inode):

```sh
adb push SystemUI_glyph.apk /data/local/tmp/sui_new.apk
adb shell "su -c 'cat /data/local/tmp/sui_new.apk > /data/local/tmp/oneos/data/local/tmp/sui_glyph.apk'"
```

then unbind and bind it in the guest's mount namespace and kill
`com.android.systemui`.
