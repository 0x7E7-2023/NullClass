# 移植上游教务适配器（第三批 · 同族第二批）

日期：2026-09-13　BASE：`afb75d3`（0.9.2 之后）　模式：完整模式 / 并行（12 个 agent）

搬运方法见 [移植手册](../jw-adapter-porting.md)，验证标准见 [测试方案](../jw-adapter-testing.md)，
前两批的实测结论见 [批次一/二](2026-09-12-port-adapters.md)。

## Goal

内置适配器 23 → 35。这批的目标不是「再多几所学校」，而是**在三个最深的族里各刷一批**，
把批次一/二攒下的编码经验落成更多学校；同时把「无表头兜底按行宽猜列」这个已知结构缺陷，
在**新件里一律按网格列号实现**（老件里 `hblgxy`/`xjie`/`hniu`/`upc` 还留着旧写法，另行处理）。

## 选型依据

拿「与已移植的 20 个上游脚本的 token 频率余弦相似度」对剩余 192 个脚本排序，取 **0.82 以上的前三族**：

| # | key | 学校 | 上游 | 平台（按**实际接口路径**核实） | 相似度 | 模板 |
|---|---|---|---|---|---|---|
| 1 | `ccit` | 长春工程学院 | `CCIT/ccit.js` | 强智 `jsxsd`（**WebVPN**） | 0.952 | cqrk |
| 2 | `hnie` | 湖南工程学院 | `HNIE/hnie_01.js` | 强智 `jsxsd` | 0.937 | cqrk |
| 3 | `stcnchu` | 南昌航空大学科技学院 | `STCNCHU/stcnchu.js` | 强智 `jsxsd`（**非标准端口 :800**） | 0.892 | cqrk |
| 4 | `hnvcc` | 湖南商务职业技术学院 | `HNVCC/HNVCC_01.js` | 强智 `jsxsd` | 0.825 | upc |
| 5 | `xzhmu` | 徐州医科大学（研究生） | `XZHMU/xzhmu_01.js` | **脚本零请求**（只读页面上画好的 `<table>`，平台无法从接口路径证实；能确认的是真表格、走 DOM 解析、不需要 OCR） | 0.844 | masu |
| 6 | `taru` | 塔里木大学 | `TARU/taru_01.js` | 正方 `jwglxt`（CAS） | 0.841 | wenhua |
| 7 | `xawl` | 西安文理学院 | `XAWL/xawl_01.js` | 正方 `jwglxt` | 0.840 | cfec |
| 8 | `zcmu` | 浙江中医药大学 | `ZCMU/zcmu.js` | 正方 `jwglxt` | 0.825 | zjut |
| 9 | `gsmc` | 甘肃医学院 | `GSMC/gsmc_01.js` | 正方 `jwglxt` | 0.821 | cfec |
| 10 | `qdhhc` | 青岛黄海学院 | `QDHHC/qdhhc.js` | 正方 `jwglxt` | 0.827 | cfec |
| 11 | `whut` | 武汉理工大学 | `WHUT/whut_01.js` | 金智 `jwapp/sys` | 0.901 | niit |
| 12 | `neu` | 东北大学 | `NEU/neu.js` | 金智 `jwapp/sys` | 0.820 | niit |
| 13 | `cqcivc` | 重庆化工职业学院 | `CQCIVC/cqcivc.js` | 正方 `jwglxt`（CAS 登录，教务另域） | 0.817 | cfec |

> 第 13 个是**开工时临时多带的**：原表只列 12 个，实际并发时按同一口径顺位多开了 `cqcivc` 一个，
> 所以交付物是 13 个目录（这条是安全审查者先指出来的——计划表与交付不一致，属漏记）。

**相似度榜的坑**：`whut`/`neu` 最像的参照是强智件（`hhtc`/`upc`），因为它们共享同一套上游脚手架
（弹窗、桥回调、作息表）。**平台要按接口路径判**（已核：这两所请求 `/jwapp/sys/`），模板用金智的
`niit`/`dlutci`，**不要**照抄榜上那个参照。

## 不进的（本批排除，理由留档）

| 学校 | 理由 |
|---|---|
| `UJS` 江苏大学 | 29K、三个域（含 webvpn），超出单批风险预算 |
| `AEPU` 安徽电气工程职院 | 登录址是深信服式 `/http/<64位令牌>/` 路径，令牌硬编码在 URL 里 |
| `HBGUHX` 河北地质大学华信学院 | `loginUrl` 是裸 IP（`61.182.88.214:8090`） |
| `GDUST` 广东科技学院 | 登录址含内网地址 `172.16.254.1` + hex 路径 |
| `WBU` 武汉商学院 | 48K，且脚本里出现成绩相关字样（需单独审） |

## 批次三专项检查表（在批次二的 8 条之上）

前两批的实测教训：**同族 ≠ 同编码**。每个件都要拿**它自己的**编码造用例，不许照抄模板的 fixture 期望。

1. **周次编码四写法**：`1-16周(单)`（标记在「周」后）、`(单)1-16周`（标记在前）、`1-16(单周)`、
   `1-3,5-9周`（混排）——每种至少一条用例，证明标记不丢、不塌成「每周都上」、不静默。
