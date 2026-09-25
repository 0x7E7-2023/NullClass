package com.nullclass.core.ui.i18n

import android.content.Context
import androidx.annotation.PluralsRes
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

    /**
     * 数量词条（`<plurals>`），按 [quantity] 选单复数形式。
     *
     * 中文只有一档，但英文等语言「1 minute / 2 minutes」必须分开，所以凡是数量可能为 1 的计数都走这里。
     * [args] 为空时以 [quantity] 作唯一参数（对应词条里的 `%1$d`）；否则按 [args] 代入，
     * 规则同 [Res]，可以嵌套 [UiText]。
     */
    class Plural(@PluralsRes val id: Int, val quantity: Int, vararg val args: Any) : UiText {
        override fun equals(other: Any?): Boolean =
            other is Plural && other.id == id && other.quantity == quantity && other.args.contentEquals(args)

        override fun hashCode(): Int = 31 * (31 * id + quantity) + args.contentHashCode()

        override fun toString(): String = "UiText.Plural($id, $quantity, ${args.joinToString()})"
    }

    /** 运行期内容，不参与翻译。 */
    @JvmInline
    value class Dynamic(val value: String) : UiText

    /**
     * 几段文字用分隔符连成一句，如「甲、乙、丙」。
     *
     * 分隔符因语言而异（中文「、」「；」，英文 ", " "; "），不能在 ViewModel 里写死，
     * 一般传分隔符词条（`common_list_separator` 等）；与语言无关的（换行）可以传 [Dynamic]。
     * [parts] 里可以放 [UiText] 或普通字符串（课程名等运行期内容）。
     */
    class Joined(val parts: List<Any>, val separator: UiText) : UiText {
        constructor(parts: List<Any>, @StringRes separator: Int) : this(parts, Res(separator))

        override fun equals(other: Any?): Boolean =
            other is Joined && other.parts == parts && other.separator == separator

        override fun hashCode(): Int = 31 * separator.hashCode() + parts.hashCode()

        override fun toString(): String = "UiText.Joined($parts, $separator)"
    }

    fun resolve(context: Context): String = when (this) {
        is Dynamic -> value
        is Res -> if (args.isEmpty()) {
            context.getString(id)
        } else {
            context.getString(id, *resolveArgs(context, args))
        }
        is Plural -> if (args.isEmpty()) {
            context.resources.getQuantityString(id, quantity, quantity)
        } else {
            context.resources.getQuantityString(id, quantity, *resolveArgs(context, args))
        }
        is Joined -> parts.joinToString(separator.resolve(context)) {
            if (it is UiText) it.resolve(context) else it.toString()
        }
    }
}

private fun resolveArgs(context: Context, args: Array<out Any>): Array<Any> =
    Array(args.size) { index -> args[index].let { if (it is UiText) it.resolve(context) else it } }

/** 在 Compose 中按当前语言取出文字。 */
@Composable
fun UiText.resolve(): String = resolve(LocalContext.current)

/** 便捷构造：`R.string.x.asUiText()`。 */
fun Int.asUiText(vararg args: Any): UiText = UiText.Res(this, *args)

/** 便捷构造：运行期文字。 */
fun String.asDynamicUiText(): UiText = UiText.Dynamic(this)
