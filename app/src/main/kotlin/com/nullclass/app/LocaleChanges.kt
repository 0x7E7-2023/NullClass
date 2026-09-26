package com.nullclass.app

import android.os.LocaleList
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 界面语言实际生效的版本号：每变一次自增。
 *
 * Activity 收到配置变更（13+ 由系统下发，12 及以下由 MainActivity 自己原地切换）后按新语言重组；但 Activity 之外
 * 已经生成好的文字不会 —— 通知渠道名、已排期提醒里预先渲染的标题正文、桌面小组件。
 * 它们观察这里，语言一变就重建渠道、重排提醒、重画小组件。
 *
 * 由 [NullClassApplication] 在启动、配置变化、12 及以下应用内切换语言后上报。
 */
@Singleton
class LocaleChanges @Inject constructor() {

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private var last: LocaleList? = null

    /** 上报当前 Application 资源的语言；与上次不同才算一次变化（首次只记录）。 */
    @Synchronized
    fun report(locales: LocaleList) {
        val previous = last
        last = locales
        if (previous != null && previous != locales) _version.value++
    }
}
