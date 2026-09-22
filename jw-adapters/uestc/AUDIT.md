# 安全审计 —— 电子科技大学（树维 EAMS 平台）

审计对象：`jw-adapters/uestc/` 下的 `extract.js` / `parse.js`，以及它们移植自的上游脚本
`shiguang_warehouse` 的 `UESTC/uestc.js`
（快照 commit `e62554a4034386b893bcd6813c7b2b64f8c730a3`，2026-09-12，MIT，上游作者 `CorunLing`）。

- 移植者：`0x7E7-2023`
- 移植日期：2026-09-16 ~ 2026-09-17
- 适配器 key：`uestc`
- 平台：树维 EAMS（`/eams/`，上海树维信息科技有限公司 SupWisdom，新开普子公司）。
  **本文件不写「强智」** —— 强智的路径是 `/jsxsd/`、登录页署名「湖南强智科技发展有限公司」，
  与 `/eams/` 不是一套；同族 13 个上游脚本也全都自称树维。

审计方式：逐行读上游 `uestc.js`（15K）与同族 6 个脚本（`HPU` / `NEUQ` / `ZUA` / `ZZVCAE` /
`CUIT` / `HUNNU`，用来核接口路径与参数形态）＋ 逐行读移植件 ＋ 静态扫描：

```
grep -nE "https?://|fetch\(|XMLHttpRequest|sendBeacon|new WebSocket|\.src\s*=|localStorage|sessionStorage|document\.cookie|password|pwd|eval\(|new Function" jw-adapters/uestc/*.js
grep -nE "=>|`|\blet |\bconst " jw-adapters/uestc/*.js     # ES5 闸门（CI 同款判据）
```

## 1. 请求域与请求清单（本节必须与代码一致，不许写得比代码窄）

上游 `uestc.js` 里**只有一个**绝对 URL：`https://eams.uestc.edu.cn`（它那条 `fetch` 写的是
`/eams/courseTableForStd!courseTable.action` 相对路径，但主机在文件头与提示文案里写死）。
移植件**没有**沿用绝对地址：所有地址都由 `originOf()` + 上下文路径 `/eams` 拼出来
（`eamsBase()` 会在当前地址里找 `/eams/` 这一段并保留它之前的前缀）。所以两个脚本请求的主机
只有**用户当前所在的那一个教务主机**，**`allowHosts` 留空**，不写通配。

| 谁 | 方法 | 地址 | 干什么 | 取不到时 |
|---|---|---|---|---|
| extract.js | GET | `<origin><前缀>/eams/courseTableForStd.action?sf_request_type=ajax` | 读学号参数 `ids`、学期组件 id `semesterBar<digits>Semester` 与它当前的值（当前学期号）。**只在当前页面 DOM 里读不全时才发**（同族 HPU / ZUA / NEUQ / CUIT 的入口请求就是它，上游 `uestc.js` 只从 DOM 读同两个字段、不发这条） | 线索不足时抛错并提示「先在教务里打开我的课表」 |
| extract.js | POST | `<origin><前缀>/eams/dataQuery.action?sf_request_type=ajax`，体 `tagId=<学期组件id>&dataType=semesterCalendar` | 学期列表原文（学年 + 第几学期 + 学期号 + 当前学期号）。**本件新增**（上游不读学期列表） | 交 `null`（学期名与当前学期号退到页面上读到的值） |
| extract.js | POST | `<origin><前缀>/eams/courseTableForStd!courseTable.action`，体 `ignoreHead=1&setting.kind=std&startWeek=&project.id=1&isEng=0&semester.id=<学期号>&ids=<学号>` | 课表 HTML 全文。**请求体与上游逐字一致**（含上游那两个 `project.id` / `isEng`） | 抛错并提示重新登录 |
| extract.js | POST | `<origin><前缀>/eams/base/calendar-info.action`，体 `version=1&semesterId=<学期号>` | 学期日历 HTML（起止日期 + 总周数）。**本件新增**（同族 ZUA / ZZVCAE 用的同一接口） | 交 `null`，parse 回落到推算并写进 `warnings` |

