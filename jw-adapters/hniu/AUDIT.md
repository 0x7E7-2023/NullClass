# 安全审计：`hniu`（湖南信息职业技术学院）

| | |
|---|---|
| 上游 | [shiguang_warehouse](https://github.com/XingHeYuZhuan/shiguang_warehouse) → `resources/HNIU/hniu_01.js`（MIT，Copyright 2025 **星河欲转**；`adapters.yaml` 的 `maintainer` 同名） |
| 上游快照 | commit `e62554a4034386b893bcd6813c7b2b64f8c730a3`（2026-09-12） |
| 上游平台 | **强智科技「高校综合管理教务系统」（湖南强智）学生端 `/jsxsd/`**，不是正方 |
| 本适配器 | `jw-adapters/hniu/`，按[适配器规范](../../docs/jw-adapter-spec.md) v1 切两段（`extract.js` 只取数 / `parse.js` 纯转换）+ 降 ES5 |
| 移植者 | NullClass（Claude Code 执行） |
| 审计日期 | 2026-09-12 |
| 审计范围 | `extract.js`、`parse.js` 全文，`manifest.json` 的 `loginUrl` / `scheduleUrlHint` / `allowHosts` |
| 结论 | **安全性通过**（逐条见 §2，请求域见 §1）。**正确性未验**：fixture 是合成的，见 §7 |

平台判据（不按上游注释，按脚本实际请求的路径）：

- 上游请求 `https://jw.hniu.cn/jsxsd/xskb/xskb_list.do`，`/jsxsd/` + `xskb/xskb_list.do` 是强智学生端的路径
  （正方新版是 `/jwglxt/` + `xskbcx_cxXsKb.html`）；解析的是 `#timetable` 里 `div.kbcontent` +
  `font[title=教师|周次(节次)|教室]`，也是强智的格子结构。上游自称「强智适配」是对的。
- `adapters.yaml` 的 `adapter_name` 是「湖南信息职业技术学院强智适配」，学校名取自脚本首行注释
  （`湖南信息职业技术学院(hniu.cn)`）。

---

## 1. 请求了哪些域

脚本一共发 **2 个请求**，都指向同一个常量 `KB_URL = https://jw.hniu.cn/jsxsd/xskb/xskb_list.do`：

| # | 方法 | URL | 何时发 | 拿什么 |
|---|---|---|---|---|
| ① | GET | `https://jw.hniu.cn/jsxsd/xskb/xskb_list.do` | 当前页面没有课表格子时 | 课表页 HTML（教务默认的当前学期） |
| ② | POST | `https://jw.hniu.cn/jsxsd/xskb/xskb_list.do` | ① 也读不到课表格子、但从①里读到了学年学期 | 指定学期的课表 HTML。body 与上游一字不差：`cj0701id=&zc=&demo=&xnxq01id=<学期>` |

两个都带 `credentials: 'include'`（用用户已登录的会话，不读、不存、不外发任何凭据）。

`allowHosts` = **`["jw.hniu.cn"]`**（精确主机，不用通配）。没有第二个域：

```bash
$ grep -oE "https?://[A-Za-z0-9.:-]+" hniu/*.js | sort -u
extract.js:https://github.com          # 文件头的出处注释，不是请求
extract.js:https://jw.hniu.cn          # KB_URL
parse.js:https://github.com            # 文件头的出处注释，不是请求

$ grep -nE "fetch\(|XMLHttpRequest|sendBeacon|new WebSocket|EventSource|\.src\s*=|localStorage|sessionStorage|document\.cookie|eval\(|new Function|password|pwd" hniu/*.js
extract.js:236:        return fetch(url, init).then(function (response) {   # requestHtml() 里唯一一处

$ grep -oE "https?://[A-Za-z0-9.:-]+" resources/HNIU/hniu_01.js | sort -u
https://jw.hniu.cn        # 上游同一个域、同一处 fetch
```

**上游登录链路上的两个域没有写进 allowHosts，这是有意的**：`authserver.hniu.cn`（统一身份认证）与
`ehall.hniu.cn`（办事大厅）是**浏览器导航**经过的页面（用户在 WebView 里手工登录），不是脚本请求的目标；
`atrust.hniu.cn` 是学校挡在教务前面的零信任网关，同理。适配器只请求 `jw.hniu.cn` 这一个域上的这一条路径。
把网关域放行等于放行它背后能转发到的全校服务（手册 §5「代理域」那条），这里不需要也不该放。

---

## 2. 移植手册 §5 八条

| # | 检查项 | 结论 |
|---|---|---|
| 1 | **不碰凭据** | 不读 `password` / `pwd` / 登录表单 / `localStorage` / `sessionStorage` / `document.cookie`（上面的 grep 为空）。请求只带 `credentials: 'include'`，用浏览器自己的会话 cookie 访问本校教务。上游弹窗问的是「起始学年 / 第几学期」（不是账号密码），移植后连这个都不问了 |
| 2 | **不外发** | 全脚本只有一处 `fetch`，只指向 `jw.hniu.cn`。没有 `sendBeacon` / `WebSocket` / `EventSource` / `new Image().src` / 重定向后二次外发；没有统计、上报、遥测。`parse.js` 完全不联网（纯转换，连 `fetch` 都没有） |
| 3 | **请求域可控** | 只有一个精确主机 `jw.hniu.cn`，见 §1 |
| 4 | **只读课表** | 只请求 `/jsxsd/xskb/xskb_list.do`（课表页）。不碰成绩、学籍、考勤、缴费、个人信息。页面读取面**只有** §3 那张表里列的几处，读到的文字里即使夹着姓名/学号，也不会进载荷（进载荷的只有课表、学期名与学期编码） |
| 5 | **不埋点** | 无统计、无上报、无遥测，也没有任何「回传失败原因」的请求 |
| 6 | **不 eval 远程代码** | 无 `eval` / `new Function`。取回的 HTML 只交给 `DOMParser`（惰性文档，不执行脚本）；`parse.js` 只对字符串做正则 |
| 7 | **不写页面** | 不写 DOM、不改表单、不触发提交、不点页面元素、不装全局函数。上游往页面写过 `window.validateYearInput`（给它自己的输入校验用），移植后**连同弹窗一起删掉了**；上游 `document.createElement('div')` + `innerHTML` 是在**游离节点**上解析 HTML，不是写页面 —— 我们直接用正则读原始 HTML，连游离节点都不需要 |
| 8 | **不依赖用户输入之外的秘密** | 没有硬编码密钥、令牌、他人学号；`xnxq01id` 从页面下拉框取。上游 `adapters.yaml` 的登录地址里那个 aTrust JWT 是网关**每次访问现场生成**的（`timeout: 600`，见 §4），没有写进任何地方 |

## 3. 读取面（`extract.js` 到底读了页面上的什么）

第 4 条说「读什么」，这里说「怎么读、读到哪一层」。本节与代码逐条对齐过（给的是函数名，不给行号 —— 行号会漂）。

| # | 读什么 | 代码位置 | 取出的值 | 去处 |
|---|---|---|---|---|
| 1 | 课表表格：`#timetable` / `#kbtable`，都没有时含 `.kbcontent` 的那张表 | `findTable()` → `rowsOfTable()` | 每个单元格的文字 + `div.kbcontent` 的原始 HTML | 载荷 `rows[]`（由 `parse.js` 解释） |
| 1b | 同一行的**首列文字**（「第1-2节」这类行标签） | `cellOf()` 的 `text` 字段 | 只用它兜底补节次（`parse.js` 里 `rowPeriods`），读不出就空着 | 载荷 `rows[][0].text` |
| 2 | 「学年学期」下拉框：id 或 name 含 `xnxq` 的 `<select>` | `readTerm()` 主路径 | 选中项的 value（`2026-2027-1`）与选项文字（学期名） | 载荷 `term` |
| 3 | 兜底①：`document.title` | `termTextHints()` | 只做一次正则匹配 | 见下 |
| 4 | 兜底②：页面上**每个** `<select>` 的 option 文字 | `termTextHints()` | 同上 | 见下 |
| 5 | 兜底③：id 或 class 含 `xnxq` 的元素的**短**文本（≤120 字） | `termTextHints()` | 同上 | 见下 |
| 6 | 当前页面主机名 `location.host` | `where()` | 只拼进**报错信息**（「当前页面：xxx」），不进载荷、不外发 | 报错文案 |

兜底（3-5）只在下拉框认不出学期时才走，实现在 `termTextHints()` 一个函数里。它是**唯一**从
「非课表结构」取文字的地方，行为如下：

- 只在这三处取，**不读 `document.body`**（第 5 项还带长度上限，避免命中一个包着整页的容器）；
- 取到的整串文字只在本页内存里做一次正则匹配
  `(20\d{2})\s*-\s*(20\d{2})\s*学年\s*第?\s*([一二三123])\s*学期`；
- 匹配到了，**只把匹配到的那一小段**（如 `2026-2027学年第一学期`）当学期名，`term.code` 由正则的
  三个捕获组拼出来；没匹配到的文字不留引用、不记日志；
- 无论匹配与否都**不外发、不进载荷**、不写页面。`term.name` / `term.code` 是这条路径上唯一可能进载荷
  的东西，而且它们是学期名，不是页面上的其它内容。

「当前页面就有课表格子」时（`harvest(document)` 命中）**一个请求都不发**：用户此刻看到的那张表就是结果。

## 4. `loginUrl` 为什么填 `https://jw.hniu.cn/jsxsd/`，而不是上游 `adapters.yaml` 里那串

上游的 `import_url` 是：

```
https://authserver.hniu.cn/authserver/login?service=https%3A%2F%2Fehall.hniu.cn%3A443%2Flogin
  %3Fservice%3Dhttps%3A%2F%2Fehall.hniu.cn%2Fnew%2Findex.html%3Fbrowser%3Dno%EF%BC%89#StudentAffairsA
```

即「统一身份认证 → 办事大厅（ehall）」。上游说明自己也写着：「登录后需要点击进入教务系统才可以点开始导入」。
没有采用它，四条理由，前两条是硬约束：

1. **取数必须同源。** 适配器的取数是 `fetch('https://jw.hniu.cn/jsxsd/xskb/xskb_list.do', {credentials:'include'})`。
   强智不会给跨源请求发 CORS 头，而 `credentials: 'include'` 的跨源请求必须是 `Access-Control-Allow-Origin`
   精确匹配 + `Allow-Credentials: true` 才可能成功。所以在 `ehall.hniu.cn` 页面上点提取**必然失败**，
   用户只能停在一条「连不上教务系统」的报错上。脚本要跑得通，用户就必须停在 `jw.hniu.cn` 的页面上 ——
   那 `loginUrl` 就该是这一页。
2. **上游那串 URL 的 `service` 指向 ehall，不是教务。** 它落不到课表页，只是把用户送进办事大厅；
   之后还得自己点进教务。它描述的是「学校门户的入口」，不是「适配器要工作的那一页」。
3. **那串 URL 里最后一个片段 `%EF%BC%89` 是一个全角括号、结尾还挂着 `#StudentAffairsA`**，是某次
   会话里复制出来的形状，不是稳定的入口地址。
4. 上门实测（2026-09-12，只读，无账号）：

   ```bash
   $ curl -s -o /dev/null -D - https://jw.hniu.cn/jsxsd/ | head -3
   HTTP/1.1 302 Moved Temporarily
   Location: https://atrust.hniu.cn:443/controller/v1/public/verify?t=<JWT>
   # JWT 载荷（base64 解开）：{"timeout":600, ..., "returnURL":"https://jw.hniu.cn/jsxsd/"}

   $ curl -sL -o /dev/null -D - https://jw.hniu.cn/jsxsd/ | grep -E '^(HTTP/|Location:)'
   HTTP/1.1 302 Moved Temporarily     # → atrust.hniu.cn/…/public/verify?t=<JWT>
   HTTP/1.1 302 Found                 # → /portal/shortcut.html?dest=#!/login
   HTTP/1.1 200 OK                    #    &appUrl=https%3A%2F%2Fjw.hniu.cn%2Fjsxsd%2F&t=<JWT>
   ```

   学校把 `jw.hniu.cn` 挡在**深信服 aTrust 零信任网关**后面：裸访问会被 302 到网关的登录页，登录后
   网关按 `appUrl` 把浏览器送回**同一个 URL**。也就是说 `https://jw.hniu.cn/jsxsd/` 自己就是完整的
   登录入口 —— 不需要上游那串手工拼的门户地址，网关会把它需要的一切带上。同一时刻
   `https://authserver.hniu.cn/authserver/login?...` 返回 200（统一身份认证页活着），
   `https://ehall.hniu.cn/new/index.html` 302 到自己带 `?browser=no`（门户活着）—— 两个域都能到，
   但它们不是取数页。

配套的：

- `scheduleUrlHint = https://jw.hniu.cn/jsxsd/xskb/xskb_list.do`（同一个主机，课表页本身）。
  宿主打开适配器时优先用它（`startUrl = preferredUrl ?: scheduleUrlHint ?: loginUrl`），
  用户一进来就在课表页；网关的 `appUrl` 也会把登录后的浏览器送回这里。
- `loginUrl` 与 `scheduleUrlHint` 同源，所以网络闸门的白名单里只需要 `jw.hniu.cn` 这一条；
  提取期页面自身的子资源不会被误伤，JS 层的 `fetch` 白名单也只放这一条。
- 用户若停在网关页（或门户页）上点「提取课表」，取数会因为跨源/白名单被拦下 —— 那时报错信息里会带上
  **当前页面主机名**（`where()`），用户一眼能看出「我还没进教务系统」。

## 5. 与 `hynu`（衡阳师范学院）的同平台对照片

两个适配器是**同平台的近克隆**：上游 `HNIU/hniu_01.js` 与 `HYNU/hynu_01.js` 的 `diff` 只有四处
（首行校名、节次正则、多出来的合并段、`fetch` 的主机名 —— 外加作息表数值），所以「哪些能复用、
哪些不能」逐层记在这里（手册《测试方案》§3.2 那张分层表的实测答案）：

| 层 | hynu | hniu | 复用结论 |
|---|---|---|---|
| 接口信封（路径、方法、请求体） | `GET/POST /jsxsd/xskb/xskb_list.do`，`cj0701id=&zc=&demo=&xnxq01id=` | **一字不差** | 复用（`extract.js` 的取数路径照搬，只换主机名） |
| 课表容器 / 一格的结构 | `#timetable`（兜底 `#kbtable`、`.kbcontent`）+ `div.kbcontent` + `font[title=教师\|周次(节次)\|教室]` | **一字不差**（上游两段代码 diff 为空） | 复用（`extract.js` 的 DOM 抓取照搬） |
| 一个格子里多门课的分隔 | 一长串减号 | **相同** | 复用（放宽成 `/-{5,}/`） |
| 星期列对齐 | 上游按「第几个格子就是星期几」硬算 | **同一个写法** | 复用 hynu 移植时的修正（按星期表头列号对齐）——两所学校都有节次列 |
| **节次编码** | 上游正则 `/\[(\d+)(?:-(\d+))?节\]/`（只认 `[1-2节]`） | 上游正则 `/\[(.*?)节\]/` + 「取括号里所有数字的 min/max」（为 `[01-02-03-04节]` 改的） | **不能整份复用**：hynu 的写法读不出 `01-02-03-04`，hniu 上游的写法会把 hynu 的 `[0102节]` 读成第 102 节。本适配器两种都认（`periodsIn()`），并各有一条用例 |
| **相邻节次合并** | 上游没有 | 上游 hniu 有（`current.startSection <= last.endSection + 1` → `1-2 + 3-4 = 1-4`） | **不复用 hynu**：保留 hniu 自己的行为（`mergeAdjacent()`），并单独造用例 |
| **作息表** | 12 节，45 分钟一节（08:30-09:15 … 22:15-23:00） | 12 节，**40 分钟**一节（08:30-09:10 … 21:10-21:50） | **不复用**：逐条照搬 hniu 上游 `saveAppTimeSlots()` 的数值。拿 hynu 的表套过来会让每一节都错 5-10 分钟 |
| 周次编码 | `1-16(周)`、`1-15(单)`、`1-4,6-8,10-16(周)` | 上游 `parseWeeks()` 与 hynu **逐字相同** | 解析逻辑可复用，但**上游那段本身是错的**（`split('(')[0]` 吞掉单/双），两个适配器用的是同一套修正 |
| 学期 / 开学日 | 页面不给，靠下拉框 + 推算 | **相同** | 复用（同一条 `warnings` 口径） |
| 分页 | 无（一次 POST 返回整张表） | **相同**（上游也是单次 POST） | 都不涉及；见 §8 末条 |

「一字不差」是 diff 读出来的，不是猜的：

```bash
$ diff resources/HYNU/hynu_01.js resources/HNIU/hniu_01.js | grep -E '^[0-9]'
1c1         # 首行校名
49c49 51a52 56d56 65d64 74d72 87d84    # 注释与变量改名（results → rawResults）
94,97c91,99     # 节次正则：/\[(\d+)(?:-(\d+))?节\]/ → /\[(.*?)节\]/ + 取所有数字的 min/max
102c104 105c107 117,118c119,137 119a139,168   # 新增的「去重与合并」段
136,147c185,196 # 作息表数值（hynu 45 分钟一节 / hniu 40 分钟一节）
193c242         # fetch 的主机名（hysfjw.hynu.edu.cn → jw.hniu.cn）
```

即：**接口、请求体、DOM 结构、周次解析函数逐字相同**，真正的差异集中在「节次怎么写」「要不要合并」
「作息表数值」这三处 —— 本适配器正是在这三处按这所学校自己的编码单独取证、单独造用例的。

## 6. 移植时改掉/删掉的上游行为

- **弹窗与 toast**（`showAlert` 确认已登录、`showPrompt` 输学年、`showSingleSelection` 选学期、
  一串 `showToast`、`window.validateYearInput`）：空课的导入流程自己会确认；学期改成从课表页
  「学年学期」下拉框里取**选中的那一项** —— 用户在页面上切学期，适配器就跟着他走，比弹窗更清楚。
- **上游硬编码的 `semesterTotalWeeks: 20` / `firstDayOfWeek: 1` / `savePresetTimeSlots`**：
  20 周与那 12 节作息表在 `parse.js` 里保留为**载荷字段**（`totalWeeks` / `periodTimes`），
  并在 `warnings` 里说明它们是适配器内置值而不是教务给的。
- **上游没有开学日期**（`config.semesterStartDate` 也没给）：按手册 §4.2 的「最近的周一」推算，
  并在 `warnings` 里如实说明。
- **上游 `parseWeeks()` 的 `split('(')[0]`**：会把 `1-16周(双)` 吞成「每周都上」且不报警，
  改成按单/双切段 + 认不出的写法进 `warnings`（见 §7 的变异测试）。
- **上游 `if (name && weekStr && start > 0)`**：格子没写节次时整块**静默丢掉**，
  改成「先按所在行的节次标签兜底，兜不了才跳过并报出课名」。

## 7. fixture 自证与变异测试

四个用例都是**合成数据**（`*_note` 里注明），期望值按规范**独立推出**（先按节次/周次该怎么算写出期望，
再跑 `parse.js` 比对），不是把脚本输出贴回去。四个用例各自盯住一小块最容易写错的地方，并做了**变异测试**
证明它们真的在看着那些逻辑（把 `parse.js` 的逻辑故意改坏 → 对应用例必须变红）：

| 改坏的地方 | basic | block-sections | odd-even | serial-drop |
|---|---|---|---|---|
| （基线，不改） | MATCH | MATCH | MATCH | MATCH |
| 单/双全部吞掉（= 上游 `split('(')[0]`） | **DIFF** | MATCH | **DIFF** | MATCH |
| 只认「数字(单)」一种写法（`周(双)`/`单周1-16` 退回每周） | DIFF | MATCH | **DIFF** | MATCH |
| 单双标记只认非段首的（认不出 `单周1-16`） | MATCH | MATCH | **DIFF** | MATCH |
| 认不出单双的写法不报警（`隔周` 静默变每周） | MATCH | MATCH | **DIFF** | MATCH |
| 节次只认两段以内（= hynu 的写法，认不出 `01-02-03-04`） | MATCH | **DIFF** | MATCH | MATCH |
| 不做相邻节次合并（= hynu 的行为） | MATCH | **DIFF** | MATCH | MATCH |
| 作息表不扩展、不报警 | MATCH | **DIFF** | MATCH | MATCH |
| 格子没写节次时不回落到行标签 | MATCH | **DIFF** | MATCH | MATCH |
| 不摘「纯数字括号」（把 `(2)` 当周次） | MATCH | MATCH | MATCH | **DIFF** |
| 跳过的块不报警 | **DIFF** | MATCH | MATCH | **DIFF** |
| 总周数被抬高时不报警 | MATCH | MATCH | MATCH | **DIFF** |
| 学期名认不出时不占位、不报警 | MATCH | MATCH | MATCH | **DIFF** |
| 一格两门课之间的长串减号不认 | **DIFF** | MATCH | MATCH | MATCH |
| 拆写成两条 font 的「周次」+「节次」不再配对 | **DIFF** | MATCH | MATCH | MATCH |
| 星期按格子序号硬算（= 上游的 `dayIndex + 1`） | **DIFF** | **DIFF** | **DIFF** | **DIFF** |

每个用例都至少有一条「只有它变红」的变异，每条变异也至少被一个用例拦住。**这些用例只保证「同样的输入
永远得到同样的输出」**，不保证真实页面上的解析是对的 —— 形状来自上游脚本的选择器与同平台约定，
我们手上没有该校账号。

除此之外做了一次**两段联调的黑盒验**：用真实浏览器引擎（Edge headless）打开本地合成的强智形状页面
（不是真教务，形状按上游选择器写的），直接跑 `extract.js`：

- 探针 A（页面本身就是课表页）：`extract.js` **一个请求都不发**，直接交出 `{now, term, rows}`；
  `term` 读的是下拉框选中项（`2026-2027-1` / `2026-2027学年第一学期`）。把这份输出原样喂给
  `parse.js`：3 门课，`1-16周(双)` 落成 `weekType:"EVEN"`、没写 `font[title=教室]` 的那门
  `location: null`、星期按表头列号对齐（周一/周四/周三各归各位）。
- 探针 B（页面没有课表）：`extract.js` 先 GET、再按下拉框里的学期 POST，实测请求体
  `cj0701id=&zc=&demo=&xnxq01id=2026-2027-1`、`credentials: include`、URL 与上游一字不差，
  载荷来自 POST 返回的那份 HTML。

这验的是「脚本在真实浏览器里跑得起来、选择器读得到、两段的接口对得上」，**不验**真实教务页面的样子。

## 8. 已知不确定（交给真机抽验与用户反馈）

- **分页**：这个接口**没有分页**，也没有记录总数 —— 一次 GET/POST 返回的就是整张课表（服务端渲染的
  HTML）。所以不存在「只取了第一页」这种失败模式；唯一要防的是「拿回来的整页里一格课都没有」，
  那种情况直接明确报错（「教务返回的课表是空的」/「没找到课表，也没找到学年学期下拉框」），
  不会静默导入一份空课表。上游也是同一条路径。
- **aTrust 网关能不能在 WebView 里走通，是本适配器最大的未知。** `jw.hniu.cn` 被深信服 aTrust
  挡着（§4 的 302 链）。浏览器路径看起来是「网关登录页 → 按 `appUrl` 回到原 URL」，但那一步能不能在
  不装 aTrust 客户端的 WebView 里完成，**从校外只读探测看不出来**（要有账号）。若学校只允许客户端接入，
  那这所学校在空课里就无法导入 —— 这是学校侧的限制，不是适配器能绕的（也不该绕）。
  上游适配器存在并被使用，说明浏览器路径大概率通；但这一条必须真机验。
- **课表容器的 id 与格子结构**（`#timetable` / `#kbtable` / `.kbcontent` / `font[title=…]`）来自上游选择器，
  与 hynu 同平台、写法一致，但没有该校的真实页面可验。
- **周次的 `单/双` 写法**（`1-15(单)`、`2-16周(双)`、`单周1-16`）是按平台约定 + 第一批挖出来的坑造的
  **防御性用例**，不是从该校真实页面抄的。`隔周` 那条更明确是构造的（见 `serial-drop` 与 `odd-even`
  的 `_note`）：它认不出单双，所以按每周放进去并**出声**，用户能看见。
- **`(2)` 这种括号序号**在这所学校的页面上是否真的会出现，没有实据；规则是防御性的，用例里把它放在
  独立的逗号段（这样「摘掉纯数字括号」有没有生效在输出上看得见）。
- **「第N节」行标签兜底**：`periodsIn()` 只认数字写法（`第1-2节` / `1-2` / `0102`），
  不认「第一大节」这种中文数字标签 —— 遇到那种页面会走「跳过 + 报出课名」，不会瞎猜节次。
- **作息表顺延**（第 13-14 节按 40 分钟一节、课间 10 分钟补出）是内置表 12 节的延伸，
  不是教务公布的时间；只在课表真的用到时才补，并且一定进 `warnings`。第 15 节以后
  （会跨到第二天凌晨）不补，按「解析不了」跳过并出声。
