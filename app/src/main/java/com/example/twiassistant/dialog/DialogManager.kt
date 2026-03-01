package com.example.twiassistant.dialog

import com.example.twiassistant.nlu.IntentResult

class DialogManager {
    var state = DialogState.IDLE
        private set

    fun handleIntent(intent: IntentResult) {
        // Use 'c' property from IntentResult subclasses
        val confidence = when (intent) {
            is IntentResult.CallContact -> intent.c
            is IntentResult.CallNumber -> intent.c
            is IntentResult.SendSms -> intent.c
            is IntentResult.ReadMessages -> intent.c
            is IntentResult.SetAlarm -> intent.c
            is IntentResult.OpenApp -> intent.c
            is IntentResult.StatusQuery -> intent.c
            is IntentResult.AdjustBrightness -> intent.c
            is IntentResult.MenuSelection -> intent.c
            IntentResult.Unknown -> 0f
        }
        state = if (confidence < 0.6f) {
            DialogState.LISTENING
        } else {
            DialogState.EXECUTE
        }
    }
}
