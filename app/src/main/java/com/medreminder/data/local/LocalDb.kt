package com.medreminder.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import com.medreminder.data.DosePlanner
import com.medreminder.data.DoseStatus
import com.medreminder.data.DoseUnit
import com.medreminder.data.FoodRule
import com.medreminder.data.Medicine
import kotlinx.coroutines.flow.Flow

/**
 * The elder's phone keeps its medicines and today's doses here, so reminders and the Today
 * screen work with no internet. Firestore is synced in the background.
 */

@Entity(tableName = "medicines")
data class MedicineEntity(
    @PrimaryKey val id: String,
    val name: String,
    val spokenName: String?,
    val photoPath: String?,
    /** The photo downloaded to the phone, so it shows offline. */
    val localPhotoFile: String?,
    val doseAmount: Double,
    val doseUnit: String,
    val foodRule: String,
    /** Comma-separated local "HH:mm" times. */
    val times: String,
    val startDate: String,
    val endDate: String?,
    val tabletsLeft: Int?,
    val active: Boolean,
) {
    fun toMedicine() = Medicine(
        id = id,
        name = name,
        spokenName = spokenName,
        photoPath = photoPath,
        doseAmount = doseAmount,
        doseUnit = DoseUnit.from(doseUnit),
        foodRule = FoodRule.from(foodRule),
        times = times.split(',').filter { it.isNotBlank() },
        startDate = startDate,
        endDate = endDate,
        tabletsLeft = tabletsLeft,
        active = active,
    )

    fun schedule() = DosePlanner.Schedule(times.split(',').filter { it.isNotBlank() }, startDate, endDate, active)

    companion object {
        fun from(m: Medicine, localPhotoFile: String?) = MedicineEntity(
            id = m.id,
            name = m.name,
            spokenName = m.spokenName,
            photoPath = m.photoPath,
            localPhotoFile = localPhotoFile,
            doseAmount = m.doseAmount,
            doseUnit = m.doseUnit.wire,
            foodRule = m.foodRule.wire,
            times = m.times.joinToString(","),
            startDate = m.startDate,
            endDate = m.endDate,
            tabletsLeft = m.tabletsLeft,
            active = m.active,
        )
    }
}

@Entity(tableName = "doses")
data class DoseEntity(
    @PrimaryKey val id: String,
    val medicineId: String,
    val scheduledAt: Long,
    val localDate: String,
    val localTime: String,
    val status: String = DoseStatus.PENDING.wire,
    val confirmedAt: Long? = null,
    val snoozedAt: Long? = null,
    val snoozeCount: Int = 0,
    val firedCount: Int = 0,
    val lastFiredAt: Long? = null,
    /** False until the confirmation or snooze has reached Firestore. */
    val synced: Boolean = true,
) {
    val doseStatus: DoseStatus get() = DoseStatus.from(status)
}

/** Every alarm currently registered with AlarmManager, so stale ones can be cancelled. */
@Entity(tableName = "scheduled_alarms")
data class ScheduledAlarmEntity(
    @PrimaryKey val key: String,
    val triggerAt: Long,
    val attempt: Int,
)

@Dao
abstract class MedicineDao {
    @Query("SELECT * FROM medicines")
    abstract suspend fun all(): List<MedicineEntity>

    @Query("SELECT * FROM medicines")
    abstract fun observeAll(): Flow<List<MedicineEntity>>

    @Query("SELECT * FROM medicines WHERE id = :id")
    abstract suspend fun get(id: String): MedicineEntity?

    @Upsert
    abstract suspend fun upsertAll(medicines: List<MedicineEntity>)

    @Query("DELETE FROM medicines WHERE id NOT IN (:keep)")
    abstract suspend fun deleteOthers(keep: List<String>)

    @Query("DELETE FROM medicines")
    abstract suspend fun clear()

    @Transaction
    open suspend fun replaceAll(medicines: List<MedicineEntity>) {
        if (medicines.isEmpty()) {
            clear()
        } else {
            deleteOthers(medicines.map { it.id })
            upsertAll(medicines)
        }
    }
}

@Dao
abstract class DoseDao {
    @Query("SELECT * FROM doses WHERE id = :id")
    abstract suspend fun get(id: String): DoseEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(doses: List<DoseEntity>)

    @Query("SELECT * FROM doses WHERE localDate = :date ORDER BY scheduledAt")
    abstract fun observeDay(date: String): Flow<List<DoseEntity>>

    @Query("SELECT * FROM doses WHERE scheduledAt BETWEEN :from AND :to ORDER BY scheduledAt")
    abstract suspend fun between(from: Long, to: Long): List<DoseEntity>

    /** Doses the alarm screen should offer: due, not yet taken. */
    @Query("SELECT * FROM doses WHERE status IN ('pending', 'snoozed') AND scheduledAt BETWEEN :from AND :to ORDER BY scheduledAt")
    abstract suspend fun open(from: Long, to: Long): List<DoseEntity>

    @Query("SELECT * FROM doses WHERE synced = 0")
    abstract suspend fun unsynced(): List<DoseEntity>

    @Query("UPDATE doses SET status = 'confirmed', confirmedAt = :at, synced = 0 WHERE id = :id")
    abstract suspend fun confirm(id: String, at: Long)

    @Query("UPDATE doses SET status = 'snoozed', snoozedAt = :at, snoozeCount = snoozeCount + 1, synced = 0 WHERE id = :id")
    abstract suspend fun snooze(id: String, at: Long)

    @Query("UPDATE doses SET firedCount = firedCount + 1, lastFiredAt = :at WHERE id = :id")
    abstract suspend fun markFired(id: String, at: Long)

    @Query("UPDATE doses SET synced = 1 WHERE id = :id")
    abstract suspend fun markSynced(id: String)

    /** Forget future doses that no longer fit any medicine's schedule. */
    @Query("DELETE FROM doses WHERE status = 'pending' AND scheduledAt > :after AND id NOT IN (:keep)")
    abstract suspend fun deleteFuturePendingExcept(after: Long, keep: List<String>)

    @Query("DELETE FROM doses WHERE scheduledAt < :before AND synced = 1")
    abstract suspend fun deleteOlderThan(before: Long)
}

@Dao
abstract class AlarmDao {
    @Query("SELECT * FROM scheduled_alarms")
    abstract suspend fun all(): List<ScheduledAlarmEntity>

    @Upsert
    abstract suspend fun upsert(alarm: ScheduledAlarmEntity)

    @Query("DELETE FROM scheduled_alarms WHERE `key` = :key")
    abstract suspend fun delete(key: String)

    @Query("DELETE FROM scheduled_alarms")
    abstract suspend fun clear()
}

@Database(
    entities = [MedicineEntity::class, DoseEntity::class, ScheduledAlarmEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun medicines(): MedicineDao
    abstract fun doses(): DoseDao
    abstract fun alarms(): AlarmDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "medreminder.db").build()
    }
}
