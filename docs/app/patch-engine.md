# OneOS app: on-device patch engine

> **Renamed after 0.3.6:** the stage is `/data/local/tmp/oneos` (was `/data/local/tmp/lineage_stage`), its set-aside copies `oneos.old-<epoch>` / `oneos.trash-<epoch>` (were `lineage_stage.old-*` / `.trash-*`), and the session scripts `oneos_switch.sh`, `guest_prepare.sh`, `guest_start.sh` (were `switch_to_lineage.sh`, `master_prepare.sh`, `master_inner.sh`). The app's root script and `scripts/deploy-phone.sh` rename an old stage and its copies when they are idle.

> **Not in the first release** (`DESIGN.md`, decision 2026-10-22): the
> server builds the patched files and publishes signed OneOS packages
> (`DESIGN.md` §5); the phone does no dex patching. This study is kept as the
> later option for user-supplied images. The host installd table is no
> longer baked into `services_patched.jar`: the app writes it on each phone
> and the guest reads it at runtime (`DESIGN.md` §6), which supersedes §4's
> `host_installd.json` and the plan's `installdTableSha256` re-patch.

Status: design study (key `patch-engine`). This is the part of the OneOS app
that turns the user's own OS image into the files the session scripts bind
over the guest. The build server does that today with
`scripts/make-overlays.sh`, `scripts/host-installd-table.sh` and
`scripts/check-dex.py`. Sources (commit `474b331`): those scripts,
`switcher/patches/*.py`, `switcher/assets/guest_prepare.sh`,
`scripts/deploy-phone.sh`, `docs/coloros-on-fold.md`. Names follow the
sibling studies `docs/app/patch-catalog.md` (entry ids, handlers, catalog
schema) and `docs/app/compat-safety-logs.md` (`GuardVerifier`,
`HostInstalldTable`, `GuestImageInspector`, gates G1-G14, fix packs).

## 0. Verified on the build server (2026-10-22)

A throwaway Java prototype (not committed) ran against the real libraries
and the ColorOS image `out/oneos-coloros.img`:

1. **Coordinates.** `com.android.tools.smali:{smali,smali-baksmali,smali-dexlib2,smali-util}:3.0.10`
   are on Google Maven (`dl.google.com/android/maven2`, versions 3.0.0 to
   3.0.10, updated 2026-09-04). Maven Central has no artifacts in that group.
2. **Same text as apktool.** The repo's apktool 3.0.3 (bundled smali
   3.0.9-dev) uses `localsDirective`, `sequentialLabels`, `debugInfo`,
   `parameterRegisters` and no implicit references. With those options and
   `BaksmaliWriter(writer, null)`, baksmali 3.0.10 printed byte-identical
   text for all 15 classes the services.jar patchers edit. With the class
   type as writer context, references are shortened (`iget-object p0, p0,
   mIdmapToken:...`) and the Python anchors stop matching.
3. **Selective disassembly works.** A dexlib2 reference scan picked 43 of
   26,651 classes in services.jar and 23 in oplus-services.jar. The
   unchanged Python patchers ran on that partial tree in `make-overlays.sh`
   order; changed classes were reassembled with smali and each affected dex
   rewritten with `DexPool`. apktool decodes of the results were identical
   to decodes of `out/overlays/services_patched.jar` and
   `oplus-services_patched.jar` (only the jar name in `apktool.yml`
   differed). `check-dex.py`: 0 empty try ranges.
4. **Deterministic.** Rewriting `classes.dex` at `-Xmx192m` and `-Xmx1g` gave
   the same bytes.
