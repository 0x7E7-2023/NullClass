# 平板与横屏适配：真 Bug 修复 + 自适应骨架

日期：2026-09-21
模式：完整（Large）
BASE：`93b6dc4`（0.9.93）

## Goal

彻查报告（本次会话四路交叉排查）确认：项目对大屏零适配基础设施，且在**手机横屏**下存在多个"点不到 / 看不见 / 丢数据"级别的真 Bug。本次把三批共 14 项全部修完。

三个全局事实决定了所有问题：

1. `targetSdk = 36`（`app/build.gradle.kts:15`）→ Android 16 起大屏设备**强制忽略**方向与尺寸限制，没有兼容性豁免可用。
2. `MainActivity.kt:36` 调用 `enableEdgeToEdge()`，且 `Theme.NullClass` 无 `windowSoftInputMode` → 边到边窗口下系统不再为 IME 收缩内容区，应用必须自己消费 ime inset，**现在零处**。
3. `themes.xml` 声明了 `windowLayoutInDisplayCutoutMode = shortEdges`（主动让内容延伸进刘海区），但全项目零处 cutout inset 补偿，课表页还显式 `contentWindowInsets = WindowInsets(0)` → 横屏挖孔转到侧边时**物理遮挡课表首列/末列**。

全库零命中清单：`WindowSizeClass`、`BoxWithConstraints`、`widthIn`、`ORIENTATION_LANDSCAPE`、`displayCutout`、`safeDrawing`、`imePadding`、`NavigationRail`、`values-sw600dp`、`values-land`。

## 现状与决策

### 决策 1：引入 `material3-adaptive`，断点用官方 `currentWindowAdaptiveInfo()`，core/ui 只做薄封装

> 本决策在用户确认"含双栏"后反转。原方案是自建轻量断点、不引依赖；但双栏所需的 `ListDetailPaneScaffold` 本就来自 `material3-adaptive`，而该库自带 `currentWindowAdaptiveInfo().windowSizeClass`。既然依赖必然引入，再自建断点就是重复造轮子且两套断点易漂移。

- 依赖：`androidx.compose.material3.adaptive:adaptive` / `adaptive-layout` / `adaptive-navigation`。先尝试由 `compose-bom 2026.08.00` 统一管理版本（不写死版本号）；若 BOM 未覆盖再在 `[versions]` 显式指定。
- `core/ui/build.gradle.kts` 现用 `implementation`，下游不可见。改为 `api(platform(libs.compose.bom))` + `api(...adaptive...)`，让全部 feature 模块通过 core/ui 传递获得，避免 6 个模块逐个加。
- `core/ui` 仍提供薄封装 `WindowSize.kt`：包装官方 `windowSizeClass` 并**补一个官方没有的 `HeightClass.Compact` 语义**（高度 < 480dp，即手机横屏），本次大量矮屏分支依赖它。
- `core/ui` 目前**只有 4 个主题文件、无任何布局组件**——这既是 16 屏各自手抄容器的成因，也是最干净的切入点。

### 决策 2：`WeekGrid` 格高必须在 `verticalScroll` 外层测量

`WeekGrid.kt:119` 把高度钉死为 `PeriodCellHeight * totalPeriods`，其注释已自述原因：外层 `verticalScroll` 令 maxHeight 无界，`fillMaxHeight` 会失效。

所以自适应格高**不能**在 WeekGrid 内部用 `BoxWithConstraints`（那里拿到的 maxHeight 是无穷）。必须在 `ScheduleScreen.kt` 的 `HorizontalPager` 内、`Column(verticalScroll)` **外**测量可用高度，算出 cellHeight 传进 WeekGrid。

`PeriodCellHeight` 现为 `internal val` 常量（`WeekGrid.kt:47`），被 `:119`、`:253`、`:257` 引用，改为参数需贯穿这几处。保留常量作为默认值与下限。

### 决策 3：`DatePickerDialog` 矮屏切 `DisplayMode.Input`，而非塞进滚动容器

