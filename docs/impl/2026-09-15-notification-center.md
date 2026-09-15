# 通知与提醒中心：权限引导 + 精确闹钟 + 节假日同步 + 跳过日期

日期：2026-09-15
模式：完整（Large）

## Goal

新功能页「**通知与提醒**」，入口放在「我的」Tab，与「导入 / 导出」「应用设置」同级（`ProfileScreen.kt:123-134` 入口分组），聚合：

1. **通知相关权限一站式引导**：通知权限、厂商自启动、忽略电池优化、精确闹钟权限、勿扰模式权限——每项显示当前状态，未授权可一键跳转对应系统页。
2. **提前提醒时间**：课程/考试提醒提前量选择器（现有功能，从应用设置迁入本页）。
3. **可选精确闹钟**：开关（默认关）。开启并授权后提醒改走 AlarmManager 精确闹钟（到点准时），未授权/关闭时维持现有 WorkManager 方案。
4. **在线同步节假日信息**：多数据源、优先级排序、失败降级，缓存进 Room。
5. **手动编辑跳过日期**：用户可增删"这天不上课"的日期；节假日同步的结果也写入同一份跳过日期，共同生效于**课程提醒调度**（跳过日的课前提醒不发）。

## 现状与决策（基于 2026-09-15 调研）

- 提醒调度：纯 WorkManager inexact（`ReminderScheduler.kt`，14 天视界、uniqueWork REPLACE），开机/改时间由 `SystemEventReceiver` 兜底重排。**保留**此方案作为精确闹钟未开启时的基线。
- 课程提醒无独立 entity，`ReminderPlanner.upcoming()`（core/model）纯函数即时排算 → 需加跳过日期过滤参数。
- 权限现状：`POST_NOTIFICATIONS`、`RECEIVE_BOOT_COMPLETED` 已声明；**需新增** `SCHEDULE_EXACT_ALARM`、`ACCESS_NOTIFICATION_POLICY`。
- 厂商自启动 + 电池优化引导已有成品（`BackgroundReliability.kt`），直接复用。电池优化维持现状走 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` 列表页，**不声明** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（避免 Play 政策风险，行为不变）。
- 网络栈：项目用裸 OkHttp（无 Retrofit/Ktor），节假日源照此风格写在 core/data，给它加 okhttp 依赖。
- 数据库：Room（`NullClassDatabase`），新表需版本号 +1 + Migration。

## 设计

### A. 跳过日期数据层（core/data）

- 新 entity `SkipDateEntity`（表 `skip_dates`）：`epochDay`(PK)、`type`（`MANUAL` / `HOLIDAY`）、`label`（节假日名，可空）、`updatedAt`。
- `SkipDateDao`：`observeAll()`、`upsertAll()`、`insert()`、`delete()`、`deleteByType(HOLIDAY)`（同步刷新时整体替换 HOLIDAY 行，MANUAL 行永不被动）。
- `NullClassDatabase` 版本 +1，空迁移建表 + 注册 DAO。

### B. 节假日多源同步（core/data）

优先级排序（用户已定"多源 + 优先级"）：

1. **timor.tech** `GET https://api.timor.tech/holiday/year/{yyyy}` —— 全年一次请求，含节假日与调休补班标记，国内访问稳定。**首选**。
2. **Nager.Date** `GET https://date.nager.at/api/v3/PublicHolidays/{yyyy}/CN` —— 全年一次请求，国际源兜底，无调休信息。

`HolidayRepository`：

- `refresh(force: Boolean)`：取当前学期覆盖的年份（通常 1~2 年），按优先级逐源尝试；某年源 1 成功则用源 1，失败/解析异常降级源 2；全部失败保留旧缓存不报错性清空。成功后以事务 `replaceSyncedRange`（按年范围删 HOLIDAY/WORKDAY 行 + 批量插入，MANUAL 行永不被动）。
- 同步节流：DataStore 记 `holiday_last_sync_ms`，距上次 <7 天且非 force 则跳过；开关关闭时自动同步跳过（手动刷新不受限）。
- 自动同步时机：进「通知与提醒」页时自动尝试一次（节流内静默跳过），不动 DailyMaintenanceWorker。
- `skipDates: Flow<List<SkipDate>>`、`skipEpochDays(): Set<Long>`（补班日不算）、`addManualDate(epochDay)`、`removeDate(epochDay)`。
- 调休补班日存为 `WORKDAY` 行，仅列表展示（标注「仅提示，不生成课程」），不参与跳过逻辑。
- 源解析各写一个小 parser（`org.json`，Android 平台自带）。

### C. 调度接线（app + core/model）

- `ReminderPlanner.upcoming()` 加参数 `skipDates: Set<Long>`，落在这几天的课程提醒直接过滤（考试提醒**不**跳过——考试日期是学校定的显式日期）。
- `ReminderController` 增加观察 `skipDates` flow，变化即 debounce 重排（复用现有 800ms debounce 管道）。
- 今日视图（TodayScreen）：今天在 skipDates 里时顶部显示一条"今日休（节假日/手动跳过）"提示条；课表内容照常显示（部分学校有调课，不强行清空）。

### D. 精确闹钟（app，可选，默认关）

