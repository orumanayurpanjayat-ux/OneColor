package com.fahadali.oneos.core.model

/** User-visible module changes take effect at the next guest restart. */
enum class GuestModuleChangeStatus {
    CURRENT,
    PENDING_RESTART,
    REMOVAL_PENDING_RESTART,
}

/** Pure rules shared by the host and in-guest module managers. */
object GuestModuleControls {
    fun changeStatus(pending: Boolean, removing: Boolean): GuestModuleChangeStatus = when {
        removing -> GuestModuleChangeStatus.REMOVAL_PENDING_RESTART
        pending -> GuestModuleChangeStatus.PENDING_RESTART
        else -> GuestModuleChangeStatus.CURRENT
    }

    fun canManage(ready: Boolean, busy: Boolean, hasError: Boolean = false): Boolean =
        ready && !busy && !hasError

    fun canToggle(
        ready: Boolean,
        busy: Boolean,
        removing: Boolean,
        frameworkSelected: Boolean,
        hasError: Boolean = false,
    ): Boolean = canManage(ready, busy, hasError) && !removing && frameworkSelected
}