6 处全是日历模式（约 568dp 高），400dp 横屏下必然被裁。切输入框模式是一个参数的事，体验远好于在 568dp 日历外面套滚动条。

### 决策 4：NavigationRail 是本次最大的结构改动，单独成阶段

`AppNavHost.kt:254` 的 `tabBar` 已抽象成 `@Composable (Modifier) -> Unit`，切入点现成。但顶层布局是 `Box` + `align(BottomCenter)` 绝对定位（`:422-424`），且每个 route 外层靠 `:166` 的 `padding(padding).consumeWindowInsets(padding)` 垫底栏高度。改成"左 Rail + 右内容"的 Row 布局会改变所有页面的 inset 假设，必须独立阶段、独立验证。

### 决策 5：旋转状态保存优先 `rememberSaveable`，WebView 用 `saveState`/`restoreState`

`MainActivity` 与 `JwImportActivity` 均无 `configChanges` → 旋转必然重建。全项目 `rememberSaveable` 仅 3 处，普通 `remember` 持有状态约 50 处。

四个主编辑页 + SettingsScreen 的表单文本在 ViewModel 里（`hiltViewModel()` 跨配置变更存活）→ **不是问题区，不动**。

真正要救的是 JW 导入链与 DaySwap 两步流程。佐证：`JwImportActivity.kt:127` 的 `BackHandler` 注释明写"登录到一半误滑一下整个导入流程就没了"——开发者已意识到该流程不可被打断并专门防了返回手势，但旋转是同一威胁的另一条路径，完全没防。

## 设计

### 阶段 1：core/ui 地基

新增 `core/ui/src/main/kotlin/com/nullclass/core/ui/layout/`：

- `WindowSize.kt`
  - `enum class HeightClass { Compact, Normal }`（断点 480dp）——官方 `WindowSizeClass` 没有可直接用的矮屏语义，本次大量横屏分支靠它
  - `data class WindowSize(val widthClass: WindowWidthSizeClass, val height: HeightClass)`，`widthClass` 直接取自官方 `currentWindowAdaptiveInfo().windowSizeClass`
  - `val LocalWindowSize = compositionLocalOf { ... }`
  - `ProvideWindowSize(content)`：官方 API 取宽度类 + `BoxWithConstraints` 补高度类，向下提供
  - 在 `MainActivity.kt` 的 `NullClassTheme` 内、`AppNavHost()` 外包一层
  - 便捷扩展：`val WindowSize.isCompactHeight`、`isExpandedWidth`
- `AdaptiveContainer.kt`
  - 限宽居中容器：`widthIn(max = 700.dp)` + `align(CenterHorizontally)`，Compact 宽度下等同透传
- `AdaptiveDialogContent.kt`
  - 弹窗内容统一包装：`heightIn(max = 可用高度 * 0.8)` + `verticalScroll`，给 21 个无滚动 Dialog 复用

### 阶段 2：第一批 —— 真 Bug（7 项）

| # | 问题 | 位置 | 做法 |
|---|---|---|---|
| 2.1 | 详情面板"编辑/删除"点不到 | `CourseDetailSheet.kt:57` | Column 加 `verticalScroll` |
| 2.2 | 横屏一条学校都看不见 | `JwImportActivity.kt:283-395` | 根容器改可滚；矮屏折叠说明文字（`:292-297`）与提示（`:307-311`） |
| 2.3 | 日期选择器被裁 | `TermEditScreen.kt:277`、`TimetableCreateScreen.kt:173`、`ExamEditScreen.kt` 等 6 处 | 矮屏 `DisplayMode.Input` |
| 2.4 | 键盘遮挡输入框 | `CourseEditScreen.kt:77`、`TermEditScreen.kt:92`、`ExamEditScreen.kt:101`、`TimetableCreateScreen.kt:82`、`SettingsScreen.kt:90` | 滚动容器加 `.imePadding()` |
| 2.5 | 刘海遮挡课表首列/末列 | `ScheduleScreen.kt:142`、`ExamScreen.kt:75` | `contentWindowInsets` 改 `safeDrawing.only(Horizontal)` |
| 2.6 | 分享弹窗按钮被挤没 | `QrShareScreen.kt:58-79` | 外层滚动 + QR 图 `sizeIn(max)` |
| 2.7 | 旋转丢导入进度 / 调课步骤 | `JwImportActivity.kt:115/117/118/483/536`、`JwWebViewStep.kt:164/167`、`DaySwapScreen.kt:107-108`、`JwAskDialogs.kt:58/117`、`TimetableListScreen.kt:149` | 改 `rememberSaveable`；WebView 加 `saveState`/`restoreState` |