5. **Dex version.** `SmaliOptions.apiLevel = 36` fails ("dexVersion must be
   within [0, 999]"). The inputs are dex `039`; assemble and write with
   `Opcodes.forDexVersion(39)` (API 28).
6. **Round-trip.** All 34,351 unpatched classes of the 6 rewritten dex files
   compared equal to the originals (5-9 s per dex), after normalising
   `const-string/jumbo`, `goto/16`, alignment `nop`s and explicit default
   static values.

Cost on the server (AMD EPYC, 6 vCPU, HotSpot JDK 25):

| Step | services.jar (4 dex, 44.6 MB) | oplus-services.jar (3 dex, 30.3 MB) |
| --- | --- | --- |
| reference scan, all dex | 7-10 s, RSS 250 MB | 14 s |
| baksmali selected classes | 5 s | under 3 s |
| patchers (Python today) | 2.2 s | 1.4 s |
| reassemble + rewrite one dex | 26-49 s (`classes.dex` 12.1 MB; heap 119-310 MB; works at `-Xmx192m`) | 18-20 s |
| total (3 cold JVMs) | 122 s, max RSS 580 MB | 79 s, max RSS 700 MB |
| for comparison, `apktool d` only | 39 s, RSS 714 MB | - |

## 1. Where the engine runs

### 1.1 Modules

| Module | Kind | Content |
| --- | --- | --- |
| `:core:dex` | pure Kotlin/JVM | `DexJar`, `SelectorScan`, `SmaliTree`, `Reassembler`, `DexRewriter`, `JarWriter`, `EmptyTryRangeCheck`, `RoundTripCheck` |
| `:core:patch` | pure Kotlin/JVM | `SmaliPatch` + 11 ports (§11), `PatchPlan`, `PlanResolver`, `PatchEngine`, `BuildCache`, `BinaryPatcher`, `ElfLocator`, `ApexPayloadExtractor`, `RroStager` |
| `:core:image` | Android library | `ImageImporter`, `ContainerProbe`, `SparseReader`, `HoleWriter`, `FsProbe` |
| `:core:safety` | sibling doc | `HostInstalldTable`, `GuardVerifier` (read dex through `:core:dex`) |
| `:engine` | in the APK | `com.fahadali.oneos.engine.EngineMain`: root entry point, jobs, progress |
| `:tools:patch-cli` | JVM CLI, server | the same `:core:patch` for golden tests and attestation hashes (§12) |

No Android API in `:core:dex`/`:core:patch`: the server runs the phone's code.
Once the golden test passes, `make-overlays.sh` calls `patch-cli` instead of
apktool + Python, so every patch has one implementation.

### 1.2 Process model

The engine needs root, about 0.8 GB of memory, and must survive the app
being closed. It runs as a root `app_process`, not in the app:

```
app --su--> oneos_engine.sh <job.json>         (APK asset)
  unshare -m --propagation private             # mounts vanish with the process
  L=$($BB losetup -r -f --show "$IMG")         # read-only loop
  $BB mount -t "$FS" -o ro${EXT4:+,noload} "$L" "$MNT"
  CLASSPATH="$APK" app_process -Xmx768m /system/bin com.fahadali.oneos.engine.EngineMain "$JOB"
  umount "$MNT"; $BB losetup -d "$L"
```

- `$BB` is the busybox the scripts find (`guest_prepare.sh:46-47`); `$APK`
  is `ApplicationInfo.sourceDir`.
- Progress: JSON lines on stdout and in `builds/<key>.tmp/progress.jsonl`,
  e.g. `{"step":"jar:services","phase":"rewrite","dex":"classes.dex","done":2,"of":3}`.
  The app can reattach after being killed.
- `flock $STAGE/engine.lock`: one job at a time. No start while
  `$STAGE/rootfs` is mounted or `$STAGE/session/current.json` is from this boot.
- Fallback when a root manager blocks `app_process`: the same `:core:dex` in
  the app process (`largeHeap`, 512 MB), with a root shell copying files in
  and out (slower, +150 MB of cache).

### 1.3 Built on the phone vs shipped

| Item | Built on the phone | Shipped in APK | Shipped in signed fix packs |
| --- | --- | --- | --- |
| `services_patched.jar`, `oplus-services_patched.jar` | yes, from the user's image | engine and patch code | rule args, expected counts, attestation hashes |
| helper smali (`OneOSPanelBacklight`) | assembled | - | yes (our code; [F8U] tables) |
| `host_installd.json` | yes, from the host's `services.jar` | - | - |
| `<short>_payload.img` (APEX) | yes, copied from the image | - | module list |
| `surfaceflinger_patched` | yes, byte patch | locator code | descriptors |
| RRO apks | no | - | yes, built from `switcher/overlays/`, signed by us |
| OS images, OEM jars and apks | never shipped | - | - |

## 2. Input handling

### 2.1 Formats

| Input | Detected by | Handling | This build |
| --- | --- | --- | --- |
| raw ext4 | `0xEF53` at 1080 | copied with holes (§2.2) | yes |
| raw erofs | `0xE0F5E1E2` (LE u32) at 1024 | copied | import yes; sessions blocked (G13) until tested: `guest_prepare.sh:84` mounts ext4 |
| Android sparse | `0xED26FF3A` at 0 | `SparseReader`: RAW, FILL, DONT_CARE, CRC32; zero FILL and DONT_CARE become holes | yes |
| `.zip` (zip64) | `PK\3\4` | commons-compress `ZipFile` on a `FileChannel` | yes |
| `.xz` | `FD 37 7A 58 5A 00` | `org.tukaani:xz` `XZInputStream`; size from `SeekableXZInputStream` | yes |
| `.gz` | `1F 8B` | `GZIPInputStream`; size unknown above 4 GiB | yes |
| `.7z` | `37 7A BC AF 27 1C` | commons-compress `SevenZFile` (seekable path; no AES) | yes |
| `.tar`, `.tar.xz` | `ustar` at 257 | commons-compress `TarArchiveInputStream` | yes |
| `super.img` | LP geometry `0x616C4467` at 4096 | refused, "not supported yet" | no |
| OTA `payload.bin` | `CrAU` at 0 | refused | no |
| `system.new.dat(.br)` | entry names | refused (brotli + sdat2img) | no |
| boot or vendor image | `ANDROID!`; no `system/build.prop` | refused | - |

`ContainerProbe` unwraps up to 3 nested layers (e.g. a sparse `system.img`
in a `.zip`). `switcher/assets/7zz` (a static binary of unknown origin from
OneLineage) is not shipped; if we ever need it, we build 7-Zip from source.

### 2.2 Import pipeline

```
source -> container readers -> [SparseReader] -> HoleWriter -> images/<tmp>/system.raw.part
                                               \-> SHA-256 over the logical bytes
```

- **Source.** A picker (SAF) file on primary storage is opened by the root
  engine as `/data/media/0/<path>` (seekable, no copy). Any other document is
  streamed into the engine's stdin; zip and 7z then need a seekable copy first.
- **`HoleWriter`** skips all-zero 4 KiB blocks, then `ftruncate(size)`. The
  ColorOS image is 10,276,003,840 bytes but allocates 9.1 GiB on f2fs.
- **Hash** over every logical byte, zeros included: the same value as
  `sha256sum system.raw` (`deploy-phone.sh:72,81-82`), with no second read.
- **Space** = raw size + 1 GiB for outputs, then G9 (compat doc §2.1).
  gz and streamed xz have no reliable size: they stop cleanly on ENOSPC.
- **Finish**: `fsync`, rename to `images/<sha16>/system.raw`; a duplicate
  hash keeps the existing copy.

### 2.3 Identification and checks

1. **Archive entry:** an entry named exactly `system.img` wins; else the one
   `*.img` whose first 4 KiB is ext4, erofs or sparse; several = the user
   picks. `vendor.img`, `boot.img` and similar are never auto-picked.
2. **Filesystem:** the superblock block count times block size must fit the
   file (ext4 and erofs). ext4 `shared_blocks` is fine (read-only).
3. **Content** (engine job `inspect`, mounted as in §2.5): `system/build.prop`,
   `system/bin/sh`, `init.environ.rc` (the `guest_prepare.sh:65` health
   check), `system/framework/services.jar`, `arm64-v8a` in
   `ro.system.product.cpu.abilist64`. It also collects the facts for
   `GuestImageInspector`.
4. **Refused:** not a system image, One UI as guest (compat doc §1.6), no
   arm64, truncated filesystem. A leftover `.part` is deleted.

### 2.4 Stage layout

The path stays (every script hard-codes it, `guest_prepare.sh:2`), but the
directory becomes root 0711 instead of OneLineage's `chmod 777`
(`MainActivity.java:357`).

```
/data/local/tmp/oneos/
  images/<sha16>/system.raw             raw image with holes
  images/<sha16>/image.json             ImageRecord
  images/<sha16>/apex/<short>_payload.img
  host/<fp16>/host_installd.json        per host build (§4)
  steps/<stepKey16>/                     cached step outputs (§9.2)
  builds/<planKey16>/build.json          BuildRecord (§9.3); builds/<key>.tmp while running
  active.json                            {planKey, imageSha256, activatedAt, bootId}
  system.raw services_patched.jar oplus-services_patched.jar surfaceflinger_patched
  <short>_payload.img OneOSWallpapersFoldGrid.apk guards.json guards.txt
                                         ^ hard links under the names the scripts read
  rootfs/ data/ oem/ session/ ...        session state, unchanged
```

```json
{"schema": "oneos.image/1", "sha256": "...", "size": 10276003840, "allocated": 9768210432,
 "fs": "ext4", "fsUuid": "1618a57d-d721-4619-918c-95841c297157",
 "quick": {"head1MiB": "...", "tail1MiB": "...", "inode": 1234, "mtime": 1761120000},
 "source": {"name": "Coloros-...-MysticGSI.img", "layers": ["raw"]}, "family": "coloros",
 "props": {"ro.build.fingerprint": "...", "ro.build.version.sdk": "37"},
 "jars": {"system/framework/services.jar": "...", "system/framework/oplus-services.jar": "..."}}
```

`quick` identifies the image in milliseconds; the full hash is recomputed
only on "Verify image". The check that protects a session is R4 (§10).

### 2.5 Mount, not extract

The engine reads the image through a read-only loop mount in a private
mount namespace (§1.2) and has no ext4 or erofs reader of its own:

- Root is required anyway, and Samsung's kernels already mount ext4 and erofs.
- erofs uses lz4/lzma/deflate compression; a Kotlin reader is a project.
- The mount is gone when the engine exits; One UI never sees it.
- `losetup -r` plus ext4 `ro,noload` never write to the image (no journal
  replay), so its hash stays valid. `guest_prepare.sh:81-84` uses neither
  yet; the stage scripts should adopt both.

Never read guest files from `$STAGE/rootfs`: in a session the host's
`/system/apex` is bound there, which is how phone-side extraction once got
the host's modules (`make-overlays.sh:151-155`). The fallback extraction in
`mount_payload` (`guest_prepare.sh:1096-1106`) goes away: a payload absent
from the build means the host's module stays, on purpose.

## 3. Framework patching (dex)

### 3.1 Libraries (exact pins)

| Coordinates | Repo | License |
| --- | --- | --- |
| `com.android.tools.smali:smali-dexlib2`, `smali-baksmali`, `smali`, `smali-util` `:3.0.10` | Google Maven | BSD-3 |
| transitive: `com.google.guava:guava:31.1-android`, `com.google.code.findbugs:jsr305:3.0.2`, `org.antlr:antlr-runtime:3.5.2`, `org.antlr:stringtemplate:3.2.1` (+ `antlr:antlr:2.7.7`), `org.antlr:antlr:3.5.2`, `org.jcommander:jcommander:1.85` | Maven Central | Apache-2.0 / BSD |
| `org.apache.commons:commons-compress:1.28.0` (input archives, `JarWriter`) | Maven Central | Apache-2.0 |
| `org.tukaani:xz:1.12` | Maven Central | 0BSD |
| `org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0` (1.12.0 is 3 days old) | Maven Central | Apache-2.0 |

Gradle lists `google()` first. R8 keeps `com.android.tools.smali.**` and
`org.antlr.runtime.**` (the parser uses reflection) and can drop most of
`org.antlr:antlr`. Any smali upgrade must pass §12 first: changed baksmali
text breaks anchors.

### 3.2 Pipeline per jar

```
DexJar (lazy DexBackedDexFile per classesN.dex)
 -> SelectorScan (selectors of all enabled patches)          ~7 s
 -> SmaliTree (baksmali the selected classes, in memory)     ~5 s
 -> patches in plan order, each in a transaction             ~2 s
 -> Reassembler (dirty + new classes, one smali run)
 -> DexRewriter (per changed dex: DexPool of originals + replacements)
 -> checks (§10) -> JarWriter
```

Selectors declare which classes a patch may touch. The selected set must be
a superset of everything its text patterns can match; otherwise a count
check could pass on a partial tree.

| Selector | Selects a class that | Pre-filter |
| --- | --- | --- |
| `ClassSuffix("com/android/server/pm/UserDataPreparer;")` | has that type suffix (Python `rel.endswith`) | class list |
| `Invokes("Landroid/os/IVold;->")` | references a method with that prefix | dex `method_ids` |
| `ConstString("sys.powerctl")` | has `const-string(/jumbo)` with that value | dex `string_ids` |
| `DefinesMethod("doSysRq(C)V")` | defines that method | class list |

The scan walks instructions with dexlib2, after skipping dex files whose id
tables cannot match (`classes4.dex`: 0.19 s). Every patcher that greps all
files in Python declares a selector for each of its patterns (§11).

### 3.3 Fixed options

```kotlin
val opcodes = Opcodes.forDexVersion(dex.dexVersion)   // 39 here; never write a newer version
fun baksmaliOptions() = BaksmaliOptions().apply {
    apiLevel = opcodes.api; localsDirective = true; sequentialLabels = true
    debugInfo = true; parameterRegisters = true; implicitReferences = false
}
fun smali(c: ClassDef) = StringWriter().also { sw ->
    BaksmaliWriter(BufferedWriter(sw), null).use { ClassDefinition(baksmaliOptions(), c).writeTo(it) }
}.toString()
val smaliOptions = SmaliOptions().apply { apiLevel = opcodes.api; jobs = 1 }
```

`sequentialLabels` gives the `:cond_2` style labels some anchors use.

### 3.4 `SmaliPatch` and fail-closed rules

```kotlin
interface SmaliPatch {
    val handler: String          // catalog impl.handler, e.g. "StorageGuardsPatch"
    val ruleVersion: Int         // bumped whenever output text changes
    fun selectors(args: PatchArgs): List<Selector>
    fun apply(tx: SmaliTree.Tx, args: PatchArgs, ctx: PatchContext): PatchReport  // throws PatchRefused
}
class SmaliTree.Tx {             // copy-on-write; committed only if apply() returns
    fun files(): Sequence<SmaliFile>
    fun one(suffix: String): SmaliFile            // exactly one, else PatchRefused
    fun addClass(type: String, text: String, placeNextTo: SmaliFile)
}
class SmaliFile(val type: String, val dex: String) { var lines: MutableList<String> }
data class PatchReport(val handler: String, val sites: List<Site>, val counts: Map<String, Int>)
```

- **Same anchors, same text.** Python constants (`OLD`/`NEW` blocks,
  regexes, headers, `KEEP`, `ALLOWED`), `split("\n")` line handling and
  generated labels (`oneos_have_binder_<i>`) are kept, so output text equals
  Python's byte for byte (§12).
- **Every `sys.exit` is a `PatchRefused`.** A transaction leaves no partial
  edit (today only `set -e` covers e.g. `guest_power_guards.py` writing AMS
  before failing on ShutdownThread).
- **Safety first.** A refused safety patch (or one a safety entry
  `requires`) fails the whole build. Others follow `on_anchor_missing`
  (catalog §5): `block_image`, `skip` or `skip_warn`, shown as "not applied".
- **Count policy.** Python's exact counts (5 IVold key calls, 2
  `sys.powerctl`, 4 `dumpenvironment`, 2 idmap calls) are tuned to this
  build. Rules carry `expect` per count: `{"eq": n}` for images a pack lists
  as tested; `"all_removed"` for drop-every-call lists (vold keys, storage,
  keystore), where any count >= 1 passes and `GuardVerifier` must find none
  left; `"exactly_one"` for every insert-at-anchor edit, never relaxed
  (decision, §13).