- 四条请求**同源、全部只读**；只有课表那一条的方法、路径、请求体与上游逐字一致，另外三条是本件为了
  「不问用户 + 有开学日」而新增/保留的（下面逐条说明依据）。
- **新增两条的依据与边界**：① `dataQuery.action` + `dataType=semesterCalendar` 是**同族 10 个上游脚本
  （HPU / NEUQ / CUIT / HFNU / XATU / TJAU / DLMU / ZUA / ZZVCAE / YANGTZEU）都在用的同一个接口**，
  `tagId` 也是同一个 `semesterBar\d+Semester` 形态 —— 上游 `uestc.js` 没读它，是因为它靠
  「学期号 483 + 每学期 +20」硬推学期列表再问用户，而不是因为该校没这个接口；
  ② `base/calendar-info.action` 是 ZUA / ZZVCAE（同族、同 `/eams/`、同 `courseTableForStd` 链路）
  正在用的学期日历接口；上游 `uestc.js` 不读它，代价是它**根本没有开学日**（见 §4 第 6 条），
  而我们的 `firstDay` 是必填的。两条都只**读**、都打在本校教务主机上，且失败一律交 `null`。
- 请求头只带 `Content-Type` / `X-Requested-With` / `Accept`，**不带任何自定义令牌**；
  Cookie 由 WebView 按同源规则自己带上，脚本不读也不写它。
- `parse.js` 不发任何请求、不碰 DOM（CI 里用 Rhino 实跑，是纯函数）。

**读到的数据里有什么 / 交出去什么**（`extract.js` 交出的键就是全部）：

| 来源 | 读什么 | 交出去什么 |
|---|---|---|
| 当前页面 | `form input[name="ids"]`、`form input[name="params"]` 里的 `ids=`、`[id$="Semester"]` 里符合 `semesterBar\d+Semester` 的那一个（它的 `value` 与选中项文本） | 只交 `semesterId` / `semesterLabel` / `tagId`。**学号不交出去**（只用它拼课表请求体，`extract.js` 里当场用完就丢），fixture 里也没有 |
| 课表响应 | 课表 HTML 全文 | 原样转交（课程是内嵌的 `TaskActivity` JS 块）。这是**用户自己的课表**，不含身份证号、家庭住址等学籍信息 |
| 学期列表响应 | 学期列表原文 | 原样转交（学年 + 第几学期 + 学期号）。是**学校的公共学期表** |
| 学期日历响应 | 学期日历 HTML | 原样转交（起止日期 + 总周数）。是**学校的公共校历** |

`extract.js` 另外交出 `today`（取数当天的 ISO 日期）。它不进载荷，只是给 `parse.js` 当推算开学日的
基准 —— 让 `parse.js` 自己去问系统时间的话，回归用例就钉不住了。

