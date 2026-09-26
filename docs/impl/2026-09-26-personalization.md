# 个性化设置（课表外观）

日期：2026-09-26
模式：完整（Large）

## Goal

在「我的」入口列表里，与「设置」同级新增一个页面 **「个性化设置」**，集中调整周课表的外观：

| # | 需求原话 | 落地成什么 |
|---|---|---|
| 1 | 设置课表背景壁纸 | 从相册选一张图，铺满课表页（含顶栏区域），可移除 |
| 2 | 启动 24 小时时间轴模式 | 开关。纵轴改为 0:00–24:00，课程卡片按**实际上课时间**定位与定长，侧边栏显示整点 |
| 3 | 隐藏节次具体时间 | 开关。侧边栏只留节次号；开了「在课程卡片上显示上课时间」时角标时间也一并隐藏 |
| 4 | 隐藏周几下面的日期 | 开关。表头只显示星期（调课标记「调·周五」照常显示） |
| 5 | 隐藏网格线 | 开关，**复用现有 `show_grid_lines`**（与课表页「显示设置」弹窗里的同一项，反向表述） |
| 6 | 用滑块设置课表页面文字颜色 | 颜色选择器（色相/饱和度/亮度三条滑块 + 常用色块），默认「跟随主题」 |
| 7 | 调节格子高度 | 滑块 40–160dp，默认「自动」（= 现在的按屏高撑满、不低于 56dp）；时间轴模式下换成独立的「每小时高度」30–180dp，默认 60dp |
| 8 | 侧边栏宽度 | 滑块 20–80dp，默认「自动」（= 现在的 44dp / 28dp 规则；时间轴模式 36dp） |
| 9 | 顶部表头高度 | 滑块 24–96dp，默认「自动」（= 现在的按内容包裹） |
| 10 | 设置课程块文字颜色 | 同 6 的颜色选择器，默认「跟随主题」 |
| 11 | 课程块文字居中对齐还是顶格 | 二选一：居中（现状）/ 顶格（左上对齐） |
| 12 | 课程块边框无样式、实线还是虚线 | 三选一：无 / 实线（现状）/ 虚线 |
| 13 | 文字缩放比例 | 滑块 70%–150%，作用于课程卡片内全部文字 |
| 14 | 课块圆角半径 | 滑块 0–24dp，默认 8dp（现状） |
| 15 | 外部间距 | 滑块 0–8dp，默认 1.5dp（现状） |
| 16 | 不透明度 | 滑块 0%–100%，默认 100%（现状）；作用于卡片**底色**，文字与边框不变淡（取舍见下文「不透明度」） |

**全部默认值 = 现在的样子**：老用户升级后课表一个像素都不变，只有动过设置的人才看到变化。

页面顶部钉一块**实时预览**（用当前学期本周的真实课表渲染，与课表页共用同一套绘制代码），
拖滑块时预览逐帧跟手，所见即所得。

## 现状（改动的出发点）

- 课表页：`feature/schedule/.../ScheduleScreen.kt`（Scaffold + TopAppBar + WeekHeader + HorizontalPager{ verticalScroll{ WeekGrid } }）。
- 网格：`WeekGrid.kt`。行高 `cellHeight` 由 ScheduleScreen 按可用高度算（`maxOf(56dp, 净高 / 节数)`）；
  节次列宽 `periodColumnWidth(showTimeInCards)` = 28/44dp，WeekHeader 的 spacer 与之对齐；
  课块 `padding(1.5dp) + RoundedCornerShape(8dp) + 不透明底色 + 15%~18% 课程色 + hairline 实线边框`，文字居中、`onSurface`。
- 表头：`WeekHeader.kt`，星期 + `M/d` 日期 + 可选调课行，高度按内容包裹。
- 显示开关都在 `core/data/.../prefs/UserPreferencesRepository.kt`（DataStore `user_prefs`，本地、不进同步）；
  `show_grid_lines` 已存在，课表页右上角「显示设置」弹窗里能切。
