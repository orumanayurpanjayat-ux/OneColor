package com.fahadali.oneos.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.fahadali.oneos.R

/**
 * The probe edition (OneOS Probe) keeps its small Home: no switching, so
 * only the shared rows of res/xml/home.xml (src/probe): Check this phone,
 * Run tests, Before you start, About. No footer, no menu.
 */
internal object EditionHome {
    fun onboarding(a: MainActivity) = Intent(a, BeforeYouStartActivity::class.java)

    fun redirect(a: MainActivity): Intent? = null

    val layoutRes: Int get() = R.layout.activity_toolbar

    fun createFragment(): Fragment = HomeFragment()

    fun created(a: MainActivity) = Unit

    fun createMenu(a: MainActivity, menu: Menu): Boolean = false

    fun menuItem(a: MainActivity, item: MenuItem): Boolean = false

    fun bind(f: PreferenceFragmentCompat) = Unit

    fun resume(f: PreferenceFragmentCompat) = Unit
}

/**
 * Probe-only preference screen. Keep this class in src/probe: the full
 * edition has a different home.xml and must not be linted against Probe keys.
 */
class HomeFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.home, rootKey)
        EditionHome.bind(this)
        findPreference<Preference>("check_phone")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), DeviceReportActivity::class.java))
            true
        }
        findPreference<Preference>("run_tests")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), RunTestsActivity::class.java))
            true
        }
        findPreference<Preference>("before_you_start")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), BeforeYouStartActivity::class.java))
            true
        }
        findPreference<Preference>("about")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
            true
        }
    }

    override fun onResume() {
        super.onResume()
        EditionHome.resume(this)
    }
}
