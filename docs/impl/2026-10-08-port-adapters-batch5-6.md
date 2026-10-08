# 移植上游教务适配器（第五、六批 · 每批 10 件）

日期：2026-10-08　BASE：主仓库 `76a390b`、适配器库（`jw-adapters/` 子模块）`d84b40a`　
模式：完整模式 / 并行 —— **haiku 移植、sonnet 两段审计（reviewer → adversary）、主 agent 调度与集成**

> **点头记录**：用户 2026-10-08 要求「按 10 个适配器一组进行适配，先进行两组，然后提交远端但不发版」，
> 并说明外出期间「有疑点请站在我的视角直接判断」。本文档按此视同点头，不再等确认。
> 收尾时用户又要求「完成现在这些个适配器之后就提交发一个适配器版本」：本批改为**发适配器库 release**
> （`index.json` 的 `version` 升到 `2026.10.08`），应用本体仍不发版。

搬运方法见[移植手册](../jw-adapter-porting.md)，验证标准见[测试方案](../jw-adapter-testing.md)，
前四批的实测结论见[批次一/二](2026-09-12-port-adapters.md)、[批次三](2026-09-13-port-adapters-batch3.md)、
[批次四](2026-09-16-port-adapters-batch4.md)。

## Goal

内置适配器 52 → 72：两批各 10 件，**先挑与已审计件最像的**（同族近克隆），让较弱的移植模型也能稳定产出，
把审计火力集中在「这一所和模板差在哪」上。

## 上游现状（2026-10-08 重新普查）

上游快照 `ff72d1f08782df965cae110034a9d87cd91e0c07`（2026-10-08）：**243 个学校目录 / 259 个脚本**
（9 月那次是 207 / 226）。已移植 50 个脚本（含我们自研的 `dlutci` 对应的 `DLUTCI`），
`USTC/ustc.js` 对应我们自研的 `ustc`。剩余 208 个按**脚本实际请求的接口路径**粗分：

| 族 | 剩余 | 备注 |
|---|---|---|
| 正方 `jwglxt` | 54 | 第五批 10 件、第六批 2 件 |
| 强智 `jsxsd` | 27 | 第六批 3 件 |
| 金智 `jwapp` | 16 | 第六批 2 件（`wdkb` 一件、`homeapp` 一件） |
| 树维 `eams` | 7 | 第六批 3 件 |
| for-std | 5 | 未动 |
| 青果 / URP / 超星 / Struts2 | 10 / 10 / 2 / 1 | 未动 |
| 其它（含长尾自研、上游通用工具） | 76 | 未动；正则没认出族的也在这里（例：`CQCST`、`NCUT` 实为强智） |

**已移植件的上游后续修改**（移植后上游又改过，留作「上游同步」单独一批，本次不动）：
`XJIE/xjie_01.js`（10-08 重构课程合并）、`XZHMU/xzhmu_01.js`（09-15 教师名提取）、
`ZZU/zzu.js`（09-28 下午第 7、8 节作息偏 10 分钟）。

## 第五批：正方 `jwglxt` 近克隆 10 件（模板一律 `gsmc`）

选法：拿每个剩余脚本与已移植件的上游脚本做行级 diff，取 diff 最小的正方件。
这 10 件与上游 `GSMC/gsmc_01.js` 是**同一份脚手架**（同一个课表接口、同一套 `kbList` 字段），
差异只在主机 / 上下文路径 / 菜单号 / 请求体 / 作息表，所以统一以已审计的 `jw-adapters/gsmc` 为模板。

