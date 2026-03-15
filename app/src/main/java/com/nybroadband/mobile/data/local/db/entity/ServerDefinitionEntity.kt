package com.nybroadband.mobile.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * API endpoint registry, seeded at install time and refreshed via Remote Config.
 *
 * SyncWorker iterates active entries in ascending [priority] order (0 = first).
 * If the primary endpoint fails a health check, the worker falls through to the
 * next active entry. Inactive entries are retained for audit/rollback.
 *
 * MVP seed: a single "primary" row pointing to the AWS API Gateway URL.
 * Additional entries (secondary region, staging) can be pushed via Remote Config
 * without requiring an app update.
 *
 * Provisioned by: DatabaseModule seed insert + RemoteConfigRepository refresh.
 */
@Entity(tableName = "server_definitions")
data class ServerDefinitionEntity(

    @PrimaryKey val id: String,                     // "primary" | "secondary" | "staging"

    val baseUrl: String,                            // https://api.nybroadband.example.com
    val region: String?,                            // AWS region, e.g. "us-east-1"
    val priority: Int,                              // 0 = highest; ascending = fallover order
    val isActive: Boolean,

    val healthCheckPath: String?,                   // e.g. "/health"; null = skip check
    val updatedAtMs: Long                           // epoch ms of last Remote Config refresh
)
