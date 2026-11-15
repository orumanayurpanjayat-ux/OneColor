Warning: truncated output (original token count: 7797)
Total output lines: 423

package com.fahadali.oneos.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PackageSetsTest {
    private val n6 = PackageSets.N6_F8U
    private val pixel = PackageSets.PIXEL_F8U
    private val xin = PackageSets.X9U_IN_SULTRA

    private val x9uWavePayloads = setOf("SystemUI_wave.dex", "OneOSWaveRepack.dex")

    /** sha256sum of out/overlays_n6g_r3, out/overlays_x9u_r51, out/overlays_x9u_in_r53 (2026-11-03) and out/overlays_pixel_f8u_r2 without the omitted files. */
    private fun shas(set: PackageSet) = Bundles.text("packages/${set.id}.sha256")

    private fun guards(set: PackageSet) = Bundles.text("packages/${set.id}.guards.txt")

    @Test fun fold8StableInheritsEverySharedPayloadAndGuard() {
        val custom = PackageSets.N6_GLOBAL_F8
        assertEquals(n6.files + PackageFile("GlobalVerifiedAddons.tar", FileRole.GUEST_FIX), custom.files)
        assertEquals(guards(n6), guards(custom))
        val sharedHashes = ShaList.parse(shas(n6)).entries
        val customHashes = ShaList.parse(shas(custom)).entries
        assertEquals(emptySet<String>(), sharedHashes.keys.filter {
            sharedHashes[it] != customHashes[it]
        }.toSet())
        assertEquals(setOf("GlobalVerifiedAddons.tar"), customHashes.keys - sharedHashes.keys)
    }

    /** The 15 APEX payloads every set carries. */
    private val commonPayloads = listOf(
        "art", "i18n", "runtime", "sdkext", "crashrecovery", "extservices", "permission", "wifi",
        "appsearch", "mediaprovider", "adservices", "rkpd", "healthfitness", "ondevicepersonalization",
        "configinfrastructure",
    ).map { "${it}_payload.img" }

    @Test
    fun setsAsData() {
        assertEquals(36, n6.files.size) // 0.5.05: + oplus-framework_patched.jar; 0.4.98: + ColorPanoramicSystemUI.apk; 0.4.63: + oplus_odm_ai.tar; 0.4.47: + KeKeThemeSpace_patched.apk; 0.4.37: + framework_patched.jar, libcamera_client_patched.so, samcam.tar; 0.4.38 (global): + cellbroadcast, Fold8SystemUI.apk, Fold8AngleRelay.dex; 0.4.39: + telephony-common_patched.jar
        assertEquals(43, pixel.files.size) // 0.4.59: OneOSFoldCutout.apk, yogi-services_patched.jar, libbootanimation_preapex_patched.so, OneOSPixelLauncher.apk, OneOSPixelDoze.apk; 0.4.24: framework_patched.jar (One UI's camera transaction codes); 0.4.45: telephony-common_patched.jar; 0.4.47: libcamera_client_patched.so, samcam.tar
        assertEquals(Images.ID_FIND_N6, n6.imageId)
        assertEquals(Images.ID_PIXEL_YOGI, pixel.imageId)
        assertEquals("out/overlays_n6g_r21", n6.sourceDir)
        assertEquals("out/overlays_x9u_in_r75", xin.sourceDir)
        assertEquals(Images.ID_FIND_X9U_IN, xin.imageId)
        assertEquals("out/overlays_pixel_f8u_r19", pixel.sourceDir)
        assertEquals(
            listOf(PackageSets.N6_GLOBAL_F8, n6, PackageSets.X9U_IN_1105_SULTRA, pixel, PackageSets.PIXEL_SULTRA, PackageSets.PIXEL_F8, PackageSets.PIXEL9_SULTRA,
                PackageSets.INFINITYX, PackageSets.HYPER_F8U, PackageSets.NOTHING_F8U, PackageSets.OXYGEN16, PackageSets.ORIGINOS, PackageSets.LINEAGE),
            PackageSets.ALL,
        )
        // Infinity-X (0.4.27): both guard jars, the camera codes (Java and native), the guest vold, the
        // AOSP signal poll, the IMS backoff, the guest app, the guards and 21 payloads, GmsCore's among them.
        // Not its cellbroadcast module (0.4.28): One UI's stays, as tethering does.
        val ifx = PackageSets.INFINITYX
        assertEquals(Images.ID_INFINITYX, ifx.imageId)
        assertEquals("out/overlays_infinityx_r16", ifx.sourceDir)
        // 0.4.55 (r4): telephony-common_patched.jar (the SMS centre) and samcam.tar (the S26 Ultra's camera).
        assertEquals(36, ifx.files.size)
        // 0.4.33: the status bar's corner padding, as on the Pixel images.
        assertTrue(ifx.file("OneOSPixelStatusBar.apk")?.role == FileRole.RRO)
        assertEquals(21, ifx.files.count { it.role == FileRole.APEX_PAYLOAD })
        assertEquals(listOf(PackageSets.TETHERING, PackageSets.CELLBROADCAST), ifx.omitted)
        assertTrue(ifx.file(PackageSets.CELLBROADCAST) == null)
        assertEquals(
            listOf("services_patched.jar", "oplus-services_patched.jar", "framework_patched.jar", "vold_patched",
                "service.incremental_patched.so", "libcamera_client_patched.so", "telephony-common_patched.jar"),
            ifx.files.filter { it.role in PackageFile.GUARDED }.map { it.name },
        )
        assertTrue(ifx.file(PackageSets.GMS_PAYLOAD) != null && ifx.hasReturnApp)
        // LineageOS 24 (0.5.06): Infinity-X's shape without OPPO's jar, samcam.tar and the Google payload (its Google
        // apps are One UI's, laid over the guest by guest_google.sh at session time, keyed on this set's id), with the
        // fold and tablet overlays and the apex requirement list. 20 payloads; neither tethering nor cellbroadcast.
        val lineage = PackageSets.LINEAGE
        assertEquals("lineage", lineage.id)
        assertEquals(Images.ID_LINEAGE, lineage.imageId)
        assertEquals("out/overlays_lineage_r5", lineage.sourceDir)
        assertEquals(38, lineage.files.size) // 0.5.07 r3: + libbootanimation_preapex_patched.so; 0.5.06 r2: + OneOSPixelDoze.apk (AOD)
        assertEquals(20, lineage.files.count { it.role == FileRole.APEX_PAYLOAD })
        assertEquals(listOf(PackageSets.TETHERING, PackageSets.CELLBROADCAST), lineage.omitted)
        assertTrue(lineage.file(PackageSets.GMS_PAYLOAD) == null && lineage.file("oplus-services_patched.jar") == null && lineage.file("samcam.tar") == null)
        assertEquals(
            listOf("services_patched.jar", "framework_patched.jar", "vold_patched", "service.incremental_patched.so",
                "libcamera_client_patched.so", "telephony-common_patched.jar"),
            lineage.files.filter { it.role in PackageFile.GUARDED }.map { it.name },
        )
        assertTrue(lineage.hasReturnApp && lineage.file(PackageSets.GUEST_APP)?.role == FileRole.GUEST_APP)
        // HyperOS r29: the refreshed Samsung-facing guards, MIUI display-feature stub, face payload,
        // guest fingerprint/lock fixes, and the existing camera, telephony and APEX payloads.
        val hyper = PackageSets.HYPER_F8U
        assertEquals(Images.ID_HYPEROS_LHASA, hyper.imageId)
        assertEquals("out/overlays_hyperos_r29", hyper.sourceDir)
        assertEquals(41, hyper.files.size) // r29 adds miui-framework_patched.jar and facehal_payload.tar.
        assertEquals(listOf("socket_exec", "libaudiohal_aidl_patched.so", "MiWallpaper_patched.apk", "samcam.tar", "samcam_bar.tar", "facehal_payload.tar"),
            hyper.files.filter { it.role == FileRole.GUEST_FIX }.map { it.name })
        assertEquals(
            listOf("services_patched.jar", "miui-services_patched.jar", "miui-framework_patched.jar", "apprecovery.proinstaller_patched.jar", "framework_patched.jar",
                "miui-wifi-service_patched.jar", "miui-telephony-common_patched.jar", "vold_patched", "service.incremental_patched.so",
                "libcamera_client_patched.so", "libsurfaceflinger_patched.so", "telephony-common_patched.jar"),
            hyper.files.filter { it.role in PackageFile.GUARDED }.map { it.name },
        )
        assertTrue(hyper.hasReturnApp && "vold_patched" in hyper.names)
        // Nothing OS (0.4.33), the smallest set: Nothing ships no vendor framework jar, so services.jar and
        // framework.jar carry every guard. The signal-poll injection RRO goes in /oem, after the image's own
        // TelephonyResCommon_Sys.apk in this guest's overlay order (run 10).
        val nothing = PackageSets.NOTHING_F8U
        assertEquals(Images.ID_NOTHING_METROID, nothing.imageId)
        assertEquals("out/overlays_nothing_r35", nothing.sourceDir)
        assertEquals(48, nothing.files.size) // 0.4.55: samcam_bar.tar, the S26 Ultra's camera for the phones
        // r12: Nothing's SystemUI with its quick settings at the screen's own density on the inner panel;
        // r13: its launcher with the drawer's category boxes per row from the width; r17: the ringtone kits
        // the boot animation on both panels and the ringtone generator's device check; 0.4.35: One UI's camera.
        assertEquals(listOf("SystemUI_patched.apk", "NothingLauncher3_patched.apk", "nothing_customringtone.tar",
                "nothing_product_media.tar", "libbootanimation_preapex_patched.so", "NothingGenerateRingtoneService_patched.apk",
                "samcam.tar", "samcam_bar.tar"),
            nothing.files.filter { it.role == FileRole.GUEST_FIX }.map { it.name })
        assertEquals(18, nothing.files.count { it.role == FileRole.APEX_PAYLOAD })
        assertEquals(listOf(PackageSets.TETHERING), nothing.omitted)
        // r2: the guest vold, so the guest registers "gvld" and the return needs no reboot.
        assertEquals(
            listOf("services_patched.jar", "framework_patched.jar", "vold_patched", "service.incremental_patched.so",
                "libcamera_client_patched.so"),
            nothing.files.filter { it.role in PackageFile.GUARDED }.map { it.name },
        )
        // The first non-ColorOS set to carry the SurfaceFlinger patch (the QTI composer skip).
        assertEquals(listOf("surfaceflinger_patched"), nothing.files.filter { it.role == FileRole.SURFACEFLINGER }.map { it.name })
        // Its Google services are hidden by folder, not staged as a payload ([ImageRef.gmsHideable]).
        assertTrue(nothing.file(PackageSets.GMS_PAYLOAD) == null)
        assertTrue(nothing.hasReturnApp && nothing.file(PackageSets.CELLBROADCAST) != null)
        // 0.4.38: no Fold8 set of its own for the Find N6 global image (n6-f8 patched the CN build's
        // SystemUI); the Z Fold8 takes the Folds' n6-f8u.
        assertTrue(PackageSets.ALL.none { it.id == "n6-f8" })
        // The Pixel Fold sets (pull request #1, the F8U's since 0.4.21): the Pixel files, the patched
        // SystemUI, the angle relay and each one's unfold RRO. The bar sets (0.4.59 r12) have only
        // double tap to sleep, from each image's own SystemUI and Pixel Launcher.
        for (bar in listOf(PackageSets.PIXEL_SULTRA, PackageSets.PIXEL9_SULTRA)) {
            assertEquals(listOf("samcam.tar", "OneOSPixelSystemUI.apk", "OneOSPixelLauncher.apk"),
                bar.files.filter { it.role == FileRole.GUEST_FIX }.map { it.name })
        }
        val hinge = listOf("Fold8PixelSystemUI.apk", "Fold8AngleRelay.dex")
        // 0.4.59 (r11): and the boot animation on every panel; r12 the launcher with double tap to sleep.
        val bootanim = listOf("libbootanimation_preapex_patched.so", "OneOSPixelLauncher.apk")
        assertEquals(listOf("samcam.tar") + hinge + "OneOSPixelUnfold.apk" + bootanim, pixel.files.filter { it.role == FileRole.GUEST_FIX }.map { it.name })
        assertEquals(listOf("samcam.tar") + hinge + "Fold8PixelUnfold.apk" + bootanim, PackageSets.PIXEL_F8.files.filter { it.role == FileRole.GUEST_FIX }.map { it.name })
        // 0.4.59: the F8U's also has Nothing OS's per-panel cutout RRO (the cover's camera hole); both
        // Fold sets (r11) the device state policy without tent mode.
        val tent = PackageFile("yogi-services_patched.jar", FileRole.FRAMEWORK_JAR)
        assertEquals(PackageSets.PIXEL_SULTRA.files.filter { it.role != FileRole.GUEST_FIX } + tent + PackageFile("OneOSFoldCutout.apk", FileRole.RRO),
            pixel.files.filter { it.role != FileRole.GUEST_FIX })
        assertEquals(PackageSets.PIXEL_SULTRA.files.filter { it.role != FileRole.GUEST_FIX } + tent, PackageSets.PIXEL_F8.files.filter { it.role != FileRole.GUEST_FIX })
        // The Pixel 9 Pro XL's …1797 tokens truncated…os-telephony.sha256"])
        assertEquals(FileRole.RRO, roles["OneOSPhoneInjection.apk"])
        assertEquals(FileRole.RRO, roles["OneOSPixelStatusBar.apk"])
        assertEquals(FileRole.GUEST_APP, roles[PackageSets.GUEST_APP])
        assertEquals(listOf("services_patched.jar", "framework_patched.jar", "telephony-common_patched.jar", "libcamera_client_patched.so", "vold_patched", "service.incremental_patched.so"), pixel.files.filter { it.role in PackageFile.GUARDED }.map { it.name })
        // 0.4.45 (r3): Samsung IMS on every Pixel set, so their guards name the carve-out (guest_prepare.sh stages
        // Samsung IMS only then) and the SMS service centre goes out as an international number.
        for (s in listOf(pixel, PackageSets.PIXEL_F8, PackageSets.PIXEL_SULTRA, PackageSets.PIXEL9_SULTRA)) {
            val head = guards(s).lines().filter { it.startsWith("#") }.joinToString(" ")
            for (w in listOf("secims", "wipesecrets", "ksuappid", "gatekeeper", "pdb", "apexd", "appid15000")) {
                assertTrue("${s.id}: $w", Regex("\\b$w\\b").containsMatchIn(head))
            }
            assertEquals(s.id, FileRole.GUARD_JAR, s.file("telephony-common_patched.jar")?.role)
        }
        assertEquals("0755", pixel.file("vold_patched")!!.mode)
        assertEquals("0644", pixel.file("service.incremental_patched.so")!!.mode)
        // 22 payloads: every set's 15, Google's cellbroadcast/bt/nfcservices, the image's own
        // telephonycore/conscrypt/profiling (no platform framework-telecom.jar) and GmsCore.
        val payloads = pixel.files.filter { it.role == FileRole.APEX_PAYLOAD }.map { it.name }
        assertEquals(22, payloads.size)
        assertEquals(
            (commonPayloads + listOf("cellbroadcast", "bt", "nfcservices", "telephonycore", "conscrypt", "profiling", "gmssystem").map { "${it}_payload.img" }).toSet(),
            payloads.toSet(),
        )
        assertTrue(PackageSets.GMS_PAYLOAD in pixel.names)
        // Never mounted (the host keeps its tethering module): left out.
        assertTrue(PackageSets.TETHERING !in pixel.names)
        // Nothing of ColorOS: no oplus jars, no patched surfaceflinger (Google's is not patched), no fold grid.
        for (n in listOf("oplus-services_patched.jar", "oplus-telephony-common_patched.jar", "ims-ext-common_patched.jar", "surfaceflinger_patched", PackageSets.WALLPAPER_GRID_RRO)) {
            assertTrue(n, n !in pixel.names)
        }
        assertTrue(pixel.hasReturnApp)
        // The free-space gate's number is the files' total (out/overlays_pixel_f8u_r4, 2026-11-04).
        assertEquals(708_239_363L, pixel.approxBytes)
        // Each image presents its Google services exactly when its sets stage GmsCore.
        for (p in Profiles.BUILT_IN) {
            for (choice in p.choices) {
                val image = Images.byId(choice.imageId)!!
                val set = PackageSets.byId(choice.packageSetId)!!
                assertEquals("${p.id} ${set.id}", image.presentsGms, set.file(PackageSets.GMS_PAYLOAD) != null)
            }
        }
    }

    @Test
    fun shaListsNameExactlyTheSetsFiles() {
        for (s in PackageSets.ALL) {
            assertEquals(s.id, emptyList<String>(), s.check(shas(s)).sorted())
        }
        // The Pixel list and a ColorOS one are not interchangeable.
        val pixelOnN6 = pixel.check(shas(n6))
        assertTrue(pixelOnN6.contains("pixel-f8u: oneos-telephony.jar missing"))
        assertTrue(pixelOnN6.contains("pixel-f8u: gmssystem_payload.img missing"))
        assertTrue(pixelOnN6.contains("pixel-f8u: oplus-services_patched.jar is not in this set"))
        assertTrue(pixelOnN6.contains("pixel-f8u: OneOSWallpapersFoldGrid.apk is not in this set"))
        val n6OnPixel = n6.check(shas(pixel))
        assertTrue(n6OnPixel.contains("n6-f8u: surfaceflinger_patched missing"))
        assertTrue(n6OnPixel.contains("n6-f8u: oneos-telephony.jar is not in this set"))
        assertTrue(n6OnPixel.contains("n6-f8u: OneOSPixelStatusBar.apk is not in this set"))
        assertTrue(xin.check(shas(pixel)).contains("x9u-in-sultra: OneOSPixelStatusBar.apk is not in this set"))
        val pixelTether = shas(pixel) + "${"a".repeat(64)}  tethering_payload.img\n"
        assertEquals(listOf("pixel-f8u: tethering_payload.img is left out of this set"), pixel.check(pixelTether))
        // Swapped lists fail: the fold grid is not in the S Ultra set.
        assertTrue(xin.check(shas(n6)).contains("x9u-in-sultra: OneOSWallpapersFoldGrid.apk is left out of this set"))
        assertTrue(n6.check(shas(xin)).contains("n6-f8u: OneOSWallpapersFoldGrid.apk missing"))

        val extra = shas(xin) + "${"a".repeat(64)}  OneOSPixelStatusBar.apk\n"
        // The published wave payloads are pinned; only the unrelated extra file is rejected.
        assertEquals(
            listOf("x9u-in-sultra: OneOSPixelStatusBar.apk is not in this set"),
            xin.check(extra),
        )
        val tether = shas(xin) + "${"a".repeat(64)}  tethering_payload.img\n"
        assertEquals(listOf("x9u-in-sultra: tethering_payload.img is left out of this set"), xin.check(tether).sorted())
        val dup = shas(xin) + shas(xin).lines().first() + "\n"
        assertEquals(listOf("${shas(xin).lines().first().substringAfter("  ")} listed twice"), xin.check(dup))
    }

    @Test
    fun guardsMatchTheGuardJars() {
        for (s in PackageSets.ALL) assertEquals(s.id, emptyList<String>(), s.checkGuards(guards(s), shas(s)))
        // N6 guards with the X9U jars: never mix builds. The guard jars, (0.4.37) the N6 set's
        // camera files and (0.4.38) the guest vold's two binaries: the Find N6 global build has the
        // IN build's vold and service.incremental.so, not the CN builds'; (0.4.39) its telephony-common_patched.jar.
        val mixed = xin.checkGuards(guards(n6), shas(xin))
        assertTrue(mixed.isNotEmpty())
        assertTrue(mixed.all { it.startsWith("guards.txt: ") })
        assertTrue(mixed.any { it.contains("the set has") })
        // A guards.txt that lists something else, or misses a jar.
        assertTrue(xin.checkGuards(guards(xin) + "${"b".repeat(64)}  OneOSGuest.apk\n", shas(xin)).single().contains("not a guard jar"))
        assertTrue(xin.checkGuards(guards(xin).lines().filterNot { it.endsWith("oplus-services_patched.jar") }.joinToString("\n"), shas(xin)).single().contains("missing"))
        // The ColorOS sets carry the guards Google services need too (rebuilt 2026-10-25).
        for (s in listOf(n6, xin)) for (t in PackageSets.GMS_GUARDS) assertTrue("${s.id} $t", Regex("\\b$t\\b").containsMatchIn(guards(s)))
    }

    /** 0.3.7: the Pixel set's guards.txt pins services_patched.jar and both gvld binaries; comment lines are fine. */
    @Test
    fun pixelGuardsPinTheGuestVoldToo() {
        val g = guards(pixel)
        val sums = shas(pixel)
        assertEquals(setOf("services_patched.jar", "framework_patched.jar", "telephony-common_patched.jar", "libcamera_client_patched.so", "vold_patched", "service.incremental_patched.so"), ShaList.parse(g).entries.keys)
        assertTrue(g.lines().first().startsWith("#"))
        assertEquals(emptyList<String>(), pixel.checkGuards(g, sums))
        // Only one of the two gvld binaries (a half-built set): refused, as the session scripts do.
        for (n in listOf("vold_patched", "service.incremental_patched.so")) {
            val half = g.lines().filterNot { it.endsWith("  $n") }.joinToString("\n")
            assertEquals(n, listOf("guards.txt: $n missing"), pixel.checkGuards(half, sums))
        }
        // A vold_patched that is not the set's.
        val wrong = g.replace(ShaList.parse(g).entries.getValue("vold_patched"), "c".repeat(64))
        assertTrue(pixel.checkGuards(wrong, sums).single().startsWith("guards.txt: vold_patched is cccccccccccc..., the set has "))
        // Something else listed.
        assertTrue(pixel.checkGuards(g + "${"b".repeat(64)}  OneOSGuest.apk\n", sums).single().contains("OneOSGuest.apk is not a guard jar of pixel-f8u"))
        // Without the comment lines naming the Google services guards: each one is missing (a switch would be refused).
        val bare = g.lines().filterNot { it.startsWith("#") }.joinToString("\n")
        assertEquals(PackageSets.GMS_GUARDS.map { "guards.txt does not name the $it guard Google services need" }, pixel.checkGuards(bare, sums))
        assertEquals(1, pixel.checkGuards(g.replace("appid15000", "appid"), sums).size)
        // Never mixed: a ColorOS set's guards do not vouch for the Pixel files, nor the other way round.
        val colorOsOnPixel = pixel.checkGuards(guards(n6), sums)
        assertTrue(colorOsOnPixel.any { it.startsWith("guards.txt: vold_patched is ") })
        assertTrue(colorOsOnPixel.any { it.startsWith("guards.txt: service.incremental_patched.so is ") })
        assertTrue(colorOsOnPixel.contains("guards.txt: oplus-services_patched.jar is not a guard jar of pixel-f8u"))
        assertTrue(colorOsOnPixel.any { it.startsWith("guards.txt: services_patched.jar is ") })
        val pixelOnN6 = n6.checkGuards(g, shas(n6))
        assertTrue(pixelOnN6.any { it.startsWith("guards.txt: vold_patched is ") })
        assertTrue(pixelOnN6.any { it.startsWith("guards.txt: service.incremental_patched.so is ") })
        assertTrue(pixelOnN6.contains("guards.txt: oplus-services_patched.jar missing"))
        assertTrue(xin.checkGuards(g, shas(xin)).isNotEmpty())
    }

    @Test
    fun shaListParsing() {
        val h = "0123456789abcdef".repeat(4)
        val ok = ShaList.parse("# comment\n$h  a.jar\n${h.uppercase()} *b_payload.img\n\n")
        assertEquals(mapOf("a.jar" to h, "b_payload.img" to h), ok.entries)
        assertTrue(ok.problems.isEmpty())
        assertEquals("$h  a.jar\n$h  b_payload.img\n", ShaList.render(ok.entries))

        for (bad in listOf("$h a.jar b", "$h  ../x", "$h  dir/x", "$h  .hidden", "${h.dropLast(1)}  a.jar", "$h\ta.jar")) {
            assertFalse(bad, ShaList.parse(bad).problems.isEmpty())
            assertTrue(bad, ShaList.parse(bad).entries.isEmpty())
        }
    }

    /**
     * Checked-in manifests name published files. The two X9U wave payloads
     * are deliberately pending: without published release assets and hashes,
     * the package-set check must continue to refuse the X9U set.
     */
    @Test
    fun checkedInPackageListsMatchTheSets() {
        val dir = System.getProperty("oneos.packagesDir")?.let(::File)
        assumeTrue("app-android/packages not there yet", dir != null && dir.isDirectory)
        for (s in PackageSets.ALL) {
            val f = File(dir, "${s.id}.sha256")
            assumeTrue("${f.name} not there yet", f.isFile)
            assertEquals(s.id, emptyList<String>(), s.check(f.readText()).sorted())
        }
        // The retired IN set's list is gone with its image (0.5.10; the CN set's in 0.3.7).
        assertEquals(PackageSets.ALL.map { "${it.id}.sha256" }.toSet(), dir!!.list()!!.filter { it.endsWith(".sha256") }.toSet())
    }

    @Test
    fun spaceToStage() {
        // AA17: about 27.1 GB for the S Ultra (0.4.51: the X9U IN image + its files + 11 GiB).
        val need = SpaceRule.toStage(Images.FIND_X9U_IN, xin)
        assertEquals("27.2 GB", Units.bytes(need)) // 0.4.97: + the wallpaper AOD SystemUI
        // The Pixel image is smaller: its own raw size and files.
        val pixelNeed = SpaceRule.toStage(Images.PIXEL_YOGI, pixel)
        assertEquals(6_559_223_808L + 708_239_363L + 11L * SpaceRule.GIB, pixelNeed)
        assertEquals("19.1 GB", Units.bytes(pixelNeed))
        assertEquals(11L * SpaceRule.GIB, SpaceRule.LAUNCH_BYTES)
        assertEquals(Images.FIND_X9U_IN.rawBytes + SpaceRule.LAUNCH_BYTES, SpaceRule.toStage(Images.FIND_X9U_IN, null))
    }
}

