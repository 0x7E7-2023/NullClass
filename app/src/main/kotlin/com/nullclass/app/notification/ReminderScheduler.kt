package com.nullclass.app.notification

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nullclass.core.data.prefs.UserPreferencesRepository
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.DayOverrideRepository
import com.nullclass.core.data.repository.HolidayRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.ReminderPlanner
import com.nullclass.core.model.ScheduleFormat
import com.nullclass.core.model.UpcomingClass
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 课程提醒编排：排算未来 14 天的上课时刻，按配置走两条投递路径之一。
 *
 * - WorkManager（默认）：持久化、免权限；Doze 下误差 0~15 分钟
 * - 精确闹钟（可选，默认关）：开关开且系统授权后到点准时；未授权/关回落 WorkManager
 * - 跳过日期（节假日/手动）里的课不排（[ReminderPlanner.upcoming] 过滤）
 * - 串课（调休）当天排的是来源日的课，时刻按当天的作息（同上，口径在 core:model）
 * - uniqueWork 名 / 闹钟 URI 都含 blockId+startAt 保证幂等；先入队、再按名单取消不再需要的
 * - remindAt 已过但课未开始（仅 WorkManager 路径）→ 重新入队立即发；
 *   课已开始 → 按 [ReminderPlanner.shouldSendLate] 判定补发「已开始」迟发通知
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
    private val userPrefs: UserPreferencesRepository,
    private val holidayRepository: HolidayRepository,
    private val dayOverrideRepository: DayOverrideRepository,
    private val exactAlarmScheduler: ExactAlarmScheduler,
    private val examReminderScheduler: ExamReminderScheduler,
) {

    suspend fun reschedule() {
        val wm = WorkManager.getInstance(context)
        val leadMinutes = userPrefs.reminderLeadMinutes.first()
        val term = termRepository.getCurrent()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val sentKeys = userPrefs.sentReminderKeys.first()

        // 先入队、后按名单取消：取消与入队之间有多个 suspend 点（DataStore/Room 读），
        // 「先全 cancel 再入队」中途进程被杀会留下空窗——全部提醒丢失到下次触发。
        val desired = mutableSetOf<String>()
        if (leadMinutes != 0 && term != null) {
            val schedule = courseRepository.observeSchedule(term.id).first()
            val times = termRepository.getPeriodTimes(term.id)
            val skipDates = holidayRepository.skipEpochDays()
            val dayOverrides = dayOverrideRepository.indexNow()

            val planned = ReminderPlanner.upcoming(
                term, schedule, times, now,
                horizonDays = HORIZON_DAYS, zone = zone, skipDates = skipDates,
                dayOverrides = dayOverrides,
            )

            if (exactAlarmScheduler.canUseExact()) {
                // 精确路径：先清 WorkManager 侧全部课程提醒（防双发），再排闹钟。
                // remindAt 已过的不再补排——错过即错过，与系统闹钟口径一致；
                // 迟发补发判定两条路径共用。
                cancelEnqueuedClassWork(wm)
                val reminders = planned
                    .filter { it.startAtMillis > now }
                    .filter { !ReminderPlanner.isAlreadySent(it, sentKeys) }
                    .map { it.toExactReminder(leadMinutes, zone) }
                exactAlarmScheduler.reschedule(reminders, now)
                planned
                    .filter { it.startAtMillis <= now }
                    .forEach { sendLateIfNeeded(it, sentKeys) }
            } else {
                exactAlarmScheduler.cancelAll()
                planned.forEach { upcoming ->
                    val remindAt = upcoming.startAtMillis - leadMinutes * 60_000L
                    if (upcoming.startAtMillis <= now) {
                        // 课已开始：迟发补发（进行中且未发过才补）
                        sendLateIfNeeded(upcoming, sentKeys)
                        return@forEach
                    }
                    if (ReminderPlanner.isAlreadySent(upcoming, sentKeys)) {
                        // 已发过（±24h 容差匹配：时区切换后同一节课重算的 epoch 也能对上；
                        // 时钟回拨时 remindAt 会重新大于 now，同样不能再入队）——
                        // 重入队会复活已划掉的通知，或换时区后立即重复通知
                        return@forEach
                    }
                    val startMinuteOfDay = Instant.ofEpochMilli(upcoming.startAtMillis)
                        .atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }
                    val name = uniqueNameOf(upcoming.block.id, upcoming.startAtMillis)
                    wm.enqueueUniqueWork(
                        name,
                        ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<ClassStartWorker>()
                            // remindAt 已过（原任务被压住）时入队即发，
                            // 迟到几分钟的「9:00 · 高数」也比静默丢失强
                            .setInitialDelay(Duration.ofMillis((remindAt - now).coerceAtLeast(0)))
                            .setInputData(
                                workDataOf(
                                    ClassStartWorker.KEY_COURSE_NAME to upcoming.course.name,
                                    ClassStartWorker.KEY_LOCATION to (upcoming.block.location ?: ""),
                                    ClassStartWorker.KEY_PERIOD_LABEL to ScheduleFormat.periodRange(upcoming.block),
                                    ClassStartWorker.KEY_START_TIME_LABEL to ScheduleFormat.minuteLabel(startMinuteOfDay),
                                    ClassStartWorker.KEY_BLOCK_ID to upcoming.block.id,
                                    ClassStartWorker.KEY_START_AT to upcoming.startAtMillis,
                                ),
                            )
                            .addTag(TAG_CLASS_REMINDER)
                            // 名单标记：取消阶段据此识别这条 work 还需不需要
                            .addTag(name)
                            .build(),
                    )
                    desired += name
                }
            }
        } else {
            // 提醒关闭 / 无学期：两条路径全清
            exactAlarmScheduler.cancelAll()
        }

        // 只取消不再需要的（课被删 / 换学期 / 提前量改档 / 提醒关闭 desired 为空全取消）。
        // 只看 ENQUEUED：已执行（SUCCEEDED）和正在跑的（RUNNING）不碰。
        // 精确路径在上面已单独清过，desired 为空时这里同样把残留清干净。
        wm.getWorkInfosByTag(TAG_CLASS_REMINDER).get()
            .filter { it.state == WorkInfo.State.ENQUEUED }
            .forEach { info ->
                if (info.tags.none { it in desired }) {
                    wm.cancelWorkById(info.id)
                }
            }

        // 考试提醒使用独立提前量和通知渠道，但与课程提醒共用同一套重排触发点。
        examReminderScheduler.reschedule()
    }

    /** WorkManager 侧课程提醒全部取消（切精确路径时防双发）。 */
    private fun cancelEnqueuedClassWork(wm: WorkManager) {
        wm.getWorkInfosByTag(TAG_CLASS_REMINDER).get()
            .filter { it.state == WorkInfo.State.ENQUEUED }
            .forEach { wm.cancelWorkById(it.id) }
    }

    /** 迟发补发：判定过的课直接投「已开始」通知并落键；没赶上的静默跳过。 */
    private suspend fun sendLateIfNeeded(upcoming: UpcomingClass, sentKeys: Set<String>) {
        val now = System.currentTimeMillis()
        if (!ReminderPlanner.shouldSendLate(upcoming, now, sentKeys)) return
        val tag = ReminderPlanner.reminderTag(upcoming)
        val text = listOf(
            ScheduleFormat.periodRange(upcoming.block),
            upcoming.block.location ?: "",
        ).filter { it.isNotBlank() }.joinToString(" · ")
        if (ReminderNotifier.post(context, tag, "已开始 · ${upcoming.course.name}", text)) {
            userPrefs.markRemindersSent(listOf(tag))
        }
    }

    /** 精确提醒 payload：标题/正文与 ClassStartWorker 的格式完全一致。 */
    private fun UpcomingClass.toExactReminder(leadMinutes: Int, zone: ZoneId): ExactReminder {
        val startMinuteOfDay = Instant.ofEpochMilli(startAtMillis)
            .atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }
        return ExactReminder(
            tag = ReminderPlanner.reminderTag(this),
            remindAtMillis = startAtMillis - leadMinutes * 60_000L,
            title = "${ScheduleFormat.minuteLabel(startMinuteOfDay)} · ${course.name}",
            text = listOf(
                ScheduleFormat.periodRange(block),
                block.location ?: "",
            ).filter { it.isNotBlank() }.joinToString(" · "),
        )
    }

    companion object {
        const val TAG_CLASS_REMINDER = "class_reminder"
        const val HORIZON_DAYS = 14

        fun uniqueNameOf(blockId: String, startAtMillis: Long): String =
            "class_reminder_$blockId" + "_$startAtMillis"
    }
}
