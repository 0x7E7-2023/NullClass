package com.nullclass.feature.settings.transfer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.ExamRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.importer.ImportProvenance
import com.nullclass.importer.NullClassCodec
import com.nullclass.importer.QrPayload
import com.nullclass.importer.ScheduleDocument
import com.nullclass.importer.wakeup.WakeUpParser
import com.nullclass.sync.SnapshotCodec
import com.nullclass.sync.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 每学期摘要（预览界面）。 */
data class TermSummary(
    val name: String,
    val totalWeeks: Int,
    val courseCount: Int,
    val blockCount: Int,
    val examCount: Int = 0,
)

/** 导入预览：解析成功 → 用户确认合并。 */
data class ImportPreview(
    /** 来源描述：「文件 xxx.nullclass」/「二维码」/「WakeUp 课表」。 */
    val source: String,
    val document: ScheduleDocument,
    val termSummaries: List<TermSummary>,
    val warnings: List<String> = emptyList(),
    /**
     * **第三方适配器**要求用户核对的说明。
     *
     * 和 [warnings] 分开放是因为渲染方式必须不同：这几条是脚本写的，用户得看得出
     * 「这是那个适配器说的，不是空课说的」—— 否则脚本逐字复制宿主文案就能冒充我们。
     */
    val adapterNotes: List<String> = emptyList(),
    /** WakeUp 导入作为新学期，合并后设为当前学期（教务导入的学期由 ImportAligner 在合并里激活）。 */
    val activateTermName: String? = null,
    /**
     * 与本地库对比的删除预警：导入文档里携带的、会按 LWW 赢过本地的墓碑数
     * （用户之前导出过课表 → 对方拿到 UUID → 恶意/损坏文件可借墓碑删库）。
     * > 0 时 UI 必须醒目提示。
     */
    val pendingDeletions: Int = 0,
)

data class TransferUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val preview: ImportPreview? = null,
    val qrPayload: String? = null,
)