### 阶段 3：第二批 —— 横屏（3 项）

- 3.1 全部 TopAppBar 加 `enterAlwaysScrollBehavior`（现**零处** scrollBehavior，`TopAppBarDefaults` 只用于 `ScheduleScreen.kt:128` 调色）。滚动时收起，矮屏还回 64dp。
- 3.2 `AppNavHost.kt` 按 `HeightClass.Compact` 切 `NavigationRail`（见决策 4）。
- 3.3 `JwWebViewStep.kt:447-509` 矮屏折叠底部操作栏（现约 130dp，导致 WebView 键盘弹出后仅剩约 100dp）。

预期：横屏一屏可见节次 3.2~3.8 节 → 约 6 节。

### 阶段 4：第三批 —— 平板（4 项）

- 4.1 `WeekGrid` 格高自适应 `max(56.dp, 可用高度 / 节数)`（见决策 2）。平板竖屏铺满不留白（现空约 390dp），横屏保底 56dp 仍可滚。
- 4.2 16 屏根容器套 `AdaptiveContainer`（现 0 处限宽）。
- 4.3 `maxLines` 改按可用高度推算行数。现为固定行数（`WeekGrid.kt:280-291`），最坏课名 3 行 + 地点 2 行需 61dp > 可用 47dp，此时 `Ellipsis` 不触发、被 `:259` 的 `clip` 硬裁成半个字；灰块（`:352-360`）系统字体放大 1.1 倍就裁掉整行周次范围。
- 4.4 Widget 补 `maxResizeWidth`/`maxResizeHeight` + `previewLayout` + `description`（两个 `widget/src/main/res/xml/*_info.xml`）。

### 阶段 5：列表-详情双栏（用户确认纳入）

在 Expanded 宽度（≥840dp，即平板横屏 / 大平板竖屏）下，把 5 组"列表→详情"从整屏 push 改为 `ListDetailPaneScaffold` 双栏；Compact/Medium 宽度维持现有单栏 push，**行为零变化**。

5 组（按覆盖面排序）：

| 组 | 列表侧 | 详情侧 | 备注 |
|---|---|---|---|
| 5.1 「我的」→ 7 个设置子页 | `ProfileScreen.kt:109-118` 的 EntryRow 列表 | `AppNavHost.kt:324-337` 的 7 个 navigate 目标 | 覆盖面最广，含第三级 `QuickActionsScreen.kt` → `DAY_SWAP`/`COURSE_CLEANUP` |
| 5.2 考试列表 → 考试编辑 | `ExamScreen.kt:113` LazyColumn | `Routes.examEdit(examId)` | |
| 5.3 学期列表 → 学期编辑 | `TermListScreen.kt:91` | `Routes.termEdit(termId)` | |
| 5.4 课表列表 → 创建/详情 | `TimetableListScreen.kt:92` | `Routes.TIMETABLE_CREATE` | |
| 5.5 课表格子 → 课程编辑 | `ScheduleScreen.kt` 网格 | `Routes.courseEdit(courseId)` | **最复杂**：详情现在是 ModalBottomSheet（`CourseDetailSheet.kt`），双栏下应改为右栏常驻 |

实现要点与风险：

