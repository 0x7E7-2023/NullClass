# 移植上游教务适配器（第四批 · 树维 EAMS 整族）

日期：2026-09-16　BASE：`924ce50`（0.9.5 之后）　模式：完整模式 / 并行（12 个 agent）

搬运方法见[移植手册](../jw-adapter-porting.md)，验证标准见[测试方案](../jw-adapter-testing.md)，
前三批的实测结论见[批次一/二](2026-09-12-port-adapters.md)与[批次三](2026-09-13-port-adapters-batch3.md)。

## Goal

内置适配器 36 → 48。前两批是「在已摸清的族里刷第二批」，这批换一个打法：
**拿下一个我们一件都没有的平台族**。

树维 EAMS（`/eams/`，上海树维信息科技有限公司，新开普子公司）在上游有 13 个脚本，
此前我们**零覆盖**。这个族的结构高度同构 —— 同一个接口三连、同一套 TaskActivity 内嵌格式、
同一套 `index = 星期 * unitCount + 节次` 的寻址，所以第一件的边际成本高、后面每一件都低。
代价是：**首件必须把这一族的编码摸清并写进检查表**，否则 12 件会一起错。

## 选型依据

对剩余 162 个未移植脚本按**脚本实际请求的接口路径**重新普查（不看上游注释、不看 adapters.yaml），
得到这个分布：

| 族 | 未移植 | 我们已有 | 本批 |
|---|---|---|---|
| 正方 `jwglxt` | 30 | 12 | — |
| 强智 `jsxsd` | 18 | 8 | — |
| **树维 `eams`** | **13** | **0** | **12 件（全部可取）** |
| 金智 `jwapp` | 14 | 3 | — |
| for-std | 5 | 3 | — |
| 其它（Struts2 / 青果 / 长尾自研） | 82 | 10 | — |

| # | key | 学校 | 上游 | 上游标注 | loginUrl | 大小 |
|---|---|---|---|---|---|---|
| 1 | `hpu` | 河南理工大学 | `HPU/hpu.js` | 树维 | `https://zhjw.hpu.edu.cn/eams/login.action` | 20K |
| 2 | `uestc` | 电子科技大学 | `UESTC/uestc.js` | EAMS | `https://eams.uestc.edu.cn/eams` | 19K |
| 3 | `zua` | 郑州航空工业管理学院 | `ZUA/zua.js` | 树维 | `http://jwglxt.zua.edu.cn/eams/loginExt.action` | 17K |
| 4 | `zzvcae` | 郑州汽车工程职业学院 | `ZZVCAE/zzvcae.js` | 树维 | `https://cas.zzvcae.edu.cn/cas/login` | 29K |
| 5 | `dlmu` | 大连海事大学 | `DLMU/dlmu_01.js` | 树维 | `http://jw.xpaas.dlmu.edu.cn/eams` | 17K |
| 6 | `neuq` | 东北大学秦皇岛分校 | `NEUQ/neuq.js` | 树维 | `https://jwxt.neuq.edu.cn` | 15K |
| 7 | `haust` | 河南科技大学 | `HAUST/haust.js` | 树维 | `https://vpn.haust.edu.cn` | 12K |
| 8 | `hfnu` | 合肥师范学院 | `HFNU/hfnu.js` | 树维 | `https://jw.hfnu.edu.cn/eams/loginExt.action` | 11K |
| 9 | `xatu` | 西安工业大学 | `XATU/myschool.js` | 树维 | `http://jwgl2018.xatu.edu.cn` | 11K |
| 10 | `tjau` | 天津农学院 | `TJAU/tjau.js` | 树维 | `http://jwxt.tjau.edu.cn/eams/homeExt.action` | 10K |
| 11 | `hunnu` | 湖南师范大学 | `HUNNU/hunnu.js` | 树维 | `https://jwglnew.hunnu.edu.cn/eams/courseTableForStd.action` | 9K |
| 12 | `cuit` | 成都信息工程大学 | `CUIT/cuit_bk_old.js` | 树维 | `https://jwc.cuit.edu.cn/` | 8K |

**这个族有三副面孔**（逐件读过上游正文才敢这么分，不是按注释分的）：

**① TaskActivity 走接口 —— 10 件**（`hpu` `uestc` `zua` `zzvcae` `dlmu` `neuq` `hfnu` `xatu` `tjau` `cuit`）：