### 3.5 New classes and `JarWriter`

`OneOSInstalldProxy` and `OneOSPanelBacklight` go into their anchor class's
dex (`guest_installd_abi.py:214-215`: `classes.dex` is at the 64K limit). On
`DexIndexOverflowException` the class moves to the dex with most method-id
room (same class loader, so still correct) and the move is logged; no room
anywhere refuses the patch.

`make-overlays.sh:48-56` copies the jar and swaps changed dex entries with
`zip -0`, which stamps the current time. `JarWriter` uses commons-compress
`ZipArchiveOutputStream`: other entries go through `addRawArchiveEntry`
(bytes, CRC and order unchanged); a new dex is STORED with
`setAlignment(4)` and the original timestamp. The jar hash then depends only
on inputs and rules, which makes attestation possible (R9).

The image ships `oat/arm64/services.{odex,vdex,art}`. Their checksums no
longer match the patched jar, so ART runs it with JIT, as today.

### 3.6 Phone budget

| Resource | Budget | Basis |
| --- | --- | --- |
| Java heap | `-Xmx768m`, one dex rewrite at a time | worked at 192m; up to 310 MB used at 1g |
| RAM gate | `MemAvailable` >= 2 GiB, else wait | 12-16 GB phones; RSS 580-700 MB measured |
| services.jar | estimated 2-5 min | 122 s on the server; needs a device measurement |
| oplus-services.jar | estimated 1.5-3 min | 79 s on the server |
| payloads, SF, RRO, checks | under 1 min | copies; round-trip 5-9 s per dex |
| disk | about 90 MB per build; payloads about 280 MB per image | `out/overlays` |

