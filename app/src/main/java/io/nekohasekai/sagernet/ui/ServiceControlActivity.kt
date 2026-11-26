package io.nekohasekai.sagernet.ui

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.bg.root.ServiceControl
import io.nekohasekai.sagernet.bg.root.ServiceCronParseResult
import io.nekohasekai.sagernet.bg.root.parseServiceCron
import io.nekohasekai.sagernet.bg.root.validServiceControlSsids
import io.nekohasekai.sagernet.bg.root.validServiceControlBssids

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

            fun changed() { needReload() }
            fun reject(message: Int): Boolean {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                return false
            }
            // Keep STOP/START for the same native event mutually exclusive.
            val pairs = listOf(
                "scWifi_connectStart" to "scWifi_connectStop",
                "scWifi_disconnectStart" to "scWifi_disconnectStop",
                "scLockStart" to "scLockStop",
                "scUnlockStart" to "scUnlockStop",
            )
            for ((a, b) in pairs) for ((x, y) in listOf(a to b, b to a)) {
                findPreference<SwitchPreference>(x)!!.setOnPreferenceChangeListener { _, v ->
                    if (v == true) findPreference<SwitchPreference>(y)!!.isChecked = false
                    changed()
                    true
                }
            }
            for (k in listOf("scEnabled", "scSchedule", "scWifi", "scKeyguard", "scMonitorOnly")) {
                findPreference<SwitchPreference>(k)!!.setOnPreferenceChangeListener { _, _ ->
                    changed()
                    true
                }
            }
            for (k in listOf("scStartCron", "scStopCron")) findPreference<EditTextPreference>(k)!!.apply {
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                    preference.text?.takeIf { it.isNotBlank() } ?: getString(R.string.sc_cron_sum)
                }
                setOnPreferenceChangeListener { _, v ->
                    if (v.toString().isNotBlank() && parseServiceCron(v.toString()) !is ServiceCronParseResult.Valid)
                        reject(R.string.sc_invalid_cron)
                    else { changed(); true }
                }
            }
            for (rule in ServiceControl.WIFI_RULES) {
                val prefix = "scWifi_$rule"
                findPreference<SwitchPreference>("${prefix}_any")!!.setOnPreferenceChangeListener { _, _ ->
                    changed()
                    true
                }
                for ((suffix, validate) in listOf("ssids" to ::validServiceControlSsids, "bssids" to ::validServiceControlBssids)) {
                    findPreference<EditTextPreference>("${prefix}_$suffix")!!.setOnPreferenceChangeListener { _, v ->
                        if (!validate(v.toString())) reject(if (suffix == "ssids") R.string.sc_invalid_ssid else R.string.sc_invalid_bssid)
                        else { changed(); true }
                    }
                }
            }
            if (Build.VERSION.SDK_INT < 33) findPreference<Preference>("scKeyguardCat")!!.isVisible = false
        }
    }
}