- **导航图不推倒重来**。17 个目的地全部保留现有路由，只在 Expanded 宽度下由承载 Scaffold 的父目的地接管详情侧渲染；窄屏仍走原 `navigate()`。这样回退路径清晰，也不破坏既有的 `saveState`/`restoreState` 与深链（`.nullclass` 文件打开）。
- **与阶段 3.2 NavigationRail 的叠加风险**：二者都改顶层骨架。串行推进，3.2 先落地并单独验证，5.x 再叠加。
- **`BackHandler` 交互**：`AppNavHost.kt:237` 已有"退出转场吞返回"的兜底，`JwImportActivity.kt:127` 另有防误滑。双栏的返回语义（先收详情栏还是直接退）需与这两处对齐，不能各写各的。
- 5.5 若成本失控，允许只做到"右栏显示原 Sheet 内容"，不强行合并编辑页。

### 阶段 6：审查

`/stage` 强制流程：fresh reviewer（sonnet）→ 非空则 fresh adversary（sonnet）→ 只修 CONFIRMED。

## Files

新增（3）：`core/ui/.../layout/WindowSize.kt`、`AdaptiveContainer.kt`、`AdaptiveDialogContent.kt`

构建（2）：`gradle/libs.versions.toml`（adaptive 三个 artifact 的 library 声明）、`core/ui/build.gradle.kts`（`implementation` 改 `api` 以向下游传递）

修改（约 27）：

- `app`：`MainActivity.kt`、`navigation/AppNavHost.kt`
- `feature/schedule`：`ScheduleScreen.kt`、`CourseDetailSheet.kt`、`WeekGrid.kt`、`WeekHeader.kt`、`TodayScreen.kt`、`DaySwapDialog.kt`
- `feature/settings`：`JwImportActivity.kt`、`JwWebViewStep.kt`、`JwAskDialogs.kt`、`JwAdapterSheets.kt`、`QrShareScreen.kt`、`SettingsScreen.kt`、`DaySwapScreen.kt`、`ProfileScreen.kt`、`NotificationSettingsScreen.kt`、`QuickActionsScreen.kt`、`CourseCleanupScreen.kt`、`AboutScreen.kt`、`TransferScreen.kt`
- `feature/edit`：`CourseEditScreen.kt`、`TermEditScreen.kt`、`TimetableCreateScreen.kt`、`TermListScreen.kt`、`TimetableListScreen.kt`、`BlockEditor.kt`
- `feature/exam`：`ExamScreen.kt`、`ExamEditScreen.kt`
- `widget`：`res/xml/today_widget_info.xml`、`res/xml/next_class_widget_info.xml`

## Task split

**已确认：主 agent 串行，按阶段 1 → 2 → 3 → 4 → 5 推进，阶段 6 审查。**

不并行的理由（技能要求"文件不相交 且 无硬顺序"才并行，此处两条都不满足）：

1. **硬顺序**：阶段 1 的 `LocalWindowSize` / `AdaptiveContainer` 是阶段 2/3/4/5 的前置，全部下游都要用。
2. **隐性耦合**：阶段 3.2 的 NavigationRail 与阶段 5 的双栏 Scaffold **都改顶层骨架**（`AppNavHost.kt:166` 的 padding 逻辑与 `:422-424` 的绝对定位）。二者必须先后落地、各自验证，不能并行也不能合并成一次改动。
3. **同文件多批次触及**：如 `TermEditScreen.kt` 同时涉及 2.3（DatePicker）、2.4（imePadding）、3.1（TopAppBar）、4.2（限宽）；`ScheduleScreen.kt` 涉及 2.5、3.1、4.1、5.5。按批并行则文件严重重叠。
4. **一致性**：16 屏要统一套同一个容器、统一加同一套 scrollBehavior，串行才能保证风格一致。

阶段内顺序要求：**3.2 必须早于 5.x**（骨架先稳），**2.7 的状态保存应早于 5.x**（双栏会改变返回语义，先把旋转丢失修好便于区分问题来源）。