A build runs on request or after a One UI update, so minutes are fine. The
UI keeps the screen on, shows step progress and applies G10 (battery,
temperature) first. Parallel dex rewrites are an option at >= 4 GiB free.

## 4. Host installd table on the phone

`scripts/host-installd-table.sh` runs `dexdump -f` on the host
`services.jar` (pulled with adb). It takes the `TRANSACTION_<name>` ints of
`android/os/IInstalld$Stub` and the first signature of each method in
`$Stub$Proxy`. The output is `switcher/patches/host_installd.txt`: 85
methods from the F8U build `F976BXXS3AZIJ`, static and never checked against
the running phone.

`HostInstalldTable` (`:core:safety`) does the same on the phone, without
mounts (`/system/framework` is world-readable):

1. Find `Landroid/os/IInstalld$Stub;` in `/system/framework/services.jar`;
   read each `TRANSACTION_*` from `field.initialValue` (`IntEncodedValue`).
2. From `$Stub$Proxy`, the first method per name gives `(params)ret`, the
   same strings dexdump prints.
3. If the host `framework.jar` also defines `IInstalld$Stub` (ColorOS's
   does), both tables must match, else BLOCK.
4. A host jar without dex (stripped) is read from `oat/arm64/services.vdex`
   (dexlib2 reads CompactDex); failing that, BLOCK.
5. Write `host/<fp16>/host_installd.json` (compat doc §1.5 schema, plus
   `tableSha256` over the sorted lines); its BLOCK rules apply.

`InstalldAbiPatch` reads the JSON. The plan records `installdTableSha256`, so
a One UI update rebuilds the services.jar step before the next session. Cost
under 2 s. Residual risk: this is the Java proxy's view; native installd is
not probed (it would take Samsung shipping two different AIDLs in one build).

## 5. APEX payloads

Today `make-overlays.sh:151-172` dumps `/system/apex/com.android.<name>.apex`
(or `.capex` -> `original_apex`) for 19 fixed names and unzips
`apex_payload.img`; 16 exist in the ColorOS image (all erofs, about 280 MB).
`guest_prepare.sh:1092-1165` loop-mounts them over `/apex/<module>` and the
`@<version>` dirs.

`ApexPayloadExtractor`, inside the read-only mount:

