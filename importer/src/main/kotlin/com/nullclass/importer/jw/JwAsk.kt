package com.nullclass.importer.jw

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 适配器脚本向用户**提问**的请求（弹窗）。
 *
 * 契约（规范 §5.2）：
 * - 脚本只能拿到**受限的结果**：选项的**索引**、布尔、或用户敲进去的文本；
 * - 标题、说明、选项文本都由脚本提供，所以宿主必须把「这是脚本在问你」这件事
 *   摆在弹窗上（不可移除），否则第三方脚本可以在应用自己的界面里伪装成空课索要凭据；
 * - 取消一律是正常结果（`null` / `false`），不是错误 —— 适配器据此安全退出。
 */
sealed interface JwAskRequest {

    /** 脚本给的标题，≤ [JwAskLimits.MAX_TITLE] 字。 */
    val title: String

    /** 可选说明，≤ [JwAskLimits.MAX_MESSAGE] 字。 */
    val message: String?

    /** 让用户在若干选项里挑一个，只回传**索引**。 */
    data class Select(
        override val title: String,
        override val message: String? = null,
        val items: List<String>,
        val defaultIndex: Int = 0,
    ) : JwAskRequest

    /** 是/否确认，回传布尔。 */
    data class Confirm(
        override val title: String,
        override val message: String? = null,
        val confirmText: String? = null,
        val cancelText: String? = null,
    ) : JwAskRequest

    /** 自由文本输入，回传字符串。**唯一会把用户输入交给脚本的形态**，弹窗上必须带警示。 */
    data class Prompt(
        override val title: String,
        override val message: String? = null,
        val placeholder: String? = null,
        val defaultText: String = "",
        val maxLength: Int = JwAskLimits.DEFAULT_PROMPT_LENGTH,
    ) : JwAskRequest
}

/** 弹窗参数的硬限制（同时是 DoS 防线：脚本不能塞一个 16M 字符的标题进来）。 */
object JwAskLimits {
    /** 单次提取允许的提问次数。 */
    const val MAX_CALLS = 8

    const val MAX_TITLE = 60
    const val MAX_MESSAGE = 300
    const val MAX_ITEMS = 50
    const val MAX_ITEM_TEXT = 60
    const val MAX_DEFAULT_TEXT = 200
    const val DEFAULT_PROMPT_LENGTH = 100
    const val MAX_PROMPT_LENGTH = 200
}

/** 弹窗参数不合法（消息面向适配器作者）。 */
class JwAskException(message: String) : Exception(message)

/** 桥消息里的 `type` 与参数解析/校验（纯 JVM，可单测）。 */
object JwAskCodec {

    const val TYPE_SELECT = "askSelect"
    const val TYPE_CONFIRM = "askConfirm"
    const val TYPE_PROMPT = "askPrompt"

    val TYPES = setOf(TYPE_SELECT, TYPE_CONFIRM, TYPE_PROMPT)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    fun decode(type: String, optionsJson: String?): JwAskRequest {
        if (type !in TYPES) throw JwAskException("不认识的问题类型「$type」")
        if (optionsJson.isNullOrBlank()) throw JwAskException("缺少参数（options 为空）")
        return when (type) {
            TYPE_SELECT -> select(parse<SelectDto>(optionsJson))
            TYPE_CONFIRM -> confirm(parse<ConfirmDto>(optionsJson))
            else -> prompt(parse<PromptDto>(optionsJson))
        }
    }

    private inline fun <reified T> parse(raw: String): T = try {
        json.decodeFromString<T>(raw)
    } catch (e: SerializationException) {
        val detail = e.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        throw JwAskException("参数不是合法 JSON${if (detail.isEmpty()) "" else "：$detail"}")
    }

    private fun select(dto: SelectDto): JwAskRequest.Select {
        checkText("title", dto.title, required = true, max = JwAskLimits.MAX_TITLE)
        checkText("message", dto.message, required = false, max = JwAskLimits.MAX_MESSAGE)
        if (dto.items.isEmpty()) throw JwAskException("items 不能为空（没得选就没有必要问）")
        if (dto.items.size > JwAskLimits.MAX_ITEMS) {
            throw JwAskException("items 太多（${dto.items.size} 个，上限 ${JwAskLimits.MAX_ITEMS} 个）")
        }
        dto.items.forEachIndexed { index, item ->
            checkText("items[${index + 1}]", item, required = true, max = JwAskLimits.MAX_ITEM_TEXT)
        }
        // 越界的 defaultIndex 只是选不中默认项，不该把整次提问判死：夹到合法范围。
        val defaultIndex = dto.defaultIndex.coerceIn(0, dto.items.lastIndex)
        return JwAskRequest.Select(dto.title.trim(), dto.message?.trim(), dto.items, defaultIndex)
    }

    private fun confirm(dto: ConfirmDto): JwAskRequest.Confirm {
        checkText("title", dto.title, required = true, max = JwAskLimits.MAX_TITLE)
        checkText("message", dto.message, required = false, max = JwAskLimits.MAX_MESSAGE)
        checkText("confirmText", dto.confirmText, required = false, max = JwAskLimits.MAX_TITLE)
        checkText("cancelText", dto.cancelText, required = false, max = JwAskLimits.MAX_TITLE)
        return JwAskRequest.Confirm(
            title = dto.title.trim(),
            message = dto.message?.trim(),
            confirmText = dto.confirmText?.trim()?.ifEmpty { null },
            cancelText = dto.cancelText?.trim()?.ifEmpty { null },
        )
    }

    private fun prompt(dto: PromptDto): JwAskRequest.Prompt {
        checkText("title", dto.title, required = true, max = JwAskLimits.MAX_TITLE)
        checkText("message", dto.message, required = false, max = JwAskLimits.MAX_MESSAGE)
        checkText("placeholder", dto.placeholder, required = false, max = JwAskLimits.MAX_TITLE)
        checkText("defaultText", dto.defaultText, required = false, max = JwAskLimits.MAX_DEFAULT_TEXT)
        if (dto.maxLength !in 1..JwAskLimits.MAX_PROMPT_LENGTH) {
            throw JwAskException("maxLength=${dto.maxLength} 超出 1..${JwAskLimits.MAX_PROMPT_LENGTH}")
        }
        return JwAskRequest.Prompt(
            title = dto.title.trim(),
            message = dto.message?.trim(),
            placeholder = dto.placeholder?.trim()?.ifEmpty { null },
            defaultText = dto.defaultText,
            maxLength = dto.maxLength,
        )
    }

    private fun checkText(field: String, value: String?, required: Boolean, max: Int) {
        if (value == null) {
            if (required) throw JwAskException("缺少 $field")
            return
        }
        if (required && value.isBlank()) throw JwAskException("$field 不能为空")
        if (value.length > max) throw JwAskException("$field 太长（${value.length} 字，上限 $max 字）")
    }

    @Serializable
    private data class SelectDto(
        val title: String = "",
        val message: String? = null,
        val items: List<String> = emptyList(),
        val defaultIndex: Int = 0,
    )

    @Serializable
    private data class ConfirmDto(
        val title: String = "",
        val message: String? = null,
        val confirmText: String? = null,
        val cancelText: String? = null,
    )

    @Serializable
    private data class PromptDto(
        val title: String = "",
        val message: String? = null,
        val placeholder: String? = null,
        val defaultText: String = "",
        val maxLength: Int = JwAskLimits.DEFAULT_PROMPT_LENGTH,
    )
}
