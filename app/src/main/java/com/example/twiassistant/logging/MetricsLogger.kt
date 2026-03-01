package com.example.twiassistant.logging

import android.content.Context
import org.json.JSONObject
import java.io.File

class MetricsLogger(context: Context) {
    private val dir = File(context.getExternalFilesDir(null), "assistant_logs")

    fun log(event: JSONObject) {
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "daily.jsonl")
        file.appendText(event.toString() + "\n")
    }
}
