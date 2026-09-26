package com.nullclass.core.model

/**
 * 应用界面语言。
 *
 * [tag] 为 BCP-47 语言标记，与 `res/values-<qualifier>/` 资源目录、以及 Android 13+
 * 系统「应用语言」设置项一一对应；[SYSTEM] 无标记，表示跟随系统语言。
 *
 * 新增一种语言需要三步（详见 `docs/i18n.md`）：
 * 1. 在此枚举中增加一项，填入其 BCP-47 标记；
 * 2. 为各模块补一份 `res/values-<qualifier>/strings.xml` 译文；
 * 3. 译文完成后将该项加入 [TRANSLATED]，并在 `app/src/main/res/xml/locales_config.xml` 中登记。
 */
enum class AppLanguage(val tag: String?) {
    /** 跟随系统语言；系统语言无对应译文时回落到默认资源（简体中文）。 */
    SYSTEM(null),
    SIMPLIFIED_CHINESE("zh-Hans"),
    ENGLISH("en");

    /** 该语言的译文是否已就绪。未就绪的语言不会出现在语言选择项中。 */
    val isTranslated: Boolean get() = this in TRANSLATED

    companion object {
        /**
         * 译文已就绪、可供用户选择的语言。
         *
         * 须与 `app/src/main/res/xml/locales_config.xml` 一致；新增前先让
         * `tools/check_translations.py` 通过（它按该清单检查各模块译文是否齐全）。
         */
        private val TRANSLATED = setOf(SYSTEM, SIMPLIFIED_CHINESE, ENGLISH)

        /** 可供用户选择的语言，顺序与枚举声明一致。 */
        val selectable: List<AppLanguage> get() = entries.filter { it.isTranslated }

        /**
         * 是否需要向用户提供语言选择入口。
         *
         * 仅有一种译文时，「跟随系统」与该语言的实际效果完全相同，展示选择项没有意义，
         * 因此在第二种译文就绪前隐藏整个入口。
         */
        val isSelectionMeaningful: Boolean
            get() = selectable.count { it != SYSTEM } > 1

        fun fromName(name: String?): AppLanguage =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
