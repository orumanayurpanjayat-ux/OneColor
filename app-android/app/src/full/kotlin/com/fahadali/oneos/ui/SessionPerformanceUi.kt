package com.fahadali.oneos.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.fahadali.oneos.R
import com.fahadali.oneos.core.model.SessionPerformanceMode
import com.fahadali.oneos.session.SessionPrefs

/** Shared settings row and mode picker for the session CPU/thermal policy. */
internal object SessionPerformanceUi {
    fun summary(context: Context): String = context.getString(
        when (SessionPrefs.performanceMode(context)) {
            SessionPerformanceMode.BALANCED -> R.string.advanced_performance_balanced_summary
            SessionPerformanceMode.SMOOTHER -> R.string.advanced_performance_smoother_summary
            SessionPerformanceMode.MAXIMUM -> R.string.advanced_performance_maximum_summary
        },
    )

    fun show(context: Context, onChanged: () -> Unit) {
        val modes = SessionPerformanceMode.entries
        val selected = SessionPrefs.performanceMode(context)
        AlertDialog.Builder(context)
            .setTitle(R.string.advanced_performance_title)
            .setMessage(R.string.advanced_performance_dialog_summary)
            .setSingleChoiceItems(
                modes.map { context.getString(label(it)) }.toTypedArray(),
                modes.indexOf(selected),
            ) { dialog, index ->
                val mode = modes.getOrNull(index) ?: return@setSingleChoiceItems
                if (mode == SessionPerformanceMode.MAXIMUM) {
                    AlertDialog.Builder(context)
                        .setTitle(R.string.performance_max_confirm_title)
                        .setMessage(R.string.performance_max_confirm_message)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.performance_max_confirm_action) { _, _ ->
                            SessionPrefs.setPerformanceMode(context, mode)
                            onChanged()
                            dialog.dismiss()
                        }
                        .show()
                } else {
                    SessionPrefs.setPerformanceMode(context, mode)
                    onChanged()
                    dialog.dismiss()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun label(mode: SessionPerformanceMode): Int = when (mode) {
        SessionPerformanceMode.BALANCED -> R.string.advanced_performance_balanced
        SessionPerformanceMode.SMOOTHER -> R.string.advanced_performance_smoother
        SessionPerformanceMode.MAXIMUM -> R.string.advanced_performance_maximum
    }
}