## 2. 移植手册 §5 八条逐条

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 不碰凭据 | **过**。两个脚本都没有 `password` / `pwd` / 登录表单值 / `localStorage` / `sessionStorage` / `document.cookie` 访问（静态扫描无命中）。唯一的「凭据」是浏览器自带的会话 Cookie，脚本既不读也不外发。**学号 `ids` 是从页面读的**（上游也这么做），但它只用来拼课表请求体，既不交给 `parse.js`、也不进载荷与 fixture |
| 2 | 不外发 | **过**。全部 `fetch` 都在 `extract.js` 里（一个 `request()` 函数，四个调用点），地址全部由 `window.location.origin` + 上下文路径拼成；没有 `sendBeacon` / `WebSocket` / `EventSource` / `new Image().src` / 隐藏表单，也没有任何第三方域 |
| 3 | 请求域可控 | **过**。请求主机只有一个：用户当前打开的那个教务主机（`loginUrl` 的主机 `eams.uestc.edu.cn`，或学校把教务挂上去的门户 / 网关主机）。`allowHosts` **留空**，不写通配、不写 `*.uestc.edu.cn` |
| 4 | 只读课表 | **过**。第一条（课表页入口）与第三条（课表）都在 `courseTableForStd*` 上；第二条是课表页自己的学期下拉组件；第四条是**学期日历**（学校的公共校历）。**不碰成绩、学籍、个人信息、缴费、选课**等任何其它接口。四条全是 POST/GET 的**读**，没有一条会改状态（课表接口是查询接口，`ignoreHead=1` 是上游原样带的展示参数） |
| 5 | 不埋点 | **过**。没有任何统计 / 上报 / 遥测，也没有 `img` 打点 |
| 6 | 不 eval 远程代码 | **过**。两个脚本里**没有** `eval` 与动态函数构造。上游 `uestc.js` 本来也没有，但**同族的 DLMU 用构造函数求值、HAUST 用 eval 解析响应**，本件**没有抄**这两处：`index` 表达式（`index = 5*unitCount+2` 与已经算好的 `index = 62`）一律用**正则**解析；学期列表原文是内嵌 JS 对象字面量（键没引号，不是 JSON），同族 7 个脚本都用构造函数求值，本件改成「先 JSON.parse，失败再按对象字面量逐段正则取字段」**不执行**远程代码 |
| 7 | 不写页面 | **过**。`extract.js` 只**读**当前页面里的表单值、学期组件与它的选中项；取课表页 HTML 时用的是 `fetch`（响应文本用正则解析，**没有 `DOMParser` 插入页面、也没有写回 DOM**）。上游的 `showAlert` / `showToast` / `showSingleSelection` / `saveImportedCourses` / `savePresetTimeSlots` / `saveCourseConfig` / `notifyTaskCompletion` 这些桥调用**全部没有移植** |
| 8 | 不依赖用户输入之外的秘密 | **过**。没有硬编码密钥、令牌或他人学号。脚本里的常量只有上下文路径 `/eams`、`sf_request_type=ajax`、`dataType=semesterCalendar`、`version=1` 这些公开页面参数 |

**结论：可以进内置库。** 四条请求全部落在本校教务主机上，只读课表（外加学期列表与学期日历两条
只读的公共数据接口），不碰凭据、不外发、不埋点、不写页面。

## 3. 本批 12 条检查表逐条

1. **周次位图的下标基准**：**过（按本批统一口径）**。`bitmap[i] === '1'` 且 `i >= 1` → 第 `i` 周；
   `bitmap[0]` 是占位符，为 `'1'` 时**不产出「第 0 周」**，改为写一条 `warnings`。
   上游 `uestc.js` 写的是「先 `weeks.push(i)` 再 `.filter(w => w > 0)`」——**等价**，区别只在下标 0
   为 `1` 时本件会出声（上游静默忽略）。位图长度按 `min(长度, 54)` 截断。
   fixture：`weeks-bitmap`（一条「第 0 位为 1」→ 忽略并出声、一条「第 1 位置位」、
   一条「只有第 0 位置位」→ 整条记录丢掉、一条 54 位）。变异：M01 → `weeks-bitmap` 变红。
2. **`TaskActivity` 的参数位**：**过**。`args[3]`=课程名、`args[5]`=教室、`args[6]`=周次（与同族一致）。
   `args[1]`（教师）**有两副面孔**：字面量，或 `actTeachers.map(...).join(",")` 这类表达式 ——
   上游对它直接取值（于是表达式原文会被当成教师名）。本件把四种形态都剥开：
   字符串字面量、数组字面量（`["张三","李四"]` 与 `[{name:"张三"}]`）、变量（在它之前就近取最后一次赋值）、
   `X.join(sep)` / `X + "后缀"`；剥不开的**留空并写进 warnings**（教师留空、教室留空都出声）。
   fixture：`basic`（拿到数组 → 剥出真名）、`edge-bitmap-variants`（教师数组字面量、教师剥不开）、
   `edge-expression-args`（课程名剥不开、教室剥不开）。变异：M07 → `basic`+`edge-bitmap-variants`；
   M19 / M21 → `edge-expression-args`。
3. **`index` 的两种写法**：**过，且不用 eval**。`index = 5*unitCount+2`（带变量）与 `index = 62`
   （已算好）都用正则解析，后者按 `day = floor(n/unitCount)+1`、`period = n%unitCount+1` 换算。
   记录归属沿用上游的「每个 `index` 归属于它前面最近的那个 `TaskActivity`」，另外还认把
   `index = ...` 与课程名写在一起的形态。fixture：`edge-bitmap-variants`（`index = 14;`）。
   变异：M09 → `edge-bitmap-variants` 变红。
