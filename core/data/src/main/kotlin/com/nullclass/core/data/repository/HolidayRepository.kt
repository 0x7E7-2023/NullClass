package com.nullclass.core.data.repository

import com.nullclass.core.data.db.dao.SkipDateDao
import com.nullclass.core.data.db.entity.SkipDateEntity
import com.nullclass.core.data.holiday.HolidayCnSource
import com.nullclass.core.data.holiday.HolidaySource
import com.nullclass.core.data.holiday.HolidayYear
import com.nullclass.core.data.holiday.NagerHolidaySource
import com.nullclass.core.data.holiday.TimorHolidaySource
import com.nullclass.core.data.prefs.UserPreferencesRepository
import com.nullclass.core.model.SkipDate
import com.nullclass.core.model.SkipDateType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 跳过日期（手动 + 节假日同步）的唯一读写入口。
 *
 * 节假日多源**逐年**按优先级降级：timor.tech（含调休补班）→ holiday-cn（国务院
 * 放假安排原文，含调休）→ Nager.Date（仅节假日）→ 保留上次缓存。学期跨年时
 * 常见「次年安排尚未发布」，此时该年保留旧缓存或用 Nager 法定日兜底，不影响
 * 已发布年份用最好的数据。同步只替换 HOLIDAY/WORKDAY 行，MANUAL 行永不被动。
 */
@Singleton
class HolidayRepository @Inject constructor(
    private val skipDateDao: SkipDateDao,
    private val termRepository: TermRepository,
    private val userPrefs: UserPreferencesRepository,
) {

    sealed interface RefreshResult {
        /** 成功，[source] 是实际采用的源名（跨年时可含多个，如「timor.tech + Nager.Date」）。 */
        data class Success(val source: String, val holidayCount: Int) : RefreshResult

        /** 未到同步间隔或开关关闭，本次跳过。 */
        data object Skipped : RefreshResult

        /** 全部源失败；旧缓存保持原样。 */
        data class Failed(val message: String) : RefreshResult
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** 按优先级排序的数据源。 */
    private val sources: List<HolidaySource> =
        listOf(TimorHolidaySource(client), HolidayCnSource(client), NagerHolidaySource(client))

    /** 全部跳过日期（含补班日），按日期升序。 */
    val skipDates: Flow<List<SkipDate>> =
        skipDateDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** 提醒调度用的「真正跳过」日期集（补班日不算）。 */
    suspend fun skipEpochDays(): Set<Long> =
        skipDateDao.getAll()
            .filter { it.type != SkipDateType.WORKDAY.name }
            .map { it.epochDay }
            .toSet()

    /**
     * 同步当前学期覆盖年份的节假日。[force]=true 跳过节流与开关检查（手动刷新按钮）。
     * 逐年选源：某年依次尝试各源，首个「可用且该年有数据」的生效；该年所有源都
     * 拿不到数据（如下一年安排未发布且兜底源也挂了）则不动该年旧缓存。
     * 任何年份都没有可用数据才算整体失败。
     */
    suspend fun refresh(force: Boolean = false): RefreshResult {
        val now = System.currentTimeMillis()
        if (!force) {
            if (!userPrefs.holidaySyncEnabled.first()) return RefreshResult.Skipped
            val lastSync = userPrefs.holidayLastSyncMs.first()
            if (lastSync > 0 && now - lastSync < SYNC_INTERVAL_MS) return RefreshResult.Skipped
        }

        val years = termYears().distinct()
        var lastError = "无可用数据源"
        val usedSources = LinkedHashSet<String>()
        var holidayCount = 0
        var wroteAny = false
        withContext(Dispatchers.IO) {
            years.forEach { year ->
                var picked: Pair<HolidayYear, String>? = null
                for (source in sources) {
                    val result = source.fetchYear(year)
                    if (result == null) {
                        lastError = "${source.name} 不可用"
                        continue
                    }
                    // 空结果 = 该年安排未发布（源正常），换下一源，不算源失败
                    if (result.holidays.isEmpty() && result.workdays.isEmpty()) continue
                    picked = result to source.name
                    break
                }
                if (picked == null) return@forEach
                val (result, sourceName) = picked
                usedSources += sourceName
                holidayCount += result.holidays.size
                val from = LocalDate.of(year, 1, 1).toEpochDay()
                val to = LocalDate.of(year, 12, 31).toEpochDay()
                // 去重 + 年份范围双重防御：源数据同日多条（脏数据/调休撞日）保第一条；
                // 跨年假期的越界日（如 2023.json 里元旦含 2022-12-31）必须滤掉——
                // 它绕过 replaceSyncedRange 的 DELETE 与 getManualDaysInRange 保护，
                // @Upsert 会把同日 MANUAL 行覆盖丢
                val rows = (result.holidays + result.workdays)
                    .distinctBy { it.epochDay }
                    .filter { it.epochDay in from..to }
                    .map { it.toEntity(now) }
                skipDateDao.replaceSyncedRange(rows, from, to)
                wroteAny = true
            }
        }
        return if (wroteAny) {
            userPrefs.setHolidayLastSyncMs(now)
            RefreshResult.Success(source = usedSources.joinToString(" + "), holidayCount = holidayCount)
        } else {
            RefreshResult.Failed("节假日同步失败（$lastError），已保留上次结果")
        }
    }

    /** 手动添加跳过日期；同一天已有节假日行时以手动语义覆盖。 */
    suspend fun addManualDate(epochDay: Long) {
        skipDateDao.upsertAll(
            listOf(SkipDateEntity(epochDay, SkipDateType.MANUAL.name, null, System.currentTimeMillis())),
        )
    }

    suspend fun removeDate(epochDay: Long) {
        skipDateDao.delete(epochDay)
    }

    /** 当前学期覆盖的日历年（跨年学期取两个）；无学期时取今年。 */
    private suspend fun termYears(): List<Int> {
        val term = termRepository.getCurrent() ?: return listOf(LocalDate.now().year)
        val startYear = LocalDate.ofEpochDay(term.firstDayEpochDay).year
        val endYear = LocalDate.ofEpochDay(term.firstDayEpochDay + term.totalWeeks * 7L - 1).year
        return (startYear..endYear).toList()
    }

    private fun SkipDateEntity.toDomain() = SkipDate(
        epochDay = epochDay,
        type = runCatching { SkipDateType.valueOf(type) }.getOrDefault(SkipDateType.MANUAL),
        label = label,
    )

    private fun SkipDate.toEntity(now: Long) =
        SkipDateEntity(epochDay = epochDay, type = type.name, label = label, updatedAt = now)

    companion object {
        /** 自动同步节流：一周内不重复拉。 */
        const val SYNC_INTERVAL_MS: Long = 7L * 24 * 3600 * 1000
    }
}