@HiltViewModel
class TransferViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val codec: SnapshotCodec,
    private val syncManager: SyncManager,
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
    private val examRepository: ExamRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TransferUiState())
    val state = _state.asStateFlow()

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun dismissPreview() = _state.update { it.copy(preview = null) }

    // ---- 导出 ----

    /** 建议的导出文件名（nullclass-<学期名>.nullclass，取最新学期）。 */
    suspend fun suggestedFileName(): String {
        val name = termRepository.getCurrent()?.name?.replace(Regex("[\\\\/:*?\"<>|]"), "-") ?: "schedule"
        return "nullclass-$name.nullclass"
    }

    /** 当前学期的日历导出文件名。日历文件只导出当前学期，避免把历史学期混进系统日历。 */
    suspend fun suggestedIcsFileName(): String {
        val name = termRepository.getCurrent()?.name?.replace(Regex("[\\\\/:*?\"<>|]"), "-") ?: "schedule"
        return "nullclass-$name.ics"
    }

    /** 导出全部数据（含所有学期与墓碑，与同步快照同构）。 */
    suspend fun exportDocument(): ScheduleDocument = codec.dump(deviceId = null)

    /** 写入 SAF 目标。 */
    fun writeDocumentTo(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(NullClassCodec.encode(codec.dump(deviceId = null)).toByteArray(Charsets.UTF_8))
                    } ?: error("无法打开目标文件")
                }
                _state.update { it.copy(busy = false, message = "已导出", messageIsError = false) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "导出失败：${e.message}", messageIsError = true) }
            }
        }
    }

    /** 生成当前学期的标准 iCalendar 文件并写入 SAF 目标。 */
    fun writeIcsTo(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                val result = withContext(Dispatchers.IO) { buildCurrentTermIcs() }
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(result.content.toByteArray(Charsets.UTF_8))
                    } ?: error("无法打开目标文件")
                }
                val skipped = if (result.skippedBlockCount == 0) {
                    ""
                } else {
                    "，跳过 ${result.skippedBlockCount} 条缺少完整节次时间的安排"
                }
                _state.update {
                    it.copy(
                        busy = false,
                        message = "已导出日历文件：${result.courseEventCount} 个课程安排、${result.examEventCount} 场考试$skipped",
                        messageIsError = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "日历导出失败：${e.message}", messageIsError = true) }
            }
        }
    }

    private suspend fun buildCurrentTermIcs(): IcsExportResult {
        val term = termRepository.getCurrent() ?: error("当前没有学期")
        return IcsCalendar.build(
            term = term,
            schedule = courseRepository.getSchedule(term.id),
            periodTimes = termRepository.getPeriodTimes(term.id),
            exams = examRepository.getForTerm(term.id),
        )
    }

    /** 把导出内容写到 cacheDir/shared/ 供系统分享面板发送。 @return FileProvider 可用的文件。 */
    suspend fun buildShareFile(): java.io.File = withContext(Dispatchers.IO) {
        val dir = java.io.File(appContext.cacheDir, "shared").apply { mkdirs() }
        // 旧分享文件清理，避免堆积
        dir.listFiles()?.forEach { it.delete() }
        java.io.File(dir, suggestedFileName()).apply {
            writeText(NullClassCodec.encode(codec.dump(deviceId = null)), Charsets.UTF_8)
        }
    }

    // ---- 二维码 ----

    fun generateQr() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null, qrPayload = null) }
            try {
                val current = termRepository.getCurrent()
                    ?: error("当前没有学期，无法生成二维码")
                val payload = withContext(Dispatchers.Default) {
                    val sliced = QrPayload.sliceForShare(codec.dump(deviceId = null), current.id)
                    QrPayload.encode(sliced)
                }
                _state.update { it.copy(busy = false, qrPayload = payload) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: QrPayload.PayloadTooLargeException) {
                _state.update {
                    it.copy(busy = false, message = "课表过大无法生成二维码，请用文件分享", messageIsError = true)
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "生成失败：${e.message}", messageIsError = true) }
            }
        }
    }

    fun dismissQr() = _state.update { it.copy(qrPayload = null) }

    /** 扫码页取消以外的失败（没权限、相机/识别异常、空内容）。 */
    fun onScanFailed(message: String) =
        _state.update { it.copy(message = message, messageIsError = true) }

    fun parseQrPayload(payload: String) {
        // 先快速判断（扫码结果可能是任意内容）
        if (!QrPayload.isNullClassPayload(payload)) {
            _state.update { it.copy(message = "不是空课二维码", messageIsError = true) }
            return
        }
        // v3 分享包是重铸 ID 的单学期书包（WakeUp 同款合并语义），导入后激活该学期；
        // v1/v2 旧码是多学期 LWW 导入，保持「不动当前学期」
        parseRaw(
            { QrPayload.decode(payload) },
            source = "二维码",
            activateTermName = { document ->
                if (document.deviceId == ImportProvenance.QR_IMPORT) {
                    document.terms.filter { it.deletedAt == null }.singleOrNull()?.name
                } else {
                    null
                }
            },
        )
    }

    /**
     * 教务提取回来的文档 → 预览。[adapterNotes] 是适配器要求重点核对的话，原样显示（标明来源）。
     *
     * 「设为当前学期」不在这里决定：合并时 [com.nullclass.sync.ImportAligner] 按来源
     * （教务 = 开始用新学期；备份 / v1 v2 旧扫码 = 不动）处理。
     */
    fun parseExtractedDocument(json: String, source: String, adapterNotes: List<String> = emptyList()) {
        parseRaw({ NullClassCodec.decode(json) }, source = source, adapterNotes = adapterNotes)
    }

    // ---- 文件导入 ----

    /** SAF/Intent 打开的文件 → 预览。 */
    fun importFromUri(uri: Uri, displayName: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val raw = try {
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: error("无法读取文件")
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "读取失败：${e.message}", messageIsError = true) }
                return@launch
            }
            _state.update { it.copy(busy = false) }
            parseRaw({ NullClassCodec.decode(raw) }, source = "文件 ${displayName ?: uri.lastPathSegment ?: ""}")
        }
    }

    /** WakeUp 文件 → 预览（新学期，合并后激活）。 */
    fun importWakeUpFromUri(uri: Uri, displayName: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val raw = try {
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: error("无法读取文件")
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "读取失败：${e.message}", messageIsError = true) }
                return@launch
            }
            _state.update { it.copy(busy = false) }
            try {
                val result = WakeUpParser.parse(raw)
                _state.update {
                    it.copy(
                        preview = ImportPreview(
                            source = "WakeUp ${displayName ?: "课表"}",
                            document = ScheduleDocument(
                                deviceId = "wakeup-import",
                                generatedAt = System.currentTimeMillis(),
                                terms = listOf(result.term),
                                courses = result.courses,
                                blocks = result.blocks,
                                periodTimes = result.periodTimes,
                            ),
                            termSummaries = listOf(
                                TermSummary(
                                    name = result.term.name,
                                    totalWeeks = result.term.totalWeeks,
                                    courseCount = result.courses.size,
                                    blockCount = result.blocks.size,
                                    examCount = 0,
                                ),
                            ),
                            warnings = result.warnings,
                            activateTermName = result.term.name,
                        ),
                    )
                }
            } catch (e: IllegalArgumentException) {
                _state.update { it.copy(message = e.message ?: "不是有效的 WakeUp 文件", messageIsError = true) }
            }
        }
    }

    // ---- 合并执行 ----

    fun confirmMerge() {
        val preview = _state.value.preview ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                // 走 SyncManager 同一把互斥锁：防止与后台 SyncWorker 的
                // dump→merge→apply 交错互相覆盖（尤其 periodTimes 整表替换，B9）
                val result = syncManager.mergeImport(preview.document)
                // WakeUp / v3 扫码分享作为新学期导入 → 设为当前学期方便立即查看
                // （教务导入的学期由 ImportAligner 在合并里标成当前；备份导入不动用户的当前学期）
                // 按名字 + 当前课表找，而不是按 id：重铸 ID 的来源被同名对齐时 id 会换成本地那份
                preview.activateTermName?.let { name ->
                    val activeTimetableId = termRepository.getCurrent()?.id?.let { currentId ->
                        result.merged.terms.firstOrNull { it.id == currentId }?.timetableId
                    }
                    result.merged.terms
                        .filter {
                            it.deletedAt == null && it.name == name &&
                                (activeTimetableId == null || it.timetableId == activeTimetableId)
                        }
                        .maxByOrNull { it.updatedAt }
                        ?.let { termRepository.setCurrent(it.id) }
                }
                _state.update {
                    val newTimetables = result.newTimetableNames
                    val suffix = when {
                        newTimetables.isEmpty() -> ""
                        newTimetables.size == 1 -> "，新增课表「${newTimetables.single()}」（我的 → 课表管理切换）"
                        else -> "，新增 ${newTimetables.size} 张课表（我的 → 课表管理切换）"
                    }
                    it.copy(
                        busy = false,
                        preview = null,
                        message = (if (result.adopted > 0) "已导入：采纳 ${result.adopted} 条记录" else "已导入（本地数据已是最新）") + suffix,
                        messageIsError = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "导入失败：${e.message}", messageIsError = true) }
            }
        }
    }

    // ---- 内部 ----

    private fun parseRaw(
        parser: () -> ScheduleDocument,
        source: String,
        adapterNotes: List<String> = emptyList(),
        /** 合并后要设为当前学期的学期名（重铸 ID 的来源按名激活——对齐可能换 id）。 */
        activateTermName: (ScheduleDocument) -> String? = { null },
    ) {
        try {
            val document = parser()
            if (document.terms.none { it.deletedAt == null }) {
                _state.update { it.copy(message = "文件里没有有效的学期数据", messageIsError = true) }
                return
            }
            val summaries = document.terms.filter { it.deletedAt == null }.map { term ->
                val courseIds = document.courses
                    .filter { it.deletedAt == null && it.termId == term.id }
                    .map { it.id }
                    .toSet()
                TermSummary(
                    name = term.name,
                    totalWeeks = term.totalWeeks,
                    courseCount = document.courses.count { it.deletedAt == null && it.termId == term.id },
                    blockCount = document.blocks.count { it.deletedAt == null && it.termId == term.id },
                    examCount = document.exams.count { it.deletedAt == null && it.courseId in courseIds },
                )
            }
            viewModelScope.launch {
                val deletions = try {
                    countPendingDeletions(document)
                } catch (e: Exception) {
                    0 // 本地读失败不阻塞预览；合并时会再兜底
                }
                _state.update {
                    it.copy(
                        preview = ImportPreview(
                            source = source,
                            document = document,
                            termSummaries = summaries,
                            adapterNotes = adapterNotes,
                            activateTermName = activateTermName(document),
                            pendingDeletions = deletions,
                        ),
                    )
                }
            }
        } catch (e: NullClassCodec.FutureVersionException) {
            _state.update { it.copy(message = e.message ?: "文件版本过新", messageIsError = true) }
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(message = e.message ?: "不是有效的空课文件", messageIsError = true) }
        } catch (e: Exception) {
            _state.update { it.copy(message = "解析失败：${e.message}", messageIsError = true) }
        }
    }

    /**
     * 信任边界：导入文档的墓碑会按 LWW 赢过本地同 id 记录（删除传播）。
     * 统计「文件里带墓碑且本地确实存在同 id 活记录」的数量——正常外部导入
     * 不会有针对本地活记录的定向墓碑，>0 几乎必然是异常/恶意文件，UI 必须醒目提示。
     */
    private suspend fun countPendingDeletions(document: ScheduleDocument): Int {
        val local = codec.dump(deviceId = null)
        fun <T> pending(imported: List<T>, localAlive: List<T>, id: (T) -> String, dead: (T) -> Boolean): Int {
            val aliveIds = localAlive.filter { !dead(it) }.map(id).toSet()
            return imported.count { dead(it) && id(it) in aliveIds }
        }
        return pending(document.timetables, local.timetables, { it.id }, { it.deletedAt != null }) +
            pending(document.terms, local.terms, { it.id }, { it.deletedAt != null }) +
            pending(document.courses, local.courses, { it.id }, { it.deletedAt != null }) +
            pending(document.blocks, local.blocks, { it.id }, { it.deletedAt != null }) +
            pending(document.exams, local.exams, { it.id }, { it.deletedAt != null })
    }
}
