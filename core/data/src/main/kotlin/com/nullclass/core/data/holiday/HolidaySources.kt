package com.nullclass.core.data.holiday

import com.nullclass.core.model.SkipDate
import com.nullclass.core.model.SkipDateType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 一个节假日数据源对某一年的解析结果。
 * [holidays] 是放假日（不上课），[workdays] 是调休补班日（周末要上课，仅展示提示）。
 */
data class HolidayYear(
    val holidays: List<SkipDate>,
    val workdays: List<SkipDate>,
)

/** 节假日数据源：拉取失败/解析异常一律返回 null，由调用方降级到下一优先级。 */
interface HolidaySource {
    /** 展示名（同步结果提示用）。 */
    val name: String

    /**
     * 拉取某一年。null = 源本身不可用（网络/限流/脏数据），调用方换下一源；
     * 空列表 = 源正常但该年放假安排未发布（如年底拉下一年），同样换下一源，
     * 但不应记录为源失败。
     */
    suspend fun fetchYear(year: Int): HolidayYear?
}

/** 拉取 + 解析的公共骨架：HTTP 失败、非 2xx、解析异常都收敛成 null。 */
internal abstract class HttpHolidaySource(private val client: OkHttpClient) : HolidaySource {

    protected abstract fun urlOf(year: Int): String

    /** 从响应原文解析出一年结果；抛任何异常都视为本源不可用。 */
    protected abstract fun parse(body: String, year: Int): HolidayYear

