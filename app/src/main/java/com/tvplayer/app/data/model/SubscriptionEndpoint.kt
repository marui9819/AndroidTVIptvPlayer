package com.tvplayer.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscription_endpoints")
data class SubscriptionEndpoint(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: String,
    val name: String,
    val url: String,
    val isEnabled: Boolean = true,
    val sortOrder: Int = 0,
    val lastTestAt: Long = 0,
    val lastSuccessAt: Long = 0,
    val lastError: String? = null
) {
    companion object {
        const val TYPE_EPG = "epg"
        const val TYPE_LOGO = "logo"
    }
}