子 agent 模型：按你的指定，reviewer 与 adversary 均用 **sonnet**。

## 进度

> 实时更新。BASE `93b6dc4`，每完成一段都确认 `:app:compileDebugKotlin` 通过。

- [x] **阶段 1 地基**：`libs.versions.toml` 加 adaptive 三件套（BOM 解析到 **1.3.0**，无需写死版本）；`core/ui/build.gradle.kts` 的 `implementation` 改 `api` 以向下游传递；新增 `WindowSize.kt` / `AdaptiveContainer.kt` / `AdaptiveDialogContent.kt`；`MainActivity` 包 `ProvideWindowSize`。
  - 实现偏差：断点判断**未用** `currentWindowAdaptiveInfo()`，改用 `BoxWithConstraints` 自测（那套 API 版本间形态有变动，且自测能顺带算出官方没有的「高度矮」语义）。adaptive 依赖保留给阶段 5 双栏。
- [x] 2.1 `CourseDetailSheet` 加 `verticalScroll`
- [x] 2.2 `SchoolPicker` 矮屏收起说明/标题、底部入口挪进列表末尾
- [x] 2.3 日期选择器矮屏切 `DisplayMode.Input` —— **实际 7 处**（文档原估 6 处）：`TermEditScreen` / `TimetableCreateScreen` / `ExamEditScreen` / `DaySwapDialog` / `CourseCleanupScreen` / `DaySwapScreen` / `NotificationSettingsScreen`
- [x] 2.4 5 个表单页换 `AdaptiveColumn`（**合并了阶段 4.2 的限宽**，避免同文件改两遍）+ Scaffold 换 `safeDrawing`
  - 偏差：原计划给内容加 `imePadding()`，实测会与 Scaffold 的 systemBars inset 叠加、键盘弹出时底部多一条空白。改为 Scaffold `contentWindowInsets = safeDrawing`（内部已对 ime/navBar/cutout 取并集），`AdaptiveColumn` 传 `imePadding = false`。
- [x] 2.5 `ScheduleScreen` / `ExamScreen` 的 `WindowInsets(0)` 改 `safeDrawing.only(Horizontal)`
- [x] 2.6 `QrShareDialog` 换 `AdaptiveDialogContent` + QR 图 `widthIn(max)` 封顶
- [x] 2.7（部分）`JwImportActivity`：`selected`/`pendingStartUrl` 改存 key + `rememberSaveable` 查回，`startUrl` 及两个对话框的 url 输入改 `rememberSaveable`
- [x] **2.7 其余**：`DaySwapScreen` 两步流程步骤索引、`JwAskDialogs` 两处输入/选择、`TimetableListScreen` 重命名（`pendingRename` 一并改存 id 查回，否则单保存文本无意义）、`JwWebViewStep` 的 `isDesktopMode`
- [x] ~~WebView `saveState`/`restoreState`~~ —— **评估后决定不做**，理由见下方「范围收缩决定」
- [x] **阶段 3 横屏**：3.1 全部 16 个 Scaffold+TopAppBar 加 `enterAlwaysScrollBehavior`；3.2 `AppNavHost` 矮屏切 `NavigationRail`（`screen()` 的预留空间从 bottom padding 改 start padding，RTL 自动镜像）；3.3 `JwWebViewStep` 矮屏收紧操作栏、状态文字单行、冻结提示缩短文案
- [x] **阶段 4 平板**：4.1 `WeekGrid` 格高改 `cellHeight` 参数、由 `ScheduleScreen` 在 `verticalScroll` 外层用 `BoxWithConstraints` 按 `max(56dp, 净高/节数)` 计算；4.2 16 屏套 `AdaptiveColumn`/`AdaptiveWidthWrapper` 限宽居中；4.3 课块 `maxLines` 改按可用高度推算（`sp.toDp()` 自带 fontScale，字体放大时自动减行而非被 clip 硬裁）；4.4 两个 widget 补 `description`
- [x] **阶段 5 双栏**：5.1「我的」双栏（`ProfileTwoPane`）、5.2「考试」双栏（`ExamTwoPane`，嵌套 NavHost）。另外 3 组有理由不做，见下
- [x] **阶段 6 审查**：reviewer（sonnet）报 3 条 → adversary（sonnet）**三条全部 CONFIRMED** → 已全部修复，见下

