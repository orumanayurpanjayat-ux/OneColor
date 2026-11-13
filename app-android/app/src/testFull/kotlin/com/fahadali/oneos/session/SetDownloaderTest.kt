package com.fahadali.oneos.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SetDownloaderTest {
    @Test fun plk110AssetsCanUseTheImageSpecificOneColorRelease() {
        val hash = "a".repeat(64)
        assertEquals(
            "https://github.com/orumanayurpanjayat-ux/OneColor/releases/download/plk110-coloros17-r1/$hash",
            SetDownloader.url("gh:orumanayurpanjayat-ux/OneColor:plk110-coloros17-r1", hash),
        )
    }

    @Test fun permissionControllerSourceIsPinnedToTheCheckedApk() {
        assertEquals(
            "https://raw.githubusercontent.com/WeWaffles/OneLineage/10cd368dc4c67f3ec7e9c885e0474233cde8edcd/assets/PermissionController_patched.apk",
            SetDownloader.url(SetDownloader.PERMISSION_PATCH_SOURCE, SetDownloader.PERMISSION_PATCH_SHA256),
        )
    }

    @Test fun directUrlsCannotBeSuppliedByAnArbitraryPackageSource() {
        assertThrows(IllegalArgumentException::class.java) {
            SetDownloader.url("url:https://example.com/other.apk", "a".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SetDownloader.url(SetDownloader.PERMISSION_PATCH_SOURCE, "a".repeat(64))
        }
    }
}
