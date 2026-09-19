# 安全审计 —— 大连海事大学（树维 EAMS 平台）

审计对象：`jw-adapters/dlmu/` 下的 `extract.js` / `parse.js`，以及它们移植自的上游脚本
`shiguang_warehouse` 的 `DLMU/dlmu_01.js`（快照 commit `e62554a4034386b893bcd6813c7b2b64f8c730a3`，
2026-09-12，MIT，上游作者 `whynusn`）。

- 移植者：`0x7E-2023`
- 移植日期：2026-09-16
- 适配器 key：`dlmu`

审计方式：逐行读上游脚本 + 逐行读移植件，并跑移植手册 §5 给的静态扫描
（`grep -oE "https?://[A-Za-z0-9.:-]+"` 与 `grep -nE "fetch\(|XMLHttpRequest|sendBeacon|new WebSocket|\.src\s*=|localStorage|sessionStorage|password|eval\(|new Function"`）。

**平台名**：本校教务是**上海树维信息科技有限公司（SupWisdom，新开普子公司）**的 EAMS
（`/eams/`，综合教学管理系统），**不是湖南强智**。判据见批次四文档「平台名订正」一节：
`/eams/` + `dataQuery.action` + `courseTableForStd.action` + TaskActivity 内嵌块是树维这一套的固定形态，
而强智的路径是 `/jsxsd/`。

## 1. 请求域与请求清单（本节必须与代码一致，不许写得比代码窄）

上游脚本里有**一个**绝对 URL：`http://jw.xpaas.dlmu.edu.cn`（四条 `fetch` 都把主机名写死）。
移植件**没有**沿用绝对地址，改成按当前页面的 origin + 上下文路径 `/eams` 拼
（`originOf()` + `eamsBase()`；当前页面已经是课表页时用 `courseTableUrl()` 保留页面自己的查询串）。

所以两个脚本请求的主机都只有**用户当前所在的那一个教务主机**
（`http(s)://jw.xpaas.dlmu.edu.cn`，即 `loginUrl` 的主机）。**`allowHosts` 留空**，不写通配。

`eamsBase()` 会在当前地址里找 `/eams/` 这一段并保留它之前的前缀 —— 万一学校把教务挂在门户 /
WebVPN 前缀下（例如 `/webvpn/eams/...`），请求跟着用户实际打开的那个前缀走，脚本不替教务系统写死主机。

| 谁 | 方法 | 地址 | 干什么 | 取不到时 |
|---|---|---|---|---|
| extract.js | GET | `<origin><前缀>/eams/courseTableForStd.action?sf_request_type=ajax`（当前页面已经是课表页时**直接读当前页面的 DOM**，这一条整个跳过） | 读学号 `ids`、学期标签 `tagId`、页面里的 `var unitCount`。**上游同款接口**（上游用它读学号） | 报错并提示重新登录 |
| extract.js | POST | `<origin><前缀>/eams/dataQuery.action?sf_request_type=ajax` | 学期列表（`dataType=semesterCalendar`），用来自动选当前学期。**上游同款接口、同款请求体**（`tagId` / `dataType` / `value` / `empty`） | 报错并提示重新登录 |
| extract.js | GET | `<origin><前缀>/eams/base/calendar-info.action?version=1&semesterId=<id>` | 学期起止日期与周数（用来定开学日与总周数）。**这条是移植时新增的**（上游 dlmu 没有；取自同族的 ZUA），取不到交 `null` | 交 `null`，parse 回落到推算并写进 `warnings` |
| extract.js | POST | `<origin><前缀>/eams/courseTableForStd!courseTable.action?sf_request_type=ajax` | 课表 HTML。**上游同款接口、同款请求体**（`ignoreHead=1&setting.kind=std&startWeek=&project.id=1&semester.id=&ids=`） | 报错并提示重新登录 |

本地桩测（`fetch` 全打桩、不打真实网络）实测的请求序列：

| 场景 | 请求（按顺序） |
|---|---|
| 不在课表页 | ① GET `courseTableForStd.action?sf_request_type=ajax` ② POST `dataQuery.action`（`tagId=semesterBar20826294511Semester&dataType=semesterCalendar&value=&empty=false`）③ GET `base/calendar-info.action?version=1&semesterId=223` ④ POST `courseTableForStd!courseTable.action`（`ignoreHead=1&setting.kind=std&startWeek=&project.id=1&semester.id=223&ids=2220260001`） |
| 已开在课表页 | 少掉第 ① 条（直接读页面），共 3 条 |
| 挂在 `/webvpn` 前缀下 | 四条路径都变成 `https://vpn.dlmu.edu.cn/webvpn/eams/...`（跟着用户实际打开的地址走） |
| 日历接口 500 / 返回垃圾 | 请求序列不变，`term.startDate` 与 `term.weekCount` 都是 `null`，**不抛异常**（降级给 parse 的推算分支） |
| 课表页里没有 `ids` | 只发第 ① 条就抛「没能从课表页里认出学号或学期标签…」 |
| 学期列表为空 | 发前两条后抛「教务系统没有返回可用的学期列表（可能登录状态已失效）…」 |

**关于新增的第三条（`base/calendar-info.action`）**：

