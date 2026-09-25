package com.nullclass.sync

import java.io.IOException

/**
 * 同步过程中可能出现的失败原因。
 *
 * 同步引擎不产出文案，只产出原因码 —— 文案要随界面语言变化，而这一层拿不到、也不该拿
 * 当前语言。原因码到文案的映射在 `:feature:settings` 的 `SyncError.messageRes`，
 * 新增枚举项时那里的 `when` 会缺分支，编译器会提醒补上。
 */
enum class SyncError {

    /** 尚未填写 WebDAV 配置。 */
    NOT_CONFIGURED,

    // ---- 配置校验 ----
    URL_EMPTY,
    URL_SCHEME,
    /** 公网地址用了 http，密码会明文传输。 */
    URL_INSECURE,
    USERNAME_EMPTY,

    // ---- 连接测试 ----
    WRITE_TEST_FAILED,
    READ_TEST_FAILED,
    /** 读回的探测文件内容对不上，多半指到了非专用目录。 */
    CONTENT_MISMATCH,
    CLEANUP_FAILED,
    CONNECT_FAILED,

    // ---- 同步传输 ----
    DOWNLOAD_MANIFEST_FAILED,
    DOWNLOAD_SNAPSHOT_FAILED,
    UPLOAD_SNAPSHOT_FAILED,
    UPLOAD_MANIFEST_FAILED,
    CREATE_DIR_FAILED,

    /** 其余未归类的异常，[SyncFailure.detail] 里带原始信息。 */
    UNEXPECTED,
}

/**
 * 一次失败：原因码加可选的原始详情（HTTP 状态码、异常消息等）。
 *
 * [detail] 不翻译，它是给用户反馈问题时用的原始信息。
 */
data class SyncFailure(val error: SyncError, val detail: String? = null)

/** 带原因码的同步异常，便于在调用层还原成可翻译的提示。 */
class SyncException(val failure: SyncFailure) : IOException(failure.detail ?: failure.error.name)
