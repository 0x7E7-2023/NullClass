package com.nullclass.importer.jw

/**
 * 教务适配器相关失败里，**普通用户**会碰到的那几类原因。
 *
 * 适配器规范校验（字段名、取值、包结构）是写给适配器作者的诊断，照旧只带中文消息；
 * 但网络失败、库地址填错、要求升级应用这几类，任何用户点「更新适配器库」「从链接添加」都可能碰到，
 * 必须能翻译。异常消息仍保留中文原文（日志、适配器作者看），界面层按 [JwErrorCode] 取词条
 * （映射见 `:feature:settings` 的 `JwErrorText.kt`）。
 */
enum class JwErrorCode {

    /** 网络请求本身失败。参数：系统给的原因。 */
    NETWORK_FAILED,

    /** 服务器返回非 2xx。参数：状态码。 */
    HTTP_STATUS,

    /** 被重定向到非 https 地址。参数：最终地址。 */
    INSECURE_REDIRECT,

    /** 响应为空。 */
    EMPTY_RESPONSE,

    /** 响应超过上限。参数：上限（KB）。 */
    RESPONSE_TOO_LARGE,

    /** 官方库没有比当前更新的版本。 */
    NO_NEWER_VERSION,

    /** 读不到适配器库索引。参数：下一层的原因（异常）。 */
    INDEX_UNREADABLE,

    /** 读不到某个适配器的文件。参数：适配器 key、文件路径、下一层的原因（异常）。 */
    ADAPTER_FILE_UNREADABLE,

    /** 库地址为空。 */
    LIBRARY_URL_EMPTY,

    /** 库地址不是合法 URL。参数：用户输入。 */
    LIBRARY_URL_INVALID,

    /** 库地址不是 http(s)。参数：用户输入。 */
    LIBRARY_URL_NOT_HTTP,

    /** 发布版只允许 https 的库地址。 */
    LIBRARY_URL_INSECURE,

    /** 库地址缺少主机名。参数：用户输入。 */
    LIBRARY_URL_NO_HOST,

    /** GitHub 地址不是「用户名/仓库名」的形状。 */
    LIBRARY_URL_GITHUB_SHAPE,

    /** 适配器规范版本高于本应用支持的。参数：适配器的版本、本应用支持的版本。 */
    SPEC_TOO_NEW,

    /** 适配器要求更新的空课。参数：要求的 versionCode、当前 versionCode。 */
    APP_TOO_OLD,
}

/**
 * 带原因码的教务异常。[code] 为 null 表示这是给适配器作者看的规范诊断，没有对应词条。
 *
 * [codeArgs] 的顺序与词条占位符一致；其中的异常（下一层原因）由界面层递归取文案。
 */
interface JwCodedError {
    val code: JwErrorCode?
    val codeArgs: List<Any>
}