1. `GET /eams/courseTableForStd.action?sf_request_type=ajax` → 拿学号 `ids` 与当前学期 `tagId`
2. `POST /eams/dataQuery.action`（`dataType=semesterCalendar`）→ 学期列表（含起止日期）
3. `POST /eams/courseTableForStd!courseTable.action`（带 `semester.id` 与 `ids`）→ 课表 HTML
4. 课表 HTML 里课程是**内嵌的 JS 块**：`activity = new TaskActivity(...)` + `index = 星期*unitCount+节次`

**② 取学期列表 + 读已渲染的 DOM 表格 —— `haust`**：只 `POST /eams/dataQuery.action` 拿学期，
课程从 `td[title]` / `td[id^='TD']` 读（和 `masu` 同一路数，但选择器不同），
周次是文本（`1-16周` / `单3-17`）不是位图。**它是本批唯一靠 DOM 读课程的接口件**，
`extract.js` 要交「格子 + 行列位置」，`parse.js` 不碰 TaskActivity。

**③ 零请求纯 DOM —— `hunnu`**：不请求任何接口，只读当前页面上已渲染的课表
（含 `iframe.eams-iframe` 里 `courseTableForStd` 的 `srcdoc` / `contentDocument`），
课程仍是内嵌的 TaskActivity JS 块 —— 所以 `parse.js` 与 ① 同族，`extract.js` 完全不同。
不需要桥也不需要白名单（`allowHosts: []`）。

## 平台名订正（顺带，必须在这批里做）

**`/eams/` 是上海树维（SupWisdom，新开普子公司），不是强智。** 证据：

- 实测 `https://www.cduestc.cn/eams/loginExt.action`、`http://211.83.88.108:8004/eams/loginExt.action`、
  `http://jxgltea.hfut.edu.cn:8780/eams5-teacher/login` 页脚均为「上海树维信息科技有限公司」；
- 强智的登录页写「湖南强智科技发展有限公司」、路径是 `/jsxsd/`，与 `/eams/` 不是一套；
- 树维官网（supwisdom.com）的产品线就是「综合教务系统 / 综合教学管理系统」。

而我们已合并的 `masu` / `xzhmu` 两个适配器的注释与 `AUDIT.md` 里写的是「强智 eams」，
批次一/三的文档也跟着这么写。**本批要订正这 6 个文件里的厂商名**（只改说明文字，
不改任何算法 —— 两个适配器都不依赖这个判断）。同族的 8 个上游脚本全都自称树维，
只有 `MASU` 被上游标成「青果/URP」，批次一又把「其实是强智」这个判断写进了文档，错在这一环。

## 不进的（本批排除，理由留档）

| 学校 | 上游 | 理由 |
|---|---|---|
| `HIIT` 河南信息科技学院 | `HIIT/hiit_01.js` | `loginUrl` 是裸 IP（`39.164.230.243:9527`）＋ aTrust 式 SSO 中转，同批次三排除 HBGUHX 的理由 |
| `YANGTZEU` 长江大学 | `YANGTZEU/yu.js` | 走 aTrust 零信任网关（`jwc3-…-s.atrust.yangtzeu.edu.cn`），请求域与登录域不同源、网关会重写全部请求，同批次三排除深信服令牌 URL 的理由 |

同族 13 件里取其 11，另加 `hunnu` 这个 DOM 变体，共 **12 件**。

## 每件的实测事实（从上游正文读出来的，不是从注释）

位图基准这一列是本批**最容易搞错**的地方：上游 12 个脚本有四种读法，注释和代码还打架。
「基准」= `weeks.push` 里的下标换算，写成 `bitmap[0]` 表示第几周：