4. **`unitCount` 要真的读**：**过**。从 HTML 里正则读 `var unitCount = N;`（上游缺省 12）；
   **读不到时按 12 并同时写进 `warnings`**（「节次数是猜的，请在导入预览里核对节次」）。
   fixture：`edge-bitmap-variants`。变异：M06 → `edge-bitmap-variants` 变红。
5. **作息时间从哪来**：**过，但来源是上游内置表**。`PERIOD_TIMES` 就是上游 `DEFAULT_TIME_SLOTS`
   那张 12 节表（第 1 节 `08:30-09:15`），**不是从教务页面读的** —— 真机核对时请对照教务处公布的
   作息；不符就改 `parse.js` 里那一张表。每一条都过 `HH:mm` 与 `00:00-23:59` 校验
   （`/^([01]\d|2[0-3]):[0-5]\d$/`）且要求结束晚于开始，不合法的那一节被丢弃并写进 `warnings`；
   课表里用到、但内置表里没有的节次也会出声。fixture：全部 8 个用例都带 `periodTimes`。
   变异：M15（写坏一个整点）与 M23（越界到 `25:00`）→ **8 个用例全部变红**。
6. **开学日**：**过**。上游**根本不写开学日**（它只 `saveCourseConfig({semesterTotalWeeks: 20})`），
   所以本件是「优先取教务、取不到推算、两种都说清楚」：
   ① 教务的学期日历（`/eams/base/calendar-info.action`，同族 ZUA / ZZVCAE 在用）里的学期开始日
   **回退到那一周的周一**（移植手册 §4.3，`firstDayOfWeek` 缺省 1）→ `warnings` 说明取自日历；
   ② 日历取不到 / 解析不出 / 日期跨度反转 → 按取数当天的**最近周一**推算 → `warnings` 说明；
   ③ 日历被判为不可信时额外再出一条「没读到教务的学期日历」。
   fixture：`basic` / `weeks-chinese` / `edge-bitmap-variants`（取日历）、`weeks-bitmap` / `edge-fallback` /
   `edge-calendar-invalid`（推算）、`edge-calendar-nonmonday`（日历给周三 → 回退到周一）。
   变异：M04（不回退到周一）→ `edge-calendar-nonmonday`；M22（不校验日期跨度）→ `edge-calendar-invalid`。
7. **周次上限**：**过**。位图最长 54 位，算出来可能到第 54 周 —— 超过载荷上限 30 的一律 clamp 到 30
   并写进 `warnings`，同时把越界的 block 收进 `[start, 30]`（否则整包会被 `JwPayloadCodec` 拒掉）。
   注意 `MAX_TOTAL_WEEKS` 是 `core/model` 的常量 30（不是同批次三口径里的 20）。
   fixture：`edge-bitmap-variants`（第 32 周 → 总周数 30 → block 28-30）。变异：M10 → 该用例变红。
8. **`teacher` / `location` 拿不到就留空**：**过**。空教师是 `null`、空教室是 `null`，
   不写「未知教师」这类占位符（上游 `uestc.js` 写的是空串，比同族的 HPU / XATU / TJAU / HFNU 好；
   本件在载荷层统一成 `null`）。fixture：`edge-fallback` 的「无教师无教室」、
   `edge-expression-args` 的「表达式教室」（教室 `null`）。变异：M07（教师写表达式原文）→ 变红。
9. **学期名**：**过**。优先用教务页面上学期组件的文字（教务自己写的最准，须匹配
   「20xx-20xx 学年 第 N 学期」）；其次用学期列表原文里对应当前学期号那条的「学年 + 第几学期」；
   都没有才用「**电子科技大学** + 按导入日期推算的学年学期」，并写进 `warnings`。
   **没有拿适配器名当学期名**。fixture：`basic` / `weeks-chinese` / `edge-bitmap-variants`
   （学期列表原文）、`edge-fallback`（完全没有 → 「电子科技大学 2026-2027学年第一学期」）。
   变异：M17 → `edge-fallback` 变红。
