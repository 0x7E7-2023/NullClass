# i18n 收尾：把剩余文案搬进 strings.xml

日期：2026-09-25
基准：`0cec7d5`（工作区快照；真实 HEAD `8a8a64b`）

## 目标

机制层（`AppLocale` / `UiText` / `AppLanguage` / `DataError` / `SyncError` / `tools/check_hardcoded_text.py`）
此前已就位，但仍有 602 处中文字面量散在 Kotlin 里。本次把它们清零，让
`python tools/check_hardcoded_text.py` 退出码为 0。

## 范围（用户 2026-09-25 确认）

- **只做抽取，不做英文译文**：`AppLanguage.ENGLISH` 继续留在 `TRANSLATED` 之外，
  语言选择入口保持隐藏。翻译是后续独立一步。
- **不动 CI**：checker 保持手动跑。
- **`:importer` 只搬用户可见的**：用户选的文件 / 扫的码报错要翻译；
  适配器规范校验（JSON 字段名与取值）按 `docs/i18n.md` 例外 2 登记进 ALLOWLIST。

## 做法

1. **界面层**：文案进各模块 `res/values/strings.xml`，`stringResource(R.string.…)` 取；
   资源名沿用既有前缀约定（`settings_jw_`、`settings_transfer_`、`settings_qr_`…）。
2. **非界面层**（ViewModel / 仓库 / 引擎）：改返回 `UiText`，不持有文字。
   `status` 这类由回调写入的状态同样存 `UiText`，渲染时 `resolve()`。
3. **纯 Kotlin 模块**（`:importer`）：只产出标识与参数 ——
   新增 `ImportNotice` / `ImportNoticeEntry`（解析提示）与 `ScheduleFileError` /
   `ScheduleFileException`（文件不合法）；`NullClassCodec.FutureVersionException`、
   `QrPayload.PayloadTooLargeException` 改为携带版本号 / 尺寸而非文案。
   词条映射在 `:feature:settings` 的 `ImportIssueText.kt` 与 `:core:ui` 的 `ErrorText.kt`。
4. **checker 增加行级豁免**：`i18n-exempt: <原因>` 注释让该行免检，
   用于同文件里既有界面文案、又有必须保留的中文（写库数据、解析关键词、开发者日志）。

## 边界之外的取舍

- **适配器诊断不翻**：桥回填给脚本的文本、适配器清单 / 载荷的规范校验、
  适配器库下载的协议错误 —— 这些是写给适配器作者看的，登记进 ALLOWLIST 并逐条写明原因。
- **写进数据库的数据不翻**：缺省学期名（「拾光课表」「导入的课表」）、
  补出的课名（「课程 $id」）、自动识别的学期名 —— 会同步到其他设备，
  与界面语言无关，按数据登记。
- **测试断言改为绑标识**：解析器测试原来断中文子串，现在断
  `ImportNotice` / `ScheduleFileError`，标识改名会被编译器抓到。

## 完成判据

- `python tools/check_hardcoded_text.py` 退出码 0。
- `./gradlew assembleDebug test` 通过。

## 收尾复查（同日）

checker 归零后逐条复查，补了 checker 看不到的几类：

- **英文调试消息漏到界面**：`TransferViewModel` 的 `CannotOpenTargetException` /
  `NoCurrentTermException`、`JwImportViewModel` 的 `CannotReadAdapterFileException`
  消息是英文，经 `toUiText` 原样显示（如「导出失败：cannot open target」）。新增 `UiTextException`
  让它们带词条；`JwImageUnreadableException` 在提取流程与 OCR 桥里各自接住，恢复原先的中文说明。
- **整文件豁免挡住了用户文案**：`JwTableAligner` / `JwOcrScheduleBuilder` 的结构提示与核对项
  会显示在状态行和「核对识别结果」弹窗里，改为产出 `ImportNotice.OCR_*`，两文件移出 ALLOWLIST；
  原先按文案里有没有「核对」二字筛选，改为 `JwTableAligner.REVIEW_NOTICES`。
- **中文分隔符**：`joinToString("、")` / `("；")` 改用分隔符词条（ViewModel 里用 `UiText.Joined`），
  写死的 `" · "` 统一到 `common_separator`；checker 同时开始检查全角标点。
- **零散**：`:feature:settings` 清单里 `JwImportActivity` 的 `android:label`；课程编辑步进器「−」
  补读屏名（`edit_stepper_decrease` 原本定义了却没接上）；页面文字还原的缺省学期名改走词条，
  与图片识别那条路一致；删掉两条已无引用的词条。
