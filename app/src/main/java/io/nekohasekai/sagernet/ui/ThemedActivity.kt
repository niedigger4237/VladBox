package io.nekohasekai.sagernet.ui

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.color.DynamicColors
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.Theme

abstract class ThemedActivity : AppCompatActivity {
    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    var themeResId = 0
    var uiMode = 0
    open val isDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!isDialog) {
            Theme.apply(this)
        } else {
            Theme.applyDialog(this)
        }
        Theme.applyNightTheme()
        // Material You colors follow the system wallpaper on Android 12+.
        // Older devices retain the app's selected color palette.
        DynamicColors.applyToActivityIfAvailable(this)

        super.onCreate(savedInstanceState)

        uiMode = resources.configuration.uiMode

        if (this is MainActivity) {
            val surface = com.google.android.material.color.MaterialColors.getColor(
                window.decorView, com.google.android.material.R.attr.colorSurface
            )
            window.statusBarColor = if (Build.VERSION.SDK_INT >= 35) android.graphics.Color.TRANSPARENT else surface
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars = androidx.core.graphics.ColorUtils.calculateLuminance(surface) > 0.5
            if (Build.VERSION.SDK_INT >= 29) window.isStatusBarContrastEnforced = false
        }

        if (Build.VERSION.SDK_INT >= 35) {
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
                val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
                findViewById<AppBarLayout>(R.id.appbar)?.apply {
                    updatePadding(top = top)
                    if (this@ThemedActivity is MainActivity) {
                        val surface = com.google.android.material.color.MaterialColors.getColor(
                            this, com.google.android.material.R.attr.colorSurface
                        )
                        setBackgroundColor(surface)
                    }
//                Logs.w("appbar $top")
                }
//            findViewById<NavigationView>(R.id.nav_view)?.apply {
//                updatePadding(top = top)
//            }
                insets
            }
        }
    }

    protected fun installBackGuard(hasUnsavedChanges: () -> Boolean, showDialog: () -> Unit) {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hasUnsavedChanges()) {
                    showDialog()
                } else {
                    isEnabled = false
                    try { onBackPressedDispatcher.onBackPressed() }
                    finally { isEnabled = true }
                }
            }
        })
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)

        themeResId = resId
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (newConfig.uiMode != uiMode) {
            uiMode = newConfig.uiMode
            ActivityCompat.recreate(this)
        }
    }

    fun snackbar(@StringRes resId: Int): Snackbar = snackbar("").setText(resId)
    fun snackbar(text: CharSequence): Snackbar = snackbarInternal(text).apply {
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
            maxLines = 10
        }
    }

    internal open fun snackbarInternal(text: CharSequence): Snackbar = throw NotImplementedError()

}