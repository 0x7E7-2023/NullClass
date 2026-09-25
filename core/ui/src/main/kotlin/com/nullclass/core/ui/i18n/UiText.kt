package com.nullclass.core.ui.i18n

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 一段「尚未取出文字」的文案。
 *
 * ViewModel、仓库等非 Compose 层不应直接持有中文字符串 —— 那样文案就被钉死在业务代码里，
 * 语言切换后不会更新。这些层改为返回 [UiText]，由界面层在渲染时解析成当前语言的文字。
 *
 * - [Res]：来自字符串资源，随语言切换而变，是默认选择。
 * - [Dynamic]：运行期才产生、无法预先翻译的内容（课程名、服务器返回的错误详情、
 *   第三方适配器提供的说明等），原样展示。
 */
sealed interface UiText {

    /**
     * 字符串资源，[args] 对应资源中的格式化占位符。
     *
     * [args] 里可以再放 [UiText]，解析时会先把它取成文字再代入 —— 例如把
     * 「结论」与「原始详情」拼成一句时，结论本身也是一条可翻译的词条。
     */
    class Res(@StringRes val id: Int, vararg val args: Any) : UiText {
        override fun equals(other: Any?): Boolean =
            other is Res && other.id == id && other.args.contentEquals(args)

        override fun hashCode(): Int = 31 * id + args.contentHashCode()

        override fun toString(): String = "UiText.Res($id, ${args.joinToString()})"
    }

    /** 运行期内容，不参与翻译。 */
    @JvmInline
    value class Dynamic(val value: String) : UiText

    /**
     * 几段文字用分隔符词条连成一句，如「甲、乙、丙」。
     *
     * 分隔符因语言而异（中文「、」「；」，英文 ", " "; "），不能在 ViewModel 里写死；
     * [parts] 里可以放 [UiText] 或普通字符串（课程名等运行期内容）。
     */
    class Joined(val parts: List<Any>, @StringRes val separator: Int) : UiText {
        override fun equals(other: Any?): Boolean =
            other is Joined && other.parts == parts && other.separator == separator

        override fun hashCode(): Int = 31 * separator + parts.hashCode()

        override fun toString(): String = "UiText.Joined($parts, $separator)"
    }

    fun resolve(context: Context): String = when (this) {
        is Dynamic -> value
        is Res -> if (args.isEmpty()) {
            context.getString(id)
        } else {
            val resolved = Array(args.size) { index ->
                args[index].let { if (it is UiText) it.resolve(context) else it }
            }
            context.getString(id, *resolved)
        }
        is Joined -> parts.joinToString(context.getString(separator)) {
            if (it is UiText) it.resolve(context) else it.toString()
        }
    }
}

/** 在 Compose 中按当前语言取出文字。 */
@Composable
fun UiText.resolve(): String = resolve(LocalContext.current)

/** 便捷构造：`R.string.x.asUiText()`。 */
fun Int.asUiText(vararg args: Any): UiText = UiText.Res(this, *args)

/** 便捷构造：运行期文字。 */
fun String.asDynamicUiText(): UiText = UiText.Dynamic(this)