1. List `*.apex`/`*.capex` under `system/apex` (and `system_ext/apex`,
   `product/apex` if present). Identify modules by `apex_manifest.pb` field 1
   (name) and 2 (version) with a tiny protobuf reader, not by file name
   (Pixel-style GSIs use `com.google.android.*` names).
2. For each module the plan lists (catalog `core.guest_apex_payloads`;
   default the 19), copy the byte range of the STORED, 4 KiB-aligned
   `apex_payload.img` with `FileChannel.transferTo`. A `.capex` is inflated
   to a temporary `.apex` first.
3. Record module, version, file, filesystem (erofs/ext4 magic), size, SHA-256
   and the `apex_pubkey` hash; write `images/<sha16>/apex/<short>_payload.img`
   (`<short>` = name without `com.android.`, as the scripts expect).
4. A module missing from the image is recorded, not an error: the host's
   module stays.

Payloads depend only on the image and survive One UI updates and fix packs.

## 6. `surfaceflinger_patched`

Today (`make-overlays.sh:101-122`): if the binary's SHA-1 starts with
`9896c0cba131`, the bytes `680200b4` (`cbz x8, +0x4c`) at file offset
`0x470bc8` become `13000014` (`b +0x4c`); otherwise nothing is built. In the
image's binary (NDK 30 `llvm-objdump`):

```
470bc0: bl  AIBinder_getExtension@plt
470bc4: ldr x8, [sp, #0x8]
470bc8: cbz x8, 0x470c14        <- becomes b 0x470c14
470bd4: bl  IQtiComposer3Client::fromBinder@plt   (only call site in the binary)
470c14: merge point (no extension client)
```

`BinaryPatcher` reads two descriptor forms from fix packs:

```json
{"id": "coloros.sf_skip_qti_composer", "input": "system/bin/surfaceflinger", "output": "surfaceflinger_patched", "mode": "0755",
 "exact": [{"inputSha256": "...", "sha1Prefix": "9896c0cba131",
            "patches": [{"offset": "0x470bc8", "old": "680200b4", "new": "13000014"}]}],
 "locator": {"kind": "aarch64_cbz_to_b_before_call", "after": "AIBinder_getExtension", "window": 8, "expectSites": 1,
             "call": "_ZN4aidl6vendor3qti8hardware7display9composer319IQtiComposer3Client10fromBinderERKN3ndk10SpAIBinderE"}}
```

`ElfLocator` (about 300 lines of Kotlin):

1. Parse `PT_LOAD` (vaddr to offset), `.dynsym`, `.dynstr`, `.rela.plt`; map
   GOT slots to symbols.
2. Find PLT stubs by decoding `adrp x16` + `ldr x17, [x16, #off]`. No fixed
   size: this binary has 24-byte BTI stubs (`.plt` 0x95f0 = 32 + 1598 x 24;
   1598 relocations).
3. The number of `bl` calls to the `call` stub must equal `expectSites`.
4. Walking back at most `window` instructions there must be a `bl` to the
   `after` stub and exactly one `cbz xN` in between that jumps past the call.
5. Rewrite it to `b` with the same target and record offset and bytes.

`exact` is used when the hash matches. The `locator` result is used only
where the catalog allows it (proposed: auto on Qualcomm hosts for guests whose
surfaceflinger imports `call`, marked experimental). Otherwise the guest runs
its stock surfaceflinger, as with `ONEOS_STOCK_SF=1`. A wrong patch is not a
safety issue: the panel stays black and the watcher returns after 150 s. The
same format serves native libraries (`libaudio*_aidl_conversion*_patched.so`).

## 7. RROs

There is no aapt2 on the phone. RROs are built on the server from
`switcher/overlays/<name>` (`make-overlays.sh:137-144`: aapt2, zipalign,
apksigner), signed with our RRO key instead of the debug key, and shipped in
fix packs. The guest takes them as today:

- `guest_prepare.sh:262-278` copies the apk to `$STAGE/oem/overlay/`, labels
  it `system_file` and binds `$STAGE/oem` over the guest's empty `/oem`.
- The guest scans `/oem/overlay` as a system partition, so a static RRO
  (`android:isStatic="true"`, `switcher/overlays/wallpapers_fold_grid/AndroidManifest.xml`)
  is preinstalled and enabled. `/oem` meets the `oem` overlayable policy and
  the guest's own idmap2d builds the idmap (`core.guest_idmap2d`). A static
  system overlay needs no target signature, which is why the debug-signed one
  works.

Instead of the aapt2 check at `make-overlays.sh:134-136` (`columnCount=4`
in `personal_card_fold_large_layout`), the pack carries a resource anchor:
the zip entry of that compiled layout in the target apk (found with `aapt2
dump resources` on the server) and its SHA-256. `RroStager` hashes that one
entry from the mounted image; a mismatch means "not applied" (`skip_warn`).

```json
{"id": "coloros.wallpapers_fold_grid", "apk": "files/rro/OneOSWallpapersFoldGrid.apk", "apkSha256": "...",
 "signerCertSha256": "...", "stageAs": "oem/overlay/OneOSWallpapersFoldGrid.apk",
 "anchors": [{"targetApk": "my_stock/priv-app/Wallpapers/Wallpapers.apk", "entry": "res/<name>.xml", "sha256": "..."}]}
```

The engine checks the apk hash and the v3 signer digest, then links the apk.
A binary XML reader that checks attribute values like aapt2 is a later option.

## 8. framework-res and other optional files

`guest_prepare.sh:339-366` binds OneLineage's optional files when present
(`framework_patched.jar`, `framework-res_patched.apk`,
`PermissionController_patched.apk`, two `libaudio*_patched.so`). ColorOS
needs none.

| File | Approach | v1 |
| --- | --- | --- |
| `framework.jar` | same `dex_jar` step, but it is on the boot class path: patching it invalidates the boot image (JIT for everything, untested) and hidden-API flags must survive | no; `advanced` only, when a guest needs it |
| `framework-res.apk` values | static RRO targeting `android` in `/oem/overlay` (§7) | via packs |
| `PermissionController.apk` | editing a signed apk changes its signer; prefer an RRO | no |
| native `.so` | `BinaryPatcher` descriptors (§6) | when a pack has them |
| features, seapp, props | stage scripts, not the engine | - |