- 上游 dlmu 用它自己的 `semesterCalendar` 响应**只解析学期 id**（`parseSemesterId`），没有用它定开学日 ——
  开学日在 upstream 里是 `showPrompt` 问用户的。移植件不问用户，所以必须自己拿到开学日：
  树维的 `semesterCalendar` 响应里**没有**学期起止日期（同族 12 个脚本都没有从它取过日期），
  而 `base/calendar-info.action` 是同族 ZUA 已经在用的同一个只读接口（同一个上下文路径、同一个模块）。
- 它只读**学期日历**（学校公共数据：学期起止日期 + 周数），**不碰成绩、学籍、个人信息**。
- 请求参数只有 `version=1` 与 `semesterId`，都是公开的页面参数，不带任何自定义令牌。
- **只有需要用的时候才发**：这一条在学期列表拿到之后才发，且失败即降级（交 `null`），不阻塞导入。
- 真机核对时若学校没装这个菜单 ⇒ 开学日走推算分支，`warnings` 里会如实说。

**读到的数据里有什么**：

| 来源 | 读什么 | 交出去什么 |
|---|---|---|
| 当前页面 | 学期下拉框（`#semesterId` / `input[name="semester.id"]` / `select[id$="Semester"]`）的选中项 | 只交它的 `value`（学期 id，纯数字才认） |
| 课表页 HTML（页面或 GET 的响应） | `bg.form.addInput(form,"ids","<数字>")` 里的学号、`id="semesterBar…Semester"`、`var unitCount = N` | 只交 `ids` / `unitCountPage`（`tagId` 只用于发请求，进 payload 的只有这三个 + 学期线索） |
| `dataQuery.action` 响应 | `{semesters:{…:[{id,schoolYear,name}], semesterId}` 与可能的外层信封 | 只交被选中那个学期的 `id` / `kind`(name) / `schoolYear` / 拼出来的可读标签 |
| `base/calendar-info.action` 响应 | 「开始/结束日期：YYYY-MM-DD ~ YYYY-MM-DD (N)」里的起始日期与周数 | 只交 `startDate` 与 `weekCount` |
| `!courseTable.action` 响应 | 全文 | **原样交出去**（parse.js 在里头找 `var teachers` 与 `new TaskActivity(...)` 块）。**不剥空白** —— 上游的 `fetchWithCleanup` 会把整份 HTML 的空白全删掉，那会把课程名、教室名里的空格一起吃掉 |
| 当前页面上的其它东西 | **不读** | 不读 cookie、不读 localStorage、不读表单值（除了上面那个学期下拉框的选中项） |

**不交出去的东西**：`!courseTable.action` 的响应里可能夹带学生信息（树维有的部署会在页脚或隐藏表单里放
`student` 相关字段），本件的 `extract.js` **整份 HTML 原样交给 `parse.js`**，`parse.js` 只从中读
`var teachers` 与 `new TaskActivity(...)` 两类片段，**不会把 HTML 带进载荷**，`fixtures/` 里的课程名 /
教师 / 教室 / 日期也全部是虚构的。

## 2. 移植手册 §5 八条逐条

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 不碰凭据 | **过**。两个脚本都没有 `password` / `pwd` / 登录表单读取，也没有 `localStorage` / `sessionStorage` / `document.cookie` 访问（静态扫描对 `cookie` / `localStorage` / `sessionStorage` / `password` / `pwd` 零命中）。唯一的「凭据」是浏览器自带会话 Cookie，脚本既不读它也不把它发到别处（`credentials: 'include'` 只是让 WebView 按同源规则带上它） |
| 2 | 不外发 | **过**。全部 `fetch` 都在 `extract.js` 里（共一处，被 `get` / `post` 两个包装复用），地址全部由 `window.location.origin` + 上下文路径拼成；没有 `sendBeacon` / `WebSocket` / `EventSource` / `new Image().src` / 隐藏表单，也没有任何第三方域。`parse.js` **不发任何请求**（CI 里用 Rhino 实跑，是纯函数） |
| 3 | 请求域可控 | **过**。请求主机只有一个：`loginUrl` 的主机 `jw.xpaas.dlmu.edu.cn`（`loginUrl` 给的是 http，`extract.js` 不在页面里跳转、只按当前 origin 拼地址，所以走 http 还是 https 由用户实际打开的那个地址决定）。`allowHosts` 留空，不写通配 |
| 4 | 只读课表 | **过**。四条请求全部落在 `/eams/` 下与课表直接相关的三处：课表页、学期列表（`dataQuery.action`）、课表接口（`courseTableForStd!courseTable.action`），外加一条**学期日历**（`base/calendar-info.action`，只读学期起止日期与周数）。**不碰成绩、学籍、个人信息、缴费**等任何其它接口。日历那条的说明见 §1 |
| 5 | 不埋点 | **过**。没有任何统计 / 上报 / 遥测，也没有 `img` 打点；`console` 只在宿主调试时用（上游也是 `console.log` 打日志） |
| 6 | 不 eval 远程代码 | **过，而且是本件改动的重点**。`parse.js` 与 `extract.js` 都**没有** `eval` / `new Function` / `setTimeout('字符串')`。上游 `dlmu_01.js` 的 `evalIndex()` 用 `new Function("return " + cleanExpr)` 执行**从网络取回的课表 HTML 里正则抓出来的表达式**，本件换成了纯正则解析（见 §3） |
| 7 | 不写页面 | **过**。`extract.js` 只**读**当前页面上的学期下拉框与（若有）输入框；取课表页 HTML 时用的是 `fetch`；需要用 DOMParser 时分两条路 —— **当前页面**用 `document` 读（`semesterIdIn(document)`，只 `querySelector` + 读 `value`），**网络取回的 HTML** 用 `new DOMParser().parseFromString(...)` 解析成**离线文档**（不插入当前页面）。既不写 DOM、不改表单、也不触发提交或点击。上游的 `showAlert` / `showToast` / `showPrompt` / `showSingleSelection` / `notifyTaskCompletion` / `saveImportedCourses` / `saveCourseConfig` / `savePresetTimeSlots` 这类桥调用**全部没有移植** |
| 8 | 不依赖用户输入之外的秘密 | **过**。没有硬编码密钥、令牌或他人的学号。脚本里的常量只有上下文路径 `/eams`、四个接口路径、上游的节次数常量 `10`、上游那张 10 节作息表，以及 `today` / 学期线索这类运行时值 |