10. **`allowHosts`**：**过**。本件**一条绝对 URL 都没有**（连上游写死的那个主机名也没留），
    地址全部由 `window.location.origin` + 上下文路径 `/eams` 拼出，所以 `allowHosts` 留空，
    不写通配。脚本**不使用** OCR / 提问桥（源码里不出现 `__ncOcr` / `__ncOcrGrid` / `__ncSelect` 等
    全局名），所以「`*.` 通配会被 `JwOriginRules` 跳过、拿不到桥」这条对本件不构成影响。
11. **`warnings` 上限**：**过**。`warn()` 逐条按 200 字截断（超出补「…」），条数超过 20 时保留前 20 条。
    8 个用例实测 **6 / 7 / 5 / 13 / 10 / 6 / 5 / 4 条**，最长 77 字，都在限内。
12. **变异测试**：**过**。见 §5，23 处变异**每一处都至少让一条用例变红**（没有一处是「改了也不红」）。

## 4. 移植时对上游做的删改（都不牵涉安全问题，但需要审阅者知道）

1. **ES6 → ES5**：去掉 `async/await`（改成 Promise 的 `then` 链）、模板串、箭头函数、对象展开与
   块级变量声明。`parse.js` 是纯同步函数。
2. **切两段**：上游在同一个脚本里取数 + 解析 `TaskActivity` + 算周次 + 合并课程 + 存配置。
   本件把取数留在 `extract.js`（只交出课表 HTML 全文、学期列表原文、学期日历 HTML、当前学期号与
   `today`），**课程解析、周次解析、合并、开学日、总周数、作息表全部落在 `parse.js`** ——
   这样这段逻辑在 CI 里才有真回归。
3. **不问用户**：上游 `showAlert` 公告 + `showSingleSelection` 让用户从「按学期号猜出来的 ±4 个学期」
   里挑（学期号还是写死的 `semesterBase 483` / `semesterStep 20`）。本件改成**自动取教务当前选中的
   那个学期**：先读当前页面上的学期组件，读不到就取一次课表页 HTML 读同一个组件；
   载荷里用 `warnings` 说明「只导入了当前学期」。**上游那套「按学期号猜学期」的写法一行没有移植。**
4. **地址不再写死**：上游写死 `https://eams.uestc.edu.cn`；本件按当前 origin + 上下文路径拼
   （见 §1）。副作用是 http/https 与门户前缀都跟着用户打开的地址走。
5. **中文周次那条路按注释声明的语义实现（这是本件最需要真机核对的一处）**：
   `parseWeeks` 先判 `/^[01]{20,54}$/`，是位图走位图，否则走 `parseChineseWeeks`。
   **上游 `parseChineseWeeks` 的三段正则有先后顺序问题**：第一段 `/(?:连)?(\d+)\s*-\s*(\d+)/`
   没有锚点、也没有排除「单 / 双」字样，于是 `单3-17` 会**先**被它匹配成「3-17 每周都上」，
   后面的 `单(\d+)` 分支根本轮不到 —— 而它的注释写的是 `'单1-17' → [1,3,5,...,17]`。
   本件按**注释声明的语义**实现：**单 / 双 优先于连续区间**。差别是实打实的：
   `单3-17` 上游给 `[3..17]`，本件给 `[3,5,...,17]`；`双2-8` 上游给 `[2..8]`，本件给 `[2,4,6,8]`。
   fixture：`weeks-chinese`（`连1-16` / `单3-17` / `双2-8` / `1-3,5-9周` / `单周1-9` / `3、5、7周`）。
   变异：M02（忽略单双标记）与 M03（单周失效）→ 该用例变红。
   另外中文那条路额外认「、」「；」作分段符、认「～ 至 到 ~ — – − －」作区间号（上游只认半角 `-` 与 `,`）。
6. **开学日与总周数**：上游只有写死的 20 周、**没有开学日**。本件加了 `base/calendar-info.action`
   （见 §3 第 6 条），拿不到就推算并如实说明。