- Manifest 加 `SCHEDULE_EXACT_ALARM`（`USE_EXACT_ALARM` 受 Play 政策限制不用；targetSdk 36 下 `SCHEDULE_EXACT_ALARM` 默认拒绝，正好匹配"可选 + 用户主动授权"）。
- DataStore key `exact_reminder`（Boolean，默认 false）。
- `ExactAlarmScheduler`：`canUseExact()` = 开关开 且 `alarmManager.canScheduleExactAlarms()`；开启时对 `ReminderPlanner` 产出的每条提醒调 `setExactAndAllowWhileIdle(RTC_WAKEUP, ...)`，`AlarmReceiver`（BroadcastReceiver + Hilt EntryPoint）收到后直接调 `ReminderNotifier.post()` 并沿用 sent-keys 去重。
- `ReminderScheduler.reschedule()` 分支：exact 可用走闹钟（同时 cancel 全部 WorkManager unique work），否则回 WorkManager（cancel 闹钟）。开关切换/授权变化（ON_RESUME 检测）触发重排。
- `SystemEventReceiver` 与 `DailyMaintenanceWorker` 现有重排入口不变，自动覆盖两条路径。

### E. 勿扰模式（app + 权限引导）

- Manifest 加 `ACCESS_NOTIFICATION_POLICY`。
- 新页内"勿扰模式"项：状态读 `notificationManager.isNotificationPolicyAccessGranted`，未授权跳 `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`；已授权时提供开关"提醒在勿扰模式下响铃"，on → 两个通知渠道 `setBypassDnd(true)`，off → false（重建渠道更新，沿用 `ensureCreated` 模式）。

### F. UI 与导航（feature/settings + app）

- 新页 `NotificationSettingsScreen.kt`（feature/settings），小节顺序：**权限引导**（通知/自启动/电池/精确闹钟/勿扰，每行图标 + 状态徽标 + 跳转）→ **课前提醒 / 考试提醒**（提前量选择器，自 `SettingsScreen.kt:205-241` 迁入）→ **后台可靠性**（自 `SettingsScreen.kt:245-270` 迁入，与权限引导节的电池/自启动行合并去重——保留一处）→ **节假日与跳过日期**（上次同步时间 + 立即刷新按钮 + 同步开关 + 列表：节假日（带名）与手动日期混排、可删；底部"添加日期"用现有日期选择器模式）。
- 新 `NotificationSettingsViewModel`（Hilt）：聚合 prefs flows、权限状态（ON_RESUME 刷新）、HolidayRepository。
- `SettingsScreen.kt` 删除已迁走的三节；`SettingsViewModel` 相应瘦身。
- `ProfileScreen.kt` 入口分组在「导入 / 导出」与「应用设置」之间插入 EntryRow「通知与提醒」；`AppNavHost.kt` 加 `NOTIFICATION_SETTINGS` route（参照 TRANSFER `:354-360`）并接线回调。

## Files

**core/model**：`ReminderPlanner.kt`（skipDates 参数）
**core/data**：新增 `db/entity/SkipDateEntity.kt`、`db/dao/SkipDateDao.kt`、`repository/HolidayRepository.kt`、`holiday/TimorHolidaySource.kt`、`holiday/NagerHolidaySource.kt`；改 `NullClassDatabase`、`di/DataModule.kt`、`UserPreferencesRepository.kt`（新 key：`exact_reminder`、勿扰响铃开关、`holiday_sync_enabled` 默认 true、`holiday_last_sync_ms`）、`build.gradle.kts`（+okhttp）
**app**：`AndroidManifest.xml`（2 权限 + receiver）、`notification/ExactAlarmScheduler.kt`、`notification/AlarmReceiver.kt`（新增）；`ReminderScheduler.kt`、`ReminderController.kt`、`NotificationChannels.kt`（bypassDnd）改
**feature/settings**：新增 `NotificationSettingsScreen.kt`、`NotificationSettingsViewModel.kt`；`SettingsScreen.kt`、`SettingsViewModel.kt`、`ProfileScreen.kt` 改；`BackgroundReliability.kt` 复用
**feature/schedule**：`TodayScreen.kt`（提示条）
**app/navigation**：`AppNavHost.kt`（route + 接线）

## Task split

任务间存在硬依赖（UI 依赖数据层接口、调度接线依赖 entity、精确闹钟依赖 Planner 参数），不满足并行条件，**主 agent 串行**，顺序：

1. 数据层（A：entity/DAO/migration/prefs/DI）
2. 节假日源与 repository（B）
3. Planner 过滤 + 调度接线 + 今日提示条（C）
4. 精确闹钟（D）
5. 勿扰（E）
6. UI + 导航 + 迁移（F）

## Out of scope

- 调休补班日不生成"周末上课"（需改课表按 dayOfWeek 的结构，另行立项；仅展示）。
- 考试提醒不跳过节假日。
- ICS 导出不加 EXDATE（跳过日仍会出现在导出的日历里）。
- 周视图/课表网格不因跳过日期变灰（仅今日视图提示条）。
- 不申请 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（维持跳系统列表页方案）。
- 小组件不受跳过日期影响。

## Done when

- 「我的」页「导入 / 导出」与「应用设置」之间出现「通知与提醒」入口，进入后五项权限各显示真实状态、可跳转、授权返回后状态即时刷新。
- 提前量选择器在新页可用，应用设置中不再出现这三节。
- 精确闹钟开关默认关；开启未授权跳 `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`；授权后重排为 exact，到点准时报（`adb shell dumpsys alarm` 可见）；关掉开关回落 WorkManager。
- 勿扰授权后"勿扰下响铃"开关可切换渠道 bypassDnd。
- 节假日同步：断网/源挂时降级到下一源，全挂保留旧缓存；同步结果出现在跳过日期列表。
- 手动添加跳过日期后，该日前 14 天内的课程提醒不再排程（重排后生效）；删除后恢复。
- 今日为跳过日时今日视图显示提示条。
- 编译通过；Room migration 可从旧版本升级；reviewer + adversary 审查流程走完。
