package com.nullclass.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 同步元数据：deviceId、lastPushAt、lastPullAt、remoteSnapshotRev。 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: String,
)
