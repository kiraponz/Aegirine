package com.kira3.ametrine.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {
    @Query("SELECT * FROM games")
    fun observeAll(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games")
    suspend fun getAll(): List<GameEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(games: List<GameEntity>)

    @Query("DELETE FROM games WHERE uri IN (:uris)")
    suspend fun delete(uris: List<String>)

    @Query("UPDATE games SET serial = :serial, dbTitle = :dbTitle, serialRead = 1 WHERE uri = :uri")
    suspend fun setSerial(uri: String, serial: String?, dbTitle: String?)

    /** Applies a scan result in one step so the library never shows a half-updated list. */
    @Transaction
    suspend fun sync(removed: List<String>, changed: List<GameEntity>) {
        if (removed.isNotEmpty()) delete(removed)
        if (changed.isNotEmpty()) upsert(changed)
    }
}