**结论：可以进内置库。** 全部请求落在本校教务主机上，只读课表（含学期日历一条只读附加接口），
不碰凭据、不外发、不埋点、不写页面、不执行页面里的代码。

## 3. 重点：拆掉上游的 `new Function`（本批的核心审计项）

### 3.1 上游怎么写的

上游 `DLMU/dlmu_01.js` 里有一个求 index 值的小函数（原文逐字）：

```js
function evalIndex(expr, unitCount) {
  const cleanExpr = expr.replace(/unitCount/g, unitCount).replace(/\s+/g, "");
  // 使用 Function 构造器替代 eval，仅允许数字和基本运算符
  try {
    const fn = new Function("return " + cleanExpr);
    return fn();
  } catch (error) {
    console.error("计算表达式失败:", expr, error);
    return 0;
  }
}
```

它的调用点是 `parseCourses` 里这一段：

```js
const indexRegex = /index\s*=\s*(\d+(?:\s*\*\s*unitCount\s*\+\s*\d+)?)\s*;/g;
while ((idxMatch = indexRegex.exec(followingCode)) !== null) {
  indices.push(evalIndex(idxMatch[1], unitCount));
}
```

`followingCode` 来自 `parseCourses(courseTableDataHtml)` —— 而 `courseTableDataHtml` 是
`POST /eams/courseTableForStd!courseTable.action` 的**响应体**，也就是**从网络上取回来的课表 HTML**。

### 3.2 为什么不合格

- **命中移植手册 §5 第 6 条**（「不 eval 远程代码」）。判断依据不是有没有 `eval` 这个词，而是
  **进到函数构造器的字符串是不是远程来的**：这里是从网络响应里正则抓的任意片段，一路没有白名单。
- 注释「仅允许数字和基本运算符」**与代码不符**：代码里**没有任何**字符白名单校验。
  `expr.replace(/unitCount/g, unitCount).replace(/\s+/g, "")` 只是做文本替换，
  替换之后 `new Function("return " + cleanExpr)` 就把它当**代码**编译执行。
- 上游那条 `indexRegex` 在**调用点**收窄了形态（`\d+` 或 `\d+*unitCount+\d+`），所以实际能走到
  函数构造器的字符串通常确实只有这两种 —— 但这层收窄只存在于调用点，函数自己不做校验，
  而且是**正则 + 字符串拼接后执行**这个模式本身构成了「把远程内容当代码」的通道：
  一旦上游改正则、或页面上出现别的写法，管道就直通执行。
- 出错分支 `return 0` 还有第二个问题：算不出来时它把那条排课**当成 index 0**（周一第 1 节）
  静默塞进课表。用户看到的是「课在周一第 1 节」，而不是「有一条排课没读懂」。

### 3.3 我们换成了什么

`parse.js` 里的 `readIndex(expr, unitCount)`：**只做正则解析与整数运算，不求值、不构造函数**。

```js
function readIndex(expr, unitCount) {
    var s = text(expr);
    if (!s) return null;
    if (/^[0-9]+$/.test(s)) return parseInt(s, 10);
    var m = /^([0-9]+)\s*\*\s*(?:unitCount|([0-9]+))\s*(?:\+\s*([0-9]+))?$/.exec(s);
    if (!m) return null;
    var factor = parseInt(m[1], 10);
    var unit = m[2] === undefined ? unitCount : parseInt(m[2], 10);
    var plus = m[3] === undefined ? 0 : parseInt(m[3], 10);
    if (!(unit >= 1)) return null;
    var value = factor * unit + plus;
    return value >= 0 ? value : null;
}
```

对应关系（与上游那条 `indexRegex` 的两种形态逐一对应）：