    override suspend fun fetchYear(year: Int): HolidayYear? = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(Request.Builder().url(urlOf(year)).build()).execute()
        } catch (e: Exception) {
            null
        } ?: return@withContext null
        response.use {
            if (!it.isSuccessful) return@withContext null
            val body = it.body?.string() ?: return@withContext null
            try {
                parse(body, year)
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * 首选源 timor.tech：聚合国内节假日与调休安排，一次请求覆盖全年。
 * 响应形如 {"code":0,"holiday":{"10-01":{"holiday":true,"name":"国庆节","date":"2026-10-01"},…}}，
 * holiday=true 为放假日，false 为调休补班日。
 *
 * URL 注意（2026-09 实测）：
 * - 旧的 api.timor.tech 子域名已从 DNS 摘除（NXDOMAIN），必须走 timor.tech/api/…；
 * - 官方接口变更：指定年份时年份后要带斜杠（/api/holiday/year/2026/）。
 */
internal class TimorHolidaySource(client: OkHttpClient) : HttpHolidaySource(client) {

    override val name = "timor.tech"

    override fun urlOf(year: Int) = "https://timor.tech/api/holiday/year/$year/"

    override fun parse(body: String, year: Int): HolidayYear {
        val root = JSONObject(body)
        // 限流/出错时常见 200 + {"code":301,…} 错误体；缺 holiday 对象同理。
        // 抛出去走 fetchYear 的异常收敛 → null → 调用方降级下一源，
        // 绝不能当成「全年无节假日」的成功结果（那会把缓存清空还不降级）
        if (root.optInt("code", -1) != 0) {
            throw IllegalStateException("timor holiday api code=${root.optInt("code", -1)}")
        }
        val entries = root.optJSONObject("holiday")
            ?: throw IllegalStateException("timor holiday api missing holiday object")
        val holidays = mutableListOf<SkipDate>()
        val workdays = mutableListOf<SkipDate>()
        for (key in entries.keys()) {
            val entry = entries.optJSONObject(key) ?: continue
            // date 缺失/格式不对的脏条目直接跳过，不让单条数据废掉整年
            val date = runCatching {
                LocalDate.parse(entry.getString("date"), DateTimeFormatter.ISO_LOCAL_DATE)
            }.getOrNull() ?: continue
            val skip = SkipDate(
                epochDay = date.toEpochDay(),
                type = if (entry.optBoolean("holiday")) SkipDateType.HOLIDAY else SkipDateType.WORKDAY,
                label = entry.optString("name").takeIf { it.isNotBlank() },
            )
            if (skip.type == SkipDateType.HOLIDAY) holidays += skip else workdays += skip
        }
        // 全年一条都没有 = 该年放假安排未发布（国务院每年 11 月前后发布次年安排，
        // 返回 {"code":0,"holiday":{}}）。返回空结果让调用方按「该年无数据」
        // 换下一源兜底，而不是把整个源判死——学期跨年时这会让好年份也跟着降级。
        return HolidayYear(holidays, workdays)
    }
}

/**
 * 兜底源 holiday-cn（NateScarlet/holiday-cn，经 jsDelivr CDN，国内可直连）：
 * 数据逐条对应国务院每年发布的放假安排原文，含完整假期跨度与调休补班日。
 * 响应形如 {"year":2026,"papers":[…],"days":[{"name":"国庆节","date":"2026-10-01","isOffDay":true},…]}，
 * isOffDay=true 为放假日，false 为调休补班日；未来一年的安排未发布时 days 为空数组。
 */
internal class HolidayCnSource(client: OkHttpClient) : HttpHolidaySource(client) {

    override val name = "holiday-cn"

    override fun urlOf(year: Int) = "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/$year.json"

    override fun parse(body: String, year: Int): HolidayYear {
        val root = JSONObject(body)
        // 年份对不上说明 CDN 返回了别的文件（理论不该发生），按源失败处理
        if (root.optInt("year", -1) != year) {
            throw IllegalStateException("holiday-cn year mismatch: expected $year, got ${root.optInt("year", -1)}")
        }
        val days = root.optJSONArray("days")
            // days 缺失属于结构异常；空数组（安排未发布）走正常空结果
            ?: throw IllegalStateException("holiday-cn missing days array")
        val holidays = mutableListOf<SkipDate>()
        val workdays = mutableListOf<SkipDate>()
        for (i in 0 until days.length()) {
            val entry = days.optJSONObject(i) ?: continue
            val date = runCatching {
                LocalDate.parse(entry.getString("date"), DateTimeFormatter.ISO_LOCAL_DATE)
            }.getOrNull() ?: continue
            val skip = SkipDate(
                epochDay = date.toEpochDay(),
                type = if (entry.optBoolean("isOffDay")) SkipDateType.HOLIDAY else SkipDateType.WORKDAY,
                label = entry.optString("name").takeIf { it.isNotBlank() },
            )
            if (skip.type == SkipDateType.HOLIDAY) holidays += skip else workdays += skip
        }
        return HolidayYear(holidays, workdays)
    }
}

/**
 * 兜底源 Nager.Date：国际公共节假日数据，仅节假日、无调休信息；
 * 前两个源都不可用时保底「至少知道哪天放假」。
 * 注意其中国数据只有单条法定日（国庆仅 10-01，无 10-02…10-07 的连休），
 * 只能作最后防线。
 */
internal class NagerHolidaySource(client: OkHttpClient) : HttpHolidaySource(client) {

    override val name = "Nager.Date"

    override fun urlOf(year: Int) = "https://date.nager.at/api/v3/PublicHolidays/$year/CN"

    override fun parse(body: String, year: Int): HolidayYear {
        val array = org.json.JSONArray(body)
        val holidays = mutableListOf<SkipDate>()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val date = runCatching {
                LocalDate.parse(entry.getString("date"), DateTimeFormatter.ISO_LOCAL_DATE)
            }.getOrNull() ?: continue
            holidays += SkipDate(
                epochDay = date.toEpochDay(),
                type = SkipDateType.HOLIDAY,
                label = entry.optString("localName").takeIf { it.isNotBlank() }
                    ?: entry.optString("name").takeIf { it.isNotBlank() },
            )
        }
        return HolidayYear(holidays, emptyList())
    }
}