2. **括号内纯数字不是周次**（`(1)`、`(1-2)` 是教学班序号）。
3. **无星期表头兜底**：**禁止 `row.length - 7` 这类按数组长度猜列**。`extract.js` 要交出**网格列号**
   （`col` / `span`，处理 `colspan`/`rowspan`），`parse.js` 按列号对星期 —— 参考 `hynu`/`gxdlxy`。
   没有表头且拿不到列号时：**进 `warnings`，不许猜**。
4. **时间合法性**：任何写进 `periodTimes` 的时间必须匹配 `HH:mm` 且 `00:00–23:59`。
   算出的 `24:00`/`85:45` 会让**整个载荷被拒**（不是跳过一节）。越界一律 clamp 进最后一刻 + warn。
5. **`warnings` 上限**：≤20 条、每条 ≤200 字（超了同样整包被拒）。长文本先截断再入数组。
6. **分页**：接口没回记录总数时不能取完第一页就停；取不全必须在 `warnings` 里说。
7. **学期名**：用教务给的（`XQMC`/`XNMC` 之类），拿不到用「学校名 + 学年学期」，**别用适配器名**。
8. **`teacher` 拿不到就留空**，不要写「未知」「暂无」。
9. **`allowHosts`**：WebVPN 学校（`ccit`）请求**当前页同源相对路径**，别把主机名写死；
   要用 OCR/提问桥的**必须写精确主机名**（`*.` 通配会被 `JwOriginRules` 跳过、拿不到桥）；
   非标准端口（`stcnchu:800`）在 `allowHosts` 里**只写主机名**，路径保持相对。
10. **每件都要做变异测试**：把 `parse.js` 里对应逻辑**故意改坏** → 该用例必须变红。
   只写「用例通过」不算数，要报出「改坏哪一处、哪条用例红了」。

## Files

**新增**（每个 agent 只碰自己这一个目录）：

```
jw-adapters/<key>/
  manifest.json  extract.js  parse.js  AUDIT.md
  fixtures/basic.extracted.json   fixtures/basic.expected.json
  fixtures/<边界>.extracted.json  fixtures/<边界>.expected.json   ← 至少一对，见检查表 1/3
```

**改动**：`jw-adapters/index.json` 加 12 条 —— **主 agent 统一加**。

**不改**：任何 Kotlin、任何既有适配器、`docs/jw-adapter-spec.md`。

## Task split（并行 12 个 agent）

条件同前两批：12 个任务、文件互不相交、无先后依赖。**不用 worktree**（各写各的目录，无共享文件）。

### 给每个 agent 的硬约束

1. **只允许新建、修改 `jw-adapters/<你的 key>/` 下的文件**，其它一律不许动（含 `index.json`、Kotlin）。
2. **不许跑 `./gradlew`**（12 个并发会抢锁），也**不许跑任何进程级命令**（`taskkill`/`pkill` 等会误杀别的 agent）。
   自验用 Node：读 `fixtures/*.extracted.json` 当 `__ncInput`，与 `*.expected.json` 逐字段比对。
3. **ES5 硬要求**：源码里不得出现 `=>`、反引号、`let `、`const `（CI 会拦，Rhino 不支持 async/await）。
   上游用 `npx @babel/cli --presets @babel/preset-env --targets "chrome>50"` 转，再手改桥调用。
   `Promise` 可以用。
4. **工具入参里不许写反斜杠转义序列**（反斜杠加 u0000 这类）—— 会被落成**真控制字符**，
   文件变二进制。写完自检 NUL 计数必须为 0。
5. **不许夹带**：写 DOM、埋点、请求第三方域、读成绩/学籍 —— 命中移植手册 §5 任一条就停下报告。
6. **拿不准一律进 `warnings`**，不许静默丢课、丢周次、猜开学日。
7. **期望值必须独立推出**（按规范算，不是把 `parse.js` 输出贴进去），并做**变异测试**。
8. **`AUDIT.md` 的声明必须与代码一致** —— 读了什么、请求了哪些域，不许写得比代码窄。

### 主 agent 集成

1. `git status` 核对：只多了 12 个目录，没有别的文件被改；
2. 加 `index.json` 12 条；
3. 跑 `./gradlew :importer:test`（真 Rhino 门，12 个一起）；
4. **反向对照**：故意改坏一个 fixture，确认门会红，再改回来（用 `diff` 核对还原正确）；
5. 全仓 NUL/BOM 扫描；
6. 按 Small 流程走审查：reviewer（安全 + 正确性）→ 有非空列表就上 adversary 证伪 → 只修 CONFIRMED。

## Out of scope

- 本批排除的 5 个（见上表）；
- 金智族除 `whut`/`neu` 外的其余件、`for-std` 族（`fjcpc`/`cqtbi`/`dlu`/`mysy`/`swu` 等留批次四）；
- 老件里 `row.length - 7` 的改造（结构改动，单独一批做）；
- 真机验证（无账号，代价见测试方案 §3）。

## Done when

- `jw-adapters/` 下多出 12 个目录，每个含 `manifest.json`/`extract.js`/`parse.js`/`AUDIT.md`/fixtures（≥2 对）；
- `index.json` 有对应 12 条；
- `./gradlew :importer:test` 全绿，且**反向对照验证过门是活的**；
- 每件的 `AUDIT.md` 签过移植手册 §5 八条 + 本批 10 条检查表；
- 12 个 `parse.js` 里没有 `=>`/反引号/`let `/`const `，没有 NUL 字节；
- 每件都有**变异测试记录**（改坏哪一处 → 哪条用例变红）；
- 审查（reviewer + adversary）跑完，CONFIRMED 的修掉。