| 页面上的写法 | 上游怎么处理 | 本件怎么处理 | 结果 |
|---|---|---|---|
| `index = 5*unitCount+2` | 抓出来后把 `unitCount` 换成 10，交给函数构造器算 → 52 | 正则捕获组 1 = `5`，`unit` 取当前节次数，捕获组 3 = `2` → `5*10+2` | 都是 52 → 周六第 3 节 |
| `index = 62` | 函数构造器算 → 62 | `/^[0-9]+$/` 直接 `parseInt` | 都是 62 → 周日第 3 节 |
| `index = 3 * 10 + 2`（unitCount 已被替换成数字） | **上游那条正则抓不到**，那条排课被跳过 | 本件认（`unit` 取捕获组 2 = `10`）→ 32 → 周四第 3 节 | **本件比上游多认一种**（算不出来时上游会整条丢掉） |
| `index = 3*unitCount-2` | **上游那条正则抓不到**（只认 `+`），而假如把正则放宽、函数构造器会算出 18 | 认不出 → 返回 `null` | 跳过该条排课并计数进 `warnings`；**不猜**（两种读法给出 18 与 32 两个不同答案，猜错就是整学期的课错位） |
| `index = 星期*unitCount+节次`、`index = '3 * 10 + 2'`、任何别的形态 | 抓不到（跳过） | 认不出 → `null` | 跳过并计数，`warnings` 里带上畸形样本 |

**语义等价性**：对上游那条正则**实际能收到**的两种形态，本件与函数构造器给出**同一个整数**
（就是普通的乘加），所以对正常数据是等价的；差别在于三条——
① 不通向代码执行；② 认不出的**不静默变成 0**，而是跳过并计数；③ 多认一种「unitCount 已被替换成数字」的写法。

### 3.4 怎么证明（变异测试）

- `M3`（把 `factor * unit + plus` 改成 `plus * unit + factor`）→ 四个用例**全部变红**
  （`index-forms` 的课程数与 block 位置都不对）。证明这套乘加**真的在被用于定位**，
  不是写了不用。
- `M4`（不再认纯数字形态，`/^[0-9]+$/` 那支返回 `null`）→ `index-forms` 变红
  （「已算好下标课」整门课消失、`indexBad` 计数变成 3 条）。证明 `index = 62` 这条路径有覆盖。
- `M5`（把认不出的分支改回上游的语义：`linear = 0`，即静默排到周一第 1 节）→ `index-forms` 变红
  （课程数从 4 变成 7，多出三门被排到周一的课）。证明「不许静默算成 0」被用例钉住了。
- `M6`（认不出时既不计数也不写 `warnings`，直接 `continue`）→ `index-forms` 变红（`warnings` 少一条）。
  证明「畸形样本必须出声」也被钉住了。
- 目标用例 `fixtures/index-forms` 里 `index = 5*unitCount+2`、`index = 62` 与三条认不出的形态同时存在，
  所以「算错」「不认」「不出声」三种错法它都拦得住。

**它挡不住什么（诚实说明）**：如果教务真的开始输出 `index = 3*unitCount-2` 这类负偏移写法，
本件会**跳过那条排课**（用户少一门课，`warnings` 里有数字与样本），而换成一个更宽的正则去解析
可能能算出来 —— 但那需要先知道真实语义。上游那层「两条正则」的收窄使这种形态迄今没有出现。
这个取舍是刻意的：**宁可少一门课并说清楚，也不要猜一个可能整学期错位的节次。**

## 4. 本批 12 条检查表逐条

1. **周次位图的下标基准**：**过**，而且**上游这里真的会产出不存在的「第 0 周」**。上游 dlmu 原文是
   `for (let i = 0; i < weekStr.length; i++) if (weekStr[i] === "1") weeks.push(i)` ——
   下标从 0 起、**没有跳过 0 位**。本批统一口径是「下标 i 就是第 i 周，下标 0 是占位符」，
   所以本件写成 `i >= 1` 才产出周次，`i === 0` 且为 1 时**不产出「第 0 周」**、改为写一条 `warnings`。
   fixture：`weeks-bitmap`（「位图基准课」第 0 位与第 1 位同时为 1 → 只产出第 1、2 周；
   「占位课」只有第 0 位为 1 → 整段跳过并计数；「混合课」第 0 位又是 1）、`basic`（「形势与政策」第 0 位为 1）。
   变异测试：`M1`（`i === 0` 时改成 `out.push(0)`）与 `M2`（改回上游的「不跳过 0 位」）→
   `basic`、`weeks-bitmap` 变红。
2. **`TaskActivity` 的参数位**：**过**。`args[1]`=教师、`args[3]`=课程名、`args[5]`=教室、
   `args[6]`=周次位图，与同族 12 件一致；上游还用 `args[2]`（本次也读了它、用于剥课程代码，
   但**没有**照抄上游 `match[2].replaceAll(/\.join\(.*?\)/g, "")` 那种对整串做正则替换的做法 ——
   见第 3 与 §5 第 2 条）。参数切分按「引号 / 括号配对」而不是按逗号切：
   `fixtures/dirty-args` 里「编译原理,上」（课名带逗号）与「离散数学」的教室
   `教学楼C-301, 东侧`（带逗号与空格）都完整保留。`args[1]` 是表达式时（`actTeachers.join(",")`）
   按上游自己的做法从**紧邻其前**的 `var teachers = [...]` 块里取 `name`；
   取不到就**留空**（`null`），并写进 `warnings`（不写「未知」）。
   变异测试：`M8`（不再从 teachers 块取名）→ `basic`、`dirty-args` 变红；
   `M14`（改回写「未知」）→ `dirty-args` 变红。
