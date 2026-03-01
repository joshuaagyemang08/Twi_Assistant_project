package com.example.twiassistant.homework

data class QuestionAnswer(
    val question: String,
    val questionTwi: String = question,
    val questionEnglish: String = question,
    val answerEnglish: String,
    val answerTwi: String,
    val imageUrls: List<String> = emptyList()
)