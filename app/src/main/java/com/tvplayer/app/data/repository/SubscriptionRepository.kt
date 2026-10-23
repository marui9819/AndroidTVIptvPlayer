package com.tvplayer.app.data.repository

import android.content.Context
import com.tvplayer.app.data.db.AppDatabase
import com.tvplayer.app.data.model.SubscriptionEndpoint
import com.tvplayer.app.network.NetworkRepository
import com.tvplayer.app.util.Constants

class SubscriptionRepository(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context),
    private val networkRepository: NetworkRepository = NetworkRepository()
) {
    private val dao = database.subscriptionEndpointDao()

    suspend fun ensureDefaults() {
        val existingEpgUrls = dao.getByType(SubscriptionEndpoint.TYPE_EPG).map { it.url }.toSet()
        Constants.DefaultEpgSources.ALL.forEachIndexed { index, url ->
            if (url !in existingEpgUrls) {
                dao.insert(
                    SubscriptionEndpoint(
                        type = SubscriptionEndpoint.TYPE_EPG,
                        name = "EPG源 ${index + 1}",
                        url = url,
                        sortOrder = index
                    )
                )
            }
        }

        val existingLogoUrls = dao.getByType(SubscriptionEndpoint.TYPE_LOGO).map { it.url }.toSet()
        Constants.DefaultLogoSources.ALL.forEachIndexed { index, url ->
            if (url !in existingLogoUrls) {
                dao.insert(
                    SubscriptionEndpoint(
                        type = SubscriptionEndpoint.TYPE_LOGO,
                        name = "Logo库 ${index + 1}",
                        url = url,
                        sortOrder = index
                    )
                )
            }
        }
    }

    suspend fun getEndpoints(type: String): List<SubscriptionEndpoint> = dao.getByType(type)

    suspend fun getEnabledEndpoints(type: String): List<SubscriptionEndpoint> = dao.getEnabledByType(type)

    suspend fun saveEndpoint(endpoint: SubscriptionEndpoint): Long {
        return dao.insert(endpoint)
    }

    suspend fun updateEndpoint(endpoint: SubscriptionEndpoint) {
        dao.update(endpoint)
    }

    suspend fun deleteEndpoint(endpoint: SubscriptionEndpoint) {
        dao.delete(endpoint)
    }

    suspend fun testEndpoint(url: String): Result<String> {
        return networkRepository.fetchUrlContent(url).mapCatching { content ->
            if (content.isBlank()) {
                throw IllegalStateException("返回内容为空")
            }
            content
        }
    }
}
