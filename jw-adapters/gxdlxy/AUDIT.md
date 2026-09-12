# 安全审计：`gxdlxy`（广西电力职业技术学院）

- 上游：`shiguang_warehouse` 的 `resources/GXDLXY/gxdlxy_01.js`（MIT，作者 **星河欲转**，`adapters.yaml`
  里的 `maintainer` 也是他）。快照 commit `e62554a4034386b893bcd6813c7b2b64f8c730a3`（2026-09-12），
  该文件的 blob `0c0da65675213b3fb1e53f1a0196f12991cfcc04`。
  上游 `adapters.yaml` 自己写着「**非本校开发者适配**」——这一点对本件的判断很重要，见文末「已知不确定」。
- 移植者：NullClass（0x7E7-2023）　日期：**2026-09-12**
- 平台：**强智科技「高校综合管理教务系统」学生端 `/jsxsd/`**（同 `hynu`）。
  依据：上游请求 `/jsxsd/xskb/xskb_list.do`（强智学生端课表页；正方新版是 `/jwglxt/`，
  课表接口 `xskbcx_cxXsKb.html`），请求体 `cj0701id=&zc=&demo=&xnxq01id=` 与 hynu 上游**逐字相同**，
  页面结构也是 `div.kbcontent` + `font[title=教师|老师|周次(节次)|教室]`。上游自称「强智教务」是对的。

**结论：通过（有两条需要写清楚的例外，都在下面标注）。** 手册 §5 的 8 条逐条过完，
没有命中红线；适配器只做「取课表」一件事。

## 请求域（全部）

脚本**不含任何绝对地址**（源码里的 `https://…` 只出现在注释与本说明里），一律请求
**当前页面同源**的课表接口：

| 请求 | 地址 | 说明 |
|---|---|---|
| `GET` | 同源 `<当前路径里 '/jsxsd/' 之前的前缀>` + `/jsxsd/xskb/xskb_list.do` | 课表页（教务默认的当前学期）。只在当前页面没有课表时才发 |
| `POST` | 同上 | 取指定学年学期的课表，body 与上游一字不差：`cj0701id=&zc=&demo=&xnxq01id=<学期>` |

`kbPath()` 从 `window.location.pathname` 里取 `/jsxsd/` 之前的那一段当前缀：直连时是空串，
WebVPN 把地址重写成 `/https-443/<站点哈希>/jsxsd/…` 时那一段会被保留（同平台的 BTBU 适配器
就是这么做的，它是对着真实 WebVPN 抓包校准过的）。这两个 `fetch` 都带 `credentials: 'include'`，
即用用户自己已登录的会话取他自己的课表。

`allowHosts` = `["jw.vpn.gxdlxy.com"]`，只有一条，**不是通配**。

### 例外 ①：为什么白名单里是这个主机，而不是 `loginUrl` 的主机

`loginUrl` 是上游 `adapters.yaml` 的入口 `https://jw.gxdlxy.com/jsxsd/framework/xsMain.jsp`
（`gxdlxy.com` 是学校自己的域名：学校官网 `www.gxdlxy.com`、英文站 `ie.gxdlxy.com` 都在这个域下，
所以 `.com` 后缀不是「第三方域」的信号）。但上游脚本请求的是**另一个主机**
`https://jw.vpn.gxdlxy.com/jsxsd/xskb/xskb_list.do` —— 那是学校 WebVPN 网关下的教务站点子域。
2026-09-12 从本机探测（只发了一次 GET，没有登录、没有带任何凭据）：

```
$ curl -m 15 -o /dev/null -w "%{http_code}" https://jw.gxdlxy.com/jsxsd/framework/xsMain.jsp
000                                     # 连接超时（DNS 解析到 111.59.245.69，80/443 都不应答）
$ curl -m 12 -D - -o /dev/null https://jw.vpn.gxdlxy.com/jsxsd/framework/xsMain.jsp
HTTP/1.1 302 Found
Location: https://ids.vpn.gxdlxy.com/authserver/login?service=https%3A%2F%2Fvpn.gxdlxy.com%2Fusers%2Fauth%2Fcas%2Fcallback…
```

即：校外能到的是 **WebVPN 门户**（`vpn.gxdlxy.com` 下的站点子域，CAS 统一身份认证），
直连主机从校外看不出可达性。所以脚本改成同源相对路径后，**页面开在哪个主机就请求哪个主机**；
用户如果是在网关主机上打开教务（校外用户的常见情况），那个主机必须在白名单里，否则会被
应用的闸门拦掉（同源也拦：白名单是 `loginUrl` 主机 + `allowHosts`）。这就是这一条白名单的**唯一**用途——
脚本自己从不请求它。