| key | 位图基准（0 位是什么） | 位图循环原文 | `unitCount` 缺省 | 作息来源 |
|---|---|---|---|---|
| `hpu` | **0 位是占位符** → `bitmap[i]` = 第 i 周 | `if (text[i]==="1" && i>=1) weeks.push(i)` | 页面读，读不到=0（**不能照抄**，要自己定） | 表头 `第N节` + `(HH:mm-HH:mm)` 动态读 |
| `uestc` | **0 位是占位符**（先收后 `.filter(w=>w>0)`） | `weeks.push(i)` 后 filter | 12 | 内置 |
| `zua` | **1 位是第 1 周** → `bitmap[week]` = 第 week 周 | `for (week=1; week<len; week++) weeks.push(week)` | 14 | 内置（另有表头读法） |
| `zzvcae` | 同 `zua` | 同 `zua` | 14 | 内置（另有表头读法） |
| `dlmu` | **0 位是占位符** | `if (weekStr[i]==="1") weeks.push(i)` | 常量 `UNIT_COUNT = 10` | 内置 |
| `neuq` | **0 位是占位符** | `if (bitmap[i]==="1") weeks.push(i)` | 12 | 内置 |
| `haust` | 位图**不适用**（文本周次） | — | — | DOM 表格自带的节次表头 |
| `hfnu` | **0 位是占位符** | `for (j=0; j<len; j++) if (bitmap[j]==='1') weeks.push(j)` | 14 | 内置（**两套校区作息**） |
| `xatu` | 同 `hfnu` | 同 `hfnu` | 14 | 内置 |
| `tjau` | 同 `hfnu` | 同 `hfnu` | 14 | 内置 |
| `hunnu` | **0 位是占位符** | `for (i=1; i<len; i++) if (bitmap[i]==="1") weeks.push(i)` | 13（页面读） | 内置 13 节 |
| `cuit` | **上游写的是「0 位是第 1 周」**，与同族其余件不一致 → **本批采用同族统一口径**，见下 | `if (bitmap[i]==="1") weeks.push(i + 1)` | 12 | 内置 |

**位图口径的统一（本批的硬结论，不许各写各的）**：

12 件里有 5 件在代码或注释里明确写下同一个约定 —— `uestc`（「position 0 始终是 0，忽略」）、
`hpu`（`i >= 1`）、`hunnu`（从 `i = 1` 起）、`zua`、`zzvcae`（都从 `week = 1` 起）：

> **位图下标 `i` 就是第 `i` 周，下标 0 是占位符。**

所以本批统一按 **`week = index`，`index 0` 不产生周次**：
- `bitmap[i] === '1'` 且 `i >= 1` → 第 `i` 周；
- `bitmap[0] === '1'` → **不产出「第 0 周」**（载荷校验也会拒），
  改为写一条 `warnings`：「周次位图第 0 位为 1，与同族约定的占位符不符，已按忽略处理，
  请在导入预览里核对周次」；
- `cuit` 上游的 `i + 1` **不照抄** —— 它与同族 5 件的约定矛盾，且没有任何证据表明该校位图格式不同。
  按统一口径实现，并在 `AUDIT.md` 里写明「上游是 `i+1`，本适配器按同族统一口径实现，
  真机核对时若发现整体差一周，改这一处即可」。

**「本批最大坑」的实情比计划里写的更细**：`zua`/`zzvcae`/`cuit`/`hfnu`/`xatu`/`tjau`
这六件的循环都是 `for (i = 0; …)`，但**只有 `cuit` 做了 `+1`**。也就是说：
`hfnu`/`xatu`/`tjau` 的 `0 位` 是真的会被 `push` 进周次数组的 —— 位图第 0 位写作 `1` 时，
它们会算出「第 0 周」。同族六个脚本、同一段循环骨架、两种语义，
**只能按本校自己的实测串定**（而本批的统一口径见上表，五种读法收敛成一种）。
每件至少要有一条「第 0 位为 1」和一条「第 1 位为 1」的对照用例。



## 开工后查实的两条工具链陷阱（本批新发现，已广播给全部 agent）

### ① CI 的 ES5 检查**连注释一起查**

`JwLibraryHarnessTest` 的实现是：

```kotlin
val source = file.readText(Charsets.UTF_8)
listOf("=>", "`").forEach { token -> assertTrue(token !in source, "$name 含非 ES5 写法：$token") }
listOf(Regex("\\blet\\s"), Regex("\\bconst\\s")).forEach { pattern -> ... }
```

`token !in source` 查的是**整个文件字符串**，没有剥注释。所以

```js
// 上游把 teacherName 写成 `未知教师`
```

这一行会被 CI 判红 —— **反引号写在注释里也算违规**。本批 5 个 agent 都踩了
（`hfnu` / `neuq` / `tjau` / `zua`，外加我自己订正厂商名时写的注释）。

**要区分清楚**：`eval` / `new Function` 这条管的是**代码** —— 手册 §5-6 是安全条款，
不是语法条款。注释里写「上游用了 new Function 拼表达式，我们换成正则」是**该写**的
技术说明，不是违规。所以本地扫描脚本要**剥注释后再查动态求值**、**不剥注释查那四样
语法 token** —— 两者判定范围不同，混在一起会逼着作者把该说清楚的事从注释里删掉。

### ② 转义序列只有一部分会落成真字符，而且 `\b` 时好时坏

项目里原有「工具入参的反斜杠转义会落成真字节」的记录，本批把它**逐条实测**了一遍：

| 工具入参里写的 | 落盘的实际字节 |
|---|---|
| 反斜杠 + `d` / `s` / `w` / `n` / `t` / `/` / `"` / `x00` / `0` | **原样两字节** ✅ |
| 反斜杠 + `u0000` | **真的 NUL（0x00）** ❌ |
| 反斜杠 + `b` | **真的退格（0x08）** ❌ —— 而且两次尝试里一次原样、一次退格，**不确定本身就是危险** |

