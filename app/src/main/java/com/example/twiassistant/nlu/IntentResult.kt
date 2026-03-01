package com.example.twiassistant.nlu

sealed class IntentResult {
    data class CallContact(val name: String, val c: Float = 1.0f) : IntentResult()
    data class CallNumber(val number: String, val c: Float = 1.0f) : IntentResult()
    data class SendSms(val nameOrNumber: String, val body: String, val c: Float = 1.0f) : IntentResult()
    data class ReadMessages(val unreadOnly: Boolean = true, val c: Float = 1.0f) : IntentResult()
    data class SetAlarm(val timeText: String, val c: Float = 1.0f) : IntentResult()
    data class OpenApp(val appName: String, val c: Float = 1.0f) : IntentResult()
    data class MenuSelection(val option: Int, val c: Float = 1.0f) : IntentResult()
    data class StatusQuery(val topic: String = "", val c: Float = 1.0f) : IntentResult()
    data class AdjustBrightness(val action: BrightnessAction, val percent: Int? = null, val c: Float = 1.0f) : IntentResult()
    object Unknown : IntentResult()
}

enum class BrightnessAction {
    SET,
    UP,
    DOWN,
    MAX,
    MIN
}