| # | key | 学校 | 上游（作者） | 上下文路径 | 登录与主机 | 作息 | 与模板的要点差异 |
|---|---|---|---|---|---|---|---|
| 1 | `haut` | 河南工业大学 | `HAUT/haut_01.js`（星河欲转） | `/jwglxt` | `jwglxt.haut.edu.cn` | 11 节 | 只有主机与作息表 |
| 2 | `ksu` | 喀什大学 | `KSU/ksu_01.js`（星河欲转） | `/jwglxt` | CAS `cas.ksu.edu.cn` → 教务 `jwnet.ksu.edu.cn`（`allowHosts` 写教务主机） | 11 节（新疆作息，10:00 起） | 登录页判断要认 CAS |
| 3 | `sdnu` | 山东师范大学 | `SDNU/sdnu.js`（LiquoriceHy） | `/jwglxt` | `jwxt.sdnu.edu.cn`（http） | 12 节 | 菜单号 `N253508`，请求体多 `kclxdm=` |
| 4 | `gzu` | 贵州大学 | `GZU/gzu.js`（Daoguan-king） | `/jwglxt` | `zhjw.gzu.edu.cn` | 11 节 | 请求体无 `kclxdm` |
| 5 | `yxhmc` | 成都银杏酒店管理学院 | `YXHMC/yxhmc.js`（Xuan-Xuann） | **根路径** | `zfjw.gingkoc.edu.cn`（http） | 13 节 | 无 `/jwglxt` 前缀 |
| 6 | `cauc` | 中国民航大学 | `CAUC/cauc.js`（星河欲转） | **根路径** | WebVPN 映射主机 `http-jwgl-cauc-edu-cn-80.webvpn.cauc.edu.cn`（即 `loginUrl` 主机） | 12 节（第 8 节 16:25-17:50 疑似上游笔误，原样保留并在 AUDIT 记一笔） | 无 `/jwglxt` 前缀 |
| 7 | `njtech` | 南京工业大学 | `NJTECH/njtech.js`（星河欲转） | **根路径** | `jwgl.njtech.edu.cn` | 10 节 | 无 `/jwglxt` 前缀 |
| 8 | `jhzyedu` | 江西航空职业技术学院 | `JHZYEDU/zhengfang.js`（JN） | **根路径** | `jw.jhzyedu.cn` | 8 节 | 无 `/jwglxt` 前缀；作息只有 8 节，模板用例里 9 节以后的课要走「超出作息表」分支 |
| 9 | `cwxu` | 无锡学院 | `CWXU/cwxu_01.js`（ewiro） | `/jwglxt` | `jwgl.cwxu.edu.cn` | 11 节 | 上游重写过周次/节次解析（认「周数：」前缀等），要逐条对照 |
| 10 | `lcudcc` | 聊城大学东昌学院 | `LCUDCC/lcudcc.js`（Haooz） | `/jwglxt` | `jw.lcudcc.edu.cn` | **上游没有作息表** | 开学日取周次校历（模板已有）；作息只能取教务接口或回落空课默认表，并如实说明；上游有「同课连续节次合并 + 同节次周次合并」 |

## 第六批：三族混编 10 件（模板各异）

| # | key | 学校 | 上游（作者） | 族 | 模板 |
|---|---|---|---|---|---|
| 1 | `shiep` | 上海电力大学 | `SHIEP/SHIEP.js`（zt11125） | 树维 `eams` | `tjau` |
| 2 | `nuaa` | 南京航空航天大学 | `NUAA/nuaa_01.js`（kemi-20） | 树维 `eams` | `tjau` |
| 3 | `shnu` | 上海师范大学 | `SHNU/shnu.js`（FaQxD233） | 树维 `eams` | `tjau` |
| 4 | `nuist` | 南京信息工程大学 | `NUIST/nuist.js`（wild0408） | 金智 `wdkb` | `niit` |
| 5 | `xjzfu` | 新疆政法学院 | `XJZFU/xjzfu.js`（jesse-s4） | 金智 `homeapp` | `neu` |
| 6 | `yssdufe` | 山东财经大学燕山学院 | `YSSDUFE/yssdufe_01.js`（星河欲转） | 强智 `jsxsd`（非标端口 8081） | `hnie` |
| 7 | `jxust` | 江西理工大学 | `JXUST/jxust.js`（星河欲转） | 强智 `jsxsd`（CAS） | `xjie` |
| 8 | `qau` | 青岛农业大学 | `QAU/qau_01.js`（ReGoMark） | 强智 `jsxsd` | `stcnchu`（校区提问照 `zcmu`） |
| 9 | `shzq` | 上海中侨职业技术大学 | `SHZQ/shzq.js`（Kredenk） | 正方 `jwglxt`（CAS） | `gsmc`（跨域登录照 `ksu`） |
| 10 | `fit` | 福州理工学院 | `FIT/fit.js`（Haooz） | 正方 `jwglxt` | 第五批的 `lcudcc`（上游 diff 仅 8 行：只换主机） |