后果：`grep`/`diff` 把文件当二进制；更阴的是**落成退格的 0x08 只数 NUL 是查不出来的**。
所以自检要数**所有控制字节**（小于 0x20 且不是换行/回车/制表）：

```js
var b = require('fs').readFileSync(f);
var bad = [];
for (var i = 0; i < b.length; i++) { var c = b[i]; if (c < 0x20 && c !== 10 && c !== 13 && c !== 9) bad.push([i, c]); }
console.log('控制字节:', bad.length);
```

**规避办法**：写正则时**不要用词边界转义**，改用 `[^A-Za-z0-9_]`；要落 NUL 分隔符时用
`String.fromCharCode(0)` / `String.fromCharCode(92)` 运行时拼，别在源码里写转义序列。
本批 `cuit` 那个 agent 自己做了 5 轮 probe 得出同一结论，并把词边界全部改写掉了。

## 批次四专项检查表

前两批的实测教训是「同族 ≠ 同编码」，这一族把这句话演到了极致：**12 个脚本对同一个位图
有五种互不相同的读法**（见上表）。逐条过：

1. **周次位图的下标基准**（本批最大的坑）—— 见上面「每件的实测事实」表与**统一口径**。
   上游五种写法互相矛盾、注释与代码还打架，**本批收敛成一种**（`week = index`，`index 0` 不产出周次
   并进 `warnings`）。每件的用例里必须有一组对照：一条「第 0 位为 1」、一条「第 1 位为 1」，
   证明基准选对了、且静默多出「第 0 周」这类错会被这条用例挡住。
2. **`TaskActivity` 的参数位**：`args[3]`=课程名、`args[5]`=教室、`args[6]`=周次位图
   （12 件一致）；但 `args[1]`（教师）有两副面孔 —— 有的是字面量，有的是
   `xxx.join(",")` **表达式**，直接取值会把 `join(...)` 当教师名，必须先剥。
   `args[2]` 只有 CUIT 用。
3. **`index = 星期 * unitCount + 节次` 的两种写法**：`index = 5*unitCount+2`（带变量）
   与 `index = 62`（已算好）。**禁止 `eval` / `new Function` 求值** ——
   `DLMU` 上游正是用 `new Function("return " + expr)`，表达式来自网络取回的 HTML，
   命中手册 §5 第 6 条；`HAUST` 用 `eval("(" + text + ")")` 解析响应。两处**都必须**
   换成正则解析（同族其余 10 件本来就这么做）。
4. **`unitCount` 要真的读**：上游有 12/13/14 三种缺省值。页面里读不到时必须
   **同时**进 `warnings`（说明节次数是猜的），不许静默用一个数字。
5. **作息时间从哪来**：`ZUA`/`ZZVCAE` 从课表表头的 `(08:00-08:45)` 读；`HFNU`/`XATU`/`TJAU`/
   `UESTC`/`HUNNU` 是脚本内置硬编码。内置的要在 `AUDIT.md` 写明来源与依据，
   且所有时间**必须过 `HH:mm` 与 `00:00–23:59` 校验**（越界会让整包被拒）。
6. **开学日**：`semesterCalendar` 给学期起止日期，用它回退到每周起始日；
   拿不到就按最近的周一推算并**必须在 `warnings` 里说**。
   `ZUA`/`ZZVCAE` 上游用 `showPrompt` 问用户 —— 移植时改成推算 + 提示。
7. **周次上限**：位图常见 50–54 位，算出来可能到第 54 周，**超过载荷上限 30**。
   越界一律 clamp 到 30 并 warn（同批次三的 `MAX_TOTAL_WEEKS` 口径）。
8. **`teacher` / `location` 拿不到就留空**。上游 `HPU`/`XATU`/`TJAU`/`HFNU` 写
   `"未知教师"` / `"未知地点"`，会被当成真姓名、真地点显示，必须改空。
