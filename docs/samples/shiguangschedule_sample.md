# 拾光课程表样本文件说明

`shiguangschedule_sample.json` 是按拾光课程表官方文档
（<https://sgschedule.jursin.top/guide/user/schedule-import>「课程文件导入」一节的示例）
手工构造的导出样本，字段与上游适配脚本实际写出来的一致
（结构另经上游适配仓库 `shiguang_warehouse` 的 `GLOBAL_TOOLS/school.js` 示例数据核实）。

导出文件名形如 `shiguangschedule_yyyyMMdd_HHmmss.json`。

## 文件结构（单个 JSON 对象，三段）

| 键 | 内容 |
|---|---|
| `courses` | **扁平课程行**数组，一行 = 一门课在某天某几节的一次安排 |
| `timeSlots` | 作息表 `[{number, startTime, endTime}]`，`number` 从 1 开始 |
| `config` | 开学日期、学期总周数、默认课长与课间（分钟） |

## 字段语义

| 字段 | 类型 | 语义 |
|---|---|---|
| `id` | string | 上游行 UUID，**空课不沿用**（重铸 ID + 按学期名对齐，见 `ImportProvenance`） |
| `name` / `teacher` / `position` | string | 课名 / 教师 / 教室；`teacher` 常为空串 |
| `day` | int | 1=周一 … 7=周日 |
| `startSection` / `endSection` | int | 起止节次（1-based，含端点） |
| `color` | int | **上游调色板下标**（适配脚本 `randomColor()` 取 1..12），不是色值 |
| `weeks` | int[] | **显式周次数组**，`[1,3,5,…]` 这种单双周也是逐周列出来的 |
| `isCustomTime` | bool? | 实际上下课时间与作息表对不上；上游 23.5% 的适配器在用 |
| `customStartTime` / `customEndTime` | string? | `"HH:mm"`，`isCustomTime` 为真时才有意义 |
| `config.semesterStartDate` | string | `"yyyy-MM-dd"`，**不是**空课的 `firstDay`（见下） |
| `config.semesterTotalWeeks` | int | 学期总周数；上游有写成 `totalWeeks` 的，两个都认 |
| `config.defaultClassDuration` / `defaultBreakDuration` | int | 分钟，作息表不够长时用来补齐 |

## 与空课模型的四处差异

对应 `docs/jw-adapter-porting.md` §4（上游就是拾光，口径复用同一份手册），
解析实现见 `importer/.../shiguang/ShiguangParser.kt`：

1. **§4.1 周次数组 → 极大段 + 单双周**：`[1,3,5,7]` → `startWeek=1, endWeek=7, weekType=ODD`；
   切不成一段的写成同一门课的多个课块。
2. **§4.3 开学日**：`semesterStartDate` 要**回退到每周起始日那一天**才是 `firstDay`，
   日期是周三、起始日是周一时直接拿来用会整学期偏 2 天。
3. **§4.4 `isCustomTime`**：还带节次就按节次导入（自定义时间是附加信息）；
   只有自定义时间就找最接近的一节（差 ≤1 小时），找不到才跳过并写进 warnings。
4. **颜色**：两边色板顺序不同，照搬下标只保得住「哪几门课同色」，保不住色相；
   下标非法时退回按课名关键词上色（与教务导入同一套）。

## 样本覆盖矩阵

| 课程 | 覆盖的情形 |
|---|---|
| 高等数学A | 连续周次（1-16）、连堂（1-2 节） |
| 线性代数 | **单周**（`[1,3,5,7,9,11]` → ODD 1-11） |
| 大学英语 | **双周** + 上游把连堂**拆成两行**（5 节、6 节各一行，应合并成 5-6 节） |
| 晨跑打卡 | 只有自定义时间没有节次（08:05 → 最接近第 1 节）、`teacher` 为空串 |

同一份文件复制在 `importer/src/test/resources/shiguang_sample1.json` 供 `ShiguangParserTest` 断言。