3. **`index` 的两种写法 / 禁止 `eval` 与函数构造器**：**过**，见 §3。上游 dlmu 用的正是
   `new Function("return " + expr)`，本件换成正则解析，认不出的跳过并计数。
   fixture：`index-forms`。变异测试：`M3`/`M4`/`M5`/`M6`。
4. **`unitCount` 要真的读**：**过**。上游是常量 `const UNIT_COUNT = 10;`（注释「每天的课程节数」），
   从不读页面。本件**优先读课表页里的 `var unitCount = N`**（就是驱动 index 寻址的那个数），
   页面值与常量不一致时**以页面值为准并写 `warnings`**；页面里读不到（或值不在 1..20）才回落常量 10，
   并**如实说明这个数字是猜的**。fixture：`dirty-args`（页面 `var unitCount = 12`，
   index 结果全部按 12 算，且有专门的 warning）与其余三份（页面值 10、与常量一致，不出现该 warning）。
   变异测试：`M7`（忽略页面值、一律用常量）→ 四个用例全部变红。
   **给真机核对的人**：若发现整学期的课都错位，先查这一处（节次数决定星期与节次的换算）。
5. **作息时间从哪来**：**过**。那张 10 节表**原样取自上游 `DLMU/dlmu_01.js` 的 `getTimeSlots()`**
   （上游把它写给拾光的 `savePresetTimeSlots`）；上游没有向教务请求作息，所以本件把它当成
   「适配器自带的值」放进载荷的 `periodTimes`，**并在 `warnings` 里逐条说明它没跟教务核对过**。
   所有时间都过 `timeOf()`（`HH:mm` 且 `00:00–23:59`）与「结束晚于开始」检查，不合法的那一节被丢弃并计数。
   课表用到第 10 节以外时用空课内建节次表补齐（`dirty-args` 用到第 12 节，补了第 11-12 节并写 `warnings`）。
   变异测试：`M15`（把第 1 节的结束时间写成 `85:45`）→ 四个用例全部变红。
6. **开学日**：**过**。优先用教务给的学期起始日期（`base/calendar-info.action`），
   并按手册 §4.3 **回退到那一周的起始日**（`weekStartOnOrBefore(..., 1)`，缺省周一）；
   拿不到就按学期序号推算（第一学期 = 9 月 1 日所在周、第二学期 = 2 月 20 日所在周、
   第三学期 = 7 月 1 日所在周），学年也读不出才退到「最近的一个周一」。
   **两条路都如实写进 `warnings`**：取到值时写「取自教务系统给出的学期起始日期」，
   推算时写「教务系统没有给出可用的学期起始日期，第 1 周按「…」推算为 YYYY-MM-DD，
   请在学期管理里核对成学校实际开学日」。上游 dlmu 在这里是 `showPrompt` 问用户 —— **这条路径没有移植**，
   移植件不弹窗、不问用户。fixture：`basic`/`index-forms`（教务给了 2026-09-09，回退到 2026-09-07）、
   `weeks-bitmap`/`dirty-args`（没给，走推算）。
   变异测试：`M9`（不取教务给的值）→ `basic`、`index-forms` 变红；
   `M10`（不回退到周一）→ `basic` 变红（`2026-09-09` vs `2026-09-07`）。
7. **周次上限**：**过**。位图里 `i > 30` 的位丢弃并计数（写进 `warnings`）；学期总周数先取教务给的、
   再被课表里更晚的周次抬高、最后 clamp 到 30（载荷校验上限），抬了/截断都单独说明。
   fixture：`basic`（教务给 20 周而课表排到第 30 周 → 抬到 30）、`weeks-bitmap`（没给周数、
   内置 20 周被第 30 周的课抬高、3 个超限位丢弃）。
   变异测试：`M11`（总周数不被抬高）→ `basic`、`weeks-bitmap` 变红；
   `M12`（超限位不再丢弃）→ `weeks-bitmap` 变红（同一门课多出两条 block）。
8. **`teacher` / `location` 拿不到就留空**：**过**。上游那几个占位符写法（同族的「未知教师」/
   「未知地点」/「待定」）**一个都没有搬**：教师取不到是 `null`，教室取不到是 `null`。
   fixture：`basic`（「形势与政策」空教室）、`dirty-args`（「网络课」教师留空、
   「可编程逻辑」第 6 个参数是裸 `null` → 教室留空）。变异测试：`M14`。
9. **学期名**：**过**。优先用学期下拉框里的文本（`label`，含「学期」二字时直接用），
   其次响应里的 `name`，拿不到才用「大连海事大学 + 学年 + 第X学期」。
   **没有拿适配器名当学期名**。fixture：四份的 `terms[0].name` 都是教务给的名字
   （`2026-2027 第1学期` / `2025-2026 第2学期`）。