## 9. `PatchPlan`

### 9.1 Plan

`PlanResolver` turns the active catalog (catalog §5), the facts, the user's
toggles, the `ImageRecord` and `host_installd.json` into a plan. The engine
reads only the plan.

```json
{"$id": "oneos/patch-plan/1", "type": "object",
 "required": ["schema", "planKey", "engine", "image", "host", "packs", "steps"],
 "properties": {
  "schema": {"const": "oneos.patchplan/1"},
  "planKey": {"type": "string", "description": "sha256 of the canonical JSON (sorted keys) without planKey"},
  "engine": {"type": "object", "properties": {"version": {"type": "integer"}, "smali": {"const": "3.0.10"}}},
  "image": {"type": "object", "properties": {"sha256": {}, "fs": {"enum": ["ext4", "erofs"]}, "family": {}, "fingerprint": {}}},
  "host": {"type": "object", "properties": {"fingerprint": {}, "servicesJarSha256": {}, "installdTableSha256": {}}},
  "packs": {"type": "array", "items": {"required": ["id", "version", "manifestSha256"]}},
  "steps": {"type": "array", "items": {"$ref": "#/$defs/step"}}},
 "$defs": {
  "step": {"type": "object", "required": ["id", "kind", "required"], "properties": {
    "id": {"examples": ["jar:services", "jar:oplus-services", "apex", "bin:surfaceflinger", "rro:wallpapers_fold_grid"]},
    "kind": {"enum": ["dex_jar", "apex_payloads", "binary_patch", "rro_stage"]},
    "required": {"type": "boolean", "description": "a safety entry depends on it"},
    "input": {"description": "path in the image; trailing ? = step vanishes if absent"},
    "inputSha256": {}, "output": {"description": "stage name the scripts read"},
    "patches": {"type": "array", "items": {"$ref": "#/$defs/patch"}},
    "modules": {"type": "array"}, "descriptor": {"type": "object"},
    "expectOutputSha256": {"description": "attestation from a pack (R9)"}}},
  "patch": {"type": "object", "required": ["entry", "handler", "ruleVersion", "safety", "onAnchorMissing"], "properties": {
    "entry": {"examples": ["safety.storage_guards"]}, "handler": {"examples": ["StorageGuardsPatch"]},
    "ruleVersion": {"type": "integer"}, "safety": {"type": "boolean"}, "args": {"type": "object"},
    "expect": {"additionalProperties": {"oneOf": [{"properties": {"eq": {"type": "integer"}}}, {"enum": ["all_removed", "exactly_one"]}]}},
    "files": {"description": "pack files the handler reads: name -> sha256"},
    "onAnchorMissing": {"enum": ["refuse_session", "block_image", "skip", "skip_warn"]}}}}}
```

The F8U ColorOS `jar:services` step, in `make-overlays.sh` order (shortened):

```json
{"id": "jar:services", "kind": "dex_jar", "required": true, "input": "system/framework/services.jar",
 "inputSha256": "...", "output": "services_patched.jar", "patches": [
  {"entry": "coloros.display_mode_fallback", "handler": "DisplayModeSpecsPatch", "safety": false, "onAnchorMissing": "block_image"},
  {"entry": "safety.vold_key_guard", "handler": "VoldKeysPatch", "safety": true, "expect": {"IVold.key_calls": {"eq": 5}}},
  {"entry": "samsung.skip_host_apex_services", "handler": "SkipHostApexServicesPatch", "safety": false},
  {"entry": "safety.power_guards", "handler": "PowerGuardsPatch", "safety": true},
  {"entry": "safety.storage_guards", "handler": "StorageGuardsPatch", "safety": true},
  {"entry": "safety.installd_abi_proxy", "handler": "InstalldAbiPatch", "safety": true, "args": {"mode": "proxy"}},
  {"entry": "safety.no_sysrq_crash", "handler": "SysRqPatch", "safety": true},
  {"entry": "core.guest_idmap2d", "handler": "OwnIdmapPatch", "safety": false},
  {"entry": "coloros.wait_for_service", "handler": "OplusWaitForServicePatch", "safety": false,
   "onAnchorMissing": "skip_warn", "args": {"only": ["DefragService"], "keep": ["installd", "cotaservice"]}}]}
```

`jar:oplus-services` (input `system/framework/oplus-services.jar?`) runs
`OplusWaitForServicePatch`, `OplusBootHangsPatch`, `StorageGuardsPatch`,
`SysRqPatch`, `InstalldAbiPatch {"mode": "callers"}` and
`BacklightCurvePatch` with the pack's helper smali (`make-overlays.sh:58-87`).
For a guest without that jar the step vanishes, and `guest_prepare.sh:32`
must stop requiring `oplus-services_patched.jar` (stage change: read the jar
list from `guards.json`).

### 9.2 Caching and idempotency

A step key is the SHA-256 of the step's canonical JSON (with `inputSha256`),
`engine.version`, the smali version, each patch's
`handler/ruleVersion/args/expect/files`, and `installdTableSha256` only for
`InstalldAbiPatch` in proxy mode. Outputs go to `steps/<stepKey16>/` via
`.tmp` + fsync + rename, so a step is complete or absent. Payloads are keyed
by the image alone.

| Change | Rebuilt |
| --- | --- |
| nothing | nothing: verify `build.json` hashes and activate (seconds) |
| a non-safety toggle | that jar |
| One UI update | `jar:services` only (callers mode needs no table) |
| a fix-pack rule | steps containing it |
| `engine.version` bump | dex and binary steps; payloads kept |
| new image | everything |

`build.json` lists step keys, so plans share step files by hard link. The
last two builds per image are kept.

### 9.3 Build record and activation

