package com.fahadali.oneos.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestModuleControlsTest {
    @Test
    fun removalStatusTakesPriorityOverOtherPendingChanges() {
        assertEquals(
            GuestModuleChangeStatus.REMOVAL_PENDING_RESTART,
            GuestModuleControls.changeStatus(pending = true, removing = true),
        )
    }

    @Test
    fun statusDistinguishesCurrentAndStagedModules() {
        assertEquals(
            GuestModuleChangeStatus.CURRENT,
            GuestModuleControls.changeStatus(pending = false, removing = false),
        )
        assertEquals(
            GuestModuleChangeStatus.PENDING_RESTART,
            GuestModuleControls.changeStatus(pending = true, removing = false),
        )
    }

    @Test
    fun managementWaitsForReadyIdleErrorFreeRuntime() {
        assertTrue(GuestModuleControls.canManage(ready = true, busy = false))
        assertFalse(GuestModuleControls.canManage(ready = false, busy = false))
        assertFalse(GuestModuleControls.canManage(ready = true, busy = true))
        assertFalse(GuestModuleControls.canManage(ready = true, busy = false, hasError = true))
    }

    @Test
    fun togglesRequireSelectedFrameworkAndCannotRestorePendingRemoval() {
        assertTrue(
            GuestModuleControls.canToggle(
                ready = true,
                busy = false,
                removing = false,
                frameworkSelected = true,
            ),
        )
        assertFalse(
            GuestModuleControls.canToggle(
                ready = true,
                busy = false,
                removing = true,
                frameworkSelected = true,
            ),
        )
        assertFalse(
            GuestModuleControls.canToggle(
                ready = true,
                busy = false,
                removing = false,
                frameworkSelected = false,
            ),
        )
    }
}