- 「我的」：`feature/settings/.../ProfileScreen.kt` 的 EntryRow 列表；宽屏走 `app/.../navigation/ProfileTwoPane.kt` 双栏；
  路由在 `app/.../navigation/AppNavHost.kt`。

## 设计要点

### 数据模型（`:core:model`，纯 JVM，可单测）

新增 `ScheduleAppearance`（一个 data class，默认值即现状）：

```kotlin
data class ScheduleAppearance(
    val timelineMode: Boolean = false,
    val hidePeriodTimes: Boolean = false,
    val hideHeaderDates: Boolean = false,
    val pageTextColor: Int? = null,      // ARGB；null = 跟随主题
    val periodCellHeightDp: Int? = null,    // 节次模式单节行高 40..160；null = 自动（撑满）
    val timelineHourHeightDp: Int? = null,  // 时间轴模式每小时高度 30..180；null = 自动（60）
    val sidebarWidthDp: Int? = null,     // 20..80；null = 自动
    val headerHeightDp: Int? = null,     // 24..96；null = 自动（包裹内容）
    val blockTextColor: Int? = null,     // null = 跟随主题
    val blockTextAlign: BlockTextAlign = BlockTextAlign.CENTER,     // CENTER / TOP_START
    val blockBorderStyle: BlockBorderStyle = BlockBorderStyle.SOLID, // NONE / SOLID / DASHED
    val blockTextScalePercent: Int = 100,  // 70..150，步长 5
    val blockCornerRadiusDp: Int = 8,      // 0..24
    val blockSpacingDp: Float = 1.5f,      // 0..8，步长 0.5
    val blockOpacityPercent: Int = 100,    // 0..100，步长 5
)
```

- 各范围写成 companion 常量；提供 `sanitized()`：越界收拢、步长取整、未知枚举名回落默认 —— 读 DataStore 时统一过一遍，
  坏值/旧值不会让界面崩或画出负高度。
- 行高拆成两个字段：节次模式的「单节行高」与时间轴模式的「每小时高度」量纲不同，共用一个字段会让
  「节次模式调到 90dp → 开时间轴 → 每小时 90dp、总高 2160dp」这种串味发生。页面上仍只有一条「格子高度」滑块，
  随当前模式读写对应字段，说明文字随之切换。
- 网格线开关**不进**这个类：继续用现有 `showGridLines`（同一 key 两处可切，语义一致）。
- 新增 `TimelineGeometry`（纯函数）：
  - `minuteSpan(block, periodTimes): IntRange?` —— 起 = 首节开始分钟，止 = 末节结束分钟；
    首节不在节次表里 → null（不画）；末节超出节次表 → 截到表内最后一节；止 ≤ 起 → 至少给 30 分钟高度。
    按节次号查表（不假设节次表按时间单调）：用户把第 3 节时间填得比第 2 节早，时间轴就如实画在更早的位置 ——
    时间轴本来就是「按实际时间画」，这是它该有的样子；止 ≤ 起的兜底保证不会画出零高或负高的卡片。
  - `hourBoundaryCovered(spans, hour)` —— 整点线是否从某张卡片中间穿过（对应现在的 `coveredRows`，线在卡片处断开）。

### 持久化（`:core:data`）

- `UserPreferencesRepository` 新增一组 key（`appearance_*`），暴露 `scheduleAppearance: Flow<ScheduleAppearance>`
  与 `updateScheduleAppearance(transform)`：在 `edit {}` 里**读-改-写**，只把 transform 改动过的字段写回，
  不会用页面里的旧快照覆盖别处刚改的值。`resetScheduleAppearance()` 清掉全部 `appearance_*` key。
