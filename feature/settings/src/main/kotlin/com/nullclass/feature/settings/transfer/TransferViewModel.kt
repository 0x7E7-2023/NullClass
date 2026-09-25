package com.nullclass.feature.settings.transfer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.ExamRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.ui.i18n.toUiText as throwableToUiText
import com.nullclass.core.ui.i18n.UiText
import com.nullclass.core.ui.i18n.UiTextException
import com.nullclass.core.ui.R as CoreR
import com.nullclass.feature.settings.R
import com.nullclass.feature.settings.toUiText as importIssueToUiText
import com.nullclass.importer.ImportNoticeEntry
import com.nullclass.importer.ImportProvenance
import com.nullclass.importer.NullClassCodec
import com.nullclass.importer.QrPayload
import com.nullclass.importer.ScheduleDocument
import com.nullclass.importer.ScheduleFileException
import com.nullclass.importer.shiguang.ShiguangParser
import com.nullclass.importer.wakeup.WakeUpParser
import com.nullclass.sync.SnapshotCodec
import com.nullclass.sync.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val source: UiText,
    val section: TransferSection,
    val document: ScheduleDocument,
    val termSummaries: List<TermSummary>,
    /** 解析时跳过了什么、替成了什么。文案随界面语言变化，因此在这里就取成 [UiText]。 */
    val warnings: List<UiText> = emptyList(),
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
    val section: TransferSection? = null,
    val busy: Boolean = false,
    /** 非界面层不持有文字：随语言切换的内容一律走 [UiText]。 */
    val message: UiText? = null,
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
            _state.update { it.copy(section = TransferSection.BACKUP, busy = true, message = null) }
            try {
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(NullClassCodec.encode(codec.dump(deviceId = null)).toByteArray(Charsets.UTF_8))
                    } ?: throw CannotOpenTargetException()
                }
                _state.update {
                    it.copy(
                        busy = false,
                        message = UiText.Res(R.string.settings_transfer_export_done),
                        messageIsError = false,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        message = UiText.Res(
                            R.string.settings_transfer_export_failed,
                            e.throwableToUiText(CoreR.string.common_unknown_error),
                        ),
                        messageIsError = true,
                    )
                }
            }
        }
    }

    /** 生成当前学期的标准 iCalendar 文件并写入 SAF 目标。 */
    fun writeIcsTo(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(section = TransferSection.CALENDAR, busy = true, message = null) }
            try {
                val result = withContext(Dispatchers.IO) { buildCurrentTermIcs() }
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(result.content.toByteArray(Charsets.UTF_8))
                    } ?: throw CannotOpenTargetException()
                }
                // 这一小句是嵌进上一句的，本身也是一条可翻译词条（UiText 支持嵌套代入）
                val skipped: Any = if (result.skippedBlockCount == 0) {
                    ""
                } else {
                    UiText.Res(R.string.settings_transfer_ics_skipped, result.skippedBlockCount)
                }
                _state.update {
                    it.copy(
                        busy = false,
                        message = UiText.Res(
                            R.string.settings_transfer_ics_export_done,
                            result.courseEventCount,
                            result.examEventCount,
                            skipped,
                        ),
                        messageIsError = false,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        message = UiText.Res(
                            R.string.settings_transfer_ics_export_failed,
                            e.throwableToUiText(CoreR.string.common_unknown_error),
                        ),
                        messageIsError = true,
                    )
                }
            }
        }
    }

    private suspend fun buildCurrentTermIcs(): IcsExportResult {
        val term = termRepository.getCurrent() ?: throw NoCurrentTermException()
        return IcsCalendar.build(
            text = IcsText.from(appContext),
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
            _state.update { it.copy(section = TransferSection.BACKUP, busy = true, message = null, qrPayload = null) }
            try {
                val current = termRepository.getCurrent() ?: throw NoCurrentTermException()
                val payload = withContext(Dispatchers.Default) {
                    val sliced = QrPayload.sliceForShare(codec.dump(deviceId = null), current.id)
                    QrPayload.encode(sliced)
                }
                _state.update { it.copy(busy = false, qrPayload = payload) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: QrPayload.PayloadTooLargeException) {
                _state.update {
                    it.copy(
                        busy = false,
                        message = UiText.Res(
                            CoreR.string.error_import_qr_too_large,
                            e.size,
                            e.limit,
                        ),
                        messageIsError = true,
                    )
                }
            } catch (e: NoCurrentTermException) {
                _state.update {
                    it.copy(
                        busy = false,
                        message = UiText.Res(R.string.settings_transfer_no_term_for_qr),
                        messageIsError = true,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        message = UiText.Res(
                            R.string.settings_transfer_qr_failed,
                            e.throwableToUiText(CoreR.string.common_unknown_error),
                        ),
                        messageIsError = true,
                    )
                }
            }
        }
    }

    fun dismissQr() = _state.update { it.copy(qrPayload = null) }

    /**
     * 扫码页取消以外的失败（没权限、相机/识别异常、空内容）。
     *
     * 传进来的文字是界面层已经取好的（相机异常详情、权限提示），原样展示。
     */
    fun onScanFailed(message: UiText) = onOperationFailed(TransferSection.QR, message)

    fun onOperationFailed(section: TransferSection, message: UiText) =
        _state.update { it.copy(section = section, busy = false, message = message, messageIsError = true) }

    fun beginScan() = _state.update { it.copy(section = TransferSection.QR, message = null) }

    fun importQrFromUri(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(section = TransferSection.QR, busy = true, message = null) }
            try {
                val payload = readQrImage(appContext, uri)
                parseQrPayload(payload)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onScanFailed(e.throwableToUiText(R.string.settings_transfer_qr_unreadable))
            }
        }
    }

    fun parseQrPayload(payload: String) {
        _state.update { it.copy(section = TransferSection.QR, busy = false, message = null) }
        // 先快速判断（扫码结果可能是任意内容）
        if (!QrPayload.isNullClassPayload(payload)) {
            _state.update {
                it.copy(
                    message = UiText.Res(R.string.settings_transfer_qr_not_nullclass),
                    messageIsError = true,
                )
            }
            return
        }
        // v3 分享包是重铸 ID 的单学期书包（WakeUp 同款合并语义），导入后激活该学期；
        // v1/v2 旧码是多学期 LWW 导入，保持「不动当前学期」
        parseRaw(
            { QrPayload.decode(payload) },
            source = UiText.Res(R.string.settings_transfer_qr_source),
            section = TransferSection.QR,
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
    fun parseExtractedDocument(json: String, source: UiText, adapterNotes: List<String> = emptyList()) {
        parseRaw(
            { NullClassCodec.decode(json) },
            source = source,
            section = TransferSection.JW,
            adapterNotes = adapterNotes,
        )
    }

    // ---- 文件导入 ----

    /**
     * SAF/Intent 打开的文件 → 预览。
     *
     * 用户直接点开拾光导出的 json 走的也是这条路（系统只知道它是 json，不知道是谁的），
     * 所以这里认一下：不是我们的格式、长得像拾光，就交给拾光解析器，别甩一句
     * 「文件格式不对」让用户去猜。
     */
    fun importFromUri(uri: Uri, displayName: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(section = TransferSection.FILE, busy = true, message = null) }
            val raw = readTextOrFail(uri, TransferSection.FILE) ?: return@launch
            _state.update { it.copy(busy = false) }
            if (ShiguangParser.looksLikeShiguang(raw)) {
                previewShiguang(raw, section = TransferSection.FILE, displayName = displayName)
                return@launch
            }
            parseRaw(
                { NullClassCodec.decode(raw) },
                source = UiText.Res(
                    R.string.settings_transfer_file_source,
                    displayName ?: uri.lastPathSegment.orEmpty(),
                ),
                section = TransferSection.FILE,
            )
        }
    }

    /** WakeUp 文件 → 预览（新学期，合并后激活）。 */
    fun importWakeUpFromUri(uri: Uri, displayName: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(section = TransferSection.WAKEUP, busy = true, message = null) }
            val raw = readTextOrFail(uri, TransferSection.WAKEUP) ?: return@launch
            _state.update { it.copy(busy = false) }
            try {
                val result = WakeUpParser.parse(raw)
                _state.update {
                    it.copy(
                        preview = ImportPreview(
                            source = UiText.Res(
                                R.string.settings_transfer_wakeup_source,
                                displayName ?: appContext.getString(R.string.settings_transfer_wakeup_generic),
                            ),
                            section = TransferSection.WAKEUP,
                            document = ScheduleDocument(
                                deviceId = ImportProvenance.WAKEUP_IMPORT,
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
                            warnings = result.warnings.map { it.importIssueToUiText() },
                            activateTermName = result.term.name,
                        ),
                    )
                }
            } catch (e: ScheduleFileException) {
                _state.update {
                    it.copy(
                        section = TransferSection.WAKEUP,
                        message = e.importIssueToUiText(),
                        messageIsError = true,
                    )
                }
            }
        }
    }

    /**
     * 拾光课程表导出的 json 文件 → 预览（新学期，合并后激活）。
     *
     * 导出文件里没有课表名，所以学期名取文件名（`shiguangschedule_20260920_101530.json`
     * 这种自动名没信息量，交给解析器用默认名）。
     */
    fun importShiguangFromUri(uri: Uri, displayName: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(section = TransferSection.SHIGUANG, busy = true, message = null) }
            val raw = readTextOrFail(uri, TransferSection.SHIGUANG) ?: return@launch
            _state.update { it.copy(busy = false) }
            previewShiguang(raw, section = TransferSection.SHIGUANG, displayName = displayName)
        }
    }

    /** 拾光解析 → 预览（两个入口共用：专门的迁移按钮，和「点开 json 文件」的兜底识别）。 */
    private fun previewShiguang(raw: String, section: TransferSection, displayName: String?) {
        try {
            val result = ShiguangParser.parse(raw, termName = termNameFromShiguangFile(displayName))
            _state.update {
                it.copy(
                    section = section,
                    preview = ImportPreview(
                        source = UiText.Res(
                            R.string.settings_transfer_shiguang_source,
                            displayName ?: appContext.getString(R.string.settings_transfer_shiguang_generic),
                        ),
                        section = section,
                        document = ScheduleDocument(
                            deviceId = ImportProvenance.SHIGUANG_IMPORT,
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
                        warnings = result.warnings.map { it.importIssueToUiText() },
                        activateTermName = result.term.name,
                    ),
                )
            }
        } catch (e: ScheduleFileException) {
            _state.update {
                it.copy(section = section, message = e.importIssueToUiText(), messageIsError = true)
            }
        }
    }

    /** 读取 SAF 文件内容；失败时直接写进 state 并返回 null（调用方 return）。 */
    private suspend fun readTextOrFail(uri: Uri, section: TransferSection): String? = try {
        withContext(Dispatchers.IO) {
            appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw CannotReadFileException()
        }
    } catch (e: Exception) {
        val reason = if (e is CannotReadFileException) {
            UiText.Res(R.string.settings_transfer_cannot_read_file)
        } else {
            UiText.Dynamic(e.message ?: e.javaClass.simpleName)
        }
        _state.update {
            it.copy(
                section = section,
                busy = false,
                message = if (e is CannotReadFileException) {
                    reason
                } else {
                    UiText.Res(R.string.settings_transfer_read_failed, reason)
                },
                messageIsError = true,
            )
        }
        null
    }

    /**
     * 拾光的导出文件名是 `shiguangschedule_yyyyMMdd_HHmmss.json`（没有课表名），
     * 用户自己改过名才有信息量——认得出自动名就交回解析器的默认学期名。
     */
    private fun termNameFromShiguangFile(displayName: String?): String? {
        val base = displayName?.substringBeforeLast('.')?.trim().orEmpty()
        if (base.isEmpty()) return null
        return if (Regex("""^shiguangschedule[_-]?\d*[_-]?\d*$""", RegexOption.IGNORE_CASE).matches(base)) null else base
    }

    // ---- 合并执行 ----

    fun confirmMerge() {
        val preview = _state.value.preview ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(section = preview.section, busy = true, message = null) }
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
                        // 后缀嵌进上面那句的结果里，所以用字符串资源而不是 UiText：留空串代表没有
                        newTimetables.size == 1 ->
                            appContext.getString(
                                R.string.settings_transfer_new_timetable_one,
                                newTimetables.single(),
                            )
                        else ->
                            appContext.getString(
                                R.string.settings_transfer_new_timetable_many,
                                newTimetables.size,
                            )
                    }
                    it.copy(
                        busy = false,
                        preview = null,
                        message = if (result.adopted > 0) {
                            UiText.Res(R.string.settings_transfer_import_done, result.adopted, suffix)
                        } else {
                            UiText.Res(R.string.settings_transfer_import_up_to_date, suffix)
                        },
                        messageIsError = false,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        preview = null,
                        message = UiText.Res(
                            R.string.settings_transfer_import_failed,
                            e.throwableToUiText(CoreR.string.common_unknown_error),
                        ),
                        messageIsError = true,
                    )
                }
            }
        }
    }

    // ---- 内部 ----

    private fun parseRaw(
        parser: () -> ScheduleDocument,
        source: UiText,
        section: TransferSection,
        adapterNotes: List<String> = emptyList(),
        /** 合并后要设为当前学期的学期名（重铸 ID 的来源按名激活——对齐可能换 id）。 */
        activateTermName: (ScheduleDocument) -> String? = { null },
    ) {
        _state.update { it.copy(section = section, busy = true, message = null) }
        try {
            val document = parser()
            if (document.terms.none { it.deletedAt == null }) {
                _state.update {
                    it.copy(
                        busy = false,
                        message = UiText.Res(R.string.settings_transfer_no_terms_in_file),
                        messageIsError = true,
                    )
                }
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
                        busy = false,
                        preview = ImportPreview(
                            source = source,
                            section = section,
                            document = document,
                            termSummaries = summaries,
                            warnings = emptyList(),
                            adapterNotes = adapterNotes,
                            activateTermName = activateTermName(document),
                            pendingDeletions = deletions,
                        ),
                    )
                }
            }
        } catch (e: NullClassCodec.FutureVersionException) {
            _state.update {
                it.copy(
                    busy = false,
                    // 文件 / 应用两个版本号写进词条里，用户反馈问题时一眼看得出差多少
                    message = UiText.Res(
                        CoreR.string.error_import_future_version,
                        e.fileVersion,
                        e.supportedVersion,
                    ),
                    messageIsError = true,
                )
            }
        } catch (e: ScheduleFileException) {
            _state.update { it.copy(busy = false, message = e.importIssueToUiText(), messageIsError = true) }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    busy = false,
                    message = UiText.Res(
                        R.string.settings_transfer_parse_failed,
                        e.throwableToUiText(CoreR.string.common_unknown_error),
                    ),
                    messageIsError = true,
                )
            }
        }
    }

    /** SAF 目标打不开（对方应用没给写权限等）。 */
    private class CannotOpenTargetException :
        UiTextException(UiText.Res(R.string.settings_transfer_cannot_open_target), "cannot open target")

    /** SAF 源读不出内容。 */
    private class CannotReadFileException : Exception("cannot read file")

    /** 需要当前学期但这个操作没有可用学期。 */
    private class NoCurrentTermException :
        UiTextException(UiText.Res(R.string.settings_transfer_no_term), "no current term")

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
