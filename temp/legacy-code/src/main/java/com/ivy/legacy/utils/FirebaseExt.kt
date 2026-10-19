package com.ivy.legacy.utils

import timber.log.Timber

fun sendToCrashlytics(
    msg: String
) {
    DeveloperException(msg).sendToCrashlytics(msg)
}

fun Exception.sendToCrashlytics(
    clarification: String? = null
) {
    clarification?.let {
        logToCrashlytics("Log: $it")
    }
    Timber.e(this)
}

fun logToCrashlytics(msg: String) {
    Timber.tag("Crashlytics").d(msg)
}

class DeveloperException(msg: String) : Exception(msg)