```json
{"schema": "oneos.build/1", "planKey": "...", "engine": 1,
 "outputs": [{"name": "services_patched.jar", "sha256": "...", "inputSha256": "...",
              "replacedDex": ["classes.dex", "classes2.dex", "classes3.dex"],
              "newClasses": {"Lcom/android/server/pm/OneOSInstalldProxy;": "classes3.dex"}}],
 "patches": [{"entry": "safety.storage_guards", "applied": true, "counts": {"IInstalld.destroyUserData": 1},
              "sites": [{"class": "Lcom/android/server/pm/Installer;", "method": "destroyUserData(Ljava/lang/String;II)V"}]},
             {"entry": "coloros.wallpapers_fold_grid", "applied": false, "reason": "anchor mismatch"}],
 "checks": [{"id": "empty-try-ranges", "ok": true}, {"id": "round-trip", "ok": true}, {"id": "guard-verifier", "ok": true},
            {"id": "attestation", "ok": null}],
 "timingsMs": {"jar:services": 181000}}
```

Activation, only when no session is up: (1) delete `guards.txt` and
`guards.json`; (2) hard-link each output to `<name>.new` and `rename(2)` it
over the stage name; (3) remove stage names the plan does not produce (e.g.
`oplus-services_patched.jar` for a Lineage guest); (4) write `guards.json`
(compat doc §2.3 schema plus `planKey`, `imageSha256`, input hashes); (5)
write `guards.txt` last in today's format (`make-overlays.sh:94-99`), then
`active.json`. `guest_prepare.sh:28-39` refuses without a matching
`guards.txt`, so an interrupted activation cannot start a guest.

## 10. Verification and refusal rules

Checks after patching:

1. **Postconditions** from each `PatchReport` against `expect`: e.g. the
   calls `guest_storage_guards.py:172-176` requires are gone; the ABI patch
   classified every method, blocked `destroyUserData`, kept or remapped
   `createAppData`.
2. **Reassembly:** each changed class, reassembled and disassembled again,
   equals its patched text.
3. **`EmptyTryRangeCheck`** (port of `check-dex.py`): no `TryBlock` with
   `codeUnitCount == 0` in a rewritten dex (run 26).
4. **`RoundTripCheck`:** every unpatched class equals the original in flags,
   supertypes, fields (defaults = absent), methods, registers, opcodes,
   references and handler types, after the §0 normalisations; class set =
   original + declared new classes.
5. **`GuardVerifier`** (compat doc §2.3): an independent scan of every jar on
   the guest's `SYSTEMSERVERCLASSPATH` plus a boot-class-path shadowing
   check. It shares no code with the patches, so a selector miss cannot hide
   a destructive call.
6. **Attestation:** if a pack lists `expectOutputSha256` for the step key, the
   hash must match. CI computes these with `patch-cli` for images and host
   tables we have (the F8U ColorOS image; tables from log bundles).

| Id | Condition | Effect | Where |
| --- | --- | --- | --- |
| R1 | a safety patch refused or a `required` step failed | build fails, nothing activated, session refused (G14) | engine |
| R2 | a non-safety patch refused | `block_image` blocks this image; `skip`/`skip_warn` = not applied | engine |
| R3 | installd table BLOCK (§4) | no plan; session refused (G2) | `HostInstalldTable` |
| R4 | `services.jar`/`oplus-services.jar` in the freshly mounted image differ from `guards.json` input hashes | refused: jars built for another image (new; under 1 s) | preflight, `guest_prepare.sh` |
| R5 | host fingerprint or `services.jar` hash differs from the plan | rebuild first | preflight |
| R6 | checks 1-5 fail | as R1 | engine |
| R7 | engine, smali or rule version differs from the active build | rebuild (asks first) | app |
| R8 | `quick` identity mismatch or `.part` present | refused; re-import or verify | preflight |
| R9 | attestation present and different | build fails ("differs from reference build") | engine |
| R10 | erofs session, missing structural safety anchor, super.img or OTA input | refused with a message (G13) | importer, `PlanResolver` |
| R11 | session running or mounts under stage `data/` (`guest_prepare.sh:7-11`) | engine does not start | `oneos_engine.sh` |

## 11. Porting table

Cplx: S < 150 Kotlin lines, M 150-400, L more. Risk = chance the port's
anchors fail on another image (patch safety risk is in the catalog).

