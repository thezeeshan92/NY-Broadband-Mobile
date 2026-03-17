package com.nybroadband.mobile.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nybroadband.mobile.data.local.db.dao.DeadZoneDao
import com.nybroadband.mobile.data.local.db.dao.MeasurementDao
import com.nybroadband.mobile.data.local.db.dao.RemoteConfigCacheDao
import com.nybroadband.mobile.data.local.db.dao.ServerDefinitionDao
import com.nybroadband.mobile.data.local.db.dao.SyncQueueDao
import com.nybroadband.mobile.data.local.db.entity.DeadZoneReportEntity
import com.nybroadband.mobile.data.local.db.entity.MeasurementEntity
import com.nybroadband.mobile.data.local.db.entity.RemoteConfigCacheEntity
import com.nybroadband.mobile.data.local.db.entity.ServerDefinitionEntity
import com.nybroadband.mobile.data.local.db.entity.SyncQueueEntity

@Database(
    entities = [
        MeasurementEntity::class,
        DeadZoneReportEntity::class,
        SyncQueueEntity::class,
        ServerDefinitionEntity::class,
        RemoteConfigCacheEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun measurementDao(): MeasurementDao
    abstract fun deadZoneDao(): DeadZoneDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun serverDefinitionDao(): ServerDefinitionDao
    abstract fun remoteConfigCacheDao(): RemoteConfigCacheDao

    companion object {
        const val DB_NAME = "nybroadband.db"

        /**
         * v1 → v2: Add dead_zone_reports, sync_queue, server_definitions, and
         * remote_config_cache tables.  The existing measurements table is unchanged.
         *
         * SQL column types must exactly match Room's generated schema:
         *   TEXT    → String (nullable or not)
         *   INTEGER → Int, Long, Boolean (0/1)
         *   REAL    → Double, Float
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {

                // ── dead_zone_reports ────────────────────────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `dead_zone_reports` (
                        `id`                TEXT    NOT NULL,
                        `timestamp`         INTEGER NOT NULL,
                        `lat`               REAL    NOT NULL,
                        `lon`               REAL    NOT NULL,
                        `gpsAccuracyMeters` REAL    NOT NULL,
                        `note`              TEXT,
                        `photoUris`         TEXT,
                        `deviceModel`       TEXT    NOT NULL,
                        `androidVersion`    INTEGER NOT NULL,
                        `appVersion`        TEXT    NOT NULL,
                        `syncStatus`        TEXT    NOT NULL DEFAULT 'PENDING',
                        `uploadAttempts`    INTEGER NOT NULL DEFAULT 0,
                        `uploadedAt`        INTEGER,
                        `remoteId`          TEXT,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dead_zone_reports_syncStatus` " +
                    "ON `dead_zone_reports` (`syncStatus`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dead_zone_reports_timestamp` " +
                    "ON `dead_zone_reports` (`timestamp`)"
                )

                // ── sync_queue ───────────────────────────────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sync_queue` (
                        `id`               INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `entityType`       TEXT    NOT NULL,
                        `entityId`         TEXT    NOT NULL,
                        `operation`        TEXT    NOT NULL DEFAULT 'UPLOAD',
                        `createdAtMs`      INTEGER NOT NULL,
                        `nextAttemptMs`    INTEGER NOT NULL,
                        `attemptCount`     INTEGER NOT NULL DEFAULT 0,
                        `lastErrorCode`    INTEGER,
                        `lastErrorMessage` TEXT
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_queue_entityType_entityId` " +
                    "ON `sync_queue` (`entityType`, `entityId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_queue_nextAttemptMs` " +
                    "ON `sync_queue` (`nextAttemptMs`)"
                )

                // ── server_definitions ───────────────────────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `server_definitions` (
                        `id`              TEXT    NOT NULL,
                        `baseUrl`         TEXT    NOT NULL,
                        `region`          TEXT,
                        `priority`        INTEGER NOT NULL,
                        `isActive`        INTEGER NOT NULL,
                        `healthCheckPath` TEXT,
                        `updatedAtMs`     INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())

                // ── remote_config_cache ──────────────────────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `remote_config_cache` (
                        `key`         TEXT    NOT NULL,
                        `value`       TEXT    NOT NULL,
                        `fetchedAtMs` INTEGER NOT NULL,
                        `expiresAtMs` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`key`)
                    )
                """.trimIndent())
            }
        }

        /**
         * v2 → v3: Add per-technology RF columns and NDT7 TCP/BBR metrics to the
         * measurements table.
         *
         * All new columns are nullable (no DEFAULT clause required in SQLite for
         * nullable — existing rows receive NULL automatically).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── 2G RF ─────────────────────────────────────────────────────
                db.execSQL("ALTER TABLE measurements ADD COLUMN gsmBer INTEGER")
                db.execSQL("ALTER TABLE measurements ADD COLUMN gsmTimingAdv INTEGER")

                // ── 3G RF ─────────────────────────────────────────────────────
                db.execSQL("ALTER TABLE measurements ADD COLUMN umtsRscp INTEGER")
                db.execSQL("ALTER TABLE measurements ADD COLUMN umtsEcNo INTEGER")

                // ── 4G RF ─────────────────────────────────────────────────────
                db.execSQL("ALTER TABLE measurements ADD COLUMN lteCqi INTEGER")
                db.execSQL("ALTER TABLE measurements ADD COLUMN lteTimingAdv INTEGER")

                // ── 5G RF (CSI metrics) ───────────────────────────────────────
                db.execSQL("ALTER TABLE measurements ADD COLUMN nrCsiRsrp INTEGER")
                db.execSQL("ALTER TABLE measurements ADD COLUMN nrCsiRsrq INTEGER")
                db.execSQL("ALTER TABLE measurements ADD COLUMN nrCsiSinr INTEGER")

                // ── NDT7 TCP/BBR extended metrics ─────────────────────────────
                db.execSQL("ALTER TABLE measurements ADD COLUMN minRttUs INTEGER")
                db.execSQL("ALTER TABLE measurements ADD COLUMN meanRttUs INTEGER")
                db.execSQL("ALTER TABLE measurements ADD COLUMN rttVarUs INTEGER")
                db.execSQL("ALTER TABLE measurements ADD COLUMN retransmitRate REAL")
                db.execSQL("ALTER TABLE measurements ADD COLUMN bbrBandwidthBps INTEGER")
                db.execSQL("ALTER TABLE measurements ADD COLUMN bbrMinRttUs INTEGER")
                db.execSQL("ALTER TABLE measurements ADD COLUMN serverUuid TEXT")
            }
        }
    }
}
