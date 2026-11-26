package com.mohit.dimplex.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "readings", indices = [Index("heaterId", "ts")])
data class Reading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val heaterId: String,
    val ts: Long,
    val mode: Int?,
    val setpoint: Int?,
    val roomTemp: Double?,
    val heating: Boolean?,
    val error: Int?,
    /** Estimated draw: rated watts (0x000a) x element demand %. Not metered. */
    val watts: Int? = null,
    /** Timer mode running our off schedule: shown as Off, not Timer. */
    val off: Boolean = false,
)

@Entity(tableName = "rssi", indices = [Index("heaterId", "ts"), Index("ts")])
data class RssiSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val heaterId: String,
    val ts: Long,
    val rssi: Int,
    /** "adv" = scan advertisement, "conn" = readRemoteRssi while connected. */
    val source: String = "adv",
)

data class HeaterAvg(val heaterId: String, val avg: Double?)
data class HeaterRssi(val heaterId: String, val rssi: Double, val ts: Long)

@Dao
interface ReadingDao {
    @Insert suspend fun insert(r: Reading)

    @Query("SELECT * FROM readings WHERE id IN (SELECT MAX(id) FROM readings GROUP BY heaterId)")
    fun latest(): Flow<List<Reading>>

    @Query("SELECT * FROM readings WHERE heaterId = :id AND ts >= :since ORDER BY ts")
    fun history(id: String, since: Long): Flow<List<Reading>>

    @Query("SELECT heaterId, AVG(roomTemp) AS avg FROM readings WHERE ts >= :since AND roomTemp IS NOT NULL GROUP BY heaterId")
    fun avgRoomTemp(since: Long): Flow<List<HeaterAvg>>

    @Query("DELETE FROM readings WHERE ts < :before")
    suspend fun prune(before: Long)
}

@Dao
interface RssiDao {
    @Insert suspend fun insert(r: RssiSample)

    @Query("SELECT * FROM rssi WHERE heaterId = :id AND ts >= :since ORDER BY ts")
    fun history(id: String, since: Long): Flow<List<RssiSample>>

    /** Mean RSSI per heater over a recent window (for the nearest-heater hint). */
    @Query("SELECT * FROM rssi WHERE ts >= :since ORDER BY ts")
    fun allSince(since: Long): Flow<List<RssiSample>>

    @Query("SELECT heaterId, AVG(rssi) AS rssi, MAX(ts) AS ts FROM rssi WHERE ts >= :since GROUP BY heaterId")
    fun recent(since: Long): Flow<List<HeaterRssi>>

    @Query("DELETE FROM rssi WHERE ts < :before")
    suspend fun prune(before: Long)
}

@Database(entities = [Reading::class, RssiSample::class], version = 3, exportSchema = true)
abstract class AppDb : RoomDatabase() {
    abstract fun readings(): ReadingDao
    abstract fun rssi(): RssiDao

    companion object {
        fun build(ctx: Context) = Room.databaseBuilder(ctx, AppDb::class.java, "dimplex.db")
            .fallbackToDestructiveMigration()
            .build()
    }
}
