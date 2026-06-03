package com.ivy.legacy.utils

import timber.log.Timber

fun sendToCrashlytics(
    msg: String
) {
    Timber.e(DeveloperException(msg), msg)
}

fun Exception.sendToCrashlytics(
    clarification: String? = null
) {
    Timber.e(this, clarification ?: message)
}

fun logToCrashlytics(msg: String) {
    Timber.i(msg)
}

class DeveloperException(msg: String) : Exception(msg)
