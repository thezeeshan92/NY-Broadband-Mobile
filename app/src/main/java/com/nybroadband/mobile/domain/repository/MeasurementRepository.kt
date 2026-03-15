package com.nybroadband.mobile.domain.repository

import com.nybroadband.mobile.data.local.db.dao.MapPointProjection
import com.nybroadband.mobile.data.local.db.entity.MeasurementEntity
import kotlinx.coroutines.flow.Flow

/**
 * Domain contract for measurement persistence.
 *
 * Concrete implementation (data/repository/MeasurementRepositoryImpl) will be
 * wired in a future task alongside PassiveCollectionService and SyncWorker.
 *
 * NOTE: MeasurementEntity is used directly for MVP pragmatism. Extract proper
 * domain models if the codebase grows beyond a single platform or adds a
 * shared domain module.
 */
interface MeasurementRepository {

    /**
     * Lightweight points for the Mapbox GeoJSON layer.
     * Limited to the 500 most-recent accurate samples — see [MeasurementDao.observeMapPoints].
     */
    fun observeMapPoints(): Flow<List<MapPointProjection>>

    /**
     * Paginated + filtered stream for the Results screen.
     * @param filter one of "ALL", "SPEED_TESTS", "PASSIVE", "DEAD_ZONES"
     */
    fun observeFiltered(filter: String): Flow<List<MeasurementEntity>>

    /** Running total displayed in Settings and sync badge. */
    fun observeTotalCount(): Flow<Int>

    /**
     * Bytes consumed by active tests since [monthStartMs].
     * Passive collection samples are excluded (no mobile data consumed).
     */
    fun observeMonthlyDataUsageBytes(monthStartMs: Long): Flow<Long>

    suspend fun save(measurement: MeasurementEntity)

    suspend fun saveAll(measurements: List<MeasurementEntity>)

    suspend fun getById(id: String): MeasurementEntity?

    /** Returns up to [limit] oldest PENDING records for SyncWorker batch upload. */
    suspend fun getPendingForSync(limit: Int = 100): List<MeasurementEntity>

    suspend fun markUploaded(ids: List<String>)

    suspend fun markFailed(ids: List<String>)
}