10. **`allowHosts`**：**过**。请求全部走**当前页同源相对路径**（`originOf()` + 上下文路径 `/eams`），
    `allowHosts` 留空、不写通配，也没有非标准端口。脚本**不使用** OCR / 提问桥
    （源码里不出现 `__ncOcr` / `__ncOcrGrid` / `__ncSelect` / `__ncConfirm` / `__ncPrompt`），
    所以「`*.` 通配会被 `JwOriginRules` 跳过、拿不到桥」这条对本件不构成影响。
11. **`warnings` 上限**：**过**。`warn()` 逐条截断到 200 字（超出补省略号），条数超过 20 时保留前 19 条
    并在最后一条如实写「另有 N 条说明因为超出上限没有显示」。四个用例实测：8 / 7 / 5 / 8 条，
    最长 81 / 99 / 149 / 101 字，都在限内（且 `index-forms` 那 149 字是**故意**把话说完的那一条，
    仍在 200 字以内）。fixture 不会触发 20 条上限，所以那条「超出上限」的分支**没有用例覆盖**（见 §7）。
12. **每件都要做变异测试**：**过**。16 处变异，**每一处都让至少一条用例变红**，清单见 §6。

## 5. 移植时对上游做的删改（都不牵涉安全问题，但需要审阅者知道）

1. **ES6 → ES5**：去掉 `async/await`（`extract.js` 改成 `.then()` 链）、模板串、箭头函数、
   对象 / 数组展开、`const` / `let`、`String.prototype.replaceAll`（`parse.js` 里改成
   `String.fromCharCode(92)` 拼出来的写法，源码里不出现反斜杠转义序列）。
2. **参数切分改成「引号 / 括号配对」**：上游对整串做 `match[2].replaceAll(/\.join\(.*?\)/g, "")` ——
   这是**非贪婪的正则替换**，遇到 `"x" + "y" + "z"` 这种拼接表达式会把中间部分整段切掉
   （`fixtures/dirty-args` 的「数理方程」就是这种写法，上游会把它切成 `"x" + "z"`）。
   本件按引号 / 括号配对切参数，拼接表达式正常还原成 `xyz`。
3. **课程名不再按「删掉结尾括号」处理**：上游 `extractCourseName` 用 `/^["']|["']$/` 去引号后
   再 `.replace(/\([^)]+\)$/, "")` ——**任意**结尾括号都会被删掉，于是「高等数学A(一)」会变成
   「高等数学A」。本件只删同族里有实据的课程代码形态（10 位数字.2 位小数，CUIT 的 `cleanCourseName`
   删的就是它），删了写进 `warnings`。fixture：`basic` 的「毕业设计(5034001234.50)」→「毕业设计」，
   而「高等数学A(一)」原样保留。变异测试：`M16`。
4. **不剥响应里的空白**：上游 `fetchWithCleanup` 里有 `return html.replace(/\s/g, "")` ——
   整份 HTML 的空白全删，**引号字符串里的空格也一起没**。本件交原文，解析器容忍空白。
5. **不问用户**：上游 `promptUserToStart`（`showAlert` 公告）+ `getAcademicYear`（`showPrompt` 问学年）
   + `selectSemester`（`showSingleSelection` 问第一 / 第二 / 小学期）全部没有移植。
   本件自动取教务当前学年学期：用户开在课表页时读页面上的学期下拉框（他自己切过的优先），
   否则取一次课表页 HTML 读同样几处。要别的学期，用户在教务页面里切一下再点「提取课表」。
6. **开学日改成「取教务 + 推算 + 提示」**：上游在这里是问用户，本件见 §4 第 6 条。
7. **index 求值去执行**：见 §3。
8. **节的合并只并真正相邻的段**：上游在单个 `TaskActivity` 内取 index 的 min/max ——
   `index = 5*unitCount+0` 与 `5*unitCount+2` 两条会被并成「第 1-3 节」，
   **中间空着的第 2 节也被算进去了**。本件只在前一段的末节 + 1 等于后一段的首节时才合并，
   有间隔就写成两条 block（载荷的 `blocks` 本来就是列表，语义等价而且更准）。
   fixture：`basic` 的「地质学基础」（第 1 节与第 3 节 → 两条 block）。变异测试：`M13`。
9. **排序全部换成确定性比较**：上游没有排序（它按页面顺序），本件按（课名、教师、教室、星期、
   起始节、结束节、周次）逐级比较 —— 不用 `localeCompare`（它排中文的结果跟引擎有关，
   而 fixture 是逐数组比对的），也不依赖排序算法的稳定性。课程顺序按**首次出现的排课行**。
10. **`String.prototype.replaceAll` 没有移植**（Rhino 与旧 WebView 都不保证有）：
    `parse.js` 里的转义还原、空白归一都改成 `split(...).join(...)` 与正则。

## 6. 变异测试记录

改坏是**在内存里**做的（读 `parse.js` → 字符串替换 → `vm.runInNewContext` 求值），
`parse.js` 文件本身从不被改写（跑前跑后的文件内容比对结果：**一致 = true**）。

基线：`basic=GREEN　weeks-bitmap=GREEN　index-forms=GREEN　dirty-args=GREEN`

