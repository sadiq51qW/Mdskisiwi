package com.example.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PlayerProfile(
    val name: String,
    val age: Int,
    val gender: String, // "ذكر" أو "أنثى"
    val country: String,
    val city: String,
    val education: String,
    val profession: String,
    val wealth: Int, // الثروة
    val goal: String,
    val health: Int = 100, // الصحة (0-100)
    val happiness: Int = 100, // السعادة (0-100)
    val relations: Int = 50, // العلاقات (0-100)
    val turnCount: Int = 0,
    val isDead: Boolean = false,
    val isWin: Boolean = false,
    val extraNotes: String = "" // لتخزين الإنجازات أو معلومات إضافية
)

@JsonClass(generateAdapter = true)
data class StateChanges(
    val ageChange: Int = 0,
    val wealthChange: Int = 0,
    val healthChange: Int = 0,
    val happinessChange: Int = 0,
    val relationsChange: Int = 0,
    val professionChange: String? = null,
    val eventStatus: String? = null // مثل "حصلت على ترقية" أو "خسارة"
)

@JsonClass(generateAdapter = true)
data class LifeEvent(
    val eventDescription: String,
    val keyCharacters: String,
    val opportunitiesAndRisks: String,
    val options: List<String>,
    val stateChanges: StateChanges = StateChanges()
)

@JsonClass(generateAdapter = true)
data class JournalEntry(
    val turn: Int,
    val eventDesc: String,
    val decision: String,
    val resultDesc: String
)
