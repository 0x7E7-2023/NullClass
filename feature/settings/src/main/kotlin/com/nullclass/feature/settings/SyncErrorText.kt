package com.nullclass.feature.settings

import androidx.annotation.StringRes
import com.nullclass.core.ui.i18n.UiText
import com.nullclass.sync.SyncError
import com.nullclass.sync.SyncFailure

/**
 * 同步失败原因的显示文案。
 *
 * `:sync` 只产出原因码（见 `SyncError` 的注释），映射放在这里。
 * 新增枚举项时下面的 `when` 会缺分支，编译器会提醒补上。
 */
@get:StringRes
val SyncError.messageRes: Int
    get() = when (this) {
        SyncError.NOT_CONFIGURED -> R.string.settings_sync_not_configured
        SyncError.URL_EMPTY -> R.string.settings_sync_url_empty
        SyncError.URL_SCHEME -> R.string.settings_sync_url_scheme
        SyncError.URL_INSECURE -> R.string.settings_sync_url_insecure
        SyncError.USERNAME_EMPTY -> R.string.settings_sync_username_empty
        SyncError.WRITE_TEST_FAILED -> R.string.settings_sync_write_test_failed
        SyncError.READ_TEST_FAILED -> R.string.settings_sync_read_test_failed
        SyncError.CONTENT_MISMATCH -> R.string.settings_sync_content_mismatch
        SyncError.CLEANUP_FAILED -> R.string.settings_sync_cleanup_failed
        SyncError.CONNECT_FAILED -> R.string.settings_sync_connect_failed
        SyncError.DOWNLOAD_MANIFEST_FAILED -> R.string.settings_sync_download_manifest_failed
        SyncError.DOWNLOAD_SNAPSHOT_FAILED -> R.string.settings_sync_download_snapshot_failed
        SyncError.UPLOAD_SNAPSHOT_FAILED -> R.string.settings_sync_upload_snapshot_failed
        SyncError.UPLOAD_MANIFEST_FAILED -> R.string.settings_sync_upload_manifest_failed
        SyncError.CREATE_DIR_FAILED -> R.string.settings_sync_create_dir_failed
        SyncError.UNEXPECTED -> R.string.settings_sync_unexpected
    }

/**
 * 结论 + 原始详情。详情（HTTP 状态码、异常消息）不翻译，它是给用户反馈问题用的。
 */
fun SyncFailure.toUiText(): UiText {
    val detail = detail?.takeIf { it.isNotBlank() } ?: return UiText.Res(error.messageRes)
    return UiText.Res(R.string.settings_sync_with_detail, UiText.Res(error.messageRes), detail)
}

/**
 * 自动同步周期的显示名称。
 *
 * 枚举定义在 `:sync`，那一层不产出文案，映射放在这里。
 */
@get:StringRes
val com.nullclass.sync.AutoSyncInterval.labelRes: Int
    get() = when (this) {
        com.nullclass.sync.AutoSyncInterval.OFF -> R.string.settings_auto_sync_off
        com.nullclass.sync.AutoSyncInterval.EVERY_6_HOURS -> R.string.settings_auto_sync_6h
        com.nullclass.sync.AutoSyncInterval.DAILY -> R.string.settings_auto_sync_daily
    }