| # | 把哪一处改坏 | 变红的用例（差异点） |
|---|---|---|
| M1 | 位图第 0 位也当周次（`i === 0` 时 `out.push(0)`） | `basic`（形势与政策多出一条第 0 周的 block）、`weeks-bitmap`（多出一门「第 0 周」的课） |
| M2 | 位图完全不跳过 0 位（改回上游写法） | 同上两条 |
| M3 | index 解析算反（`factor * unit + plus` → `plus * unit + factor`） | 四个用例全部变红 |
| M4 | 不再认「已算好的纯数字」（`/^[0-9]+$/` 那支返回 null） | `index-forms`（「已算好下标课」消失） |
| M5 | 畸形 index 静默算成 0（上游的 `return 0`） | `index-forms`（课程数 4 → 7，多出三门排到周一的课） |
| M6 | 畸形 index 完全静默（不计数、不写 warnings） | `index-forms`（warnings 少一条） |
| M7 | `unitCount` 一律用上游常量、忽略页面值 | 四个用例全部变红 |
| M8 | 教师表达式不再从 `var teachers` 块取名 | `basic`（同一门课被拆成两门）、`dirty-args`（教师类型不同） |
| M9 | 开学日不取教务给的值（一律推算） | `basic`、`index-forms`（firstDay 2026-08-31 vs 2026-09-07） |
| M10 | 开学日不回退到那一周的起始日 | `basic`（2026-09-09 vs 2026-09-07） |
| M11 | 总周数不被课表里更晚的周次抬高 | `basic`、`weeks-bitmap`（totalWeeks 20 vs 30） |
| M12 | 位图超过 30 周的位不再丢弃 | `weeks-bitmap`（「超限课」的 block 从 2 条变 4 条） |
| M13 | 相邻节次的合并放宽成「只要其它字段相同就并」 | `basic`（「地质学基础」的两条 block 被并成一条 1-3 节） |
| M14 | 教师拿不到时写占位符「未知」（上游写法） | `dirty-args`（teacher 类型不同） |
| M15 | 作息表里写坏一整点（`08:45` → `85:45`） | 四个用例全部变红 |
| M16 | 课名不再剥课程代码 | `basic`（`毕业设计(5034001234.50)` vs `毕业设计`） |

第一轮跑时 `M13` 曾经**没有用例变红**（当时的变异只是把「相邻」放宽成「只要有重叠」，
而在同一个 `TaskActivity` 内取到的 index 本来就同属一节，条件永远成立 —— 变异无效）。
按测试方案 §3.1 的规矩（「如果它照旧绿，那它盖的不是你以为的那条路径」），
既改了变异（放宽成「只要其它字段相同就并」，这才真正对应上游的 min/max 语义），
也在 `basic` 里补了一条「同一门课同一天第 1 节与第 3 节、中间隔一节」的排课，
现在 `M13` 与 `M11` 都会让它变红。

## 7. 用例与自验

| 用例 | 打什么 |
|---|---|
| `basic` | 连堂合并（1-2 节 + 3-4 节）、**不相邻的两节不许并**（地质学基础）、完全重复行去重、同一门课的分段周次、单周 / 单周段、教师写成 `actTeachers.join(',')`（从 `var teachers` 块取名）、位图全 0（整段跳过）、位图第 0 位为 1、周次到第 30 周（把教务给的 20 周抬到 30）、**课程名末尾的课程代码**、空教室、教务给了学期起始日期（周三 → 回退到周一） |
| `weeks-bitmap` | 位图基准（第 0 位是占位符 / 第 1 位是第 1 周）、只有第 0 位的空位图、单周段 1-7、双周段 4-10、连堂 + 单周、超 30 周的位丢弃、总周数被抬高、开学日与周数都走推算 |
| `index-forms` | `index = 5*unitCount+2`、`index = 62`、`index = 3 * 10 + 2`（字面节次数，本件认）、`index = 3*unitCount-2`（负偏移，不认）、`index = 星期*unitCount+节次`（不认）、`index = '3 * 10 + 2'`（带引号，不认） |
| `dirty-args` | 教师表达式（前面**没有** `var teachers` 块 → 留空并计数；前面**有**块 → 取名）、页面 `var unitCount = 12` 与常量 10 不一致、课名 / 教室名里带逗号、教室是拼接表达式、index 越界（第 8 天）、参数多于 7 个（第 6 个是裸 `null`）、`args[2]` 是拼接表达式、用到第 12 节要补作息 |

自验方式（**用 node，没有跑 `./gradlew`**）：

- 四对 fixture 在 `vm` 里实跑 `parse.js`，与 `expected` 逐字段比对（键顺序无关、数组顺序有关）：
  **全部 MATCH**（`basic` 9 门课 8 条 warnings、`weeks-bitmap` 6 门课 7 条、`index-forms` 4 门课 5 条、
  `dirty-args` 7 门课 8 条；`totalWeeks` 分别 30 / 30 / 18 / 20，`firstDay` 分别
  2026-09-07 / 2026-02-16 / 2026-09-07 / 2026-08-31）。
- 四份 `expected.json` 按 `JwPayloadCodec.validate` + `JwManifest.validate` 的规则逐条核过
  （`totalWeeks` 1..30、`endWeek ≤ totalWeeks`、`weekType ∈ {ALL,ODD,EVEN}`、时间 `HH:mm` 且不逆序、
  `warnings` ≤ 20 条且每条 ≤ 200 字 —— 实测最长 149 字、`firstDay` 是 `yyyy-MM-dd`、
  `periodIndex ≥ 1`）：全部通过。
