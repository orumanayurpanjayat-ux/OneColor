package com.fahadali.oneos.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.fragment.app.Fragment
import com.fahadali.oneos.R

/**
 * Home. Each edition draws its own ([EditionHome], src/full and src/probe):
 * OneOS shows one status and one button (the system, Last time, Advanced,
 * and the footer button; About and How OneOS works in the menu); OneOS Probe
 * keeps its own HomeFragment in src/probe with res/xml/home.xml.
 */
class MainActivity : ToolbarActivity() {
    override val screenTitle: CharSequence get() = getString(R.string.app_name)
    override val showBack: Boolean get() = intent.getBooleanExtra("system_detail", false)
    override val layoutRes: Int get() = EditionHome.layoutRes

    override fun createFragment(): Fragment = EditionHome.createFragment()

    /** Until Before you start is acknowledged, every launch goes there first. */
    override fun redirect(): Intent? =
        if (BeforeYouStart.isAcknowledged(this)) EditionHome.redirect(this) else EditionHome.onboarding(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        setExpandedSubtitle(getString(R.string.home_subtitle))
        EditionHome.created(this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean = EditionHome.createMenu(this, menu) || super.onCreateOptionsMenu(menu)

    override fun onOptionsItemSelected(item: MenuItem): Boolean = EditionHome.menuItem(this, item) || super.onOptionsItemSelected(item)
}