### 例外 ②：上游的跨主机请求其实是跑不通的写法

上游把 `https://jw.vpn.gxdlxy.com` 写死在脚本里。用户从它自己 adapters.yaml 的入口
（`jw.gxdlxy.com`）打开页面时，这是**跨源**请求：带 Cookie 的跨源 `fetch` 需要服务端给出
CORS 响应头，教务系统不会给，浏览器会直接拦掉。也就是说上游那种写法只有「用户恰好把页面开在
网关主机上」时才成立。移植后不再有这个问题，也不需要把网关主机写进代码。

```bash
$ grep -oE "https?://[A-Za-z0-9.-]+" gxdlxy_01.js | sort -u
https://jw.vpn.gxdlxy.com
$ grep -nE "fetch\(|XMLHttpRequest|sendBeacon|new WebSocket|\.src\s*=|localStorage|eval\(|new Function|password|pwd" gxdlxy_01.js
194:    const response = await fetch("https://jw.vpn.gxdlxy.com/jsxsd/xskb/xskb_list.do", {
```

即上游全脚本只有一个绝对 URL、一处 `fetch`，没有存储、没有 `eval`、没有凭据相关标识符。
它写过一处页面全局（`window.validateYearInput`，给它的弹窗做输入校验），移植后**连同弹窗一起删掉**。

## 逐条（移植手册 §5 的安全审计清单）

1. **不碰凭据** — 不读 `password`/`pwd`/登录表单值/`localStorage`/Cookie 字符串，不做任何形式的
   凭据采集。上游让用户手输的是**起始学年**（不是账号密码），移植后连这也不问了（见「移植改动」）。
2. **不外发** — 两处请求都是本校教务的课表页，且是同源。没有 `sendBeacon`/`WebSocket`/`EventSource`/
   `new Image().src`，没有重定向后的二次外发，`parse.js` 完全不联网（纯转换，CI 里在 Rhino 跑）。
3. **请求域可控** — 见上面的表。脚本无绝对地址；白名单只有一条精确主机，没有通配。
4. **只读课表** — 只请求课表页。不碰成绩、学籍、考勤、缴费、个人信息。页面读取面逐条列在下一节，
   **本节之外没有其它读取**（`extract.js` 里对页面/文档的每一次访问都在那张表里）。
5. **不埋点** — 无统计、无上报、无遥测，也没有任何「回传失败原因」的请求；出错只是 `throw`，
   由应用自己显示，不发给任何服务器。
6. **不 eval 远程代码** — 无 `eval` / `new Function`；取回的 HTML 只交给 `DOMParser`（惰性文档，
   不执行脚本、不加载子资源——`extract.js` 里根本没有 `<script>` 相关操作）。
7. **不写页面** — `extract.js` 对页面只读：不写 DOM、不改表单、不触发提交、不点元素、不挂全局函数。
   上游那两个「写」的东西（欢迎弹窗 + `window.validateYearInput`）都删掉了；提示交给应用自己的流程。
8. **不依赖用户输入之外的秘密** — 没有硬编码密钥、令牌、他人学号；`xnxq01id` 来自页面上的
   「学年学期」下拉框（用户自己选中的那一项）。

## 读取面（`extract.js` 到底读了页面上的什么）

本节与代码逐条对齐（给函数名，不给行号 —— 行号会漂）。只有这五处：

| # | 读什么 | 代码位置 | 取出的值 | 去处 |
|---|---|---|---|---|
| 1 | `window.location.pathname` | `kbPath()` | 只做一次「`/jsxsd/` 在第几位」的查找，取它前面的前缀拼请求路径 | 不进载荷 |
| 2 | 课表表格：`#kbtable` / `#timetable`，都没有时含 `.kbcontent` 的那张表 | `findTable()` → `rowsOfTable()` → `cellsOfRow()` → `cellOf()`/`partsOf()` | 每格的文字（`textContent`）+ 该格 `div.kbcontent` 的**原始 HTML**（优先带 `display:none` 的那份）+ 列号/跨列数 | 载荷 `rows[]`，由 `parse.js` 解释 |
| 3 | 「学年学期」下拉框：`id` 或 `name` 含 `xnxq` 的 `<select>` | `readTerm()` 主路径 | 选中项的 `value`（`2026-2027-1`）与选项文字（学期名） | 载荷 `term` |
| 4 | 兜底①：`document.title`；兜底②：页面上**每个** `<select>` 的 option 文字；兜底③：`id` 或 `class` 含 `xnxq` 的元素的**短**文本（≤120 字） | `termTextHints()` | 只在这三处取文字，整串只做一次正则匹配 `(20\d{2})\s*-\s*(20\d{2})\s*学年\s*第?\s*([一二三123])\s*学期` | 匹配到的那一小段当学期名；没匹配到的不留引用、不记日志 |
| 5 | 每个 `div.kbcontent` 的 `style` 属性 | `partsOf()` | 只判断里面有没有 `none`（决定取隐藏那份还是第一份） | 不进载荷 |

