package io.nekohasekai.sagernet.ui

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.needReload

/** Настройки service control (Wi-Fi / расписание / блокировка экрана) для asteriskd. */
class ServiceControlActivity : ThemedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_service_control)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.service_control)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settings, Prefs()).commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class Prefs : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.configurationStore
            setPreferencesFromResource(R.xml.service_control_preferences, rootKey)

            // start and stop for the same event are mutually exclusive: the daemon rejects the config otherwise
            val pairs = listOf(
                "scWifi_connectStart" to "scWifi_connectStop",
                "scWifi_disconnectStart" to "scWifi_disconnectStop",
                "scLockStart" to "scLockStop",
                "scUnlockStart" to "scUnlockStop",
            )
            for ((a, b) in pairs) for ((x, y) in listOf(a to b, b to a)) {
                findPreference<SwitchPreference>(x)!!.setOnPreferenceChangeListener { _, v ->
                    if (v == true) findPreference<SwitchPreference>(y)!!.isChecked = false
                    true
                }
            }
            for (k in listOf("scStartCron", "scStopCron")) findPreference<Preference>(k)!!.apply {
                setOnPreferenceChangeListener { _, v ->
                    val ok = v.toString().isBlank() || v.toString().trim().split(Regex("\\s+")).size == 5
                    if (!ok) Toast.makeText(requireContext(), R.string.sc_cron_sum, Toast.LENGTH_LONG).show()
                    ok
                }
            }
            if (Build.VERSION.SDK_INT < 33) findPreference<Preference>("scKeyguardCat")!!.isVisible = false
        }
    }
}
