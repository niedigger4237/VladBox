package io.nekohasekai.sagernet.ktx

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable

// Android 7–12 do not provide the typed overload. Keep the legacy call
// isolated to that compatibility branch rather than suppressing callers.
@Suppress("DEPRECATION")
internal fun <T : Parcelable> Bundle.parcelableCompat(key: String, type: Class<T>): T? =
    if (Build.VERSION.SDK_INT >= 34) getParcelable(key, type)
    else getParcelable<Parcelable>(key)?.let { if (type.isInstance(it)) type.cast(it) else null }

@Suppress("DEPRECATION")
internal fun <T : Parcelable> Intent.parcelableCompat(key: String, type: Class<T>): T? =
    if (Build.VERSION.SDK_INT >= 34) getParcelableExtra(key, type)
    else getParcelableExtra<Parcelable>(key)?.let { if (type.isInstance(it)) type.cast(it) else null }
