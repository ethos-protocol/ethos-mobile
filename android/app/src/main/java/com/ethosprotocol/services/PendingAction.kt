package com.ethosprotocol.services

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class PendingActionType { CHECK_IN, CREATE_VAULT }

@Entity(
    tableName = "pending_actions",
    indices = [Index(value = ["dedupeKey"], unique = true)]
)
data class PendingAction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: PendingActionType,
    val vaultId: String? = null,
    val payloadJson: String? = null,
    val queuedAt: Long,
    // Re-queuing the same logical action (e.g. check-in for a given vault) should
    // replace the earlier queued item rather than pile up duplicates. SQLite treats
    // each NULL in a unique index as distinct, so action types with no natural key
    // (e.g. create-vault) can simply leave this null and queue freely.
    val dedupeKey: String? = null
)

@Dao
interface PendingActionDao {
    @Query("SELECT * FROM pending_actions ORDER BY queuedAt ASC")
    suspend fun getAll(): List<PendingAction>

    @Query("SELECT COUNT(*) FROM pending_actions")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PendingAction)

    @Delete
    suspend fun delete(item: PendingAction)

    @Query("DELETE FROM pending_actions")
    suspend fun deleteAll()
}
