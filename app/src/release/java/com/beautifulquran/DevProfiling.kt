package com.beautifulquran

import android.app.Activity
import android.app.Application
import android.content.Context

/** Release builds contain no profiling registration or capture behaviour. */
object DevProfiling {
    fun install(application: Application) = Unit
    fun reportFullyDrawn(activity: Activity) = Unit
    fun recordSystemTrace(context: Context) = Unit
    fun mark(label: String) = Unit
    inline fun <T> trace(label: String, block: () -> T): T = block()
}