> 原计划第 9 件是 `MKU` 闽南科技学院，派工前换成 `SHZQ`：MKU 是强智**新版 layui 界面**
> （`td[name=kbDataTd]` + `li` 卡片 + 行标签里的大节时间推作息），与我们任何一件强智模板的 DOM 都不同，
> 等于新写一套 extract；SHZQ 是正方近克隆，风险低一档。MKU 留给后续强智批次。

逐件要点（从上游正文读出来的，不是从注释）：

| key | 取数 | 编码 / 解析要点 | 作息 |
|---|---|---|---|
| `shiep` | `courseTableForStd.action`（**不带** `sf_request_type=ajax`）→ `dataQuery.action`（`tagId`+`semesterCalendar`+`value=<当前学期>`+`empty=false`）→ `courseTableForStd!courseTable.action`；上游对**不完整响应重试 4 次**、识别统一身份认证跳转 | `activity = new TaskActivity(...)` 切块；课名 `args[3]` 去掉末尾「(课程号)」；教师 `args[1]`（可能是 `join` 表达式，照批次四检查表 2 剥）；教室 `args[5]`；位图 `args[6]` 从下标 1 起（同族统一口径）；`unitCount` 缺省 13 | 13 节 |
| `nuaa` | 同 `tjau` 三连（带 `sf_request_type=ajax`）；默认学期取页面 `semesterCalendar_target` | **参数位不同**：课名 `args[3]`（不切括号）、教室 `args[6]`、位图 `args[7]`；**节次映射**：单元 1–4 → 第 1–4 节、5–6 丢弃（午间）、7–13 → 第 5–11 节（丢弃的要进 warnings）；`unitCount` 缺省 13 | 两套 11 节：教室含「天目湖」用天目湖表，否则明故宫/将军路表 |
| `shnu` | 课表页先试 `?sf_request_type=ajax` 再试不带；`dataQuery` 解析上游用 `Function(...)`（**等同 eval，必须换成 `tjau` 的字面量解析**）；课表请求体多 `startWeek=&project.id=1` | 按引号串取参数：课名 `quoted[1]`、教室 `quoted[3]`（或位图前一个）、位图 `quoted[4]`（或第一个 `^[01]{6,}$`）；教师取前一块的 `actTeachers` | 14 节，`unitCount` 缺省 14 |
| `nuist` | `jshkcb/dqxnxq.do` 当前学期 → `xskcb/cxxszhxqkb.do`（`XNXQDM`）→ `jshkcb/jc.do` 作息 → `jshkcb/cxjcs.do`（`XN`+`XQ`，给 `XQKSRQ` 开学日、`ZZC` 总周数） | `SKZC` 位串下标 i = 第 i+1 周；`extParams.code≠1` 时带教务原因报错；教室 `JASMC（校区）` | 教务 `jc.do`，取不到回落上游 12 节 |
| `xjzfu` | `homeapp/api/home/kb/xnxq.do`（`selected` 为当前学期）→ `student/getMyScheduleDetail.do`（`termCode`、`campusCode=1`、`type=term`，头 `fetch-api: true`）→ `getTermWeeks.do`（首周 `startDate` = 开学日，长度 = 总周数） | `week` 位串下标 i = 第 i+1 周；教师从 `weeksAndTeachers` 取带「[主讲]」的名字 | 11 节（新疆作息，10:00 起） |
| `yssdufe` | `jxzl/jxzl_query`（GET 学期下拉；POST `xnxq01id` 取周历：首行 `td[title]`「YYYY年MM月DD」= 开学日、首列最大数字 = 总周数）→ `xskb/xskb_list.do` | `font[title="周次(节次)"]` 的 `1-16(周)[01-02节]`；上游把「(」后面全丢了（含单双） | 11 节（第 11 节 20:10-21:50） |
| `jxust` | CAS `authserver.jxust.edu.cn` → 教务 `jw.jxust.edu.cn`；GET `xskb/xskb_list.do`（当前学期） | 上游按**行号**定大节（第 1–5 行 → 1-2 … 9-10 节），忽略格子里的 `[xx-yy节]` 与单双 —— 显式节次优先、行号兜底 | 10 节 |
| `qau` | GET `xskb/xskb_list.do`（当前学期） | 行表头「第1,2节」定节次；上游合并同课连续节次（跨度 ≤4） | **三个校区三张 11 节表**（青岛 / 平度 / 蓝谷）：照 `zcmu` 用 `__ncSelect` 问一次，桥不在或取消 → 青岛校区 + warnings |
| `shzq` | CAS `cas.shzq.edu.cn` → 教务 `jw.shzq.edu.cn/jwglxt`；请求体 `xnm&xqm&kzlx=ck&xsdm=`（**无** `kclbdm`） | 同正方 | 12 节 |
| `fit` | 主机 `oaa.fitedu.net`（http），其余与 `LCUDCC` 逐字相同 | 同 `lcudcc` | 同 `lcudcc`（上游无作息表） |

## 不进的（本两批排除，理由留档）

| 学校 | 理由 |
|---|---|
| `DLU` 大连大学 | WebVPN 入口但上游请求写死根相对路径，映射方式（子域 / 路径前缀）不明，`allowHosts` 只能写通配；另有周末独立作息（`isCustomTime`） |
| `CQUST` 重庆科技大学 | 上游一份脚本挂三个入口（WebVPN / IPv6 / 校园网），要拆三个适配器，单独做 |
| `QDU` 青岛大学 | 请求培养方案三个接口（`pyfa_query` 等），超出「只读课表」，需单独裁剪审计 |
| `SYIST` 沈阳科技学院 | 额外合并实验课表与调课记录，逻辑重，留给后续金智批 |
| `GDOU/gdouyj2.js` | 走校外微信评教入口，读 `localStorage` 与密码字段 |
| 裸 IP / 内网 / 深信服令牌 / aTrust：`HBGUHX` `SICNU` `HZU` `AHSZU` `WZZY` `DZU` `SCEMI` `XUST` `GDUST` `AEPU` `HQU` `YANGTZEU` `HIIT` `JNMC` `IMNC` | 同批次三、四的排除口径 |

## 专项检查表（第五批，在批次三/四检查表之上）

1. **上下文路径**：`/jwglxt` 部署与**根路径**部署（`yxhmc`/`cauc`/`njtech`/`jhzyedu`）不一样。根路径件的
   `jwBase` 不许拼 `/jwglxt`；仍要能跟随网关前缀（当前地址里 `/xtgl/`、`/kbcx/` 之前那一段）。
2. **菜单号与请求体**照本校上游逐字：`sdnu` 是 `N253508` + `kclxdm=`，其余 `N2151`。
3. **作息表逐节核对**：`SCHOOL_PERIOD_TIMES` 必须与本校上游 `TimeSlots` 逐节一致（这是最容易抄串的地方），
   `MAX_PERIOD` 注释里的节数跟着改；作息比模板短的学校（`jhzyedu` 8 节、`njtech` 10 节）要有
   「课表用到的节次超出作息表」的用例。
4. **跨域登录**：CAS 在别的主机（`ksu`）时，`allowHosts` 只写教务主机；登录页判断要认出 CAS 页面。
5. **模板残留**：全目录 grep 不得再出现「甘肃医学院」「GSMC」「gsmc」「jw.gsmc.edu.cn」。
6. 其余沿用批次三/四：周次四写法、括号序号、warnings 上限、`HH:mm` 合法性、学期名、`teacher` 留空、
   `AUDIT.md` 与代码一致、期望值独立推出 + 变异测试、ES5（注释也算）、控制字节。

## Files

**新增**（每个 agent 只碰自己那一个目录）：

```
jw-adapters/<key>/
  manifest.json  extract.js  parse.js  AUDIT.md
  fixtures/*.extracted.json  fixtures/*.expected.json   ← 模板用例全部改成本校形状 + 至少 1 对本校专属边界
```

**改动**：`jw-adapters/index.json` 加 20 条 —— **主 agent 统一加**；`version` 升到 `2026.10.08`（见「提交与发布」）。

**不改**：任何 Kotlin、任何既有适配器、规范文档。

## Task split

每批 10 件并行，每件一个 **haiku** agent（不用 worktree：各写各的目录，无共享文件）。
派工时给每个 agent 一张任务卡（学校 / 上游 / 模板 / 主机 / 上下文路径 / 菜单号 / 作息 / 特殊点）、
上游 diff（`.test/port-batch5/diffs/<key>.diff`，模板学校的上游 → 本校上游）与下面的硬约束。

自检工具（主 agent 预置，与 CI 门同口径）：`node .test/port-batch5/tools/check.js jw-adapters/<key>`
—— manifest、ES5（不剥注释）、控制字节 / BOM、`eval`/`new Function`、**Rhino 1.8.0 解释模式实跑全部 fixture**
（与 `JwLibraryHarnessTest` 同参数）、按 `JwPayloadCodec.validate` 校验期望载荷、`allowHosts` 覆盖检查。

### 给每个 agent 的硬约束

1. 只许新建、修改 `jw-adapters/<你的 key>/`，其它一律不许动（含 `index.json`、模板目录、`.test` 下的工具）。
2. 不许跑 `./gradlew`、不许跑进程级命令（`taskkill`/`pkill`）、不许跑改仓库状态的 git 命令、不许联网。
3. ES5 硬要求（注释里也不许出现箭头、反引号、`let `、`const `）；`Promise` 可以用。
4. 写文件用 Write/Edit 工具；工具入参里不写「反斜杠 + b」与「反斜杠 + u + 四位十六进制」（会落成真字节）。
5. 安全红线（手册 §5）命中即删并在 `AUDIT.md` 说明；删了就不能用的，停下来报告。
6. 拿不准一律进 `warnings`；`teacher`/`location` 拿不到留空；学期名用教务给的。
7. 期望值独立推出 + 变异测试（改坏哪一处 → 哪条用例变红），记录进 `AUDIT.md`。
8. `AUDIT.md` 的声明与代码一致；移植者签名写「0x7E7-2023（haiku 移植）」。

## 审计（每批）

按 stage 的 Small 第 3–7 步走，规模化成「一个 reviewer 管两件」：

1. **reviewer × 5（sonnet）**：每个拿两件、拿模板与上游对照，找安全与正确性 bug（输出 stage 的 Bugs 格式）；
2. **adversary（sonnet）**：每个非空列表一个，独立证伪（CONFIRMED / REFUTED / UNCLEAR）；
3. CONFIRMED 交回 **haiku** 按件修复，主 agent 复跑自检与 CI 门；REFUTED 丢弃；UNCLEAR 主 agent 能验就验、
   验不了且不影响安全的跳过并记录。不自动复审。

## 审计结果与修复（实测，2026-10-08）

两批各 5 个 sonnet reviewer（每个管两件，对照模板与上游）；有发现的 9 份清单各交一个 sonnet adversary
逐条独立复核（Rhino 实跑、桩 fetch 复现）。CONFIRMED 交回 haiku 按件修，主 agent 逐件复跑自检、读改动，
对不上的退回再改。

### 统一修订（同一模板里的共性问题，按同一口径改）

正方 12 件（第五批 10 件 + `shzq`、`fit`）共用 `gsmc` 的脚手架，模板级的问题统一修，不各写各的（来历见本节末「过程记录」）：

| 编号 | 问题 | 统一改法 |
|---|---|---|
| H1 | 校历的第 1 周离学期锚点差 45 天以上（判为别的学期的校历）时，只作废了开学日，周数仍被拿去定学期总周数 | 整份校历作废，总周数走内置 20 周（课表更晚就抬高），提醒里说明原因 |
| H2 | 「抬高总周数」的提醒在校历没给周数时写成「教务校历写的一学期是 0 周」 | 按来源分开写：校历给了周数一句、没给 / 被作废一句 |
| H3 | 课表用到的节次超出作息表时：模板无条件用空课默认 12 节补，会补出与本校最后一节重叠甚至倒挂的时间；有的件加了守卫干脆不补，而应用里没有时间的节次画不出来（课看不见） | 逐节补：空课默认节次表里同一节的开始不早于上一节下课就用它，否则按课间 5 分钟、每节 45 分钟顺推，越过 23:59 就停（课照常导入）；补了哪些、哪些补不了都写进提醒 |
| H4 | 提醒里提到应用页面的叫法不对（「学期设置」） | 统一写应用里的真名「学期管理」 |
| X1 | 周次括号：`(1-16周)` 被当成教学班序号整段删掉（整门课丢）；`1-16周(1,2)`、`1-16周(3组)` 删括号后数字粘成 161 / 163 → 读成 1-30 周 | 删「周」之前先按括号内容分三类：纯数字是序号（整组删）、含别的字是备注（整组删并提醒）、其余保留；落单的括号换成逗号，不许直接删 |
| X2 | 给学生看的提醒里有「接口」「载荷」「上游」「脚本」 | 按 [界面文案规范](../ux-writing.md) 第五节改成大白话，统一用「节次时间」 |
| X3 | `extract.js` 的报错里有「HTTP」「JSON」 | 「教务系统返回错误（代码 N）」「返回的内容格式不对」；第六批的 `shiep`、`nuaa`、`shnu`、`yssdufe`、`jxust`、`qau` 同样改了 |

X1 的变异要钉两处：把第 1 步挪回删「周」之后，`(1-16周)` 那条必须变红；把第 3 步改回「删括号」，**落单括号**那条
（`1-16周(3`）必须变红。`1-16周(1,2)` 在第 1 步就被整组当序号删掉，钉不住第 3 步（`yxhmc` 实测指出，口径已更正）。

### 逐件确认的问题

| key | 本件确认的问题与修法 |
|---|---|
| `haut` `sdnu` `cauc` `yxhmc` | 只有上面的统一修订 |
| `ksu` | AUDIT 说 CAS 主机「不放行」不对（应用白名单 = `loginUrl` 主机 + `allowHosts`），照实改写；H2 两句提醒没有用例钉住，补两对 |
| `gzu` | 注释里留着模板名，还把内置节次表说成「模板的 12 节表」（其实是空课默认节次表），照实改 |
| `njtech` | AUDIT 说「删掉了上游的登录页判断」不实（本校上游根本没有），照实改 |
| `jhzyedu` | 内置节次表被换成了本校 8 节表，与 H3 口径不符，恢复成空课默认 12 节；两处注释 / AUDIT 不实 |
| `cwxu` | X1、X2（reviewer 的 B2、B5） |
| `lcudcc` | 连续节次合并只跟「正在合并的那一条」去重，合并后才出现的重复行漏网，得到互相重叠的两块；改为合并前先去掉完全相同的行（上游同样有这个问题，AUDIT 记为对上游的修正） |
| `shzq` | AUDIT「读了什么、交出去什么」写窄了（漏了 `total` 等顶层键） |
| `fit` | 照 `lcudcc` 的成品重新同步（上游两份脚本只差主机） |
| `shiep` | 无确认问题；收尾时自查修了第二学期推算锚点、学期名补学年、提醒文案 |
| `nuaa` | 学期名：「第一学期」不补学年会让不同学年的同名学期互相覆盖、非 1–4 的序号拼出「第5学期」、原型属性名会拼出函数源码；追加修了第二学期推算锚点只认 `2`、提醒与报错里的「学期组件」 |
| `shnu` | 无确认问题；X3 与兜底提醒里的「学期组件」由主 agent 顺手改了 |
| `nuist` | 教师「姓名/工号」把工号当成第二位教师；「总周数小于最大周次」的提醒打印的是抬高后的值、自相矛盾；教务给的出错原因（如「课表尚未发布」）被吞 |
| `xjzfu` | 注释与 AUDIT 的覆盖说明不实（文字周次路径其实没被用例跑到），补一对位串缺失 / 全 0 的用例 |
| `yssdufe` | 周历日期不带「日」读不出（开学日退成推算）；3 节以上连排写法整门课被跳过；总周数误读上游、硬抬到 20；提醒里的开发者用语 |
| `jxust` | 一门课都没解析出来时返回空学期（导入后会把本地同名学期清空）→ 改为报错；块级提醒挤掉重要提醒 → 按类型计数；部分表头时静默丢课；节次数字没有上限（`[1-50000000节]` 在 Rhino 里循环 36 秒）；注释与 AUDIT 写的是别家上游的行为（修复代理跑了近一小时没收尾，由主 agent 接手完成，见过程记录） |
| `qau` | 列号对不上星期的课被静默丢掉 → 计数提醒；提醒里的开发者用语；格子里显式写的节次改为优先于行表头 |

**不成立（REFUTED，丢弃）**：`gzu` B1 与 `njtech` B2（作息、校历响应没有字段白名单——与模板及已发布件一致、没有个人信息证据）；
`lcudcc` B4（课表行整行交给 parse——既定设计，parse 只读固定字段）；`qau` B4（课程格子按子串匹配）；
`nuaa` B1（默认学期正则没有邻近限制）。

### 过程记录

- **统一口径先于逐件修**：H1–H4 是主 agent 集成时把 12 个正方件横向比对查出来的（同一处，各件处理不一致）；
  X1、X2 来自审计（reviewer 在 `cwxu` 上报出、adversary 用 Rhino 复现），确认是模板级缺陷后 12 件统一改；
  X3 是收尾时按同一份界面文案规范补的。口径写成一份带参考实现的说明（ES5、主 agent 先自测过）再派给各件，
  避免 12 个 haiku 各写各的。
- **修复轮中途会话中断一次**，在跑的代理全部丢失。改为把背景、工具、做法、硬约束写成一份自包含的公共说明，
  每件只附自己的问题清单重新派发；中断前做到一半的件，接手者先读完整个目录再动手。
- **代理的报告逐件复核，不直接采信**：主 agent 对每件复跑自检、读改动、对照报告。查出过几类不实：
  AUDIT 声称的行尾 / 覆盖范围与文件不符、声称做了的替换没做、把变异钉不住的用例写成钉住了
  （X1 第 3 步原说明本身有误，由 `yxhmc` 实测指出后更正）。不实的地方都退回改正。
- **`jxust` 由主 agent 接手**：它的修复代理跑了近一小时仍未收尾（用户要求接手）。停掉代理后核对已做的部分
  （B1–B4 的代码与用例都在、自检通过），补完剩下的：两个数的节次区间按起止读（`[01-03节]` = 第 1–3 节，
  代理写成了必须逐节相差 1，补用例钉住）、HTTP 报错、AUDIT 全文重写（B5 上游行为、B6 读取面）、
  在副本上补齐 9 条变异和 B1 的 Rhino 实跑记录。
- **`fit` 的明文 http**：同步代理把它列为「待确认」。主 agent 核对后按规范接受——规范允许 http 的 `loginUrl`，
  应用界面会标「不安全连接」，`network_security_config.xml` 放开了明文，已发布件里另有 13 件是 http 入口。
- **自检工具**每次运行在系统临时目录留一个 `ncchk-*` 目录不删，本批累计一千多个；已改为跑完即删，旧目录已清理。

## 主 agent 集成

1. `git status` 核对只多了本批的目录；
2. 加 `index.json` 条目（`version` 留到发版提交里再升）；
3. 全量自检 + `./gradlew :importer:test`（真 Rhino 门）；
4. 反向对照：故意改坏一个 fixture，门必须红，再改回（`diff` 核对还原）；
5. 全仓控制字节 / BOM 扫描；
6. 审计 → 修复 → 复跑门。

### 集成实测（2026-10-09）

- 20 件自检全部 PASS，合计 179 对 fixture；
- 20 个目录无 BOM、无控制字节、全是合法 UTF-8；manifest 登记的 fixture 与目录里的文件一一对应，没有多余文件；
- 提醒与报错的用语扫描（接口 / 载荷 / 上游 / 脚本 / 字段 / 课程块 / 组件 / HTTP / JSON / 学期设置 等）零命中；
  正方 12 件全目录（含 AUDIT）没有模板学校的残留；第六批的非正方件只在注释和 AUDIT 里有意引用模板件
  （如「照模板 tjau」），代码、manifest、给学生看的文字里没有；
- `./gradlew :importer:test`：206 个测试全过，其中 `JwLibraryHarnessTest` 5 个（72 件适配器的 fixture 全部用 Rhino 实跑）；
- 反向对照：把 `jxust` 一个期望值的 `endPeriod` 从 3 改成 4，门变红（1 failed）；还原后 sha256 一致，门复绿；
- `git status`：适配器库只多了 20 个目录和 `index.json` 的 20 条；
- 各代理留在系统临时目录的变异副本、测试页面等约一百个目录已清理。

## 提交与发布

- **适配器库**：两批各一个提交，外加一个发版提交（`index.json` 的 `version` 从 `2026.10.07` 升到 `2026.10.08`），
  推到 `main`。CI 的 harness 通过后，release job 发出 `v2026.10.08`（`nullclass-adapters.zip` + 签名 + `latest.json`），
  用户在应用里「检查更新」就能拿到这 20 件。
- 原计划是「不发版」（用户最初的要求）；收尾时用户改为「完成这些适配器之后提交发一个适配器版本」，所以本批发适配器库 release。
- **主仓库**：提交本文档 + 子模块指针，推到 `main`；**不发应用版本**——不升 versionCode、不打 `v*` tag、不写 CHANGELOG
  （纯适配器改动只发适配器库）。

## Out of scope

- 本两批排除的件（见上表）与其余 188 个；
- 已移植件的上游同步（`XJIE` / `XZHMU` / `ZZU`）；
- 真机验证（无账号，代价见测试方案 §3）；
- 老件里 `row.length - 7` 的改造。

## Done when

- `jw-adapters/` 下多出 20 个目录，每个含 `manifest.json`/`extract.js`/`parse.js`/`AUDIT.md`/fixtures（≥2 对）；
- `index.json` 有对应 20 条，`version` 升到 `2026.10.08`；
- 自检全部 PASS，`./gradlew :importer:test` 全绿，且反向对照验证过门是活的；
- 每件 `AUDIT.md` 签过手册 §5 八条 + 本批检查表，带变异测试记录；
- 两批各自的 reviewer + adversary 跑完，CONFIRMED 修掉；
- 两个仓库已推送；适配器库 CI 发出 release `v2026.10.08`；应用不发版（不升 versionCode、无 `v*` tag、无 CHANGELOG）。

## 后续（本批查出、留给后续批次）

- **已发布件里的同类缺陷**（按「纯适配器改动只发适配器库」单独出一个修复批，本批不动已发布件）：
  - `gsmc`（第五批的模板）有 X1、H1、H2 的全部问题；
  - `zjut`、`cqcivc` 有 X1 的后一半：删括号后数字粘连（`1-16周(3组)` → 1-163 → 读成 1-30 周）。
- **全库提醒文案统一**（已发布的 52 件）：39 件的提醒还写「作息表」（术语表用「节次时间」）；
  38 件的报错写「教务系统返回 HTTP N」；`jstc`、`neu`、`neu-grad`、`xatu`、`zzu` 的提醒写「学期设置」
  （应用里叫「学期管理」，用户找不到入口）；`tjau` 的提醒写「学期组件」。本批 20 件已按新口径写。
- **正方模板的提醒溢出少算一条**：提醒超过 20 条时，第 20 条被换成「另有 N 条核对提示超出上限未显示」，
  被换掉的那一条没算进 N。正方件的提醒都按类型汇总、互斥分支多，实际到不了 20 条；随 `gsmc` 修复批一起改。
- **`fit` 与 `lcudcc` 是孪生件**：上游两份脚本只差主机，本批 `fit` 直接由 `lcudcc` 的定稿复制、只改学校信息；
  以后修任何一边都要同步另一边（两边的 AUDIT 都写明了）。
- **上游同步**：`XJIE`（10-08 重构课程合并）、`XZHMU`（09-15 教师名提取）、`ZZU`（09-28 下午第 7、8 节作息）。
- `MKU` 闽南科技学院（强智新版 layui 界面）留给后续强智批次。
- **两种周次写法本批没有处理**（12 个正方件口径一致，各件 AUDIT 已写明，等见到真实数据再定）：
  - 未闭合的括号（`1-8周(10`）会把括号后的数字并进周次，不提醒；
  - 括号里周次标记和备注混写时整组按备注丢掉：`(1-16周 实验)` 这门课没有周次，进「数据不全」的提醒；
    `1-16周(双周 实验)` 读成第 1-16 周全周（丢了「双周」），只出一条「括号备注已忽略」的提醒。
