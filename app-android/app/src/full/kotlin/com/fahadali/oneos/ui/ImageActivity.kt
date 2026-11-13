package com.fahadali.oneos.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.LinearLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.fahadali.oneos.R
import com.fahadali.oneos.core.device.FactProbe
import com.fahadali.oneos.core.model.ImageIdentifier
import com.fahadali.oneos.core.model.ImageIdentity
import com.fahadali.oneos.core.model.ImageState
import com.fahadali.oneos.core.model.Images
import com.fahadali.oneos.core.model.Level
import com.fahadali.oneos.core.model.PhoneProfile
import com.fahadali.oneos.core.model.Plain
import com.fahadali.oneos.core.model.Profiles
import com.fahadali.oneos.core.model.Units
import com.fahadali.oneos.core.root.RootProbe
import com.fahadali.oneos.core.root.RootScript
import com.fahadali.oneos.session.ImageCheckJob
import com.fahadali.oneos.session.SessionPrefs
import com.fahadali.oneos.session.StageCheck
import java.util.concurrent.Executors
import dev.oneuiproject.oneui.R as OuiIcons

/**
 * Load a custom GSI file (Advanced; Choose a system's last row): Pick the
 * file (the system file picker; no storage permission), Check the file
 * (SHA-256 of the whole .img or .img.gz, with progress), and reuse the system already on
 * this phone when the stage holds a system.raw of one this phone takes. Both
 * say which system it is (ImageIdentifier) in plain words (Plain); a file
 * this phone does not take is a BLOCK naming the ones it does. The systems
 * this phone takes are listed last. From For the OneOS team it is "File
 * details and checks": the same page with Details open (file names, sizes,
 * labels, the raw findings, what the checks said).
 */
class ImageActivity : ToolbarActivity() {
    private val team: Boolean get() = intent.getBooleanExtra(EXTRA_TEAM, false)

    override val screenTitle: CharSequence get() = getString(if (team) R.string.image_title_team else R.string.image_title)

    override fun createFragment(): Fragment = ImageFragment()

    lateinit var pick: ActivityResultLauncher<Unit>

    override fun onCreate(savedInstanceState: Bundle?) {
        pick = registerForActivityResult(OpenSystemImage()) { uri -> if (uri != null) picked(uri) }
        super.onCreate(savedInstanceState)
    }

    private fun picked(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        var name: String? = null
        var size: Long? = null
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    size = if (c.isNull(1)) null else c.getLong(1)
                }
            }
        }
        SessionPrefs.setPicked(this, uri, name, size)
        SessionPrefs.setSource(this, SessionPrefs.Source.GZ)
        (supportFragmentManager.findFragmentById(R.id.fragment_container) as? ImageFragment)?.redraw()
    }

    /** ACTION_OPEN_DOCUMENT for .img or .img.gz (providers label disk images inconsistently). */
    private class OpenSystemImage : ActivityResultContract<Unit, Uri?>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/gzip", "application/x-gzip", "application/x-android-disk-image", "application/octet-stream", "*/*"))

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
    }

    companion object {
        /** Opened from For the OneOS team: titled "File details and checks", Details open. */
        const val EXTRA_TEAM = "com.fahadali.oneos.extra.TEAM"
    }
}

class ImageFragment : CardsFragment() {
    private var unsubscribe: (() -> Unit)? = null
    private var unsubscribeCheck: (() -> Unit)? = null
    private val io = Executors.newSingleThreadExecutor()
    private var profile: PhoneProfile? = null
    private var profileRead = false

    private val team: Boolean get() = activity?.intent?.getBooleanExtra(ImageActivity.EXTRA_TEAM, false) == true

