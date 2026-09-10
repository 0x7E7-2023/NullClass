package com.nullclass.app

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.TodaySnapshot
import com.nullclass.widget.NextClassGlanceWidget
import com.nullclass.widget.TodayGlanceWidget
import com.nullclass.widget.buildTodaySnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 小组件推送刷新（双轨）：
 * - 数据轨：Room Flow 变化 → 防抖 → updateAll，并自增 [dataVersion] 唤醒时钟轨。
 * - 时钟轨：上课中每分钟一拍（驱动小组件「上课中·还剩X分钟」倒计时）；
 *   课间/课前睡到下一个上课时刻或跨天零点再醒。
 * 进程死后由 DailyMaintenanceWorker 兜底。
 */
@Singleton
class WidgetAutoUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
) {

    private val todayWidget = TodayGlanceWidget()
    private val nextClassWidget = NextClassGlanceWidget()

    /**
     * 课表/学期变化版本号：数据轨推送时自增，唤醒睡眠中的时钟轨重算唤醒点——
     * 否则睡前算好的「睡到零点」会错过睡醒前新加的课（delay 期间数据轨只推不唤）。
     */
    private val dataVersion = MutableStateFlow(0)

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.Default) {
            termRepository.observeCurrent()
                .flatMapLatest { term ->
                    if (term == null) {
                        flowOf(null)
                    } else {
                        courseRepository.observeSchedule(term.id).map { term }
                    }
                }
                // 编辑保存连发多条通知，防抖合并
                .debounce(500)
                .collect {
                    dataVersion.value++
                    todayWidget.updateAll(context)
                    nextClassWidget.updateAll(context)
                }
        }
        scope.launch(Dispatchers.Default) {
            var lastKey: String? = null
            while (true) {
                val now = LocalTime.now()
                val nowMinute = now.hour * 60 + now.minute
                val snapshot = buildTodaySnapshot(termRepository, courseRepository)
                val ongoing = snapshot.inProgress(nowMinute)
                // key 变了才推：上课中随剩余分钟走拍，其余时段只在课节切换/跨天时动
                val key = buildString {
                    append(LocalDate.now())
                    append('|')
                    append(ongoing?.placed?.block?.id)
                    append(':')
                    append(ongoing?.let { snapshot.remainingMinutes(it, nowMinute) })
                    append('|')
                    append(snapshot.nextUp(nowMinute)?.placed?.block?.id)
                }
                if (key != lastKey) {
                    lastKey = key
                    todayWidget.updateAll(context)
                    nextClassWidget.updateAll(context)
                }
                val versionAtSleep = dataVersion.value
                // 睡到下一拍：数据轨推送（version 变化）立即醒；最长 1 小时兜底醒一次
                // （delay 走单调时钟，系统墙钟跳变/深睡挂起时防止睡过头）
                withTimeoutOrNull(
                    minOf(
                        nextTickDelay(snapshot, nowMinute, now.second),
                        MAX_IDLE_SLEEP_MS,
                    ),
                ) {
                    dataVersion.first { it > versionAtSleep }
                }
            }
        }
    }

    /**
     * 时钟轨下一拍隔多久：上课中 1 分钟（倒计时走拍）；
     * 课间/课前睡到今天下一个上课时刻或跨天零点——醒来重组快照即接管新一天。
     */
    private fun nextTickDelay(snapshot: TodaySnapshot, nowMinute: Int, secondOfMinute: Int): Long {
        val minuteMs = 60_000L
        if (snapshot.inProgress(nowMinute) != null) return minuteMs - secondOfMinute * 1_000L
        val nextStart = snapshot.blocks.asSequence()
            .map { it.startMinuteOfDay }
            .filter { it > nowMinute }
            .minOrNull()
            ?: 1440 // 今天没课了：睡到零点，下一拍的快照已换新日期
        return ((nextStart - nowMinute) * minuteMs - secondOfMinute * 1_000L).coerceAtLeast(30_000L)
    }

    private companion object {
        /** 空闲期最长睡眠：醒来重算一次（无变化不推送，只花一次 Room 读）。 */
        const val MAX_IDLE_SLEEP_MS = 60_000L * 60
    }
}
