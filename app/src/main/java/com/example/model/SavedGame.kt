package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_games")
data class SavedGame(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val characterName: String,
    val profession: String,
    val wealth: Int,
    val health: Int,
    val happiness: Int,
    val lastActiveTurn: Int,
    val lastSaved: Long = System.currentTimeMillis(),
    val playerProfileJson: String,  // Moshi String representation
    val journalJson: String,        // Moshi String representing List<JournalEntry>
    val currentEventJson: String    // Moshi String representing LifeEvent
)
