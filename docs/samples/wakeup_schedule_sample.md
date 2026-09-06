# WakeUp 课表样本文件说明

`wakeup_schedule_sample.wakeup_schedule` 是手工构造的 `.wakeup_schedule` 格式样本（结构经开源解析器
[Xtao-Labs/WakeUp2XiaoAi](https://github.com/Xtao-Labs/WakeUp2XiaoAi) 的 models 核实，字段与真实
WakeUp 备份一致；行 0-2 的具体内容为合理推测，**解析器不得依赖行号**）。

## 文件结构（逐行 JSON，每行一个独立 JSON 文档）

| 行 | 内容 | 说明 |
|---|---|---|
| 0 | `"3"` | 版本号（字符串数字） |
| 1 | 学期设置对象 | courseTableName/startTime/nodesPerDay/maxWeek 等 UI 设置 |
| 2 | 节次时间数组 | `[{node, startTime, endTime}, ...]`，node 从 1 开始 |
| 3 | **CourseInfo 数组** | `[{id, color, courseName}]` —— 课名与颜色的"课程表" |
| 4 | **Course 数组** | `[{id, day, startWeek, endWeek, startNode, step, room, teacher, type?}]` —— 时间安排 |

关键关系：**CourseInfo.id ↔ Course.id**。一门课多个时间安排 = 多条 Course 行共享同一 id（样本中
「高等数学(上)」有周一 1-2 节和周三 1-2 节两条）。这正好对应空课的 Course + ScheduleBlock 模型。

## 字段语义

| 字段 | 类型 | 语义 |
|---|---|---|
| `day` | int | 1=周一 … 7=周日 |
| `startWeek`/`endWeek` | int | 周次范围（含端点） |
| `startNode` | int | 开始节次（1-based） |
| `step` | int | 连节数（大课=2）→ 空课 `startPeriod=startNode, endPeriod=startNode+step-1` |
| `type` | int? | 0=每周 1=单周 2=双周；**字段可选**，缺失按每周处理 |
| `room` | string | 教室 |
| `teacher` | string | 教师 |
| `color` | string | 8 位 AARRGGBB（`#FF` + RGB） |

## 样本覆盖的测试矩阵（6 门课 / 7 条安排）

| 课 | 安排 | 覆盖点 |
|---|---|---|
| 高等数学(上) | 周一 1-2 节 A101 每周 1-20 | 基础连堂 |
| 高等数学(上) | 周三 1-2 节 A302 每周 1-20 | **同课多安排**（一对多结构） |
| 大学英语(一) | 周三 3-4 节 B202 **单周** 1-19 | WeekType.ODD |
| 数据结构 | 周二 5-6 节 实验楼404 **双周** 1-16 | WeekType.EVEN + 下午节次 |
| 体育(一) | 周五 7-8 节 操场 每周 **2-20** | startWeek≠1（首周缺课） |
| 大学物理(一) | **周日** 9-10 节 C303 双周 **3-18** | day=7 + 晚上节次 + 双端偏移 |
| 形势与政策 | 周四 11-12 节 D404 每周 **1-8** | 短周期课 + 深夜节次 |

学期信息：`startTime=2026-09-07`（周一）、`maxWeek=20`、`nodesPerDay=12`。

## 给解析器实现者（M4 T4）的注意点

1. **不得依赖行号**：逐行 `readLine` → 尝试 `Json.parseToJsonElement`，按内容特征分类——
   数组元素含 `courseName` → CourseInfo 行；含 `startNode` → Course 行；元素含 `node`+`startTime` → 节次时间行
2. **type 缺失防御**：默认每周
3. **颜色映射**：去 alpha 取 RGB，与空课 12 色板做欧氏最近匹配
4. 节次时间行（行 2）如果存在，**优先用它**生成 period_times（比 DefaultPeriodTimes 准）；
   不存在再退化到默认模板（节数取 `max(nodesPerDay, max(startNode+step-1))`）
5. 学期行（行 1）的 `startTime` 是 `yyyy-MM-dd`；不是周一时**回退到所在周的周一**（与空课模型对齐）
6. WakeUp 的 Course 行**没有独立 UUID**——导入时给每条 Course 生成新 UUID 作 blockId，
   同 id 的多条 Course 行归并到同一个新 Course（课名取 CourseInfo）
