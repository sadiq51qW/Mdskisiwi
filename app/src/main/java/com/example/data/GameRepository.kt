package com.example.data

import com.example.model.SavedGame
import kotlinx.coroutines.flow.Flow

class GameRepository(private val dao: SavedGameDao) {
    val allSaves: Flow<List<SavedGame>> = dao.getAllSavedGames()

    suspend fun saveGame(game: SavedGame) {
        dao.insertSave(game)
    }

    suspend fun deleteGame(id: Int) {
        dao.deleteSaveById(id)
    }

    suspend fun getGameById(id: Int): SavedGame? {
        return dao.getSaveById(id)
    }
}
