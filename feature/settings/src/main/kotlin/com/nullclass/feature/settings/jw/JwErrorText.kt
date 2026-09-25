package com.nullclass.feature.settings.jw

import androidx.annotation.StringRes
import com.nullclass.core.ui.i18n.UiText
import com.nullclass.core.ui.i18n.toUiText
import com.nullclass.feature.settings.R
import com.nullclass.importer.jw.JwCodedError
import com.nullclass.importer.jw.JwErrorCode

/**
 * 教务相关异常 → 用户看得懂、能翻译的提示。
 *
 * 带原因码的（[JwCodedError]）取对应词条；其余是给适配器作者看的规范诊断或系统原文，
 * 交给通用的 [toUiText]（有消息原样展示，没有用 [fallback]）。
 */
fun Throwable.toJwUiText(@StringRes fallback: Int): UiText {
    val coded = this as? JwCodedError
    val code = coded?.code ?: return toUiText(fallback)
    // 参数里的异常是下一层原因（「无法读取索引：网络请求失败：…」），递归取文案
    val args = coded.codeArgs.map { if (it is Throwable) it.toJwUiText(fallback) else it }
    return UiText.Res(code.messageRes, *args.toTypedArray())
}

@get:StringRes
private val JwErrorCode.messageRes: Int
    get() = when (this) {
        JwErrorCode.NETWORK_FAILED -> R.string.settings_jw_err_network_failed
        JwErrorCode.HTTP_STATUS -> R.string.settings_jw_err_http_status
        JwErrorCode.INSECURE_REDIRECT -> R.string.settings_jw_err_insecure_redirect
        JwErrorCode.EMPTY_RESPONSE -> R.string.settings_jw_err_empty_response
        JwErrorCode.RESPONSE_TOO_LARGE -> R.string.settings_jw_err_response_too_large
        JwErrorCode.NO_NEWER_VERSION -> R.string.settings_jw_err_no_newer_version
        JwErrorCode.INDEX_UNREADABLE -> R.string.settings_jw_err_index_unreadable
        JwErrorCode.ADAPTER_FILE_UNREADABLE -> R.string.settings_jw_err_adapter_file_unreadable
        JwErrorCode.LIBRARY_URL_EMPTY -> R.string.settings_jw_err_library_url_empty
        JwErrorCode.LIBRARY_URL_INVALID -> R.string.settings_jw_err_library_url_invalid
        JwErrorCode.LIBRARY_URL_NOT_HTTP -> R.string.settings_jw_err_library_url_not_http
        JwErrorCode.LIBRARY_URL_INSECURE -> R.string.settings_jw_err_library_url_insecure
        JwErrorCode.LIBRARY_URL_NO_HOST -> R.string.settings_jw_err_library_url_no_host
        JwErrorCode.LIBRARY_URL_GITHUB_SHAPE -> R.string.settings_jw_err_library_url_github_shape
        JwErrorCode.SPEC_TOO_NEW -> R.string.settings_jw_err_spec_too_new
        JwErrorCode.APP_TOO_OLD -> R.string.settings_jw_err_app_too_old
    }

/** 宿主限额触发时的说明（「脚本执行超时（60 秒）」）。 */
fun JwScriptLimitException.toUiText(): UiText = when (kind) {
    JwScriptLimitException.Kind.SCRIPT_TIMEOUT -> UiText.Res(R.string.settings_jw_script_timeout, value)
    JwScriptLimitException.Kind.WAIT_TIMEOUT -> UiText.Res(R.string.settings_jw_wait_timeout, value)
    JwScriptLimitException.Kind.RESULT_TOO_LARGE -> UiText.Res(R.string.settings_jw_result_too_large, value)
}