7. **课程合并保留上游语义**（按「课程名 + 教师」聚合；天 + 教室分组；相邻节次且**周次重合度 ≥ 30%**
   才并成一段；教室为「停课」的记录跳过并计数），但两处改动：
   ① **排序换成确定性比较** —— 上游用 `localeCompare` 排中文，Rhino 与 V8 的结果未必一致，
   而 fixture 是**逐数组比对**的；
   ② **课程按首次出现的顺序排**（上游按合并后的顺序），block 按
   （星期、起始节、结束节、起始周、结束周、单双周、教室）排 —— 载荷语义与顺序无关，
   但这样人工核对 fixture 不用在脑子里跑一遍排序。
   另外：上游 `normals` 为空（整组都是「停课」）时 `continue` 是**静默**丢课，本件改成计数 + `warnings`。
8. **课程名清洗沿用上游规则**：去掉末尾半角括号里的课程代码（`大学物理Ⅱ(D1200440.18)` → `大学物理Ⅱ`）。
   全角括号的课名（`高等数学（二）`）不动。
9. **`index` 与记录的归属**：上游从整份 HTML 找所有 `index = ...`，再「归给前面最近的那个
   `TaskActivity`」，但**没有在上一个活动与下一个活动之间设边界**，`index` 会跨活动归错；
   本件改成在**两个活动声明之间**的那一段里找 `index`，并在这段里遇到
   `tableN.marshalTable` 就截断（那是页面渲染表格的位置）。

## 5. 变异测试记录

改坏是**在内存里**做的（读 `parse.js` → 字符串替换 → `vm` 求值），`parse.js` 文件本身从不被改写
（跑前跑后 `sha256` 都是 `3cbaf36006dd5c0e8dea555e0d658226092c108c161e7c6961bf05600e3b91dd`）。

基线：`parse.js` sha256 前后一致 · 8/8 用例 MATCH。

| # | 把哪一处改坏 | 变红的用例 |
|---|---|---|
| M01 | 位图基准改成从下标 0 开始收（本批统一口径的反面） | `weeks-bitmap` |
| M02 | 中文周次的单双标记整个忽略（= 上游「单3-17」上的实际行为） | `basic`、`weeks-chinese`、`edge-fallback` |
| M03 | 中文单周按「每周都上」算（单周标记失效） | `weeks-chinese`、`edge-fallback` |
| M04 | 开学日不回退到周一（直接拿日历上的日期当 `firstDay`） | `edge-calendar-nonmonday` |
| M05 | 总周数忽略学期日历，一律用内置 20 | `edge-calendar-nonmonday` |
| M06 | `unitCount` 读不到时静默按 12（不出声） | `edge-bitmap-variants` |
| M07 | 教师剥不开时把表达式原文当教师名（上游 `args[1]` 的取值方式） | `basic`、`edge-bitmap-variants` |
| M08 | 「停课」记录不再跳过 | `basic`、`edge-bitmap-variants`、`edge-fallback` |
| M09 | 线性下标写法（`index = 62`）不再识别 | `edge-bitmap-variants` |
| M10 | 超过 30 周的周次不截断 | `edge-bitmap-variants` |
| M11 | 相邻节次的周次重合度闸门取消（周次不相交的两节也合并） | `basic` |
| M12 | 极大段的单双周类型一律写成 `ALL` | `basic`、`weeks-bitmap`、`weeks-chinese`、`edge-fallback` |
| M13 | 位图第 0 位为 1 时不再出声（静默忽略） | `weeks-bitmap` |
| M14 | 课程名末尾的课程代码不再去掉 | `basic` |
| M15 | 作息表里写坏一个整点（第 1 节结束改成 `85:45`）—— 合法性校验要拦下它 | **8 个用例全部变红** |
| M16 | 课表里更晚的周次不再抬高总周数 | `edge-bitmap-variants` |
| M17 | 学期名不推算（拿不到就写适配器名） | `edge-fallback` |
| M18 | 节次超出 `unitCount` 的记录不再计数（静默丢课） | `edge-bitmap-variants` |
| M19 | 课程名剥不开时静默丢掉（不出声） | `edge-expression-args` |
| M20 | `args[6]` 一律当位图（中文那条路整个不认） | `basic`、`weeks-chinese`、`edge-bitmap-variants`、`edge-fallback` |
| M21 | 教室剥不开时静默留空（不出声） | `edge-expression-args` |
| M22 | 学期日历的日期跨度不再校验（`end < start` 也照收） | `edge-calendar-invalid` |
| M23 | 作息时间越界（第 1 节结束写成 `25:00`）不再被 `HH:mm` 校验拦下 | **8 个用例全部变红** |

