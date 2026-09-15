# 周课表纵横分割线（默认开启，能见度略低于早晚休分割线）

日期：2026-09-15
模式：完整（Large）

## Goal

周课表显示纵向（按日期分列）与横向（按课节分行）的分割线：

- **可选**：用户可在显示设置里开关。
- **默认开启**。
- **能见度略低于早晚休分割线**（上午/下午/晚会话分隔线）。

## 现状与决策

代码库已有完全对应的实现：

- `feature/schedule/src/main/kotlin/com/nullclass/feature/schedule/WeekGrid.kt:102-129`
  在 `drawBehind` 里画网格：竖线 `for (column in 0..weekDays.size)`（按日期分割），
  横线 `for (row in 0..totalPeriods)`（按课节分割），受 `showGridLines` 开关控制。
- 设置 key `show_grid_lines` 已存在（`UserPreferencesRepository.kt:105-111`），
  快捷设置弹窗里已有开关 UI（`ScheduleScreen.kt:326-338`）。

**决策：不新增设置项**，复用现有 `show_grid_lines`，只改两处：

1. 默认值 `false` → `true`（新用户/未动过该开关的用户默认看到分割线）。
2. 网格线颜色从 `outlineVariant.copy(alpha = 0.45f)` 提到 `alpha = 0.7f`。

能见度对比（目标"略低于"早晚休分割线）：

| 线 | 颜色 | 厚度 | 能见度 |
|---|---|---|---|
| 早晚休（会话）分隔线 | `outlineVariant` 全不透明 | 1dp（M3 HorizontalDivider 默认） | 基准 |
| 网格线（改后） | `outlineVariant.copy(alpha = 0.7f)` | 1 物理像素 hairline | 略低于基准 |

改后网格线颜色更接近但 alpha 打七折、且是 hairline 而非 1dp，整体观感略淡于会话分隔线。
（当前 0.45f 是"明显更淡"，不满足"略低"。）

明确关闭过该开关的老用户不受影响（DataStore 已持久化 `false`）。

## Files

1. `core/data/src/main/kotlin/com/nullclass/core/data/prefs/UserPreferencesRepository.kt`
   — `showGridLines` flow 默认值 `false` → `true`（约 `:105-111`）。
2. `feature/schedule/src/main/kotlin/com/nullclass/feature/schedule/WeekGrid.kt`
   — `gridColor` 的 `alpha = 0.45f` → `0.7f`（约 `:84`）。

## Task split

单一小任务（2 处一行级改动），主 agent 串行执行，不并行。

## Out of scope

- 不动早晚休（会话）分隔线本身的行为、颜色、开关。
- 不动今日视图（TodayScreen，非网格布局）。
- 不新增独立设置 key（避免与 `show_grid_lines` 语义重叠的两套开关）。
- 不改设置项文案（"显示网格线"语义已准确）。
- 不迁移已显式关闭的用户。

## Done when

- 全新安装（无 DataStore 历史）打开周课表即见纵横分割线，纵向按日期、横向按课节。
- 快捷设置里关闭"显示网格线"后线消失，重开恢复。
- 网格线观感略淡于上午/下午/晚分隔线，肉眼可辨但差距不大。
- 编译通过；审查流程（reviewer + adversary）走完。
