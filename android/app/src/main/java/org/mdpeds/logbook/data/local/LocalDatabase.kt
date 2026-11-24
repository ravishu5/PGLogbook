package org.mdpeds.logbook.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** A logbook draft written on the device and waiting to be synchronised (offline outbox). */
@Entity(tableName = "outbox")
data class OutboxEntity(
    /** Client-generated UUID used as the record id, so retries are idempotent. */
    @PrimaryKey val id: String,
    val ownerId: String,
    val studentId: String,
    val templateCode: String,
    val periodNo: Int?,
    val slotNo: Int?,
    /** Server version the draft was based on (null for a brand-new record). */
    val baseVersion: Int?,
    val dataJson: String,
    /** SAVE or SUBMIT. */
    val action: String,
    /** PENDING, SYNCING, FAILED or CONFLICT. */
    val state: String,
    val error: String?,
    val updatedAt: Long,
)

/** Last successfully fetched read models, used when the device is offline. */
@Entity(tableName = "cache")
data class CacheEntity(@PrimaryKey val key: String, val json: String, val updatedAt: Long)

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox WHERE ownerId = :owner ORDER BY updatedAt")
    fun observe(owner: String): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox WHERE ownerId = :owner ORDER BY updatedAt")
    suspend fun all(owner: String): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun get(id: String): OutboxEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OutboxEntity)

    @Query("UPDATE outbox SET state = :state, error = :error WHERE id = :id")
    suspend fun mark(id: String, state: String, error: String?)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM outbox WHERE ownerId = :owner")
    suspend fun count(owner: String): Int

    @Query("DELETE FROM outbox")
    suspend fun clear()
}

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache WHERE `key` = :key")
    suspend fun get(key: String): CacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: CacheEntity)

    @Query("DELETE FROM cache")
    suspend fun clear()
}

@Database(entities = [OutboxEntity::class, CacheEntity::class], version = 1, exportSchema = true)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun outbox(): OutboxDao
    abstract fun cache(): CacheDao
}

object OutboxState {
    const val PENDING = "PENDING"
    const val SYNCING = "SYNCING"
    const val FAILED = "FAILED"
    const val CONFLICT = "CONFLICT"
}

object OutboxAction {
    const val SAVE = "SAVE"
    const val SUBMIT = "SUBMIT"
}
