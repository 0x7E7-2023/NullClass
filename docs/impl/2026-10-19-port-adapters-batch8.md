# 移植上游教务适配器（批次八 · 5 件正方近克隆）

日期：2026-10-19　BASE：主仓库 `f2f3fdd`、适配器库（`jw-adapters/` 子模块）当前 `main`
模式：/stage 轻量模式 —— **haiku 移植（5 件并行）、fresh reviewer → adversary 审计、主 agent 调度与集成**

> 用户 2026-10-19 要求：「进行下一阶段的适配器移植工作，派发五个 agent，移植五个适配器」。
> 按[纯适配器改动只发适配器库](../jw-adapter-porting.md)的口径，本批只动 `jw-adapters/`，应用本体不发版；
> 用户已经明确要求开工，本文档视同点头，不再等确认。

搬运方法见[移植手册](../jw-adapter-porting.md)，验证标准见[测试方案](../jw-adapter-testing.md)，
上一批的审计结论见[批次七](2026-10-17-port-adapters-batch7.md)。

## Goal

内置适配器 76 → 81：五件都是**正方新版 `jwglxt`**，与已审计的 `lcudcc` / `shzq` 同族。
选法沿用第五批的思路：先挑与已审计件最像的（行级 diff 最小），把审计火力集中在「这一所和模板差在哪」上。

## 上游

