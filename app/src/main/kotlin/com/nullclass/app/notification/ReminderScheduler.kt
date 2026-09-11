package com.nullclass.app.notification

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.data.prefs.UserPreferencesRepository
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
 * 提醒编排：排算未来 14 天的上课时刻，每条入队一个 OneTimeWorkRequest。
 *
 * - 持久化、免权限；Doze 下误差 0~15 分钟（课表场景可接受，文档明示）
 * - uniqueWork 名含 blockId+startAt 保证幂等；先入队、再按名单取消不再需要的
 * - remindAt 已过但课未开始 → 重新入队立即发（原任务被压住时不再静默丢失）
 * - 课已开始 → 按 [ReminderPlanner.shouldSendLate] 判定补发「已开始」迟发通知
 *   （进程死掉/被省电策略压住时 WorkManager 任务迟到，这条路径保证用户至少知道课开始了）
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
    private val userPrefs: UserPreferencesRepository,
) {

    suspend fun reschedule() {
        val wm = WorkManager.getInstance(context)
        val leadMinutes = userPrefs.reminderLeadMinutes.first()
        val term = termRepository.getCurrent()

        // 先入队、后按名单取消：取消与入队之间有多个 suspend 点（DataStore/Room 读），
        // 「先全 cancel 再入队」中途进程被杀会留下空窗——全部提醒丢失到下次触发。
        val desired = mutableSetOf<String>()
        if (leadMinutes != 0 && term != null) {
            val schedule = courseRepository.observeSchedule(term.id).first()
            val times = termRepository.getPeriodTimes(term.id)
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val sentKeys = userPrefs.sentReminderKeys.first()

            ReminderPlanner.upcoming(term, schedule, times, now, horizonDays = HORIZON_DAYS, zone = zone)
                .forEach { upcoming ->
                    val remindAt = upcoming.startAtMillis - leadMinutes * 60_000L
                    if (upcoming.startAtMillis <= now) {
                        // 课已开始：迟发补发（进行中且未发过才补）
                        sendLateIfNeeded(upcoming, now, sentKeys)
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

        // 只取消不再需要的（课被删 / 换学期 / 提前量改档 / 提醒关闭 desired 为空全取消）。
        // 只看 ENQUEUED：已执行（SUCCEEDED）和正在跑的（RUNNING）不碰。
        wm.getWorkInfosByTag(TAG_CLASS_REMINDER).get()
            .filter { it.state == WorkInfo.State.ENQUEUED }
            .forEach { info ->
                if (info.tags.none { it in desired }) {
                    wm.cancelWorkById(info.id)
                }
            }
    }

    /** 迟发补发：判定过的课直接投「已开始」通知并落键；没赶上的静默跳过。 */
    private suspend fun sendLateIfNeeded(upcoming: UpcomingClass, now: Long, sentKeys: Set<String>) {
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

    companion object {
        const val TAG_CLASS_REMINDER = "class_reminder"
        const val HORIZON_DAYS = 14

        fun uniqueNameOf(blockId: String, startAtMillis: Long): String =
            "class_reminder_$blockId" + "_$startAtMillis"
    }
}
