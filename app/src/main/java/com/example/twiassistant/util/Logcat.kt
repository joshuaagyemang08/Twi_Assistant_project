package com.example.twiassistant.util

import android.util.Log

object Logcat {
    fun d(tag: String, msg: String) = Log.d(tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = Log.w(tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = Log.e(tag, msg, t)
}