第 3 项只在**主路径**（下拉框）走不通时才走第 4 项；第 4 项**不读 `document.body`**，
第 4 项的第③条还带长度上限（避免命中一个包着整页的容器）。这三处取到的文字
**不外发、不进载荷**（进载荷的只有匹配出来的学期名），也不写页面。

## 数据完整性（不丢课、不静默降级）

审计审的是安全，但这一节列的是**本批次审查挖出来的坑**在本件里的落点（正确性）：

| 坑 | 本件怎么处理 | 用例 |
|---|---|---|
| 单/双写在「周」字后面（`1-16周(双)`） | 按分段认单双并切 `weekType`，不回退成「每周都上」 | `weeks`（M1 变红） |
| 括号里的纯数字序号不是周次 | 括号内容不参与周次解析（`5-8周(2)` 的 `(2)` 被忽略） | `weeks`（M2 变红） |
| 读不出节次不许静默丢课 | 三个来源都试；都不行才跳过，并计数进 `warnings`（上游是静默丢） | `cells`（M4/M9 变红） |
| `periodTimes` 要覆盖每一节 | 上游表只到第 10 节；第 11、12 节按空课默认模板补出并进 `warnings`，再晚的补不出来也进 `warnings` | `merge`（用了 11-12 节） |
| 分页 | **本平台没有分页**：`/jsxsd/xskb/xskb_list.do` 一次返回整学期的课表 HTML，请求体里也没有分页参数（上游的 `cj0701id=&zc=&demo=&xnxq01id=` 里 `zc`（周次）留空 = 不按周过滤，所以拿到的是全学期）。同平台的 BTBU 适配器对真实 dump 的描述也是「API 返回整页 HTML」。因此不存在「只取第一页」的问题；反过来，取不到时**不能静默给空表**：`extract.js` 在「页面、GET、POST 三条路都没读到带课内容的格子」时直接报错，`parse.js` 在一门课都没解析出来时也报错 | `noheader`（M10 变红：兜底失效时整表空掉会报错） |
| 总周数被更晚的周次抬高 | 抬到课表里最大的周次并进 `warnings`（`22 周` 那条） | `weeks`（M7 变红） |

## 与 hynu 的同平台对照（相似度 0.739，差在哪）

两个脚本都是「强智学生端课表页 + `div.kbcontent` + `font[title=…]`」，但**按层看，
能复用的和不能复用的完全分开**（这正是测试方案 §3.2 说的那件事）：

| 层 | 能不能复用 | 本件的事实 |
|---|---|---|
| 接口信封（`/jsxsd/xskb/xskb_list.do`、POST body `cj0701id=&zc=&demo=&xnxq01id=`） | **能** | 与 hynu 上游逐字相同 |
| 行字段名（`教师/老师`、`周次(节次)`、`教室` 三个 font title） | **半能** | 教师那条 font 的 title 两校**不一样**：hynu 上游取 `font[title="教师"]`，gxdlxy 上游取 `font[title="老师"]`。本件两个都认（`TEACHER_TITLE`） |
| 取数路径（接口 + 参数 + 学期从哪来） | **不能** | 两边上游都弹窗问学年/学期；本件与 hynu 都改成读页面下拉框 —— 这一层是**移植件的共同改法**，不是照抄 |
| **节次编码（节次写在哪）** | **不能** | **最大的一处差异**，见下 |
| 周次编码 | **不能** | hynu 上游 `split('(')[0]`；gxdlxy 上游 `replace(/周\|\(.*?\)/g,'')` —— 写法不同、错得一样（单双周被洗掉） |
| 星期对齐（首列是节次列） | **不能** | 两边上游都按「第几个格子就是星期几」，本件都改成按表头列号 |
| 连堂合并 | **不能** | gxdlxy 上游多一个 `mergeAndDistinctCourses()`（1-2 节 + 3-4 节 → 1-4 节），hynu 上游没有 |
| 课表容器 id | **不能** | 上游 hynu 用 `#timetable`，上游 gxdlxy 用 `#kbtable`（本件两个都找，都找不到再按 `.kbcontent` 反查） |
| 作息表 | **不能** | hynu 上游 12 小节（08:30–23:00），gxdlxy 上游 10 小节（08:30–21:10），时间也不同 |
| 学期总周数 | **能** | 两边上游都写死 `semesterTotalWeeks: 20` + `firstDayOfWeek: 1`（本件照抄为 `totalWeeks` 默认值 + 周一） |

