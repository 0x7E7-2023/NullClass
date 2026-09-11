package com.nullclass.app

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.nullclass.core.data.prefs.UserPreferencesRepository
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.TodaySnapshot
import com.nullclass.widget.NextClassGlanceWidget
import com.nullclass.widget.TodayGlanceWidget
import com.nullclass.widget.buildTodaySnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
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
 *
 * 三条轨道全部异常自愈：一次 updateAll（Glance IPC）或 Room 读炸掉只会跳过这一拍并记日志，
 * 不会杀死协程——此前时钟轨裸奔，一次异常就静默停止分钟级刷新且无任何线索。
 */
@Singleton
class WidgetAutoUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
    private val userPreferences: UserPreferencesRepository,
) {

    private val todayWidget = TodayGlanceWidget()
    private val nextClassWidget = NextClassGlanceWidget()

    /**
     * 课表/学期变化版本号：数据轨推送时自增，唤醒睡眠中的时钟轨重算唤醒点——
     * 否则睡前算好的「睡到零点」会错过睡醒前新加的课（delay 期间数据轨只推不唤）。
     */
    private val dataVersion = MutableStateFlow(0)

    fun start(scope: CoroutineScope) {
        // 数据轨：Flow 收集本身抛错（Room 打不开等）时退避重启，推送体抛错跳过本次
        scope.launch(Dispatchers.Default) {
            while (true) {
                try {
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
                        .collect { pushBoth() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "data track died, restarting", e)
                    delay(RESTART_BACKOFF_MS)
                }
            }
        }
        // 字号档：设置页改完立即重绘。跳过首帧——启动时数据轨已经会 push 一次。
        scope.launch(Dispatchers.Default) {
            while (true) {
                try {
                    userPreferences.widgetFontSize.drop(1).collect { pushBoth() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "font track died, restarting", e)
                    delay(RESTART_BACKOFF_MS)
                }
            }
        }
        scope.launch(Dispatchers.Default) {
            var lastKey: String? = null
            while (true) {
                try {
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
                        pushBoth()
                        // 推送成功才提交 key：pushBoth 抛异常时不提交，
                        // 退避后的重试会重算出同一 key 再推一帧，丢的这帧能补回来
                        lastKey = key
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 这一拍炸了（Glance IPC / Room 读）：退避后下一拍照常，时钟不因此停摆
                    Log.e(TAG, "clock tick failed", e)
                    delay(RESTART_BACKOFF_MS)
                }
            }
        }
    }

    /** 数据/字号轨的推送体：自增版本唤醒时钟轨 + 刷两个小组件。 */
    private suspend fun pushBoth() {
        dataVersion.value++
        todayWidget.updateAll(context)
        nextClassWidget.updateAll(context)
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
        private const val TAG = "WidgetAutoUpdater"

        /** 空闲期最长睡眠：醒来重算一次（无变化不推送，只花一次 Room 读）。 */
        const val MAX_IDLE_SLEEP_MS = 60_000L * 60

        /** 轨道异常后的退避：防紧崩循环，也让故障可从 logcat 看出节奏。 */
        const val RESTART_BACKOFF_MS = 60_000L
    }
}
