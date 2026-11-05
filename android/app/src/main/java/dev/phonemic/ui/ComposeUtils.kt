package dev.phonemic.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Runs [block] once, in the first composition, so the lines under it already read what it loaded. */
@Composable
fun RunOnce(block: () -> Unit) {
    remember { block(); true }
}

/** The activity behind a Compose context (it may be wrapped, for example by the theme). */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
