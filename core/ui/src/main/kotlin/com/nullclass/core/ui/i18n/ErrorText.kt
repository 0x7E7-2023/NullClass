package com.nullclass.core.ui.i18n

import androidx.annotation.StringRes
import com.nullclass.core.model.DataException

/**
 * 把一个异常转成可展示给用户的提示。
 *
 * 四档，按优先级：
 * 1. [DataException] 带原因码，取对应的可翻译词条；
 * 2. [UiTextException] 抛出时已带好词条，直接用；
 * 3. 其他异常若带消息，原样展示 —— 那多半是系统或服务器给的原始信息，翻不了，
 *    但对反馈问题有用；
 * 4. 都没有时用调用方给的兜底词条。
 *
 * 这样各处 ViewModel 不必重复写同一段 `e.message ?: 默认文案`，
 * 也不会把内部错误码当成文案直接抛给用户。
 */
fun Throwable.toUiText(@StringRes fallback: Int): UiText = when {
    this is DataException -> UiText.Res(error.messageRes)
    this is UiTextException -> text
    !message.isNullOrBlank() -> UiText.Dynamic(message.orEmpty())
    else -> UiText.Res(fallback)
}

/**
 * 抛出点已经知道该怎么跟用户说的异常：把词条带上，[toUiText] 直接取用。
 *
 * [debugMessage] 只进日志与堆栈，不会展示给用户 —— 否则 `toUiText` 的第 3 档
 * 会把这句英文原样端到界面上。
 */
open class UiTextException(val text: UiText, debugMessage: String) : Exception(debugMessage)