**23/23 每一处都至少让一条用例变红。** 其中 M15 / M23 之所以全红，是因为每个载荷都带
`periodTimes` —— 这道闸确实在拦。

## 6. 用例与自验

| 用例 | 打什么 |
|---|---|
| `basic` | 连堂合并（两节同周次 → 1-2 节）、周次不相交的两节**不**合并、中文周次（`双2-8`）、教师是 `join` 表达式（剥出 `周明`）、「停课」跳过、课程代码清洗、位图 1-16 与 1-4、学期日历（2026-09-07 起 20 周）、内置 12 节作息表 |
| `weeks-bitmap` | 位图基准对照：第 0 位为 1（忽略并出声）、第 1 位置位、53 位整学期、中间断档、只有第 0 位置位（整条丢）、54 位；没有学期日历（开学日与总周数都推算） |
| `weeks-chinese` | **uestc 独有的中文周次那条路**：`连1-16` / `单3-17` / `双2-8` / `1-3,5-9周` / `单周1-9` / `3、5、7周` |
| `edge-bitmap-variants` | `unitCount` 读不到、`index` 写成已算好的线性下标、教师数组字面量、教师剥不开、课程名用变量拼、第 32 周要截到 30、节次/星期越界、没有 `index` 的记录、参数不足 7 个、中文描述认不出 |
| `edge-fallback` | 学期日历与学期列表**都**取不到（学期名与开学日都推算；当天周四 → 回退到周一）、空教师与空教室、「停课」、中文单周、全零位图 |
| `edge-expression-args` | 课程名与教室都写成剥不开的脚本表达式：课程名剥不开的整条跳过、教室剥不开的课还在但教室留空，两种情况都出声 |
| `edge-calendar-invalid` | 学期日历解得出日期但**结束早于开始** → 整份日历判为不可信，开学日与总周数都退到推算 |
| `edge-calendar-nonmonday` | 学期日历第 1 周从 2026-09-09（周三）开始 → `firstDay` 必须回退到那一周的周一 2026-09-07；总周数取日历的 19 周 |

自验方式（全部用 node，**没有跑 `./gradlew`**，也没有跑任何进程级命令）：

- 8 个用例在 `vm` 里实跑 `parse.js`，与 `expected` 逐字段比对：
  **8/8 MATCH**（`JSON.stringify` 全等，数组顺序也一致）。
- 8 个 `expected.json` 按 `JwPayloadCodec.validate` 的规则另外独立核过一遍
  （`specVersion` / `kind`、`warnings` ≤ 20 条且每条 ≤ 200 字、
  `totalWeeks` 1..30、`firstDay` 是 `yyyy-MM-dd`、时间 `HH:mm` 且不递增、`endWeek ≤ totalWeeks`、
  `weekType ∈ {ALL,ODD,EVEN}`、`startPeriod ≤ endPeriod`）：**全部通过**。
- `extract.js` 用桩页面 + 桩 `fetch` 跑过三遍（CI 里跑不到的那一段）：**请求条数与顺序与 §1 一致**
  （已开在课表页 = 3 条 POST；不在 = 先 1 条 GET 再 3 条 POST；挂在 `/webvpn/eams` 前缀下时
  四条路径都跟着前缀走），交出的键与 `parse.js` 的读法对得上，
  **载荷里不含学号**（`/"ids"|\b20\d{8}\b/` 对 extract 的返回串为 `false`）。
- ES5 自检（CI 同款判据：整个文件字符串里查 `=>`、反引号、`\blet\s`、`\bconst\s`）：
  `parse.js` / `extract.js` **bad lines 为空**。
- NUL 与其它控制字符自检：`parse.js` / `extract.js` / 16 个 fixture 文件 **NUL 计数 0、
  其它控制字符 0、无 BOM**。

## 7. 没能确定的事与已知边界（交接给下一位）