### 审查发现与修复

| id | 问题 | 修复 |
|---|---|---|
| **B1** | `JwImportActivity` 的 `findAdapter(key)` 与适配器列表的异步加载竞态。进程被杀后恢复时 `rememberSaveable` 先还原 `selectedKey`，而 `state.builtin`/`state.user` 要等 IO 回来才非空——这个窗口内 `selected` 为 null，界面退回学校列表，**且返回键会走 `onCancel()` 把整个导入流程退掉**（正是 `BackHandler` 当初要防的那件事，被我自己的改动从另一条路放了进来） | 新增 `restoringSelection = selectedKey != null && selected == null`。返回键与 `BackHandler` 一律改按 `selectedKey`（用户意图）判断，只有真要渲染适配器内容时才用 `selected`；加载窗口内显示进度占位而不是退回列表 |
| **B2** | 「今日」「我的」两个 Tab 漏掉了给课表/考试加的横屏刘海修复，四个同级 Tab 覆盖不一致 | 两页 Scaffold 加 `contentWindowInsets = WindowInsets.safeDrawing`。注意**不能**照抄课表页的 `.only(Horizontal)`：这两页没有 TopAppBar，顶部状态栏 inset 还得靠它，只取水平方向会把内容顶到状态栏下面 |
| **B3** | 宽度档位在同一 composition 内翻转（折叠屏展开、分屏拖拽跨 840dp）时，`if (twoPane) ... else ...` 会整棵子树替换，详情栏里没保存的编辑随 ViewModelStore 一起销毁 | 根因在 `LocalWindowSize` 取自 `BoxWithConstraints`（实时翻转）。改为取自 `LocalConfiguration`：档位只随配置变更翻转，而两个 Activity 都没声明 `configChanges`，配置变更必然走 Activity 重建，此时 `NavBackStackEntry` 的 ViewModelStore 跨配置变更存活，编辑得以保住 |

另有一处是我在派出 reviewer 后自查发现并主动修的：`bottomBarHeightPx` 在 Rail 模式存宽度、Bar 模式存高度，改用 `remember(useRail)` 作键重置，避免翻转时把上一个方向的尺寸当这个方向用。

reviewer 另外逐一核对过但**未发现问题**的高风险点：`AdaptiveColumn` 的嵌套 Column 与 `weight()` 语义、`AdaptiveDialogContent` 的 `heightDp == 0` 兜底、`contentWindowInsets` 从 systemBars 换 safeDrawing 是否重复计算（WindowInsets 取并集而非相加，不会）、`WeekGrid` 的除零与负值边界、`DaySwapScreen` 两步流程的 `rememberSaveable`、`JwWebViewStep` 里那几个「有意不保存」的状态。

## Out of scope

### 阶段 5 的范围判断（与原计划不同）

原计划列了 5 组，实际落地 2 组，另外 3 组**有理由不做**：

- **5.5 课表格子 → 课程编辑：不做**。平板横屏 1280dp 双栏后课表只剩约 500dp 摊给 7 列，每列约 65dp，比手机竖屏还局促——属于负优化。课表这类二维网格需要全宽，详情更适合现有的 bottom sheet 形态。
- **5.3 学期列表 / 5.4 课表列表：已被 5.1 覆盖**。这两个列表本身就是「我的」的子页，现在显示在 5.1 的右栏里；再给它们自己套一层双栏就成了三栏嵌套，可读宽度被切碎。
- **5.2 之所以能做**：「考试」是顶层 Tab、自身独立，不存在嵌套问题。