上游 [shiguang_warehouse](https://github.com/XingHeYuZhuan/shiguang_warehouse) 最新 `c4a35fe`（2026-10-19 拉取，259 个学校目录）。
本次换了新快照（旧的 `sgw-20261008` 工作区为空，已重新 clone 到 `%TEMP%/sgw-latest`）。
五份脚本与快照逐字核对过（只差 CRLF），行级相似度（相对已移植件的上游脚本）：

| key | 学校 | 上游（作者） | 本校脚本提交 | 与谁最像（相似度） | 模板 / 参考件 |
|---|---|---|---|---|---|
| `xhkjzyxy` | 宣化科技职业学院 | `XHKJZYXY/xhkjzyxy_01.js`（星河欲转） | `eb1921c`（10-08） | `SHZQ/shzq.js` 0.974 | 模板 `lcudcc`（合并逻辑）；作息表参考 `shzq` |
| `hueb` | 河北经贸大学 | `HUEB/hueb.js`（Haooz） | `80e30cf`（09-23） | `LCUDCC/lcudcc.js` 0.961 | 模板 `lcudcc` |
| `jsei` | 江苏电子信息职业学院 | `JSEI/jsei_01.js`（星河欲转） | `4570e79`（10-14） | `SHZQ/shzq.js` 0.940 | 模板 `lcudcc`；作息表参考 `shzq` |
| `cqepc` | 重庆航天职业技术学院 | `CQEPC/cqepc.js`（yang01） | `80e30cf`（09-23） | `LCUDCC/lcudcc.js` 0.909 | 模板 `lcudcc` |
| `qqhrit` | 齐齐哈尔工程学院 | `QQHRIT/qqhrit.js`（星河欲转） | `80e30cf`（09-23） | `LCUDCC/lcudcc.js` 0.842 | 模板 `lcudcc` |

逐件要点（任务卡全文在 `.test/port-batch8/card-<key>.md`，公共说明在 `.test/port-batch8/prompt-common.md`，已 gitignore）：

| key | 取数 / 作息 | 与模板不同构之处 | 登录 / 主机 |
|---|---|---|---|
| `xhkjzyxy` | 三连 + 作息 `POST /jwglxt/jzgl/skxxMobile_cxRsdjc.html?gnmkdm=N2154`（`xqh_id=1`）；取不到用上游 10 节 | CAS 登录；教务在 `:8387`；`scheduleUrlHint` 必须带端口 | `loginUrl` CAS `:18088`；`allowHosts: []` |
| `hueb` | 三连；无作息接口，用模板的 `cxRjc` 可选请求，取不到用上游 13 节 | WebVPN 映射主机（`202-206-194-100.vpn.hueb.edu.cn:8118`）；**上游文件头把校名写成「湖北工程学院」，以 adapters.yaml 的「河北经贸大学」为准** | 登录页就在映射主机上；`allowHosts: []` |
| `jsei` | 三连 + 作息同 `xhkjzyxy`；取不到用上游 11 节 | 上游有 WebVPN / 校园网双入口；只做一个适配器：登录用 WebVPN 映射主机（照 `cauc` 先例），`allowHosts` 放校园网直连主机 | `loginUrl` `jwpd-443.webvpn.jsei.edu.cn`；`allowHosts: ["jwpd.jsei.edu.cn"]` |
| `cqepc` | 三连；无作息接口，用模板的 `cxRjc`，取不到用上游 14 节 | 14 节表里第 5、6 节是上游的「午休占位」，原样保留并在 AUDIT 记一笔 | `http://jw.cqepc.cn/`；`allowHosts: []` |
| `qqhrit` | 三连；无作息接口，用模板的 `cxRjc`，取不到用上游 11 节 | 上游按周末 / 特殊教室（`2#`/`3#`、`图`/`馆`/`齐三机床`）切逐课自定义时间；我们的载荷只有一个全局节次表，按手册 §4.4 丢自定义时间、按类型汇总进 warnings，不静默丢课 | CAS `tysf.qqhrit.com:20261`；教务 `jwxt.qqhrit.com:20266`；`allowHosts: ["jwxt.qqhrit.com"]` |

本批共性问题（直接写进了公共说明）：上游行过滤器会把缺教师 / 缺教室的课整行丢掉，必须改成允许 `null`；
上游弹窗选学年学期改成读页面 `#xnm/#xqm`；课表请求体五所都带 `kclbdm=`；
每条 warning 走同一个 ≤200 字出口（批次七审计真抓到过整次导入被拒收）；H3 节次补齐用 `lcudcc` 的实现。

## Files

新增 `jw-adapters/{xhkjzyxy,hueb,jsei,cqepc,qqhrit}/`（`manifest.json`、`extract.js`、`parse.js`、`AUDIT.md`、`fixtures/`）；
`jw-adapters/index.json` 加 5 条并把 `version` 升到 `2026.10.19`（主 agent 统一改）。不改任何 Kotlin、既有适配器、规范文档。

## Task split

5 个 haiku agent 并行，各写各的目录（无共享文件，不用 worktree）。硬约束沿用批次五～七。
主 agent 负责：写任务卡、派工、集成 `index.json`、跑全量 `:importer:test`、extract.js 浏览器冒烟、推送与发 API 适配器库 release。

## 审计

stage 轻量模式第 3–7 步：每件一个 fresh reviewer（对照上游与模板找正确性与安全 bug）→ 非空清单各交一个 fresh adversary 独立证伪
→ CONFIRMED 交回 haiku 修、主 agent 复跑自检与 CI 门；REFUTED 丢弃；UNCLEAR 能验就验。不自动复审。

## 实施与审计记录（实测，2026-10-19）

- 五件全部完成，共 40 对 fixture（`xhkjzyxy` 9、`hueb` 8、`jsei` 8、`cqepc` 7、`qqhrit` 8）。
- 自检 `check.js`：五件全部 `RESULT: PASS`。
- extract.js 浏览器冒烟（`.test/port-batch8/smoke8.py`）：五件的请求路径 / 方法 / 请求体与任务卡一致；
  响应里埋的 `xsxx`、学号、身份证号、手机号都没有出现在交出的数据里；五份载荷合法。
- `./gradlew :importer:test`：全绿（81 件，含本批 5 件）。
- 集成扫描（`.test/port-batch8/integ8.py`）：模板学校只在 AUDIT 的有意说明里出现；
  warnings 全部 ≤200 字 / ≤20 条、无开发者用语；`index.json` 81 条与目录一致。

### 审计结果

| key | reviewer 报出 | adversary | 处理 |
|---|---|---|---|
| `cqepc` | 无 | —— | —— |
| `xhkjzyxy` | B1 丢了上游「共 N 周」剥离；B2 集中实践课点名未截 20 字 | 两条 CONFIRMED | 已修；新增 `weeks-markers` / `practice-long-name` 用例 |
| `jsei` | B1 多段周次里段首单/双标记被并到上一段（`(单)1-8周,(双)9-16周` → 1-16 ALL）；B2 作息表重复 `jcmc` 导致整表回落 | B1 CONFIRMED、B2 REFUTED | 修 B1；新增 `week-markers` 用例 |
| `qqhrit` | B1 用户可见提醒用了「作息表 / 空课默认作息」 | CONFIRMED | 统一改「节次时间」，期望同步 |
| `hueb` | B1 缺教师 / 教室提醒指向「学期管理」（那里改不了）；B2 AUDIT 行号引用漂移；B3「共 N 周」贪婪正则吞掉后续周次 | 三条 CONFIRMED | 已修：指向「编辑课程」、行号校准、正则收紧 |

### 同批统一修正（模板级）

1. **单/双标记归属**：模板 `lcudcc` 的 `weeksOf` 只向前找最近周次段，`(单)1-8周,(双)9-16周` 会把两个标记并到前一段，
   整门课退化成 1-16 每周都上。五件都改为先按括号与相邻数字的位置归属（前缀贴后段、后缀贴前段），
   独立成段的标记仍走就近逻辑。模板本身未改（只读），留给后续批次回填。
2. **「共 N 周」剥离**：上游 `XHKJZYXY` / `JSEI` 的 `parseWeeks` 有这一步，模板没有；五件统一补上，
   并采用只剥备注本身的正则（`/共\s*[0-9]+\s*周?/g`），避免从「共」删到行尾把后面的真实周次段一起吞掉。
3. **缺教师 / 缺教室提醒**：模板不提醒；五件统一按类型汇总计数、课名截 20 字，整条 ≤200 字。
   `xhkjzyxy` / `hueb` / `cqepc` 的提醒指向「编辑课程」（学期管理改不了教师与教室）。

## 提交与发布（实测）

- 适配器库两个提交：`e5347b9`（新增 5 件，索引条目 76 → 81，版本仍为 `2026.10.17`）、
  `3d283da`（`chore(release)`：版本 `2026.10.17` → `2026.10.19`），已推送 `origin/main`。
- CI（run `39201314091`）harness 通过，release job 发出 `v2026.10.19`
  （`latest.json`、`nullclass-adapters.zip` 与签名），应用里「检查更新」即可热更新。
- 主仓库：只新增了本文档；子模块指针与应用版本按纯适配器口径未动
  （不升 versionCode、不打应用 tag、不写主仓库 CHANGELOG）。

## Out of scope

真机验证（没有五校账号）；其余未移植件；已发布件的同类缺陷修复。

## Done when

- 五个目录齐全，自检全部 PASS，`./gradlew :importer:test` 全绿；
- 每件 `AUDIT.md` 签过移植手册 §5 八条，带变异测试记录；
- reviewer + adversary 跑完，CONFIRMED 修掉；
- 适配器库推送并发 release（应用不发版）。