- `extract.js` 用桩 `window` / `document` / `DOMParser` / `fetch` 跑了上面那张表的六个场景
  （CI 里跑不到的那一段）：请求条数与顺序与 §1 一致，挂 `/webvpn` 前缀时路径跟着前缀走，
  交出的键（`today` / `term` / `unitCountPage` / `ids` / `tableHtml`）与 `parse.js` 的读法一一对齐，
  日历取不到时 `term.startDate` / `term.weekCount` 为 `null` 且不抛异常，
  「没有 ids」与「学期列表为空」两种失败各给了**不同**的、带下一步动作的报错。
- ES5 / token 检查：`parse.js` 与 `extract.js` 里 `=>`、反引号、`\blet\s`、`\bconst\s` 命中为 **0 行**
  （**注释里也没有**——这条 CI 查的是整份文件字符串）；两个文件都不含 `eval` 这个词
  （注释里提到上游写法时一律描述为「函数构造器」/「动态代码求值」，`AUDIT.md` 里需要引用原文，
  不受这条限制）。
- NUL / BOM / 其它控制字符：两个脚本 + `manifest.json` + 8 个 fixture 共 11 个文件，**全部为 0**
  （fixture 里的 HTML 用单引号 / 双引号字面量写，全程没有在工具入参里出现反斜杠转义序列）。

## 8. 已知边界与没做的事

- **fixture 是合成的**：按上游实际读取的形状（`var teachers=[...]`、`activity = new TaskActivity(...)`、
  `index = …`、`var unitCount = N`）编出来的形状正确的数据，**不是真实抓取**，
  课名 / 教师 / 教室 / 日期均为虚构（测试方案 §3）。它只保证「同样的输入永远得到同样的输出」，
  **不保证真实页面上解析正确**。拿到真实 dump 后请替换 fixture 并重跑门。
- **上游的哪些写法在本校真实数据里出现**：没有账号，验不了。本件按「能认的都认、认不出就出声」处理：
  `index` 认两种（含 unitCount 已被替换成数字的形态），别的跳过并计数；
  教师表达式认 `join(...)` / `actTeacherName`（从 `var teachers` 块取名），别的留空并计数。
- **`index` 的第三种写法（负偏移等）会被跳过**：见 §3.4 末尾的取舍说明。
- **`warnings` 超过 20 条的分支没有用例覆盖**：四份用例都在 5-8 条。真机上一次导入同时
  触发（位图异常 + index 认不出 + 教师表达式 + 课名代码 + 重复行 + 越界 + 补作息）时才会走到，
  该分支的写法与同族 `qdhhc` 一致（保留前 19 条 + 一条汇总）。
- **`base/calendar-info.action` 的返回形态**：只按同族 ZUA 的写法认
  「开始 / 结束日期：YYYY-MM-DD ~ YYYY-MM-DD (N)」这一种（去掉标签后正则匹配）。
  真实部署若换字段名或格式，会退化成推算 —— **有 warning，不静默**。
- **学期列表的响应信封**：只认 `{semesters:{学年:[{id,schoolYear,name}]}, semesterId}` 与
  外裹一层 `{datas:{...}}` 两种。认不出时报「教务系统没有返回可用的学期列表」并提示重新登录，
  不会静默用一个猜的学期。
- **`unitCount` 的取值上限**：页面值不在 1..20 之间时当作读不到、回落常量 10 并写 warning
  （防止把 `var unitCount = 0` 这种占位值当成真值，那会让 index 换算全部变成 NaN）。
- **`extract.js` 里那个 `semesterId` 只认纯数字**：`ids` 与 `semesterId` 都是 `/^[0-9]+$/` 才收，
  因为课表接口把它们当纯数字参数用；万一学校用的是带字母的学期 id，会退化成「列表第一个学期」
  （§7 的 `pickSemester`），并在学期名 `warnings` 里体现（用户看到的是别的学期名，可以直接反馈）。
- **`today` 由 `extract.js` 取本机日期**：解析链路里只有「学年也读不出来」这一条兜底分支会用到它，
  那条分支不能进 fixture（用例会随日期失效）。fixture 里给的都是固定的 `today`。
- **上游的 `semesterCalendar` 里没有起止日期**：这是同族 12 个脚本共同的实情（本件新增的
  日历接口就是为它补的）。若真机上该接口也不可用，开学日只能推算，`warnings` 里会说。
- **需要真实环境才能验的**：登录后的会话是否被这四条接口接受、`var unitCount` 与
  `bg.form.addInput(form,"ids",…)` 在课表页 HTML 里的真实形态、`unitCount` 的实际值（是 10 还是别的）、
  那张 10 节作息表与学校作息是否一致、`loginUrl` 给的是 http（应用会显示「不安全连接」标记，
  学校如果也开了 https，维护者可以直接换成 https）。

## 9. 签名

- 上游作者：`whynusn`（shiguang_warehouse，MIT）
- 移植：`0x7E-2023`
- 日期：2026-09-16
