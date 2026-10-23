package com.tvplayer.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tvplayer.app.data.model.SubscriptionEndpoint
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionEndpointDao {
    @Query("SELECT * FROM subscription_endpoints ORDER BY type ASC, sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<SubscriptionEndpoint>>

    @Query("SELECT * FROM subscription_endpoints WHERE type = :type ORDER BY sortOrder ASC, id ASC")
    fun observeByType(type: String): Flow<List<SubscriptionEndpoint>>

    @Query("SELECT * FROM subscription_endpoints WHERE type = :type ORDER BY sortOrder ASC, id ASC")
    suspend fun getByType(type: String): List<SubscriptionEndpoint>

    @Query("SELECT * FROM subscription_endpoints WHERE type = :type AND isEnabled = 1 ORDER BY sortOrder ASC, id ASC")
    suspend fun getEnabledByType(type: String): List<SubscriptionEndpoint>

    @Query("SELECT * FROM subscription_endpoints WHERE id = :endpointId LIMIT 1")
    suspend fun getById(endpointId: Long): SubscriptionEndpoint?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(endpoint: SubscriptionEndpoint): Long

    @Update
    suspend fun update(endpoint: SubscriptionEndpoint)

    @Delete
    suspend fun delete(endpoint: SubscriptionEndpoint)

    @Query("SELECT COUNT(*) FROM subscription_endpoints WHERE type = :type")
    suspend fun countByType(type: String): Int
}
