# 移植上游教务适配器（批次九 · 5 件正方）

日期：2026-10-20　BASE：主仓库 `f2f3fdd`、适配器库（`jw-adapters/` 子模块）`3d283da`
模式：/stage 轻量模式 —— **haiku 移植（5 件并行）、fresh reviewer → adversary 审计、主 agent 调度与集成**

> 用户 2026-10-20 要求：「再并发进行5个适配器的移植工作」。
> 按[纯适配器改动只发适配器库](../jw-adapter-porting.md)的口径，本批只动 `jw-adapters/`，应用本体不发版；
> 用户已经明确要求开工，本文档视同点头，不再等确认。

搬运方法见[移植手册](../jw-adapter-porting.md)，验证标准见[测试方案](../jw-adapter-testing.md)，
上一批的审计结论见[批次八](2026-10-19-port-adapters-batch8.md)。

## Goal

内置适配器 81 → 86：五件都是**正方新版 `jwglxt`**，按行级相似度从剩余上游脚本里挑最近的正方件。
模板改用**批次八已审计、已补齐两个周次坑**的 `hueb` / `jsei`（`lcudcc` 的单双标记归属与「共 N 周」剥离尚未回填）。

## 上游

上游 [shiguang_warehouse](https://github.com/XingHeYuZhuan/shiguang_warehouse) 快照更新到 `19a65bf`（2026-10-19 22:41）；
快照 `%TEMP%/sgw-latest` 从 `c4a35fe` 快进。新快照里 `XATU/myschool.js` 有改动（已移植件 `xatu` 的上游），**不在本批范围**，单独记账。

| key | 学校 | 上游（作者） | 本校脚本提交 | 与谁最像（相似度） | 模板 / 参考件 |
|---|---|---|---|---|---|
| `zwu` | 浙江万里学院 | `ZWU/zwu_01.js`（星河欲转） | `bdb97ca`（10-14） | `JSEI/jsei_01.js` 0.782 | 模板 `jsei`（门户登录 + 教务主机进 allowHosts） |
| `xust` | 西安科技大学 | `XUST/xust_01.js`（星河欲转） | `80e30cf`（09-23） | `NJTECH/njtech.js` 0.731 | 模板 `hueb`；逐课自定义时间参考 `qqhrit` |
| `gzmtu` | 广州航海学院 | `GZMTU/gzmtu.js`（weiwei221206） | `80e30cf`（09-23） | `HUEB/hueb.js` 0.640 | 模板 `hueb` |
| `gdust` | 广东科技学院 | `GDUST/gdust.js`（星河欲转） | `80e30cf`（09-23） | `NJTECH/njtech.js` 0.872 | 模板 `hueb`；WebVPN 路径前缀参考 `cauc` |
| `gdpu` | 广东药科大学 | `GDPU/gdpu_01.js`（星河欲转） | `80e30cf`（09-23） | `CQCIVC/cqcivc.js` 0.672 | 模板 `hueb`；WebVPN 参考 `cauc`；提问桥参考 `lnu` |

相似度表在 `.test/port-batch8/survey9-out.txt`。没选的近邻：`GXCME`（教务主机名上游没给，拿不准 `allowHosts`）、
`QDU` / `DLU`（强智 / 非正方）、`LYU`（EnLink VPN 的 `enlink-vpn` 参数语义不明）。

逐件要点（任务卡全文在 `.test/port-batch9/card-<key>.md`，公共说明在 `.test/port-batch9/prompt-common.md`，已 gitignore）：

| key | 取数 / 作息 | 与模板不同构之处 | 登录 / 主机 |
|---|---|---|---|
| `zwu` | 三连；**不请求作息接口**（上游明说不用）；冬令时（10–4 月）/夏令时（5–9 月）两张 14 节表 | 载荷只有一张节次时间表：按 extract 交出的 `today` 所在月份选表，另一张的差异写进 warnings | `loginUrl` 门户 `ehall.zwu.edu.cn`；`allowHosts: ["jw.zwu.edu.cn"]` |
| `xust` | 三连 + 可选 `cxRjc`；取不到用上游 10 节 | 教务是裸 IP `59.74.174.150`（http）；上游给「体育场地 / 9–18 号楼」第 3–4 节换自定义时间，按手册 §4.4 丢自定义时间并汇总进 warnings | `loginUrl` 门户 `jwsso.xust.edu.cn`；`allowHosts: ["59.74.174.150"]` |
| `gzmtu` | 三连 + 可选 `cxRjc`；取不到用上游 10 节 | 课表主接口失败时改打备用接口 `xskbcx_cxXsKb.html`；节次字段 `jcor`/`jcs`/`jc` 依次取；课名 `kcmc_raw` 兜底；**不拿 `cd_id`（编号）当教室** | `loginUrl` 上游课表页（http）；`allowHosts: []` |
| `gdust` | 三连（**根路径**，无 `/jwglxt`）+ 可选 `cxRjc`；取不到用上游 10 节 | WebVPN 路径前缀 `/http/<hex>/` + 查询串 `vpn-12-o1-…`；校内直连时会落到 `http://172.16.254.1` | `loginUrl` WebVPN CAS；`allowHosts: ["172.16.254.1"]` |
| `gdpu` | 三连（根路径）+ 可选 `cxRjc`；取不到时按校区选上游 15 节表 | WebVPN 路径前缀同上；两个校区两张表：`cxRjc` 拿到就用，拿不到用 `__ncSelect` 问一次校区（没有提问能力时用广州 / 云浮表并说明） | `loginUrl` WebVPN 统一认证；`allowHosts: []` |

本批共性（写进公共说明）：沿用批次八全部共性；模板换成批次八件；提醒里不许让学生去「学期管理」改教师 / 教室（指向「编辑课程」）；
AUDIT 的行号引用收尾时按当前文件逐条校准。

## Files

新增 `jw-adapters/{zwu,xust,gzmtu,gdust,gdpu}/`（`manifest.json`、`extract.js`、`parse.js`、`AUDIT.md`、`fixtures/`）；
`jw-adapters/index.json` 加 5 条并把 `version` 升到 `2026.10.20`（主 agent 统一改）。不改任何 Kotlin、既有适配器、规范文档。

## Task split

5 个 haiku agent 并行，各写各的目录（无共享文件，不用 worktree）。硬约束沿用批次五～八。
主 agent 负责：写任务卡、派工、集成 `index.json`、跑全量 `:importer:test`、extract.js 浏览器冒烟、推送与发适配器库 release。

## 审计

stage 轻量模式第 3–7 步：每件一个 fresh reviewer（对照上游与模板找正确性与安全 bug）→ 非空清单各交一个 fresh adversary 独立证伪
→ CONFIRMED 交回修、主 agent 复跑自检与 CI 门；REFUTED 丢弃；UNCLEAR 能验就验。不自动复审。

## 实施与审计记录（实测，2026-10-20）

- 五件全部完成，共 33 对 fixture（`zwu` 7、`xust` 6、`gzmtu` 8、`gdust` 6、`gdpu` 6），自检 `check.js` 全部 `RESULT: PASS`。
- extract.js 浏览器冒烟（`.test/port-batch9/smoke9.py`，15 个场景）全部通过：WebVPN 前缀与 `vpn-12-o1-…` 片段（含查询串里没有时补上）、
  `gdust` 校内直连不带片段、`gzmtu` 主接口 500 改打备用接口、`gdpu` 教务给了节次时间不问 / 没给时问一次校区 / 没有提问能力时不问、
  门户页 / WebVPN 首页 / 登录页一律不发请求；响应里埋的学号、身份证号、手机号、`cd_id` 都没有出现在交出的数据里。
  （冒烟脚本初版的提问桥桩被 Playwright 当函数调用了一次，报出两个假 FAIL，修桩后通过。）
- `./gradlew :importer:test --rerun`：全绿（`JwLibraryHarnessTest` 覆盖 86 件全部 fixture）。
- 集成扫描（`.test/port-batch9/integ9.py`）：提醒全部 ≤200 字 / ≤20 条、无开发者用语、补教师 / 教室都指向「编辑课程」；
  模板学校名只出现在 `xust` / `gzmtu` 的 AUDIT「与模板的差异」对照表里（有意）。
- 主调度裁定：`zwu` 只导入当季一张节次时间表（另一套写进提醒）；`gzmtu` 表外节次先借空课默认 12 节、第 13 节起顺推，是模板 H3 口径，任务卡「11 节起顺推」的写法不严谨，以模板为准。

### 审计结果

| key | reviewer 报出 | adversary | 处理 |
|---|---|---|---|
| `zwu` | 无 | —— | —— |
| `xust` | 无 | —— | —— |
| `gzmtu` | 无 | —— | —— |
| `gdust` | 无 | —— | —— |
| `gdpu` | B1 教务节次时间非空但不合法时不问校区；B2 `hasPeriodRows` 认 `kbList` 信封而 `slimRows` 不认 | B1 REFUTED（任务卡定的口径）、B2 CONFIRMED | 修 B2：两处共用 `ENVELOPE_KEYS`（含 `kbList`），新增冒烟场景 `gdpu-envelope`；AUDIT 记录 |

## 提交与发布（实测）

- 适配器库两个提交：`5273e1e`（新增 5 件，索引条目 81 → 86）、`dbd72cc`（`chore(release)`：版本 `2026.10.19` → `2026.10.20`），已推送 `origin/main`。
- CI（run `39377761314`）harness 通过，release job 发出 `v2026.10.20`
  （`latest.json`、`nullclass-adapters.zip` 与签名），应用里「检查更新」即可热更新。
- 主仓库：只新增了本文档；子模块指针与应用版本按纯适配器口径未动。

## Out of scope

真机验证（没有五校账号）；`xatu` 上游改动的跟进；模板 `lcudcc` 的周次修正回填；其余未移植件。

## Done when

- 五个目录齐全，自检全部 PASS，`./gradlew :importer:test` 全绿；
- 每件 `AUDIT.md` 签过移植手册 §5 八条，带变异测试记录；
- reviewer + adversary 跑完，CONFIRMED 修掉；
- 适配器库推送并发 release（应用不发版）。