- 新增 `ScheduleWallpaperStore`（`@Singleton`，同在 `prefs` 包）：
  - `import(uri)`：`BitmapFactory` 先读尺寸 → `inSampleSize` 降采样 → 按 EXIF 方向旋正 → 长边不超过
    `min(屏幕长边, 2560px)` → JPEG(90) 写到 `filesDir/wallpaper/schedule-<时间戳>.jpg` → 记进 DataStore → 清理目录。
    先写新文件再切指针，中途失败旧壁纸不受影响。解码失败（损坏文件、Android 8.x 上的 HEIC 等平台解不了的格式，
    `BitmapFactory` 返回 null）一律当失败，由界面给「无法读取这张图片，请换一张重试。」
  - **目录清理是不变式而不是「删旧文件」这一步**：每次切完指针、以及首次加载时，扫 `filesDir/wallpaper/`，
    删掉所有不等于当前指针的文件。切指针后、删旧文件前进程被杀留下的孤儿文件，下一次就会被扫掉。
  - `clear()`：清 key + 清目录。
  - `wallpaper: StateFlow<Bitmap?>`：单例内解码一次，课表页与预览共用，切 Tab 不重复解码、不闪。
    Store 自持一个 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`（`:core:data` 目前没有应用级 scope，这是本模块第一次
    这样用，注释里说明）；导入、清除、解码全部在 IO 线程。
  - **指针指向的文件不存在或解码失败**（例如系统备份恢复只回来了偏好、没回来图片）：按「没有壁纸」处理，并顺手清掉这个坏指针（自愈）。
  - 文件在 `filesDir`，随系统自动备份走；备份不保证两者同时恢复，所以才要上一条的自愈。

### 课表渲染（`:feature:schedule`）

- 新增 `GridStyle`（Compose 侧解析后的样式：Dp/Color/枚举），由 `ScheduleAppearance + showTimeInCards + showGridLines`
  算出；ScheduleScreen 与预览用同一个函数，保证两边一致。自动值规则：
  - 侧边栏：时间轴模式 36dp；节次模式下侧边栏不显示时间（开了「时间标在卡片上」或「隐藏节次时间」）28dp，否则 44dp。
  - 格子高度：节次模式沿用现在的撑满逻辑；时间轴模式每小时 60dp（1 分钟 ≈ 1dp）。
  - 表头：包裹内容；设了高度则固定高度、内容垂直居中，放不下的行（例如调课日多出的「调·周五」）被裁掉 ——
    这是用户自己把表头调矮的直接结果，预览里一眼可见，不另做降级显示。
- ScheduleScreen 里「行高 + 顶栏钉住/可收起」判定拆成三支，共用现有的 pinned/enterAlways 切换与 heightOffset 归零：
  - 节次 · 自动：现状公式不动（`maxOf(56dp, 顶栏展开时净高 / 节数)`，`56dp × 节数 > 净高` 才可滚）；
  - 节次 · 固定行高：行高 = 设定值，`设定值 × 节数 > 净高` 才可滚；
  - 时间轴：高度 = 24 × 每小时高度，同样按 `> 净高` 判定（实际上几乎总是可滚）。
- `WeekGrid` / `WeekHeader` 改为接收 `GridStyle`（替换现在的 `showTimeInCards` / `showGridLines` 参数），分两种纵轴：
  - **节次模式**（现状）：行高 × 节数；网格线、会话分隔线、当前时间线逻辑不变。
  - **时间轴模式**：总高 24 × 每小时高度；侧边栏每个整点一个「H:00」标签；卡片按 `TimelineGeometry.minuteSpan` 定位；
    网格线改为每小时一条（遇卡片断开），不画上午/下午/晚上分隔线；当前时间线全天可画（不再限于首节~末节之间）；
    首次进入时纵向自动滚到「首节开始前 30 分钟」，切换模式时重新定位。
    「隐藏节次时间」在时间轴模式下只隐藏卡片角标时间，整点刻度照常显示（它是纵轴本身，不是节次时间）；设置页该开关下的说明文字写明这一点。
- 课程卡片（含「非本周」灰卡）统一吃样式：外边距、圆角、边框、文字缩放、对齐、不透明度。
  - 边框：无 / hairline 实线（现状，仍用 `Modifier.border`）/ 1dp 虚线。Compose 没有虚线 border，虚线用
    `drawRoundRect(style = Stroke(pathEffect = dashPathEffect))` 自绘，**矩形向内收半个线宽、圆角同步减半个线宽**，
    否则居中描边的外半圈会被前面的 `clip` 裁掉。
  - 文字缩放：字号与「按可用高度推算行数」的逻辑一起乘系数；行数仍至少 1 行 —— 行高调得很矮、字又放到 150% 时，
    这一行会被卡片裁掉，与现在系统字体调大时的表现一致。
  - 对齐：顶格 = 左上对齐；若同时显示角标时间，文字区上下各让出一个角标行高（左上是开始时间、右下是结束时间，两头都要避让）。
    居中模式保持现状。
  - 不透明度：乘到「不透明垫底 + 课程色」两层底色上，边框与文字不变。**明确的取舍**：垫底层原本的作用是让同一时段
    冲突的两门课叠画时不混色；不透明度低于 100% 时它必须一起变透明（否则壁纸永远透不过卡片，这个滑块就失去意义），
    所以此时冲突课的重叠处会互相透出。默认 100% 下行为与现在完全一致。
  - 文字颜色：真卡片用自定义色（地点 75%、角标 70% 透明度沿用现在的层次）；灰卡沿用主题灰，设了自定义色时用它的 70%，保持「比真卡片淡一档」。
- 页面文字颜色作用于：表头星期/日期（今天那列仍用主题强调色 + 加粗，保留「今天」的辨识）、侧边栏节次号与时间、时间轴整点。
  顶栏标题/副标题与图标**只在设了壁纸时**跟随它（此时顶栏透明、叠在壁纸上）；没有壁纸时顶栏有自己的不透明底色，
  继续跟主题走，避免把右上角的设置入口调得看不见。
- 自定义颜色是固定色值，不随深浅色主题切换 —— 已知局限，设置页颜色项下的说明文字写明「不随深浅色模式变化」。
- 有壁纸时：壁纸 `ContentScale.Crop` 铺在整页 Scaffold 之下（顶栏、状态栏区域也铺满），Scaffold 与顶栏（含滚动后的颜色）改透明；
  无壁纸时与现在完全一致。
- **壁纸自动压暗**（用户确认时追加的要求）：显示时在图片上叠一层 20% 的黑色，课表页与预览同一处代码。
  在显示时叠而不是导入时烧进文件：原图保持不动，以后要调深浅不必让用户重新选图。
- ScheduleScreen 的「显示设置」弹窗底部加一个「更多个性化设置」按钮直达新页面；
  开了「隐藏节次时间」时，弹窗里的「在课程卡片上显示上课时间」置灰（此时它没有可见效果）。

### 个性化设置页（`:feature:schedule`，由 `:app` 接路由）

页面放在 `:feature:schedule` 而不是 `:feature:settings`：预览要直接复用 `internal` 的 WeekGrid/WeekHeader。
「我的」只多一个回调，路由照旧由 `:app` 组装。

```
┌ 个性化设置                    恢复默认 ┐
│ ┌──────── 实时预览（钉在顶部）──────┐ │
│ │ 壁纸 + 表头 + 网格，按真实尺寸，可在框内上下滑 │ │
│ └──────────────────────────────────┘ │
│ 背景          [选择图片] [移除]        │
│ 显示内容      24 小时时间轴 ◯          │
│               隐藏节次时间 ◯            │
│               隐藏日期 ◯                │
│               隐藏网格线 ◯              │
│ 课表页面      文字颜色 ■ 跟随主题 ↺     │
│               格子高度 ───●── 自动 ↺    │
│               侧边栏宽度 / 表头高度      │
│ 课程卡片      文字颜色 / 文字对齐[居中|顶格] │
│               边框[无|实线|虚线]         │
│               文字大小 / 圆角 / 外边距 / 不透明度 │
└──────────────────────────────────────┘
```

- 预览：当前学期本周（不在学期内时第 1 周）的真实课表 + 真实节次时间；没有学期或这一周一节课都没有时，用内置示例课程。
  高约 240dp；手机横屏（矮屏）改为左右分栏（左预览、右设置项），不然预览会吃掉整屏。
  排课结果（`WeekLayout.layoutForWeek` / `otherWeekLayout`）在 ViewModel 里只随课表数据重算，与外观草稿彻底分开 ——
  拖滑块时只重算样式和 Compose 布局，不重跑排课。
- 滑块：拖动时只更新页面内草稿（预览跟手），松手写入一次，不在拖动中每帧写盘。每行右侧显示当前值（「自动」/「56 dp」/「120%」），
  改过的项显示 ↺ 单项恢复。
- 颜色选择器：预设色块（白、黑、深灰、浅灰）+ 色相/饱和度/亮度三条渐变轨道滑块 + 当前色预览；↺ 回到「跟随主题」。
- 选图用系统照片选择器（`PickVisualMedia`，低版本自动退回文档选择器），无需任何存储权限。
- 「恢复默认」二次确认：「个性化设置将恢复为默认，已设置的背景图片也会移除。」—— 同时清掉 `appearance_*`、壁纸，并把网格线恢复为显示。
- 文案按 `docs/ux-writing.md`；中英文同批写，`tools/check_translations.py` 通过。
- 「我的」入口：图标用 Material「Palette」（core 图标集没有，在 `:feature:settings` 内用路径数据构建一个 ImageVector），
  标题「个性化设置」，副标题「课表背景、布局与课程卡片样式」，排在「设置」之后。宽屏双栏右侧同样可打开。

## Files

新增：
1. `core/model/src/main/kotlin/com/nullclass/core/model/ScheduleAppearance.kt` — 模型 + 枚举 + 范围常量 + `sanitized()`
2. `core/model/src/main/kotlin/com/nullclass/core/model/TimelineGeometry.kt` — 时间轴定位纯函数
3. `core/model/src/test/kotlin/com/nullclass/core/model/ScheduleAppearanceTest.kt`
4. `core/model/src/test/kotlin/com/nullclass/core/model/TimelineGeometryTest.kt`
5. `core/data/src/main/kotlin/com/nullclass/core/data/prefs/ScheduleWallpaperStore.kt` — 壁纸导入/清除/解码缓存
6. `feature/schedule/src/main/kotlin/com/nullclass/feature/schedule/GridStyle.kt` — 样式解析 + 自动值规则
7. `feature/schedule/src/main/kotlin/com/nullclass/feature/schedule/ScheduleWallpaper.kt` — 壁纸背景 composable
8. `feature/schedule/src/main/kotlin/com/nullclass/feature/schedule/PersonalizationScreen.kt` — 页面 + 预览
9. `feature/schedule/src/main/kotlin/com/nullclass/feature/schedule/PersonalizationViewModel.kt`
10. `feature/schedule/src/main/kotlin/com/nullclass/feature/schedule/AppearanceControls.kt` — 滑块行、分段选择、颜色选择器
11. `feature/schedule/src/test/kotlin/com/nullclass/feature/schedule/GridStyleTest.kt`
12. `feature/settings/src/main/kotlin/com/nullclass/feature/settings/PaletteIcon.kt`

修改：
13. `core/data/.../prefs/UserPreferencesRepository.kt` — `appearance_*` key、读写、重置
14. `feature/schedule/.../WeekGrid.kt` — 接 `GridStyle`；时间轴纵轴；卡片样式
15. `feature/schedule/.../WeekHeader.kt` — 接 `GridStyle`；隐藏日期、固定高度、文字颜色
16. `feature/schedule/.../ScheduleScreen.kt` — 壁纸、透明顶栏、格子高度/滚动判定、时间轴初始滚动、弹窗加入口
17. `feature/schedule/.../ScheduleViewModel.kt` — appearance、wallpaper 作为两个独立 StateFlow 暴露，不并进现有的
    uiState combine 链（那条已经 4 路，而且外观变化本来就不该触发重新排课）
18. `feature/schedule/build.gradle.kts` — 显式依赖 activity-compose（照片选择器）
19. `feature/schedule/src/main/res/values{,-en}/strings.xml` — 新文案
20. `feature/settings/.../ProfileScreen.kt` + `res/values{,-en}/strings.xml` — 新入口
21. `app/.../navigation/AppNavHost.kt` — `Routes.PERSONALIZATION`；ScheduleScreen 新回调
22. `app/.../navigation/ProfileTwoPane.kt` — 双栏右侧可打开
23. `CHANGELOG.md` — [Unreleased] 新增一条

## Task split

强依赖链（模型 → 持久化 → 渲染 → 设置页与预览），且渲染与设置页共用 `GridStyle`/`WeekGrid` 的新签名，
文件集合不相交也拆不开先后。**主 agent 串行实现**，不并行：

1. 模型 + 单测（`:core:model`）
2. 持久化 + 壁纸存储（`:core:data`）
3. 课表渲染改造（WeekGrid / WeekHeader / ScheduleScreen / VM），默认值下与现状逐项核对
4. 个性化设置页 + 预览 + 控件
5. 入口与路由（ProfileScreen / ProfileTwoPane / AppNavHost）、文案中英文、CHANGELOG
6. 构建 + 单测 + 译文检查 → 模拟器实机走一遍 → reviewer → adversary → 只修 CONFIRMED

## Out of scope

- 今日页、考试页、桌面小组件的外观（只改周课表页）。
- 壁纸模糊、可调的压暗程度（固定 20%）、按深浅色分别设壁纸、动态取色跟随壁纸。
- 个性化设置进 WebDAV 同步或导出文件（与现有显示开关一致，只存本机）。
- 时间轴模式下的上午/下午/晚上分隔线、自定义时间轴起止范围（固定 0:00–24:00）。
- 现有「显示设置」弹窗里其他开关的去留与改名。
- 工作区里另一批尚未提交的小组件改动（`widget/`、`WidgetAgendaPage*`、CHANGELOG 里小组件那几条）不碰、不算进本次审查范围。

## Done when

- 「我的」里「设置」下方出现「个性化设置」，手机整屏、平板双栏都能打开；课表页「显示设置」弹窗能直达。
- 16 项需求逐项生效，预览与课表页一致；全部保持默认时课表页与改动前逐项一致（节次列宽、行高撑满、卡片样式、表头、网格线）。
- 选图 → 课表页铺满壁纸；移除后恢复原样；杀进程重进壁纸仍在；选一张损坏/不支持的文件给出提示且原壁纸不丢；
  连换几次壁纸后 `filesDir/wallpaper/` 下只剩一个文件；手动删掉壁纸文件后重进，课表按无壁纸显示且不崩。
- 固定行高 / 时间轴模式下，顶栏只在网格真的滚得动时才随滚动收起，不出现「弹簧」伸缩。
- 时间轴模式：卡片位置/长度与节次时间一致，课间空档可见；整点网格线遇卡片断开；当前时间线位置正确；进入即滚到首节附近。
- 「恢复默认」后一切回到默认。
- `:core:model:test`、`:feature:schedule:testDebugUnitTest`、`:app:assembleDebug` 通过；`python tools/check_translations.py` 通过。
- 模拟器实机截图核对：默认 / 壁纸 + 半透明卡片 + 白字 / 时间轴模式 / 顶格 + 虚线 + 大圆角 各一张。
- reviewer → adversary 走完，CONFIRMED 全部修复。
