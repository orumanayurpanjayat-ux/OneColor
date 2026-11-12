package com.fahadali.oneos.ui

import android.os.Bundle
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import com.fahadali.oneos.R
import com.fahadali.oneos.core.model.GuestModuleBundle
import com.fahadali.oneos.core.model.GuestModuleChangeStatus
import com.fahadali.oneos.core.model.GuestModuleControls
import com.fahadali.oneos.core.root.RootScript
import com.fahadali.oneos.core.root.RootShell
import com.fahadali.oneos.session.GuestRuntimeStore
import com.fahadali.oneos.session.RootVerbs
import dev.oneuiproject.oneui.layout.ToolbarLayout
import java.io.File
import java.util.concurrent.Executors

/** Native module list and fixed import action from the supplied Figma design. */
class GuestRuntimeActivity : EdgeToEdgeActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var image: String
    private lateinit var state: GuestRuntimeStore.State
    private var busy = false
    private var loaded = false
    private var importFramework = false
    private var pendingModuleUri: Uri? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPickedModule(uri)
    }
    private fun importPickedModule(uri: Uri) {
        if (busy) {
            pendingModuleUri = uri
            return
        }
        importModule { com.fahadali.oneos.shared.ModuleUpdates.copyDocument(this, uri) }
    }

    private fun importPendingModuleIfReady() {
        val uri = pendingModuleUri ?: return
        if (busy || !loaded || state.error != null) return
        pendingModuleUri = null
        if (!state.ready) {
            showError(getString(R.string.guest_modules_needs_update))
            return
        }
        importPickedModule(uri)
    }
    private fun importModule(source: () -> File) {
            busy = true; render()
            io.execute {
                val result = runCatching {
                    val zip = source()
                    val bundle = File.createTempFile("guest-module-", ".bundle", cacheDir)
                    try {
                        val info = GuestModuleBundle.write(zip, bundle)
                        val md = java.security.MessageDigest.getInstance("SHA-256")
                        bundle.inputStream().use { input ->
                            val buffer = ByteArray(65536)
                            while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
                        }
                        val sum = md.digest().joinToString("") { "%02x".format(it) }
                        val reply = RootVerbs.run(applicationContext, RootScript.Verb.GUEST_MODULE,
                            listOf(image, info.id, bundle.length().toString(), sum),
                            RootShell.Feed { out -> bundle.inputStream().use { it.copyTo(out) } })
                        check(reply.ok) { reply.problem ?: "Module import failed" }
                        GuestRuntimeStore.read(applicationContext, image) to info.name
                    } finally { zip.delete(); bundle.delete() }
                }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    busy = false
                    result.onSuccess { (next, name) -> state = next; Toast.makeText(this, "$name staged for the next boot", Toast.LENGTH_LONG).show() }
                        .onFailure { showError(it.message ?: "Could not import module") }
                    render()
                }
            }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        image = intent.getStringExtra("image") ?: run { finish(); return }
        state = GuestRuntimeStore.State(image)
        importFramework = intent.getBooleanExtra("import_framework", false)
        setContentView(R.layout.activity_guest_modules)
        val footer = findViewById<View>(R.id.guest_modules_footer)
        val toolbar = findViewById<ToolbarLayout>(R.id.guest_modules_toolbar)
        toolbar.setTitle(getString(R.string.guest_modules_title))
        toolbar.showNavigationButtonAsBack = true
        toolbar.setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        toolbar.setAdaptiveMarginProvider(dev.oneuiproject.oneui.widget.AdaptiveCoordinatorLayout.MARGIN_PROVIDER_ZERO)
        toolbar.setEdgeInsetHorizontal(0f)
        applyEdgeToEdgeInsets(toolbar) { bottom -> footer.updateBottomMarginForNavigationBar(0, bottom) }
        toolbar.findViewById<dev.oneuiproject.oneui.widget.RoundedFrameLayout>(dev.oneuiproject.oneui.design.R.id.tbl_main_content).apply {
            roundedCorners = 0; fillHorizontalPadding = false
        }
        toolbar.findViewById<View>(dev.oneuiproject.oneui.design.R.id.tbl_bottom_corners).visibility = View.GONE
        val scroll = findViewById<NestedScrollView>(R.id.guest_modules_scroll)
        scroll.seslSetFadingEdgeEnabled(true)
        scroll.seslSetFadingEdgeColor(getColor(R.color.preboot_page))
        scroll.seslSetFadingEdgeWindowBottomAlignment(true)
        scroll.seslSetAllowTopFadingEdgeWithoutEdgeToEdge(true)
        footer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val navigation = (footer.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
            val space = footer.height + navigation + dp(24)
            scroll.setPadding(0, 0, 0, space)
            scroll.seslForceBottomFadingEdgeClamped(space)
        }
        findViewById<Button>(R.id.guest_modules_import).setOnClickListener {
            picker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
        }
        render()
    }
    override fun onResume() {
        super.onResume()
        refreshModules()
    }

    private fun refreshModules() {
        if (!::state.isInitialized || busy) return
        busy = true; render()
        GuestRuntimeStore.refresh(this, image) { next ->
            if (isDestroyed) return@refresh
            busy = false; loaded = true
            state = if (next.error == null) next else state.copy(error = next.error)
            render()
            if (importFramework && state.error == null && state.ready) {
                importFramework = false
                picker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
            } else importPendingModuleIfReady()
        }
    }
    override fun onDestroy() { io.shutdown(); super.onDestroy() }
    private fun render() {
        if (!::state.isInitialized) return
        val list = findViewById<LinearLayout>(R.id.guest_modules_list)
        list.removeAllViews()
        when {
            !loaded -> empty(list, getString(R.string.guest_modules_reading), null)
            state.error != null -> empty(list, getString(R.string.guest_modules_load_error), state.error, retry = true)
            state.modules.isEmpty() -> empty(list, getString(R.string.guest_modules_empty), getString(
                if (state.ready) R.string.guest_modules_empty_summary else R.string.guest_modules_needs_update,
            ))
            else -> state.modules.forEach { module ->
                val options = moduleOptions(module)
                com.fahadali.oneos.shared.ModuleCards.add(list, module.name, module.description,
                    module.enabled, GuestModuleControls.canManage(state.ready, busy, state.error != null), getColor(R.color.preboot_card),
                    version = module.version.ifBlank { module.versionCode.takeIf { it > 0 }?.toString().orEmpty() }, author = module.author,
                    status = when (GuestModuleControls.changeStatus(module.pending, module.removing)) {
                        GuestModuleChangeStatus.CURRENT -> null
                        GuestModuleChangeStatus.PENDING_RESTART -> getString(R.string.guest_modules_status_pending)
                        GuestModuleChangeStatus.REMOVAL_PENDING_RESTART -> getString(R.string.guest_modules_status_removal)
                    },
                    toggleEnabled = GuestModuleControls.canToggle(
                        ready = state.ready,
                        busy = busy,
                        removing = module.removing,
                        frameworkSelected = module.framework == null || module.id == state.selected,
                        hasError = state.error != null,
                    ),
                    manageTitle = getString(R.string.guest_modules_actions),
                    manageSummary = resources.getQuantityString(R.plurals.guest_modules_actions_count, options.size, options.size),
                    options = options) { value ->
                    busy = true; render()
                    GuestRuntimeStore.setModule(this, image, module.id, value) { next ->
                        if (isDestroyed) return@setModule
                        busy = false
                        state = next
                        if (next.error != null) showError(next.error)
                        render()
                    }
                }
            }
        }
        findViewById<Button>(R.id.guest_modules_import).apply {
            isEnabled = GuestModuleControls.canManage(state.ready, busy, state.error != null) && loaded
            setText(if (busy) R.string.guest_modules_working else R.string.guest_modules_import)
            contentDescription = getString(if (busy) R.string.guest_modules_working else R.string.guest_modules_import)
        }
    }
    private fun moduleOptions(module: GuestRuntimeStore.Module): List<Pair<String, () -> Unit>> = buildList {
        if ("webui" in module.capabilities) add("WebUI · Open inside guest" to { showError("Open this module's WebUI from OneOS inside the guest.") })
        if ("action" in module.capabilities) add("Action · Run inside guest" to { showError("Run this module's action from OneOS inside the guest.") })
        if (module.updateUrl.isNotBlank() && !module.pending && !module.removing) add("Check for updates" to {
            busy=true; render()
            com.fahadali.oneos.shared.ModuleUpdates.check(this@GuestRuntimeActivity,module.name,module.id,module.versionCode,module.updateUrl) { zip ->
                busy=false; render(); if(zip != null) importModule { zip }
            }
        })
        add((if(module.removing) "Cancel uninstall" else "Uninstall ${module.name}") to {
            AlertDialog.Builder(this@GuestRuntimeActivity).setTitle(if(module.removing) "Cancel uninstall?" else "Uninstall ${module.name}?")
                .setMessage("This change applies at the next guest restart. Module cleanup scripts run only inside the guest.")
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok) { _,_ ->
                    busy=true; render()
                    GuestRuntimeStore.changeModule(this@GuestRuntimeActivity,image,module.id,if(module.removing) "restore" else "remove") { next ->
                        busy=false
                        state=next
                        if(next.error != null) showError(next.error)
                        render()
                    }
                }.show()
        })
    }
    private fun empty(list: LinearLayout, title: String, line: String?, retry: Boolean = false) {
        val card = PrebootCards.section(list, gap = false)
        PrebootCards.row(card, title, line)
        if (retry) PrebootCards.row(
            card,
            getString(if (busy) R.string.guest_modules_retrying else R.string.guest_modules_retry),
            getString(R.string.guest_modules_retry_summary),
            chevron = true,
            enabled = !busy,
        ) { refreshModules() }
    }
    private fun showError(message: String) {
        AlertDialog.Builder(this).setTitle("Guest modules").setMessage(message).setPositiveButton(android.R.string.ok, null).show()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}