    override val detailsOpenAtStart: Boolean get() = team

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = requireContext().applicationContext
        io.execute {
            val f = runCatching { FactProbe(app).collect(RootProbe(app).signals()) }.getOrNull()
            activity?.runOnUiThread {
                profile = f?.let { Profiles.resolve(it).profile }
                profileRead = true
                redraw()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        unsubscribe = ImageCheckJob.observe { redraw() }
        unsubscribeCheck = StageCheck.observe { redraw() }
    }

    override fun onStop() {
        unsubscribe?.invoke()
        unsubscribeCheck?.invoke()
        unsubscribe = null
        unsubscribeCheck = null
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun build(column: LinearLayout) {
        val ctx = column.context
        if (!profileRead) {
            CardRows.text(Cards.section(column, title = null), getString(R.string.report_reading))
            return
        }
        val p = profile
        val images = p?.images().orEmpty()
        if (p == null || images.isEmpty()) {
            val none = Cards.section(column, getString(R.string.image_no_profile_title), null, Level.BLOCK)
            CardRows.text(none, getString(R.string.image_no_profile_summary))
            return
        }

        // Your file: pick it, check it, use it (or forget it).
        val source = SessionPrefs.source(ctx)
        val picked = SessionPrefs.picked(ctx)
        val job = ImageCheckJob.state
        val file = Cards.section(column, getString(R.string.image_cat_file), rows = true)
        Cards.row(file, OuiIcons.drawable.ic_oui_folder_open, getString(R.string.image_pick_title), getString(R.string.image_pick_summary)) {
            (activity as? ImageActivity)?.pick?.launch(Unit)
        }
        var id: ImageIdentity? = null
        if (picked != null) {
            val pid = ImageIdentifier.identify(picked.check, p)
            id = pid
            val size = picked.bytes?.let { Units.bytes(it) } ?: getString(R.string.image_size_unknown)
            Cards.row(
                file, OuiIcons.drawable.ic_oui_file_type_zip,
                picked.name ?: getString(R.string.image_picked_unnamed),
                if (source == SessionPrefs.Source.GZ) size else getString(R.string.image_not_used, size),
            )
            identity(file, pid, p, onPhone = false)
            val busy = job.busy && job.uri == picked.uri
            if (busy) {
                val total = job.total ?: picked.bytes ?: pid.image?.let { image ->
                    if (picked.check.hashedBytes == image.rawBytes) image.rawBytes else image.gzBytes
                }
                CardRows.tint(
                    Cards.row(
                        file, OuiIcons.drawable.ic_oui_refresh,
                        if (total != null) getString(R.string.image_checking, (job.read * 100 / total.coerceAtLeast(1)).toInt())
                        else getString(R.string.image_checking_unknown),
                        if (total != null) getString(R.string.image_checking_bytes, Units.bytes(job.read), Units.bytes(total))
                        else getString(R.string.image_checking_unknown_bytes, Units.bytes(job.read)),
                    ),
                    Level.INFO,
                )
                Cards.row(file, OuiIcons.drawable.ic_oui_close, getString(R.string.tests_stop_title), getString(R.string.image_stop_summary)) { ImageCheckJob.cancel() }
            } else if (pid.state == ImageState.NOT_CHECKED || pid.state == ImageState.UNCOMPRESSED || pid.state == ImageState.DAMAGED ||
                (pid.state == ImageState.UNKNOWN && pid.finding.reason == ImageIdentifier.R_SIZE_UNKNOWN)
            ) {
                Cards.row(file, OuiIcons.drawable.ic_oui_security_outline, getString(R.string.image_check_title), getString(R.string.image_check_summary)) {
                    ImageCheckJob.start(ctx, picked.uri, picked.bytes)
                }
            } else if (pid.state == ImageState.VERIFIED && source != SessionPrefs.Source.GZ) {
                Cards.row(file, OuiIcons.drawable.ic_oui_checkbox_checked_outline, getString(R.string.image_use_gz_title), getString(R.string.image_use_gz_summary)) {
                    SessionPrefs.setSource(ctx, SessionPrefs.Source.GZ)
                    redraw()
                }
            }
            Cards.row(file, OuiIcons.drawable.ic_oui_delete_outline, getString(R.string.image_forget_title), getString(R.string.image_forget_summary)) {
                SessionPrefs.clearPicked(ctx)
                redraw()
            }
        }

        // Already on this phone (owner's request): which system it is, reused when setup keeps it.
        val onPhone = Cards.section(column, getString(R.string.image_cat_on_phone), rows = true)
        val check = StageCheck.state
        val st = check.result?.status
        when {
            check.busy -> CardRows.tint(Cards.row(onPhone, OuiIcons.drawable.ic_oui_refresh, getString(R.string.image_looking), null), Level.INFO)
            st == null -> Cards.row(
                onPhone, OuiIcons.drawable.ic_oui_search, getString(R.string.image_look_title),
                getString(if (check.message != null) R.string.image_look_failed else R.string.image_look_summary),
            ) { confirmLook() }
            st.rawSize == null -> CardRows.status(onPhone, Level.INFO, getString(R.string.image_on_phone_none), null)
            else -> {
                val sid = ImageIdentifier.identifyStaged(st.stagedRaw, p)
                identity(onPhone, sid, p, onPhone = true)
                // It can be reused only when setup keeps the stage: not when the stage is set aside
                // (another system's data, an older system's, leftovers OneOS did not prepare).
                val keepable = RootScript.BeginMode.KEEP in st.beginModes(sid.image?.id)
                if (sid.finding.level == Level.OK && !keepable) {
                    Cards.row(onPhone, 0, getString(R.string.image_on_phone_aside), getString(R.string.image_on_phone_aside_summary))
                } else if (sid.finding.level == Level.OK) {
                    if (source == SessionPrefs.Source.ON_PHONE) {
                        CardRows.status(onPhone, Level.OK, getString(R.string.image_on_phone_chosen), getString(R.string.image_on_phone_chosen_summary))
                    } else {
                        Cards.row(onPhone, OuiIcons.drawable.ic_oui_checkbox_checked_outline, getString(R.string.image_use_on_phone_title), getString(R.string.image_use_on_phone_summary)) {
                            SessionPrefs.setSource(ctx, SessionPrefs.Source.ON_PHONE)
                            redraw()
                        }
                    }
                } else {
                    Cards.row(onPhone, 0, getString(R.string.image_on_phone_replaced), null)
                }
            }
        }
        if (!check.busy && st != null) Cards.row(onPhone, OuiIcons.drawable.ic_oui_refresh, getString(R.string.image_look_again), null) { confirmLook() }

        // The systems this phone takes: which file to download.
        val systems = Cards.section(column, getString(R.string.image_takes_title, p.label), rows = true)
        CardRows.text(systems, getString(R.string.image_takes_summary), R.style.OneOS_Text_Caption, inList = true)
        for ((i, image) in images.withIndex()) {
            val notes = buildList {
                add(getString(R.string.image_choice_sizes, Units.bytes(image.gzBytes), Units.bytes(image.rawBytes)))
                add(getString(if (p.choice(image.id)?.tested == true) R.string.systems_chip_tested else R.string.systems_chip_untested))
                if (image.googleServices) add(getString(R.string.systems_chip_google))
            }
            Cards.row(systems, GLYPHS[i % GLYPHS.size], Images.systemName(image, p), notes.joinToString(" · "))
        }

        // Details: the files' names and labels, the raw findings, what the checks said (the OneOS team's).
        val more = Cards.section(column, title = null, rows = true)
        details(more, getString(R.string.image_details_summary)) { card ->
            for (image in images) {
                Cards.row(
                    card, 0, getString(R.string.image_choice_title, image.shortName),
                    getString(R.string.image_details_file, image.gzName, Units.bytes(image.gzBytes), Units.bytes(image.rawBytes), image.label),
                )
            }
            CardRows.text(card, getString(R.string.image_details_check), R.style.OneOS_Text_Caption, inList = true)
            id?.let { CardRows.finding(card, it.finding) }
            picked?.sha256?.let { Cards.row(card, 0, getString(R.string.image_details_sha), it) }
            job.message?.takeIf { job.uri == picked?.uri }?.let { Cards.row(card, 0, getString(R.string.setup_details_result), it) }
            if (st?.rawSize != null) CardRows.finding(card, ImageIdentifier.identifyStaged(st.stagedRaw, p).finding)
            (check.step ?: check.message)?.let { Cards.row(card, 0, getString(R.string.image_details_look), it) }
        }
    }

    /** Which system the file (or the system on this phone) is, in plain words; the finding itself is under Details. */
    private fun identity(card: LinearLayout, id: ImageIdentity, p: PhoneProfile, onPhone: Boolean) {
        val f = id.finding
        if (f.level == Level.OK) {
            val system = Images.systemName(id.image, p)
            CardRows.status(card, Level.OK, getString(if (onPhone) R.string.image_on_phone_ok else R.string.image_file_ok, system), null)
            return
        }
        val m = Plain.of(f, id.image, p)
        // Unchecked images stop nothing: setup checks the whole file before writing it.
        val level = if (f.reason == ImageIdentifier.R_NOT_CHECKED || f.reason == ImageIdentifier.R_UNCOMPRESSED) Level.INFO else f.level
        CardRows.status(card, level, m.title, m.line)
    }

    private fun confirmLook() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.image_look_dialog_title)
            .setMessage(R.string.image_look_dialog_message)
            .setPositiveButton(R.string.image_look_run) { _, _ -> StageCheck.start(requireContext(), StageCheck.Mode.STAGE_ONLY) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private companion object {
        /** Device glyphs by the system's place in the phone's list (as Choose a system: never by brand). */
        val GLYPHS = intArrayOf(R.drawable.ic_oneos_system_0, R.drawable.ic_oneos_system_1, R.drawable.ic_oneos_system_2)
    }
}