- **fixture 是合成的**：本校没有可用的测试账号，课表 HTML 是按上游与同族适配器实际解析的形态
  （内嵌 `TaskActivity(...)` + `index = 天*unitCount+节次`）编出来的，**不是真实抓取**
  （见 `docs/jw-adapter-testing.md` §3）。课名、教师、教室、日期均为虚构，无真实个人信息。
  **代价：只保证「同样的输入永远得到同样的输出」，不保证真实页面上解析正确。**
  拿到真实 dump 后请替换 fixture 并重跑门。学号按 §1 的承诺**没有**进 fixture。
- **中文周次那条路的语义是按上游注释实现的，不是按上游代码**（见 §4 第 5 条）。
  真机上若发现某门课明明写着「单3-17」却每周都有课，或者反过来少了课，先看这里 ——
  改回去只需要动 `weeksFromChinese` 里那一个判单双的分支。
- **`args[1]` 的表达式剥法**：支持字符串 / 数组字面量 / 变量（就近取最后一次赋值）/
  `X.join(sep)` / `X + "后缀"`。更复杂的拼法（`a.map(f).join(",") + "老师"`、
  三元表达式、函数调用）会**留空并出声**，绝不把表达式原文当教师名。
  **上游对 `args[1]` 是直接取值的**，所以这一条是我们比上游严格的地方，不是「修 bug」。
- **`dataQuery.action` 的请求参数**：本件用的是最简形态
  `tagId=<id>&dataType=semesterCalendar`（HPU / NEUQ / CUIT / HFNU / XATU / TJAU 都是这一形态）。
  ZUA / ZZVCAE 还多带 `value=<当前学期号>&empty=false`。真机上若学期列表返回空，
  先试加上 `empty=false`（ZUA 形态）。取不到不影响导入（有 `warnings`）。
- **`base/calendar-info.action` 是否装有**：本校有没有装这个菜单未知（同族 ZUA / ZZVCAE 在用）。
  取不到时开学日与总周数都走推算，会连出两条 `warnings`。**这是本件最需要真机确认的一条** ——
  如果能确认该校有别的学期日历接口，改 `extract.js` 里那一条即可。
- **作息时间来自上游脚本内置表**，不是从教务页面读的：`PERIOD_TIMES` 就是上游 `DEFAULT_TIME_SLOTS`
  那张 12 节表（第 1 节 `08:30-09:15`）。真机核对时请对照教务处公布的作息；不符改
  `parse.js` 里那一张表。**同族 HPU 是从课表表头的「第N节(HH:mm-HH:mm)」动态读的**，
  如果本校课表页表头也带时间，可以照那条路升级（本件没做，因为没有页面样本）。
- **合并的「周次重合度 ≥ 30%」是上游的启发式**（相邻节次的周次不同时不会并成一段）。
  保留原样，是因为没有真实数据判断它在该校是否合理；真机上若发现连堂课被拆成两条，
  先看这里（`ratio < 0.3`）。
- **学期名从学期列表原文取时用的是「学年 + 第几学期」**（如「2026-2027学年第一学期」）。
  上游 `uestc.js` 自己那套「483 + 每学期 20」的学期名生成**没有移植**（它要用户先选学期）。
- **`loginUrl` 写的是 `https://eams.uestc.edu.cn/eams/loginExt.action`**（`/eams/loginExt.action`
  是同族多校在用的 EAMS 登录路径）。学校如果实际用的是 `/eams/login.action`，
  维护者改 manifest 一行即可 —— 它只影响「一键打开登录页」，不影响取数（取数一律同源相对路径）。
- **需要真实环境才能验的**：登录后的会话是否被这四条接口接受、课表页 HTML 里是否真有
  `semesterBar<digits>Semester` 组件与 `ids` 表单字段、课表接口是否接受上游那套请求体
  （含 `project.id=1` / `isEng=0`）、`unitCount` 在真实页面里的值（上游缺省 12）、
  位图在真实数据里的长度与下标基准。

## 8. 签名

- 上游作者：`CorunLing`（shiguang_warehouse，MIT）
- 移植：`0x7E7-2023`
- 日期：2026-09-16 ~ 2026-09-17
