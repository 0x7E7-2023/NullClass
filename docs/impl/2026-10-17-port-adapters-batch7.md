# 移植上游教务适配器（批次七 · 大连理工 / 东北林业 / 辽宁大学）

日期：2026-10-17　BASE：主仓库 `40dd59f`、适配器库（`jw-adapters/` 子模块）`e184e15`　
模式：/stage 轻量模式 —— **haiku 移植（3 件并行）、fresh reviewer → adversary 审计、主 agent 调度与集成**

> 用户 2026-10-17 要求：「用 /stage 方式派发 haiku 进行工作，完成大连理工 + 东北林业 + 辽宁大学这三所学院的适配器的移植。
> 移植完毕后单独发版一个适配器」。按[纯适配器改动只发适配器库](#提交与发布)的口径：只发适配器库 release，应用本体不发版。

搬运方法见[移植手册](../jw-adapter-porting.md)，验证标准见[测试方案](../jw-adapter-testing.md)，
上一批的审计结论见[批次五 / 六](2026-10-08-port-adapters-batch5-6.md)。

## Goal

内置适配器 73 → 76（含 10-10 合并的社区贡献 `xjtu`）：三所学校分属三个平台，每件都有与模板**不同构**的一层，派工时逐件写清哪一层要按上游重写。

## 上游

上游 [shiguang_warehouse](https://github.com/XingHeYuZhuan/shiguang_warehouse) 最新 `1dc0772`（2026-10-17）。
本批三份脚本与本地快照（10-08 普查时拉的）逐字一致（只差 CRLF）。

| key | 学校 | 上游（作者） | 本校脚本提交 | 平台 | 模板 / 参考件 |
|---|---|---|---|---|---|
| `dlut` | 大连理工大学 | `DLUT/dlut_01.js`（dffzcqs78694） | `0de4191`（09-17） | 树维 for-std | 模板 `jstc`；取数照 `ustc`（get-data + POST datum 的 `lessonList` / `scheduleList`） |
| `nefu` | 东北林业大学 | `NEFU/nefu.js`（Chr0n0stasis） | `247e1e1`（09-28） | 强智 `jsxsd` **新版界面**（`table.qz-weeklyTable` + `li.courselists-item`） | 模板 `yssdufe`（教学周历、文案）；课表解析按上游重写 |
| `lnu` | 辽宁大学 | `LNU/LNU_01.js`（739C1AE2） | `94c2bea`、`b0f05f7`（08-30、09-05） | URP 综合教务 | 模板 `neau`（同一个课表接口）；校区提问照 `qau` |

逐件要点（任务卡全文在 `.test/port-batch7/card-<key>.md`，公共说明在 `.test/port-batch7/prompt-common.md`，已 gitignore）：

| key | 取数 | 与模板的不同构之处 | 节次时间 / 开学日 |
|---|---|---|---|
| `dlut` | `GET /student/for-std/course-table`（学期下拉 + `stdPersonId`）→ `GET .../course-table/get-data?bizTypeId=2&semesterId=` → `POST /student/ws/schedule-table/datum` → `POST .../timetable-layout` → `GET /student/ws/semester/get/{id}` | 课表数据是 `lessonList` / `scheduleList`（不是 jstc 的 print-data）；排课记录给的是起止时刻，要按节次表换算节次；`stdPersonId` 只进请求体、不交出去 | 教务 `courseUnitList`，取不到用上游内置 12 节；开学日 `semester/get`，取不到按排课记录最早日期 + 周号回推（上游用 UTC 运算，改为本地日期） |
| `nefu` | `GET /jsxsd/xskb/xskb_list.do?viweType=0`（三个下拉：学期 / 作息模式「本部」/ 周次）→ 同路径带 `xnxq01id`、`kbjcmsid`、`zc=` 取整学期 | 新版界面 DOM，与已有强智件全不同；上游判据很严（表头、网格、卡片计数），结构问题整体报错，单张坏卡改为跳过并点名 | 上游**不导入作息与学期设置**；我们按行标签的大节起止推小节时间（课间 5 分钟，提醒核对），开学日 / 总周数新增教学周历请求（同 yssdufe） |
| `lnu` | 页面 `#planCode` 选中项 → `POST` 课表接口（`planCode=`；无下拉框则 `GET`）→ `GET /indexCalendar`（只取 `rq` 开学日、`skzc` 总周数两个值） | neau 是早期件（通配 `allowHosts`、报错文案旧），按现行规则改；本校上游只读 `classWeek` 位串 | 三校区（崇山 / 蒲河 / 武圣）内置 12 节表或教务 `jcsjbs`，`__ncSelect` 问一次，默认项按课程校区猜；所选不是默认学期时不用 `indexCalendar` 的值 |

## Files

新增 `jw-adapters/{dlut,nefu,lnu}/`（`manifest.json`、`extract.js`、`parse.js`、`AUDIT.md`、`fixtures/`）；
`jw-adapters/index.json` 加 3 条并把 `version` 升到 `2026.10.17`（主 agent 统一改）。不改任何 Kotlin、既有适配器、规范文档。

## Task split

3 个 haiku agent 并行，各写各的目录（无共享文件，不用 worktree）。硬约束沿用批次五 / 六，另把那两批审计确认过的
共性问题（H3 节次超出节次时间表的补法、X2 / X3 给学生看的文案）直接写进公共说明，开工即按新口径写。

## 审计

stage 轻量模式第 3–7 步：每件一个 fresh reviewer（对照上游与模板找正确性与安全 bug）→ 非空清单各交一个 fresh adversary 独立证伪
→ CONFIRMED 交回 haiku 修、主 agent 复跑自检与 CI 门；REFUTED 丢弃；UNCLEAR 能验就验。不自动复审。

## 审计结果与修复（实测，2026-10-17）

每件一个 sonnet reviewer（读全目录、对照上游与模板、Rhino 实跑自造输入），非空清单交 sonnet adversary 独立复核。

| key | reviewer 报出 | adversary | 修法（haiku 修、主 agent 复跑自检与读改动） |
|---|---|---|---|
| `dlut` | B1 带课名的提醒没有限长：三门约 70 字的英文课名就让一条提醒超过 200 字，应用会拒收整次导入；B2 非 2xx 报错写成「异常状态码」 | 两条 CONFIRMED | 全部提醒走同一个限长出口（每条 ≤200 字、点名课名截到 20 字、≤20 条），补 `long-names` 用例；报错改为「教务系统返回错误（代码 N）」 |
| `lnu` | B1 同上（`pushWarning` 只限条数；三门 80 字课名都缺连续节数时一条 292 字） | CONFIRMED | 同上，补 `long-names` 用例 |
| `nefu` | 无 | —— | 主 agent 顺手把 AUDIT 里「节次超出时间表不会发生」改准（走默认 12 节 + 顺推、越过 23:59 时会出现，代码已提醒） |

两件的「整条 200 字截断」变异钉不住（课名先截到 20 字后，现有提醒最长约 110 字），属等价变异，AUDIT 已如实写明；点名课名截短那一步有用例钉住。

**派工后主调度定的点**（代理报告里的「拿不准」）：

- `lnu`：教室名在去掉校区前缀后，再去掉与楼名重复的前缀（上游会拼出「崇山校区文科楼文科楼101」）；`extract.js` 不再交 parse 用不到的学期代码。
- `nefu`：节次时间推不全且课表超过 12 节时，交空课默认 12 节再顺推补齐（H3 口径）；学期名占位沿用 `yssdufe` 的「东北林业大学当前学期」；
  上游的周次回显校验原样保留（回显不是空串会整体报错，AUDIT 记为待真机核对）。
- `dlut`：H3 不适用（节次号只从正在用的节次表映射出来，不会超出表长）；分组键多带教室，避免同一时段两间教室被并掉一间。

## 主 agent 集成（实测）

- **extract.js 浏览器冒烟**（`.test/port-batch7/smoke/smoke.py`）：Chromium 里用 route 拦下全部请求、起假教务站（不联网），跑真实的 `extract.js`，
  再交 Rhino 跑 `parse.js`。三件的请求路径、方法、请求体都与上游一致（dlut 的 `stdPersonId` 只出现在 datum 请求体里；lnu 是
  `planCode=` 的 POST + 校区提问默认项按课程校区猜中「崇山」；nefu 选中「本部」作息模式、`zc=` 空、周历 POST），
  假响应里埋的人员 ID、身份证号、手机号、学号、姓名都没有出现在交出去的数据里；三份载荷都合法。
- 3 件自检 PASS（合计 16 对 fixture）；无 BOM / 控制字节；给学生看的文字零开发者用语；模板学校只在注释与 AUDIT 里被有意引用。
- `./gradlew :importer:test`：206 个测试全过（`JwLibraryHarnessTest` 用 Rhino 实跑 75 件的全部 fixture）。
  反向对照：把 `nefu/fixtures/basic.expected.json` 的一个 `endPeriod` 从 2 改成 3，门变红（1 failed）；还原后 sha256 一致，门复绿。

## 提交与发布

- **适配器库**：一个移植提交 + 一个发版提交（`index.json` 的 `version` `2026.10.08` → `2026.10.17`），推到 `main`；
  CI 的 harness 通过后 release job 发出 `v2026.10.17`，用户在应用里「检查更新」即可拿到。
- 推送时发现远端多了 10-10 合并的社区 PR #1（西安交通大学 `xjtu`，Stellortus 贡献，当时没升版本、没发版）。
  两个提交变基到它之上，`index.json` 两边都在末尾追加，合并为 `xjtu` 之后接本批 3 条；变基后的 76 件
  `:importer:test` 全绿。所以 `v2026.10.17` 同时带上了 `xjtu`。
- **主仓库**：提交本文档 + 子模块指针，推到 `main`；**不发应用版本**（不升 versionCode、不打 `v*` tag、不写 CHANGELOG）。

## Out of scope

真机验证（没有三校账号）；其余未移植件；已发布件的同类缺陷修复（见上一批「后续」）。

## Done when

- 三个目录齐全，自检全部 PASS，`./gradlew :importer:test` 全绿；
- 每件 `AUDIT.md` 签过移植手册 §5 八条，带变异测试记录；
- reviewer + adversary 跑完，CONFIRMED 修掉；
- 两个仓库已推送，适配器库 CI 发出 `v2026.10.17`；应用不发版。
