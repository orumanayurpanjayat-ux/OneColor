package com.fahadali.oneos.guest

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.NestedScrollView
import com.fahadali.oneos.core.model.GuestModuleBundle
import com.fahadali.oneos.core.model.GuestModuleChangeStatus
import com.fahadali.oneos.core.model.GuestModuleControls
import com.fahadali.oneos.shared.ModuleCards
import com.fahadali.oneos.shared.NativeCards
import dev.oneuiproject.oneui.layout.ToolbarLayout
import dev.oneuiproject.oneui.utils.applyEdgeToEdge
import java.io.File

/** Same module cards/import layout as pre-boot; changes are staged for the next guest boot. */
class GuestModulesActivity : AppCompatActivity() {
    private var state = GuestRuntime.State()
    private var busy = false
    private var loaded = false
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) importModule { com.fahadali.oneos.shared.ModuleUpdates.copyDocument(this,uri) }
    }
    private fun importModule(source: () -> File) {
            busy = true; render()
            GuestRuntime.work({
                val zip = source()
                val bundle = File.createTempFile("guest-module-", ".bundle", cacheDir)
                try {
                    val info = GuestModuleBundle.write(zip, bundle)
                    GuestRuntime.control("import", info.id, bundle.absolutePath)
                    GuestRuntime.read(applicationContext)
                } finally { zip.delete(); bundle.delete() }
            }) { next -> finishChange(next, "Module staged for the next guest boot") }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); applyEdgeToEdge(); setContentView(R.layout.activity_guest_modules)
        val toolbar = findViewById<ToolbarLayout>(R.id.guest_modules_toolbar)
        toolbar.setTitle("Manage guest modules"); toolbar.showNavigationButtonAsBack = true
        toolbar.setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        toolbar.setAdaptiveMarginProvider(dev.oneuiproject.oneui.widget.AdaptiveCoordinatorLayout.MARGIN_PROVIDER_ZERO)
        toolbar.setEdgeInsetHorizontal(0f)
        val footer = findViewById<View>(R.id.guest_modules_footer)
        toolbar.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            (footer.layoutParams as ViewGroup.MarginLayoutParams).apply { bottomMargin = bars.bottom; footer.layoutParams = this }
            insets
        }
        toolbar.findViewById<dev.oneuiproject.oneui.widget.RoundedFrameLayout>(dev.oneuiproject.oneui.design.R.id.tbl_main_content).apply { roundedCorners = 0; fillHorizontalPadding = false }
        toolbar.findViewById<View>(dev.oneuiproject.oneui.design.R.id.tbl_bottom_corners).visibility = View.GONE
        val scroll = findViewById<NestedScrollView>(R.id.guest_modules_scroll)
        if (GuestName.read()?.contains("Infinity", true) != true) {
            scroll.seslSetFadingEdgeEnabled(true); scroll.seslSetFadingEdgeColor(getColor(R.color.preboot_page))
        }
        footer.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->
            val n = (footer.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
            scroll.setPadding(0,0,0,footer.height+n+dp(24))
        }
        findViewById<Button>(R.id.guest_modules_import).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Import guest module")
                .setMessage("The module will be staged for the next guest boot. It will not run during this session.")
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton("Choose ZIP") { _,_ ->
                    picker.launch(arrayOf("application/zip","application/octet-stream","application/x-zip-compressed"))
                }.show()
        }
        render()
    }
    override fun onResume() { super.onResume(); refreshModules() }
    private fun refreshModules() {
        if (busy) return
        val previous = state
        busy = true
        render()
        GuestRuntime.refresh(this) { next ->
            if (!isDestroyed) {
                state = if (next.error == null) next else previous.copy(error = next.error)
                loaded = true
                busy = false
                render()
            }
        }
    }
    private fun finishChange(next: GuestRuntime.State, message: String) {
        if (isDestroyed) return
        busy=false
        state = if (next.error == null) next else state.copy(error = next.error)
        if (next.error == null) Toast.makeText(this,message,Toast.LENGTH_LONG).show()
        else AlertDialog.Builder(this).setTitle("Guest modules").setMessage(next.error).setPositiveButton(android.R.string.ok,null).show()
        render()
    }
    private fun render() {
        val list=findViewById<LinearLayout>(R.id.guest_modules_list); list.removeAllViews()
        when {
            !loaded -> NativeCards.row(
                NativeCards.section(list, null, getColor(R.color.preboot_card)),
                getString(R.string.guest_modules_reading),
                getString(R.string.guest_modules_retry_summary),
            )
            state.error != null -> {
                val errorCard = NativeCards.section(list, null, getColor(R.color.preboot_card))
                NativeCards.row(errorCard, getString(R.string.guest_modules_load_error), state.error)
                NativeCards.row(
                    errorCard,
                    getString(if (busy) R.string.guest_modules_retrying else R.string.guest_modules_retry),
                    getString(R.string.guest_modules_retry_summary),
                    click = { refreshModules() },
                ).isEnabled = !busy
            }
            else -> {
                if (!state.root) NativeCards.row(
                    NativeCards.section(list, null, getColor(R.color.preboot_card)),
                    getString(R.string.guest_modules_root_off),
                    getString(R.string.guest_modules_root_off_summary),
                )
                if (state.modules.isEmpty()) NativeCards.row(
                    NativeCards.section(list, null, getColor(R.color.preboot_card)),
                    getString(R.string.guest_modules_empty),
                    getString(R.string.guest_modules_empty_summary),
                ) else for (module in state.modules) {
                    val options = moduleOptions(module)
                    ModuleCards.add(list,module.name,module.description,
                        module.enabled,GuestModuleControls.canManage(state.root,busy,state.error != null),getColor(R.color.preboot_card),
                        version = module.version.ifBlank { module.versionCode.takeIf { it > 0 }?.toString().orEmpty() }, author = module.author,
                        status = when (GuestModuleControls.changeStatus(module.pending, module.removing)) {
                            GuestModuleChangeStatus.CURRENT -> null
                            GuestModuleChangeStatus.PENDING_RESTART -> getString(R.string.guest_modules_status_pending)
                            GuestModuleChangeStatus.REMOVAL_PENDING_RESTART -> getString(R.string.guest_modules_status_removal)
                        },
                        toggleEnabled = GuestModuleControls.canToggle(
                            ready = state.root,
                            busy = busy,
                            removing = module.removing,
                            frameworkSelected = !module.framework || module.id == state.selected,
                            hasError = state.error != null,
                        ),
                        manageTitle = getString(R.string.guest_modules_actions),
                        manageSummary = resources.getQuantityString(R.plurals.guest_modules_actions_count, options.size, options.size),
                        options = options) { enabled ->
                        busy=true; render()
                        GuestRuntime.work({ GuestRuntime.control("toggle",module.id,if(enabled) "on" else "off"); GuestRuntime.read(applicationContext) }) {
                            finishChange(it,"Change saved for the next guest boot")
                        }
                    }
                }
            }
        }
        if (GuestModuleControls.canManage(state.root, busy, state.error != null)) {
            NativeCards.row(NativeCards.section(list,null,getColor(R.color.preboot_card)),"Restart guest","Apply pending module changes without returning to One UI") { GuestRestart.confirm(this) }.isEnabled=!busy
        }
        findViewById<Button>(R.id.guest_modules_import).isEnabled=loaded && GuestModuleControls.canManage(state.root, busy, state.error != null)
    }
    private fun moduleOptions(module: GuestRuntime.Module): List<Pair<String, () -> Unit>> = buildList {
        if ("webui" in module.capabilities && !module.pending && !module.removing) add("Open WebUI" to {
            startActivity(android.content.Intent(this@GuestModulesActivity,ModuleWebActivity::class.java).putExtra("module",module.id))
        })
        if ("action" in module.capabilities && !module.pending && !module.removing) add("Run action" to {
            busy=true; render()
            GuestRuntime.work({
                val result=GuestRuntime.control("action",module.id)
                runOnUiThread { if(!isDestroyed) AlertDialog.Builder(this@GuestModulesActivity).setTitle(module.name)
                    .setMessage(result.takeLast(32768).ifBlank { "Action completed" }).setPositiveButton(android.R.string.ok,null).show() }
                GuestRuntime.read(applicationContext)
            }) { finishChange(it,"Action finished") }
        })
        if (module.updateUrl.isNotBlank() && !module.pending && !module.removing) add("Check for updates" to {
            busy=true; render()
            com.fahadali.oneos.shared.ModuleUpdates.check(this@GuestModulesActivity,module.name,module.id,module.versionCode,module.updateUrl) { zip ->
                busy=false; render(); if(zip != null) importModule { zip }
            }
        })
        add((if(module.removing) "Cancel uninstall" else "Uninstall ${module.name}") to {
            AlertDialog.Builder(this@GuestModulesActivity).setTitle(if(module.removing) "Cancel uninstall?" else "Uninstall ${module.name}?")
                .setMessage("This change applies at the next guest restart. The active session keeps its current module files.")
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok) { _,_ ->
                    busy=true; render()
                    GuestRuntime.work({ GuestRuntime.control(if(module.removing) "restore" else "remove",module.id); GuestRuntime.read(applicationContext) }) {
                        finishChange(it,"Change saved for the next guest restart")
                    }
                }.show()
        })
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
}