### 带导航参数的双栏为什么要嵌套 NavHost

`ExamEditViewModel`（`:48`）、`TermEditViewModel`、`CourseEditViewModel` 都在 `init` 里 `savedStateHandle.get<String>("examId")` 取 id。**把编辑屏直接嵌进详情栏，ViewModel 拿不到 nav 参数，会把「编辑」当成「新建」**。

顺带纠正一个容易被误导的地方：`TermEditScreen(termId: String?, ...)` 这个形参**从头到尾没被用过**，真正的 id 来源是 SavedStateHandle。

`ExamTwoPane` 因此在详情侧起了一个嵌套 NavHost，nav 参数天然可用，ViewModel 与编辑屏一行都不用改。

### 4.4 的取舍：不加 `maxResizeWidth/Height`

审计建议给 widget 补尺寸上限。实际**没加**：两个 widget 都是 `SizeMode.Exact`，Glance 会按实际尺寸重新布局，加上限只会挡住平板用户把小组件拖大。只补了 `description`。`previewLayout` 需要另做一套静态预览布局，留待后续。

### material3-adaptive 依赖：已移除

曾引入（BOM 解析到 1.3.0），但两个双栏**都用 `Row` 实现**，没用 `ListDetailPaneScaffold`：5.1 的子页不带导航参数、详情侧只是单选渲染，`Row` 完全等价；5.2 需要的是「详情侧能提供 nav 参数」，靠嵌套 NavHost 解决，官方组件在这点上帮不上忙。本项目返回逻辑相当敏感（`AppNavHost` 有大段退出转场吞返回的注释），能不叠返回栈就不叠。

模拟器实测确认两个双栏工作完好后，该依赖自始至终没有被任何代码引用，遂连同 `core/ui/build.gradle.kts` 里那三行 `api(...)` 一并删除。移除后 `assembleDebug` 通过、重装冒烟正常。将来若要扩展带导航参数的双栏，再加回即可（由 compose-bom 统一管版本，不必写版本号）。

### 模拟器实测（标准 AVD，Android 34，google_apis x86_64）

设备 1080×2340@440dpi。用 `wm size`/`wm density` 临时改分辨率模拟平板，不必另建 AVD。

| # | 场景 | 结果 |
|---|---|---|
| 1 | 手机竖屏 392×851dp 课表 | ✅ 7 列、1–10 节、底栏 4 Tab，与改造前一致，无回归 |
| 2 | 手机横屏 801×345dp 课表 | ✅ **NavigationRail 生效**，课表拿到全部高度，可见节次约 3 → 约 4.7 节（顶栏滚动收起后约 5.9） |
| 3 | 平板竖屏 1067×1547dp 课表 | ✅ **格高自适应生效**，12 节铺满，不再有改造前那 390dp 空白（每节 56 → 约 111dp） |
| 4 | 平板「我的」双栏 | ✅ 左栏列表 + 右栏空态占位 + 分隔线，比例 0.38/0.62 |
| 5 | 平板双栏点「应用设置」 | ✅ 右栏渲染设置页（含自己的返回箭头），左栏列表常驻并高亮当前项 |
| 6 | 双栏下按系统返回 | ✅ 先收详情栏回空态，不退出 Tab |
| 7 | 平板「考试」双栏 + 新建 | ✅ **嵌套 NavHost 传参成功**，右栏「添加考试」自动关联课程、预填日期 |
| 8 | 手机横屏表单键盘 | ✅ 焦点输入框自动滚到键盘正上方完全可见，TopAppBar 被 `enterAlwaysScrollBehavior` 自动收起把 64dp 让给内容 |
| 9 | 手机横屏日期选择器 | ✅ **DisplayMode.Input 生效**，显示 `09/21/2026` 输入框 + 数字键盘，而非 568dp 日历 |

全程 `logcat` 无 FATAL / AndroidRuntime 异常。截图存于 `.test/shots/01..17-*.png`。