### 差异点逐条

1. **节次从哪个字段读（最关键的一条）**
   - hynu 上游：`font[title="周次(节次)"]` 的文本里匹配 `\[(\d+)(?:-(\d+))?节\]`，即 `[01-02节]`。
   - gxdlxy 上游：`font[title="教室"]` 的文本里匹配 `\[(\d+)-(\d+)\]节`（`[01-02]节`，且按行尾锚定），
     匹配到再从教室名里把这段删掉。
   - 两处写法不同（`[01-02节]` vs `[01-02]节`）、位置不同（周次字段 vs 教室字段）。
     同平台其它学校的真实页面（BTBU 的 dump、HNUST/HNIU 的脚本）用的都是 **`[01-02节]` 写在
     「周次(节次)」里**这一种；上游 gxdlxy 是 28 个用 `kbcontent` 的上游脚本里**唯一**一个从教室字段读节次的。
   - 本件**两个位置都认**（先「周次(节次)」，再教室），再用**所在行的节次标签**兜底，
     三个都读不出来才跳过并计数进 `warnings`。理由：上游判据是 `if (name && startSection > 0)`，
     一旦它假设的位置不对，**整张课表会一门课都不剩而且不报错**；而它自己写着「非本校开发者适配」，
     这个假设没有被真实页面证过。
2. **连堂合并**：上游 `mergeAndDistinctCourses()` 把「同一天 + 同一周次 + 同一教室、节次相邻」的两条
   合成一条（并去掉完全重复的）。hynu 上游没有这一步。本件按上游原意保留
   （实现改成按「天 + 周次 + 教室」分组，结果等价、不依赖课程名排序）。
3. **单双周**：上游 `parseWeeks()` 的 `replace(/周|\(.*?\)/g,'')` 会把 `(单)`/`(双)` 一起洗掉，
   `1-16周(双)` 变成「每周都上」且不报警。本件按规范切极大段 + `weekType`，
   并且**括号里的纯数字不参与周次**（用例 `weeks` 里的 `5-8周(2)`）。
4. **星期对齐**：上游 `cells.forEach((cell, dayIndex) => …)` 把格子在行里的下标当星期几；
   课表首列是节次列时（强智的常见形态）整表错一天。本件按表头星期标签对齐**列号**，
   并把 `colspan` 算进去（`extract.js` 交 `col`/`span` 给 `parse.js`）。
5. **DOM 用法**：上游把取回的 HTML 用 `DOMParser` 解析成游离文档，却用**主文档**的
   `document.createElement` 去切块（游离文档里的节点被塞进主文档元素里），而且判空用了
   `innerText`（游离文档里布局信息不可靠）。本件 `extract.js` 只交原始 HTML，
   `parse.js` 纯字符串 + 正则，不碰 DOM。
6. **打开方式**：见上面「例外 ①/②」（单主机 vs 学校域名 + WebVPN 网关两个主机）。

**结论**：第一层的接口信封与行字段名可以复用（本件也确实沿用了同一套取值方式），
**取数路径、节次编码、周次编码、分页/对齐、合并规则这五层不能复用** ——
所以本件是按 gxdlxy 自己的编码单独造的用例（5 个），不是照抄 hynu 的 `parse.js`。

## 移植改动（相对上游 gxdlxy_01.js 的全部行为差异）

- 删：欢迎弹窗、一串 toast、`window.validateYearInput`、`getSemesterParamsFromUser()` 的
  「手输学年 + 选第几学期」；学期改成读页面「学年学期」下拉框**选中**的那一项
  （用户在页面上切学期，适配器就跟着他走）。
- 删：上游写死的跨主机地址，改成同源相对路径 + 当前路径前缀（见「例外 ①/②」）。
- 改：周次解析（单双周、括号序号）、节次解析（三处来源 + 兜底计数）、星期对齐（表头列号 + colspan）、
  连堂合并（保留，实现改为分组）。