| Today | Kotlin | Entry | Jar | Selectors | Checks kept | Cplx | Risk |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `display_mode_specs.py` | `DisplayModeSpecsPatch` | `coloros.display_mode_fallback` | services | `ConstString("Desired display mode specs from SurfaceFlinger are null")` | `OLD` block (`.line 252`, v0/v1/v3/v7/v8) once | S | High: pinned to this build |
| `guest_no_vold_keys.py` | `VoldKeysPatch` | `safety.vold_key_guard` | services | `Invokes("Landroid/os/IVold;->")` | 5 key calls (`expect`) | S | Low |
| `skip_host_apex_services.py` | `SkipHostApexServicesPatch` | `samsung.skip_host_apex_services` | services | `ClassSuffix(".../SystemServer;")` | header; `:cond_2` + `startServiceFromJar` once; uses v4/v5/v6, `:goto_2` | M | Medium: labels and free `v5` assumed |
| `guest_power_guards.py` | `PowerGuardsPatch` | `safety.power_guards` | services | `ClassSuffix` AMS, ShutdownThread, PowerManagerService, RecoverySystemService; `ConstString("sys.powerctl")`; `Invokes(".../IStorageManager;->commitChanges")` | 1 `commitChanges` (`{v1}`); 4 entry points (`.locals` >= 2); 2 `sys.powerctl`; 2 recovery methods | M | Medium: AMS `v1` pinned |
| `guest_storage_guards.py` | `StorageGuardsPatch` | `safety.storage_guards` | both | `Invokes` `IVold;->`, `IInstalld;->`, `AndroidKeyStoreMaintenance;->`, `RecoverySystem;->rebootPromptAndWipeUserData`; `ClassSuffix` UserDataPreparer, StorageManagerService, StorageStatsService | `nop` per dropped call; keystore result `const/4 0`; headers once; one wipe call; run-25 calls must be found | M | Low-Medium |
| `guest_installd_abi.py` | `InstalldAbiPatch` (proxy) | `safety.installd_abi_proxy` | services | `ClassSuffix` `IInstalld$Stub$Proxy;`, `IInstalld$Stub;`, `pm/Installer;`; `Invokes` `IInstalld$Stub;->asInterface`, `IInstalld$Stub$Proxy;-><init>` | all methods classified; `ALLOWED` + same signature -> host code, else throw before `transact`; `destroyUserData` blocked; no proxy reference left; Installer redirected; class not present before | L | Medium; reads §4 table |
| `guest_installd_abi.py --callers` | `InstalldAbiPatch` (callers) | same | oplus | the two `Invokes` | no reference left | shared | Low |
| `guest_no_sysrq_crash.py` | `SysRqPatch` | `safety.no_sysrq_crash` | both | `DefinesMethod("doSysRq(C)V")` | exact private header; `.locals` >= 1; at least one | S | Low |
| `guest_own_idmap.py` | `OwnIdmapPatch` | `core.guest_idmap2d` | services | `ClassSuffix(".../om/IdmapDaemon;")` | one class, 2 start/stop calls | S | Low |
| `oplus_wait_for_service.py` | `OplusWaitForServicePatch` | `coloros.wait_for_service` | oplus; services (`only`) | `Invokes(".../ServiceManager;->waitForService")` | `KEEP`; name = nearest `const-string` within 15 lines; null guard only in void methods | M | Medium: heuristic |
| `oplus_boot_hangs.py` | `OplusBootHangsPatch` | `coloros.boot_hangs` | oplus | `ClassSuffix` GameGpuControlPanel, AppErrorsExtImpl; `ConstString("dumpenvironment")` | exact headers, `.locals 2`; 4 dumpenvironment blocks (v0/v2) | S | Medium: build-specific |
| `samsung_backlight_curve.py` + `OneOSPanelBacklight.smali` | `BacklightCurvePatch` + `BacklightHelperCheck` | `coloros.backlight_curve` [F8U] | oplus | `ClassSuffix(".../LocalDisplayDeviceExtImpl;")` | header + `.locals 3`; not patched yet; helper clamps and 21 rising values (run 29) | M | Medium: per-panel table |
| `scripts/check-dex.py` | `EmptyTryRangeCheck` | `safety.dex_verify` | outputs | - | 0 empty ranges | S | Low |
| `scripts/host-installd-table.sh` | `HostInstalldTable` | `safety.host_installd_table` | host | `ClassSuffix` `IInstalld$Stub;`, `$Stub$Proxy;` | unique codes; `destroyUserData`; framework.jar cross-check | S | Low-Medium |
| `make-overlays.sh:21-87` (debugfs, apktool, `zip -0`) | `DexJar` ... `JarWriter` | - | - | - | `set -e` -> transactions, R1 | M | Low (prototype matched) |
| `make-overlays.sh:94-99` | `GuardManifestWriter` | `safety.guard_manifest` | - | - | `guards.txt` last | S | Low |
| `make-overlays.sh:101-122` | `BinaryPatcher`, `ElfLocator` | `coloros.sf_skip_qti_composer` | SF | - | input hash, old bytes, one site | M | Medium (locator) |
| `make-overlays.sh:124-149` | `RroStager` | `coloros.wallpapers_fold_grid` | - | - | resource anchor hash | S | Low |
| `make-overlays.sh:151-172` | `ApexPayloadExtractor` | `core.guest_apex_payloads` | - | - | manifest name; payload fs | S | Low |
| `deploy-phone.sh:66-85` | `ImageImporter`, `HoleWriter` | `core.image_import` | - | - | `file \| grep ext` -> `FsProbe` | M | Low |

## 12. Tests

- **Golden (CI):** for each image we hold, `make-overlays.sh` and `patch-cli`
  with the same host table; apktool decodes must `diff -r` clean (as the
  prototype did) and `PatchReport.counts` must match the Python stderr.
- **Selector superset:** each port on the full apktool tree and on its
  selected tree gives identical output.
- **Negative:** mutated smali (changed `.line 252`, a third `sys.powerctl`,
  no `isQuotaSupported`, `OneOSInstalldProxy` already present) must give
  `PatchRefused` wherever Python exits.
- **Device determinism:** the F8U build of the CI image must hash like CI's,
  or R9 needs phone-made hashes.
- **Fault injection:** kill the engine during a rewrite and during
  activation; preflight must refuse and the next start must clean `.tmp`.

## 13. Open risks and decisions

1. **Phone speed is unmeasured.** dexlib2 in a root `app_process` may run
   JIT-only. Measure on the F8U; if slow, ship a baseline profile or rewrite
   dex files in parallel.
2. **Cross-VM determinism** (HotSpot vs ART) of `DexPool` is assumed; if it
   fails, attestation hashes must come from phones.
3. **Count policy:** exact counts for tested images, `all_removed` +
   `GuardVerifier` for drop-all guards elsewhere, `exactly_one` always. Exact
   counts everywhere would refuse every untested guest, including testers'
   S26 Ultras with other GSIs, until a pack arrives.
4. **Build-pinned anchors:** `display_mode_specs.py` (`.line 252`),
   `skip_host_apex_services.py` (`:cond_2`, `:goto_2`, free `v5`) and AMS
   `invoke-interface {v1}` will break on other builds. Later: instruction-level
   matching with dexlib2 `MethodAnalyzer` (needs the guest boot class path).
5. **Scripts are ColorOS-only:** `guest_prepare.sh:32` needs
   `oplus-services_patched.jar`, `:84` mounts ext4, and `lineage`/`aosp` have
   only the safety set. The engine handles these; the stage scripts must follow.
6. **Root `app_process`** under KernelSU, Magisk and APatch is untested; the
   in-app fallback (§1.2) is plan B.
7. **The installd table is Java-level** (§4); native installd is not probed.
8. **The SF locator** has seen one binary; only `exact` is tested.
9. **No odex** for patched jars (JIT), as today; a `framework.jar` patch would
   be far worse (§8).
10. **Space:** 9.1 GiB allocated for ColorOS + about 0.4 GB outputs + the
    staged `/data`; nearly full phones hit G9.
