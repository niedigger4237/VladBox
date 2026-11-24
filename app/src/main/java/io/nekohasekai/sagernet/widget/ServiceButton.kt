package io.nekohasekai.sagernet.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.util.AttributeSet
import android.view.PointerIcon
import androidx.appcompat.widget.TooltipCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.BaseProgressIndicator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService

/**
 * v2rayNG-style start/stop button: a plain round button whose icon and colour follow the service
 * state directly. There is no animation queue, so it can't get stuck between two states.
 */
class ServiceButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FloatingActionButton(context, attrs, defStyleAttr) {

    private var progress: BaseProgressIndicator<*>? = null

    fun initProgress(progress: BaseProgressIndicator<*>) {
        this.progress = progress
        progress.isIndeterminate = true
        progress.hide()
    }

    @Suppress("UNUSED_PARAMETER")
    fun changeState(state: BaseService.State, previousState: BaseService.State, animate: Boolean) {
        val (icon, color) = when (state) {
            BaseService.State.Connected -> R.drawable.ic_service_stop to COLOR_CONNECTED
            BaseService.State.Connecting, BaseService.State.Stopping -> R.drawable.ic_service_wait to COLOR_BUSY
            else -> R.drawable.ic_service_play to COLOR_IDLE
        }
        setImageResource(icon)
        val surface = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
        val primary = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        val foreground = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        val dark = androidx.core.graphics.ColorUtils.calculateLuminance(surface) < 0.5
        val tint = when (state) {
            BaseService.State.Connected -> if (dark) Color.parseColor("#A5D5B1") else Color.parseColor("#386A49")
            BaseService.State.Connecting, BaseService.State.Stopping -> color
            else -> androidx.core.graphics.ColorUtils.blendARGB(primary, if (dark) foreground else surface, if (dark) 0.55f else 0.12f)
        }
        backgroundTintList = ColorStateList.valueOf(tint)
        imageTintList = ColorStateList.valueOf(if (androidx.core.graphics.ColorUtils.calculateLuminance(tint) > 0.45) Color.BLACK else Color.WHITE)

        if (animate && state != previousState) {
            animate().cancel()
            scaleX = 0.94f
            scaleY = 0.94f
            animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
        }

        val busy = state == BaseService.State.Connecting || state == BaseService.State.Stopping
        progress?.let { if (busy) it.show() else it.hide() }

        val description = context.getText(if (state.canStop) R.string.stop else R.string.connect)
        contentDescription = description
        TooltipCompat.setTooltipText(this, description)
        val enabled = state.canStop || state == BaseService.State.Stopped
        isEnabled = enabled
        if (Build.VERSION.SDK_INT >= 24) pointerIcon = PointerIcon.getSystemIcon(
            context,
            if (enabled) PointerIcon.TYPE_HAND else PointerIcon.TYPE_WAIT
        )
    }

    private companion object {
        val COLOR_IDLE = Color.parseColor("#4C3A78")
        val COLOR_CONNECTED = Color.parseColor("#8B5CF6")
        val COLOR_BUSY = Color.parseColor("#F9A825")
    }
}