9. **学期名**：用 `semesterCalendar` 给的，拿不到用「学校名 + 学年学期」，别用适配器名。
10. **`allowHosts`**：这一族多数请求**当前页同源相对路径**，写 `allowHosts: []`；
    有绝对 URL 的（`neuq`/`hfnu`/`xatu`/`zua`）按脚本实际请求的主机写精确域名，
    **不许通配**。
11. **`warnings` 上限**：≤20 条、每条 ≤200 字（超了整包被拒）。
12. **每件都要做变异测试**：把 `parse.js` 里对应逻辑故意改坏 → 该用例必须变红。
    只写「用例通过」不算数，要报出「改坏哪一处、哪条用例红了」。

## Files

**新增**（每个 agent 只碰自己这一个目录）：

```
jw-adapters/<key>/
  manifest.json  extract.js  parse.js  AUDIT.md
  fixtures/basic.extracted.json      fixtures/basic.expected.json
  fixtures/<边界>.extracted.json      fixtures/<边界>.expected.json   ← 至少一对，见检查表 1/3
```

**改动**：

- `jw-adapters/index.json` 加 12 条 —— **主 agent 统一加**；
- 平台名订正：`jw-adapters/masu/{extract.js,parse.js,AUDIT.md}`、
  `jw-adapters/xzhmu/{extract.js,parse.js,AUDIT.md}`，
  以及 `docs/impl/2026-09-12-port-adapters.md`、`docs/impl/2026-09-13-port-adapters-batch3.md`
  里把 `/eams/` 说成强智的地方 —— **主 agent 统一改**。

**不改**：任何 Kotlin、任何既有适配器的算法。

## Task split（并行 12 个 agent）

条件同前两批：12 个任务、文件互不相交、无先后依赖。**不用 worktree**（各写各的目录，无共享文件）。

### 给每个 agent 的硬约束

1. **只允许新建、修改 `jw-adapters/<你的 key>/` 下的文件**，其它一律不许动（含 `index.json`、Kotlin、
   其它适配器目录）。
2. **不许跑 `./gradlew`**（12 个并发会抢锁），也**不许跑任何进程级命令**（`taskkill`/`pkill` 等会误杀
   别的 agent）。自验用 Node：读 `fixtures/*.extracted.json` 当 `__ncInput`，与 `*.expected.json`
   逐字段比对。
3. **ES5 硬要求**：源码里不得出现箭头函数、反引号、`let `、`const `（CI 会拦，Rhino 不支持
   `async/await`）。上游用 `npx @babel/cli --presets @babel/preset-env --targets "chrome>50"` 转，
   再手改桥调用。`Promise` 可以用。
4. **工具入参里不许写反斜杠转义序列**（反斜杠加 u0000 这类）—— 会被落成**真控制字符**，
   文件变二进制。写完自检 NUL 计数必须为 0。
5. **不许夹带**：写 DOM、埋点、请求第三方域、读成绩/学籍 —— 命中移植手册 §5 任一条就停下报告。
   **特别地：不许出现 `eval` / `new Function`**（本批有两个上游件带这个，见检查表 3）。
6. **拿不准一律进 `warnings`**，不许静默丢课、丢周次、猜开学日、猜节次数。
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

- 本批排除的 2 个（`HIIT` / `YANGTZEU`，见上表）；
- 同族的 for-std 5 件（`NWPU`/`SICNU`/`CUPK`/`XAUAT`/`CUP`，留批次五；
  其中 `SICNU` 的 `loginUrl` 是裸 IP、`NWPU` 有学生画像接口，都要单独审）；
- 金智 `jwapp` 的其余 11 件、正方 `jwglxt` 的其余 30 件、强智 `jsxsd` 的其余 18 件；
- 老件里 `row.length - 7` 的改造（结构改动，单独一批做）；
- 真机验证（无账号，代价见测试方案 §3）。

## Done when

- `jw-adapters/` 下多出 12 个目录，每个含 `manifest.json`/`extract.js`/`parse.js`/`AUDIT.md`/fixtures（≥2 对）；
- `index.json` 有对应 12 条；
- 平台名订正的 6 个适配器文件 + 2 份批次文档改完；
- `./gradlew :importer:test` 全绿，且**反向对照验证过门是活的**；
- 每件的 `AUDIT.md` 签过移植手册 §5 八条 + 本批 12 条检查表；
- 12 个 `parse.js` 里没有箭头函数、反引号、`let `、`const `、`eval`、`new Function`，没有 NUL 字节；
- 每件都有**变异测试记录**（改坏哪一处 → 哪条用例变红）；
- 审查（reviewer + adversary）跑完，CONFIRMED 的修掉。
