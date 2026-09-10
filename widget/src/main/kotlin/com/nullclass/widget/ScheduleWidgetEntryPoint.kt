package com.nullclass.widget

import android.content.Context
import com.nullclass.core.data.prefs.UserPreferencesRepository
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.TermRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 小组件取依赖的唯一入口。
 *
 * 不把 receiver 标 @AndroidEntryPoint（Glance 广播生命周期注入坑），
 * provideGlance 里拿到普通 Context 后走 EntryPointAccessors —— 官方推荐路径。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScheduleWidgetEntryPoint {
    fun termRepository(): TermRepository
    fun courseRepository(): CourseRepository
    fun userPreferences(): UserPreferencesRepository
}

fun widgetEntryPoint(context: Context): ScheduleWidgetEntryPoint =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        ScheduleWidgetEntryPoint::class.java,
    )