**一处实测才发现的行为**：`Configuration.screenWidthDp` 是**已减去系统栏的可用宽度**（横屏实测 `w801dp`，而按物理像素换算是 851dp），而 Material 的 840dp 断点本是按窗口宽度定义的——等于我们的断点被实际推高了约 50dp。后果是手机横屏（801dp）不触发双栏，**这反而是合适的**：801×345dp 的窗口切两栏、每栏只有 345dp 高，会比单栏更差。平板（1067dp）正常触发。该偏差有利，故保留，在此记录以免后人困惑。

### 范围收缩决定：WebView 状态恢复不做

原计划给 `JwWebViewStep.kt:164` 的 WebView 加 `saveState`/`restoreState`（或保存当前 URL 旋转后重新加载）。读完这段代码后决定**不做**：

- **收益比预想小**：WebView 的 cookie 是进程级全局的，旋转后登录态本来就不丢。能省的只是「重新导航回课表页」的几次点击。
- **风险明显更大**：这段是安全敏感区且踩坑密度极高（`JwNetworkGate` 白名单、`allowCurrentHost` 只对 `promptsForStartUrl` 适配器放行、提取期冻结网络、自动提取抢跑）。恢复 URL 时 `gate` 是 `remember(adapter.key)` 的、旋转后为全新实例，此时 `loadUrl(savedUrl)` 很可能撞上尚未 `allowCurrentHost` 的白名单而被静默拦截；`saveState` 路线则另有 `TransactionTooLargeException` 风险。
- 这类 bug 隐蔽、只在特定适配器上复现，不配真机验证不该盲改。

同时**有意不保存**的还有：`frozen`/`extracting`（提取期运行时态，旋转后协程已死，恢复成 true 会让网络一直冻着、按钮一直转圈，比丢失更糟）、`ocrEnabled`（OCR 能力探测结果，`LaunchedEffect` 每次都会重写）、`autoTriggered`（防重复自动提取的哨兵，页面重载后本就该允许再触发）。这些理由已写进代码注释。

**建议单独立项**，配合标准 AVD 真机逐适配器验证。

## Out of scope

- **版本号不动**：保持 0.9.93，不发版、不写 CHANGELOG（未到点头前不上 1.0.0 的既定约定）。
- **不自动 commit**：改完停在工作区，由你决定。
- **进程被杀后恢复**：本次只解决配置变更（旋转）。无 `onSaveInstanceState`、`SavedStateHandle` 只读 nav 参数不写回，进程死后仍全丢——属更大的持久化议题，不在本次范围。
- `values-sw600dp` 等资源限定符目录：本次走 Compose 运行时断点，不引入资源分支。
- 折叠屏铰链感知（`FoldingFeature` / `WindowLayoutInfo`）：`ListDetailPaneScaffold` 自带的铰链处理照单接受，不额外定制。

## Done when

1. `./gradlew assembleDebug` 通过。
2. 三批 14 项 + 阶段 5 的 5 组双栏全部落地，每项可指到具体 diff。
3. reviewer 跑完；列表非空则 adversary 跑完；CONFIRMED 全部修掉，REFUTED 丢弃。
4. 自检复述：横屏可见节次、刘海遮挡、键盘遮挡、旋转丢失、平板双栏五项各自的前后对比。
5. **窄屏零回归**：Compact 宽度 + Normal 高度（普通手机竖屏）下，所有交互路径与 BASE 行为一致——双栏与矮屏分支都只在各自断点内生效。

## 待实测（代码侧确凿，渲染结果需真机确认）

`imePadding` 缺失是代码侧确凿的，但 5 个已有 `verticalScroll` 的表单页最终表现受 Compose bring-into-view 行为影响，子代理未实机验证。建议改完用**标准 AVD 横屏**复测（LDPlayer 注入不了系统级手势/输入法行为，不适合验这类）。

`SchoolPicker` 与 `JwWebViewStep` 两屏**不可滚动**，不依赖该推断，是确定的 Bug。