- 留：上游写死的作息表（10 小节）与总周数 20 周 —— 它们是**载荷字段**（`periodTimes`/`totalWeeks`），
  并在 `warnings` 里说明这是适配器内置值、不是教务给的。课表里出现第 11 节及以后的课时，
  按**空课自己的默认作息模板**补出第 11、12 节（再晚的补不出来），同样进 `warnings`。
- 开学日期：教务不给（上游也没有 `config.semesterStartDate`），按 §4.2 的「最近的周一」推算，
  并在 `warnings` 里如实说明。
- 教师/教室：上游写死 `"未知教师"`/`"未知地点"`，改为**留空**（`null`）——手册 §4.7：
  空着比写「未知」好，后者会被当成真名显示。

## fixture 与变异测试（测试方案 §3.1）

5 对 fixture 都是**合成数据**（文件头的 `_note` 写了来源与用例名），只保证「同样的输入得到同样的输出」，
**不保证**真实页面上的解析是对的；谁拿到真实 dump，替换它们是最高优先级的贡献。

期望值是**独立推出来**的（先按规范算该是什么，再跑），不是把 `parse.js` 的输出贴进去；
并且做了变异测试 —— 把 `parse.js` 的对应逻辑故意改坏，看用例是否真的变红：

| 变异 | basic | weeks | cells | merge | noheader |
|---|---|---|---|---|---|
| 原始 | MATCH | MATCH | MATCH | MATCH | MATCH |
| M1 单双周不生效 | MATCH | **DIFF** | MATCH | MATCH | MATCH |
| M2 把括号里的纯数字当周次 | MATCH | **DIFF** | MATCH | MATCH | MATCH |
| M3 节次只认教室字段（上游的读法） | **DIFF** | **DIFF** | **DIFF** | **DIFF** | **DIFF** |
| M4 去掉「行节次标签」兜底 | MATCH | MATCH | **DIFF** | MATCH | MATCH |
| M5 不做连堂合并 | MATCH | MATCH | MATCH | **DIFF** | MATCH |
| M6 连堂合并不去重 | MATCH | MATCH | MATCH | **DIFF** | MATCH |
| M7 总周数不抬高也不报警 | MATCH | **DIFF** | MATCH | MATCH | MATCH |
| M8 星期按格子下标算（上游的写法） | **DIFF** | **DIFF** | **DIFF** | **DIFF** | MATCH |
| M9 读不出节次时静默丢课 | **DIFF** | **DIFF** | **DIFF** | MATCH | MATCH |
| M10 认不出表头时不兜底 | MATCH | MATCH | MATCH | MATCH | **THROW→红** |

M1/M2/M7 只让 `weeks` 变红，M4 只让 `cells` 变红，M5/M6 只让 `merge` 变红，M10 只让 `noheader` 变红 ——
说明这几个用例盖的是 `basic` 盖不住的路径（不是把当前行为钉了一遍）。
M3 与 M9 让多份用例一起红，正好是「节次来源」与「不许静默丢课」这两条主线。

## 已知不确定（交给真机抽验与用户反馈）

- **入口主机**：2026-09-12 的探测里 `jw.gxdlxy.com` 从校外连不通、`jw.vpn.gxdlxy.com` 指向学校的
  CAS 单点登录。本件按上游 `adapters.yaml` 把 `loginUrl`/`scheduleUrlHint` 留在这个学校域名上，
  并请求同源相对路径。**如果真机上发现学生实际只能从 WebVPN 网关进**，改法是把 `loginUrl`
  （和 `scheduleUrlHint`）换成网关主机一行即可 —— 脚本不用改，因为它请求的是「当前页面同源」。
- 上游自己写着「非本校开发者适配」：本件对页面结构的假设（`#kbtable`、`div.kbcontent`、
  `font[title=老师]`、节次写在哪）**全部来自上游脚本与同平台其它学校**，没有真实页面 dump 可验。
  这也是本件把「节次三个来源都认 + 读不出就报警」写进去的原因。
- `colspan` 与「没有星期表头」两条兜底路径没有真实页面可验，只在合成 fixture 上跑过（见上表 M10）。
- 连堂合并是上游的行为，本件保留；两节之间的其它信息（比如同一格里的多个 `[节次]`）若与课名不一致，
  合并结果可能与真实排课不符 —— 那时会在导入预览里表现为一门课占了一个大块，欢迎反馈。
- 学期总周数 20 周、作息表 10 小节都是**上游写死的值**，不是教务给的；它们会进 `warnings`，
  用户在「学期管理」里可以改。
- fixture 是合成的（见上）。
