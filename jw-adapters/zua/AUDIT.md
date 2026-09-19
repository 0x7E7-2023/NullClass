# 安全审计 —— 郑州航空工业管理学院（树维 EAMS 平台，`zua`）

审计对象：`jw-adapters/zua/extract.js` + `parse.js` + `manifest.json` + `fixtures/`，
以及它们移植自的上游脚本 `shiguang_warehouse` 的 `ZUA/zua.js`
（快照 commit `e62554a4034386b893bcd6813c7b2b64f8c730a3`，2026-09-12，MIT，
上游 `resources/ZUA/adapters.yaml` 的 `maintainer` 是 `xBefore`）。

- 移植者：`0x7E-2023`
- 移植日期：2026-09-17
- 适配器 key：`zua`
- 平台：**上海树维信息科技有限公司（SupWisdom，新开普子公司）的综合教务系统**（路径带 `/eams/`）

审计方式：逐行读上游 `ZUA/zua.js`（469 行）与两个移植件，并跑移植手册 §5 给的静态扫描。

---

## 0. 先说三件必须在审计里讲清楚的事

1. **平台名不是「强智」。** `/eams/` 是树维（SupWisdom）；强智的登录页写「湖南强智科技发展有限公司」、
   路径是 `/jsxsd/`。本批（批次四）统一订正这个厂商名，理由与实测见
   `docs/impl/2026-09-16-port-adapters-batch4.md` 的「平台名订正」一节。
   本件的注释与 manifest 一律写树维。
2. **上游 zua.js 有 `Function("return (" + raw + ")")`**（`parseSemesterResponse`），
   把网络取回的字符串当代码执行 —— 命中移植手册 §5 第 6 条。
   **本件没有移植那一步**，全部换成正则 + 括号配对扫描（见 §2 第 6 条、§4 第 2 条）。
3. **上游 zua.js 并没有从课表表头读作息时间。** 逐行核对：它只有文件开头那张写死的
   `ZUA_TIME_SLOTS`（10 节），`fetchTimeSlots()` 根本没有（文件里一次 `courseTableForStd.action`
   只用来读 ids / tagId）。从表头读 `(HH:mm-HH:mm)` 的是**同平台的 `ZZVCAE/zzvcae.js`**
   （`parseTimeSlotsFromHtml`，`th[id="0_N"]`）。
   本件按本批检查表第 5 条的「优先用表头读到的」实现，但依据是**同平台的既有写法**，
   不是上游 zua.js —— 这里如实写明，免得后面的人以为是从上游搬的。

---

## 1. 请求域与请求清单（本节必须与代码一致，不许写得比代码窄）

上游脚本里有**一个**绝对 URL：`http://jwglxt.zua.edu.cn`（`BASE_URL`，三条 `fetch` 都把主机名写死）。
移植件**没有**沿用绝对地址：`eamsBase()` 按当前页面的 `origin` + `/eams` 上下文路径拼
（`/eams/` 前面若有门户 / WebVPN 前缀，前缀跟着用户实际打开的那个地址走，脚本不替教务系统写死主机）。
所以两个脚本请求的主机都只有**用户当前所在的那一个教务主机**（`http(s)://jwglxt.zua.edu.cn`，
即 `loginUrl` 的主机）。**`allowHosts` 是空数组**，不通配、也不写 `*.zua.edu.cn`。

| 谁 | 方法 | 地址 | 干什么 | 取不到时 |
|---|---|---|---|---|
| extract.js | GET | `<origin><前缀>/eams/courseTableForStd.action` | 读 `ids`（学号）/ 学期下拉元素的 id / 当前学期 id；这份 HTML 同时也是 parse.js 读作息表头的来源。**上游同款**（上游 `detectParameters`） | 报错并提示重新登录 |
| extract.js | POST | `<origin><前缀>/eams/dataQuery.action` | 学期列表（`tagId` + `dataType=semesterCalendar` + `value` + `empty=false`，请求体与上游逐字一致）。**上游同款** | 报错（没有学期就没法查课表） |
| extract.js | POST | `<origin><前缀>/eams/courseTableForStd!courseTable.action` | 课表 HTML（`ignoreHead=1&setting.kind=std&startWeek=&semester.id=&ids=`，与上游逐字一致）。**上游同款** | 报错并提示重新登录 |
| extract.js | POST | `<origin><前缀>/eams/base/calendar-info.action` | 学期日历（`version=1&semesterId=`），用来定开学日与总周数。**上游同款**（上游 `fetchCalendarInfo`） | 交空串，parse 回落推算并写进 `warnings`，**不因为附加接口挂掉就导不进课表** |

- 四条请求的路径、方法、请求体全部与上游 `ZUA/zua.js` 一致，**没有引入上游没有的接口**；
  唯一动过的是主机名从写死改成同源相对（见上）。
- 请求头只带 `Content-Type`；**不带任何自定义令牌**。Cookie 由 WebView 按同源规则自己带，
  脚本不读也不写它（全文没有 `document.cookie`）。
- `parse.js` **不发任何请求**（CI 里用 Rhino 实跑，是纯函数）。

**读到的数据里有什么**：

| 来源 | 读什么 | 交出去什么 |
|---|---|---|
| 课表页 HTML | `bg.form.addInput(form,"ids","…")` 的 ids、`id="semesterBar…Semester"` 元素及其 `value`、`firstDayOfWeek = N`（有才读） | 只交 `ids` 的**值**（学号，课表接口的必填参数）、两个元素 id、当前学期 id、每周起始日；HTML 原文另外整份交给 parse.js 读作息表头 |
| dataQuery 响应 | `semesters` 里的每个学期块 | **只交 `id` / 名称 / 学年 / 学期序号**，以及**有才带**的起止日期与总周数。响应里可能夹带的其它字段一律不带出，也不进 fixture |
| 课表响应 HTML | 整个响应体（课程以 `new TaskActivity(...)` 内嵌） | 整份交出去（它就是课表本身），外加 TaskActivity 的**个数**用于对账 |
| calendar-info 响应 | 整个响应体（学期起止日期） | 整份交出去 —— 它是**学校的学期日历**，不含任何个人信息 |

学号 `ids` 说明：它是课表接口的必填表单字段（上游同一个用法），**只发给本校教务主机**，
不落盘、不写日志、不进 fixture（fixture 里是虚构的 `20230012345`）。这是「读令牌用于本校接口」的
正常用法，不是外发。

---

## 2. 移植手册 §5 八条逐条

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 不碰凭据 | **过**。两个脚本都没有 `password` / `pwd` / 登录表单读取，没有 `localStorage` / `sessionStorage` / `document.cookie`（静态扫描无命中，见 §6）。唯一的「凭据」是浏览器自带会话 Cookie，脚本既不读它也不把它发到别处 |
| 2 | 不外发 | **过**。全部 `fetch` 都在 `extract.js` 里（一个 `request()` 被四个调用点复用），地址全部由 `window.location.origin` + 上下文路径拼成；没有 `sendBeacon` / `WebSocket` / `EventSource` / `new Image().src` / 隐藏表单，也没有任何第三方域（全文 `https?://` 只命中三处，全在注释里：上游仓库链接 ×2、说明平台用的 `http://jwglxt.zua.edu.cn`） |
| 3 | 请求域可控 | **过**。请求主机只有一个：`loginUrl` 的主机 `jwglxt.zua.edu.cn`。`loginUrl` 给的是 http（上游给的就是 http，学校如果也开了 https，维护者可以直接换），**脚本自己不在页面里跳转**，走 http 还是 https 由用户实际打开的那个地址决定。`allowHosts: []`，不通配。本件**不使用** OCR / 提问桥（源码里不出现 `__ncOcr` / `__ncOcrGrid` / `__ncSelect` 等全局名），所以「`*.` 通配会被 `JwOriginRules` 跳过、拿不到桥」这条对本件不构成影响 |
| 4 | 只读课表 | **过**。四条请求全部落在 `/eams/` 的课表模块（课表页、学期列表、课表接口、学期日历），**不碰成绩、学籍、个人信息、缴费**。课表接口的响应是课表本身；学期日历是**学校的公共数据**。响应里若有夹带的其它字段，`extract.js` 不解析、不带出 |
| 5 | 不埋点 | **过**。没有任何统计 / 上报 / 遥测；连 `console.log` 都没有（上游的 `console.warn` / `showToast` 也没有移植） |
| 6 | 不 eval 远程代码 | **过，而且是本件特意拆掉的一处**。上游 `parseSemesterResponse` 用 `Function("return (" + raw + ")")` 解析 dataQuery 的返回（`raw` 是网络取回的字符串）—— 命中本条。**本件没有移植**：`extract.js` 的 `sliceBalanced()` / `propertyValue()` / `fieldOf()` 按引号与括号配对扫描取字段，`parse.js` 用正则读 `index` 表达式。两个脚本全文没有 `eval` / `new Function` 的**调用**（全文命中三处，都在注释里说明上游那块为什么不搬） |
| 7 | 不写页面 | **过**。`extract.js` 只**读**课表页的字符串（用 `fetch` 拿 HTML，不往当前页面插 DOM），既不写 DOM、不改表单、也不触发提交或点击。上游的 `showToast` / `showSingleSelection` / `showPrompt` / `notifyTaskCompletion` / `saveImportedCourses` / `saveCourseConfig` / `savePresetTimeSlots` 这类桥调用**全部没有移植** |
| 8 | 不依赖用户输入之外的秘密 | **过**。没有硬编码密钥、令牌或他人的学号。脚本里的常量只有 `/eams` 路径、上游那张 10 节作息表与默认值（`unitCount` 14、总周数 20） |

**结论：可以进内置库。** 全部请求落在本校教务主机上，只读课表与学期日历，不碰凭据、不外发、
不埋点、不写页面、不 eval 远程代码。

---

## 3. 取数契约（`extract.js` → `parse.js`）

`extract.js` 交给 `parse.js` 的是这个对象（JSON 字符串）：

```json
{
  "today": "2026-09-17",
  "semester": { "id": "1", "name": "第一学期", "schoolYear": "2026-2027", "startDate": "…", "endDate": "…", "totalWeeks": 19 },
  "semesters": [ …所有学期，字段同上… ],
  "currentSemesterId": "1",
  "firstDayOfWeek": 1,
  "tableHtml": "<课表页 HTML 原文>",
  "courseHtml": "<课表响应原文，课程是内嵌的 TaskActivity>",
  "calendarHtml": "<学期日历响应原文，取不到就是空串>",
  "taskActivityCount": 5,
  "extractWarnings": ["…"]
}
```

- `firstDayOfWeek` 可能为 `null`（页面没有那个声明时）——`parse.js` 会按 `null` 处理并取约定的 1。
- `extractWarnings` 是「取数阶段拿不准的地方」（例如学期日历接口挂了、当前学期没读出来只能取列表第一个）；
  `parse.js` 把它们**原样放进载荷的 `warnings`**，所以用户能在导入预览里看到。
- `calendarHtml` 取不到不是失败：`parse.js` 回落到推算（并用 `warnings` 如实说）。

---

## 4. 对上游做的删改（逐条，都不牵涉安全问题，但审阅者需要知道）

1. **ES6 → ES5**。上游满篇 `async/await`、模板串、箭头函数、`URLSearchParams`、对象展开、`const/let`。
   `extract.js` 改成 `.then()` 链 + 手写 `pair()`；`parse.js` 本来就是纯函数，全同步。两个文件都没有
   `=>` / 反引号 / `let ` / `const `（**连注释里都没有** —— CI 的 ES5 检查是整个文件字符串查找，
   注释里出现也算违规）。
2. **拆掉 `Function("return (...")`**（上游解析 dataQuery 返回的那一处）。换成
   `sliceBalanced`（按引号与括号配对切一段）+ `propertyValue`（找 `key:` 后的那段）+ `fieldOf`
   （在对象块里取标量字段）。语义与上游一致：`semesters` 既认数组，也认按学年分组的对象
   （`{"2026-2027":[{...}]}`）。
3. **不问用户**。上游 `getSelectedSemester` 用 `showSingleSelection` 让用户挑学期；
   本件自动取教务当前选中的那个（学期元素上的 `value` → 响应里的 `semesterId` → 只有一个学期就取它
   → 否则取列表第一个并写进 warnings）。用户此刻就开在教务页面上，他自己切过的学期优先，
   这比弹窗更清楚（手册 §3 第 1 步）。
4. **不问开学日**。上游用 `showPrompt` 问用户（`trySaveCalendarInfo` 之外还有一条问询路径），
   并且把 `firstDayOfWeek` 写死 1。本件改成：优先用学期日历（`/eams/base/calendar-info.action`）
   给的学期起止日期，按 `firstDayOfWeek` **回退对齐**（手册 §4.3，缺省 1 = 周一）；
   拿不到就按最近的每周起始日推算，**两种情况都写进 `warnings`**。
5. **切两段**。上游在一个脚本里请求接口、算周次、拼课程、存配置、推作息；本件把 TaskActivity 解析、
   周次位图、作息、开学日、总周数全部落在 `parse.js`（CI 里跑得到的那一段），`extract.js` 只取数。
   代价是多一对 fixture，收益是这段逻辑第一次有真回归。
6. **周次位图按本批统一口径**：下标 `i` 就是第 `i` 周，下标 0 是占位符。上游 zua.js 的循环是
   `for (week = 1; week < value.length && week <= MAX_SUPPORTED_WEEK; week++)`，与本口径一致
   （批次四的「每件的实测事实」表里 `zua` 那一行也是这么记的）。差别在出声：
   **位图第 0 位是 1 时上游一个字都不说**，本件计一条 `warnings`（`bitmap[0] === '1'` 会算出
   「第 0 周」，而载荷校验会拒，所以必须忽略 + 出声）。
7. **周次上限 clamp**。上游的 `MAX_SUPPORTED_WEEK` 是 60；我们的载荷校验（`MAX_TOTAL_WEEKS`）
   是 1..30，`totalWeeks` / `startWeek` / `endWeek` 越界会让**整个载荷**被拒（不是丢掉那几周）。
   本件把超过 30 的位图位 clamp 到 30 并 warn（报出被 clamp 的个数与位图里最大的周次）。
8. **`unitCount` 读不到必须出声**。上游缺省 14 且不说。本件同样缺省 14，但**同时**写一条 warnings
   （「一天排几节是猜的」）。
9. **作息**：表头读到的优先，读不到回落到上游那张 10 节表 —— 两种情况都写 warnings。
   课表里用到作息表没有的节次时，用空课内建节次表补（载荷带了 `periodTimes` 时应用不会自己补），
   补了/补不出来都写 warnings。
10. **教师 / 教室拿不到就留空**（`null`），不用上游的 `"未知教师"` / `"未知地点"` 占位
    （手册 §4.7：占位符会被课表当成真姓名、真地点显示）。
11. **课程名按原文**。上游 `cleanCourseName` 会把名字末尾半角括号里的内容删掉（`\([^()]*\)\s*$`），
    「大学英语(一)」→「大学英语」。**这是数据丢失**，而且同一门课在不同学期可能因此重名。
    本件只做 `trim` + 空白归一。代价：如果某校真的把课程序号写在名字末尾，课名里会带着它 ——
    但那是教务给的原文，用户一眼能看出是什么；删掉反而看不出来。
12. **合并逻辑（上游 `mergeContinuousLessons`）语义原样移植**：按（课名|教师|地点|星期）分组，
    逐周记下这节课占用的节次集合，再把每周的节次切成连续段（`1-2` + `3-4` → `1-4`），
    最后按（星期、起始节、结束节、课名、教师、地点、周次）确定性排序。树维 EAMS 的语义正是
    「同一门课不同周可以落在不同节次上」，所以不能简化成「取最小最大节次」。
    只有排序键换成与引擎无关的确定性比较（上游用 `localeCompare` 排中文，Rhino 与 V8 的结果未必
    一致，而 fixture 是逐数组比对的），并给每个键补了完整次级键（不依赖排序算法的稳定性）。
13. **周次按「天」而不是按「活动」聚合**：上游是「每个 TaskActivity 各自算好节次区间再合并」，
    本件是「先按（课名|教师|地点|星期）把每周的节次收成集合，再切连续段」。前者在「同一门课同一天
    有多个活动、周次不同」时会把课拆成多条 block，后者会把它们的节次并起来；
    本件的做法与上游 `mergeContinuousLessons` 的分组键一致，**周次语义等价，块数可能更少**。
14. **静默丢数据的三处改成出声**：位图一位都没有的活动、`TaskActivity` 参数不足 7 个的活动、
    星期或节次超范围的下标，各自计数后写进 `warnings`（手册 §4：不许静默丢课）。
    另加一条 **TaskActivity 数量对账**（`extract.js` 数出响应里的个数，`parse.js` 发现解析到的比它少
    就出声）。上游这些地方一处都不出声。
15. **`index` 的第三种写法**。上游正则同时认 `index = 5*unitCount+2` 与 `index = 5*14+2`
    （用页面读到的 unitCount 拼进正则），但**不认已经算好的裸数字** `index = 62` ——
    那种写法整节课会被静默丢掉。本件三种都认（裸数字按 `Math.floor(n / unitCount) + 1` 反算星期、
    `n % unitCount + 1` 反算节次，与同族 `HPU/hpu.js` 的算法一致）。**禁止 `eval` / `new Function`**
    求值：表达式来自网络取回的 HTML（手册 §5 第 6 条）。
16. **课程顺序按确定性排序的首次出现**（不依赖任何不稳定排序），block 顺序按
    （星期、起始节、结束节、起始周、结束周、单双周、地点）排 —— 两者都是确定的，人工核对 fixture
    时不用在脑子里跑一遍排序。
17. **没有移植的桥调用**：`showToast` / `notifyTaskCompletion` / `saveImportedCourses` /
    `saveCourseConfig` / `savePresetTimeSlots`。我们这条链路是「拉」不是「推」，作息也不单独推
    （直接放进载荷的 `periodTimes`）。

---

## 5. 批次四 12 条检查表逐条

1. **周次位图的下标基准**：**过**，按本批统一口径（`week = index`，`index 0` 是占位符）。
   上游 zua.js 正是 `for (week = 1; …)`，与口径一致，所以是「照抄 + 出声」而不是「改语义」。
   用例：`weeks-bitmap` 里第 1 条课的位图第 0 位是 1、第 1-4 位也是 1 → 必须得到第 1-4 周；
   第 2 条课只有第 1、2 位是 1 → 第 1-2 周（**基准差一位这里就红**）；第 3 条只有第 5 位 → 第 5 周。
   变异：`bitmap-base`（把循环起点从 1 改成 0）→ `weeks-bitmap` 变红；`zero-bit-silent`
   （第 0 位不再计数）→ `weeks-bitmap` 变红。
2. **`TaskActivity` 的参数位**：**过**。`args[3]` = 课程名、`args[5]` = 教室、`args[6]` = 周次位图，
   `args[1]` = 教师。教师那一项有**两副面孔**：有的是字面量（`"王建国"`），
   有的是表达式（`actTeacherName.join(",")`）—— 后者直接取值会把 `join(...)` 当教师名。
   本件先看表达式里有没有 `join(` / `actTeacherName` / `teachers[`，有就取**离该活动最近的前一个**
   教师声明块里的 `name: "…"`（多个教师用逗号连起来，与上游 `parseTeacherName` 的 `names.join(",")`
   一致），没有就按字面量用；两路都拿不到就留空。
   这里比上游多认一种写法：上游的正则只认 `\bname\s*:`，漏掉单引号属性名（`'name':`）与
   未加引号的键名（`name:` 无引号）—— 本件的正则两种都认。
   用例：`basic`（字面量 + `actTeacherName.join(",")` 两路，教师块里有两个人）、
   `weeks-bitmap`（某条活动之前**没有任何教师声明** → `teacher` 必须是 `null`）。
   变异：`teacher-no-literal`（字面量一律不算数）→ `weeks-bitmap` 变红；
   `teacher-unknown-placeholder`（拿不到时写「未知教师」）→ `weeks-bitmap` 变红。
3. **`index = 星期 * unitCount + 节次` 的两种写法**：**过**，而且比上游多认一种。
   带 `unitCount` 变量的、把 `unitCount` 写死的、**已算好的裸数字**三种都认（见 §4 第 15 条）。
   严禁 `eval` / `new Function`（本件全文没有调用）。用例：`index-forms`
   （`5*unitCount+2` → 第 6 天第 3 节、`62` → 第 5 天第 7 节、`2*14+1` → 第 3 天第 2 节）。
   变异：`index-no-bare-number`（正则里去掉裸数字那一路）→ `index-forms` 变红（8 处差异）。
4. **`unitCount` 要真的读**：**过**。从课表 HTML 读 `/\bunitCount\s*=\s*(\d+)\s*;/`（上游同款正则），
   读不到或读到 1..30 之外时用上游的缺省 14 并**同时**写一条 warnings。
   用例：`basic`（读到 5，第 6 天的课能证明它真的生效）、`index-forms`（读到 14，
   三条 index 的换算都依赖它）、`units-default`（读不到 → 缺省 14 + warning）。
   变异：`unitcount-always-default`（一律用 14）→ **6 个用例变红**（只有 `units-default` 是绿的，
   因为它本来就是缺省路径，另外补了 `index-forms` 专门盯这件事）。
5. **作息时间从哪来**：**过**，但依据与检查表写的不一样，如实说明：
   上游 zua.js **只**有那张写死的 10 节表（`ZUA_TIME_SLOTS`，第 1 节 `08:00-08:45`），
   从课表表头读 `(HH:mm-HH:mm)` 的是**同平台的 `ZZVCAE/zzvcae.js`**（`th[id="0_N"]`）。
   本件按检查表「优先用表头读到的」实现，依据是该平台的既有写法。
   写进载荷的每个时间都过 `HH:mm` 与 `00:00–23:59` 校验（`timeOf`）并检查「结束晚于开始」，
   不合法的那一节直接丢掉、不进 `periodTimes`（越界会让**整个载荷**被拒，不是跳过一节）。
   用例：`basic` / `units-default` 的表头第 1 节是 `08:05-08:50`、第 5 节是 `14:35-15:20`
   （与内置表的 `08:00-08:45` / `14:30-15:15` **不同**，表头优先才读得到）；
   `weeks-bitmap` / `weeks-over-limit` / `index-forms` / `first-day-nonmonday` / `dirty-activities`
   的表头没有时间 → 回落内置表 + warning。
   变异：`timeslots-always-builtin`（一律用内置表）→ `basic`、`units-default` 变红。
6. **开学日**：**过**。优先用学期日历给的学期**起止日期**（先剥 HTML 标签、再用
   `开始 / 结束日期：YYYY-MM-DD ~ YYYY-MM-DD (N)` 取开始日与周数，日期用 UTC 构造后逐项回比，
   2 月 30 日这类假日期会被拒）；拿到后按 `firstDayOfWeek` **回退对齐**（手册 §4.3）。
   拿不到就按最近的每周起始日推算，并且**推算值一定出现在 `warnings` 里**（手册 §4.2 要求如实说）。
   上游是 `showPrompt` 问用户 —— 问用户的路径没有移植。
   用例：`basic`（日历第 1 周从周一 2026-09-07 开始）、
   **`first-day-nonmonday`（日历起始日 2026-09-09 周三、`firstDayOfWeek` = 7，
   必须回退到 2026-09-06 周日）**、`weeks-bitmap` 等（没有日历 → 推算最近的周一 2026-09-14）。
   变异：`firstday-no-align`（直接拿起始日当面开学日）→ `first-day-nonmonday` 变红（5 处）；
   `firstday-ignore-firstdayofweek`（把每周起始日当成周一）→ 同一条变红（3 处）。
7. **周次上限**：**过**。位图里超过第 30 周的位置 clamp 到第 30 周并 warn（报出被 clamp 的个数与
   位图里最大的周次），`totalWeeks` 也随之被抬/被 clamp（永不越界）。
   用例：`weeks-over-limit`（第 1-54 周 → 第 1-30 周，48 个周次被 clamp，报出的最大周次是 54）。
   变异：`bitmap-no-clamp`（不 clamp）→ `weeks-over-limit` 变红（4 处）；
   `overlimit-silent`（clamp 了但不出声）→ 同一条变红；
   `totalweeks-not-raised`（总周数不被课表里更晚的周次抬高）→ 同一条变红。
8. **`teacher` / `location` 拿不到就留空**：**过**。上游的 `"未知教师"` / `"未知地点"` 没有搬；
   空教师、空教室都是 `null`。用例：`weeks-bitmap`（没有教师声明的活动 → `teacher: null`）。
   变异：`teacher-unknown-placeholder` → `weeks-bitmap` 变红。
9. **学期名**：**过**。优先用教务给的（`dataQuery` 的学期名），但会补学年 ——
   树维的学期名常常只是「第一学期」，单看它看不出是哪一学年，所以这种名字会补成
   「2026-2027学年第一学期」；「学期名 + 学年」都拿不到时才拼「郑州航空工业管理学院 + 学年学期」。
   **没有拿适配器名当学期名**，也不会出现光秃秃的「第一学期」。
   变异：`semester-name-adapter`（学期名写死成适配器名）→ 变红（**7 个用例全部**，每条 2 处差异）。
10. **`allowHosts`**：**过，是空数组**。本件不是 WebVPN 学校，而且不把主机名写死（见 §1），
    请求全部同源，所以不需要任何额外域名；**没有通配**，也没有 `*.zua.edu.cn`。
11. **`warnings` 上限**：**过**。`warn()` 逐条截断到 200 字（超出补「…」），条数超过 20 时保留前 19 条
    并在最后一条如实写「另有 N 条说明因为超出上限没有显示」。七个用例实测
    **4 / 5 / 5 / 4 / 7 / 5 / 7 条，最长 62 / 93 / 93 / 93 / 73 / 93 / 105 字**，都在限内。
    上限分支本身用对抗输入验过：喂 25 条 extract 说明 → 输出正好 20 条、
    最后一条是「另有 10 条说明因为超出上限没有显示…」；喂一条 300 字的说明 → 输出 200 字。
    **诚实说明**：`no-warnings-cap` 这个变异（把上限判断改成永假）**七个用例都是绿的** ——
    因为用例里的 warnings 本来就没到 20 条；但这条分支**不是**没人守：它一旦坏掉，
    上面那个 25 条输入的载荷会变成 29 条 warnings，`JwPayloadCodec.validate` 会直接
    「核对提示太多（29 条，上限 20 条）」拒掉整包（已实测）。也就是说这条分支的护栏是载荷校验，
    不是 fixture —— 用例留在 §7 的「没做的事」里。
12. **变异测试**：**过**。17 处，每一处都记录在 §7，其中 16 处至少让一条用例变红，
    剩下 1 处（warnings 上限）的护栏是载荷校验（理由见上）。

---

## 6. 静态扫描记录

```bash
# ES5（CI 的 JwLibraryHarnessTest 是整文件字符串查找，注释里出现也算违规）
node -e "var s=require('fs').readFileSync('jw-adapters/zua/parse.js','utf8');var bad=[];
s.split(String.fromCharCode(10)).forEach(function(l,i){
  if(l.indexOf('=>')>=0||l.indexOf(String.fromCharCode(96))>=0||/\blet\s/.test(l)||/\bconst\s/.test(l))bad.push(i+1)});
console.log('bad lines:',bad.join(','))"
# parse.js → bad lines:（空）
# extract.js → bad lines:（空）

grep -nE '\basync\b|\bawait\b|URLSearchParams|Object\.assign|Array\.from|new Set|\.includes\(|padStart|\.\.\.' jw-adapters/zua/*.js
# 只命中注释里说明「上游是这么写的、我们改成什么」的行，没有一处是代码

grep -nE 'eval\s*\(|new\s+Function' jw-adapters/zua/*.js
# 三处，全部在注释里（说明上游的 Function("return (...)") 为什么没搬、index 为什么不用 eval 求值）

grep -nE 'password|pwd|localStorage|sessionStorage|document\.cookie|sendBeacon|WebSocket|XMLHttpRequest|\.src\s*=' jw-adapters/zua/*.js
# 无输出

grep -oE 'https?://[A-Za-z0-9.:-]+' jw-adapters/zua/*.js | sort -u
# https://github.com（两处，文件头的出处链接）
# http://jwglxt.zua.edu.cn（一处，文件头说明平台与 loginUrl；代码里没有任何绝对地址）
```

- NUL 字节：`extract.js` / `parse.js` / `manifest.json` / 14 个 fixture **全部为 0**，无 BOM。
- 复合键分隔符用 `String.fromCharCode(0)` 取（源码里不出现控制字符，也不出现转义序列 ——
  前几批有两个适配器把转义序列落成了真 NUL 字节，文件被 `grep` 当二进制看）。

---

## 7. 变异测试记录

改坏是**在内存里**做的（读 `parse.js` → 字符串替换 → `vm` 求值），`parse.js` 文件本身从不被改写。
基线（7 对 fixture 全部 MATCH）：
`basic / weeks-bitmap / weeks-over-limit / index-forms / units-default / first-day-nonmonday / dirty-activities`

| # | 把哪一处改坏 | 变红的用例（差异点数） |
|---|---|---|
| 1 | 位图循环起点从 1 改成 0（丢掉「下标 0 是占位符」） | `weeks-bitmap`（1） |
| 2 | 位图不再 clamp 到 30 周 | `weeks-over-limit`（4） |
| 3 | clamp 了但不再出声 | `weeks-over-limit`（1） |
| 4 | 总周数不被课表里更晚的周次抬高 | `weeks-over-limit`（2） |
| 5 | 开学日不回退（直接拿学期日历的起始日） | `first-day-nonmonday`（5） |
| 6 | 回退时忽略 `firstDayOfWeek`（一律按周一） | `first-day-nonmonday`（3） |
| 7 | `unitCount` 一律用缺省 14，不读页面 | `basic`(1) `weeks-bitmap`(2) `weeks-over-limit`(2) `index-forms`(1) `first-day-nonmonday`(1) `dirty-activities`(3) |
| 8 | 作息一律用内置表，不读表头 | `basic`(5) `units-default`(5) |
| 9 | `index` 不再认已算好的裸数字 | `index-forms`（8） |
| 10 | `args[1]` 的字面量教师不再生效（一律取教师块） | `weeks-bitmap`（2） |
| 11 | 教师拿不到时写占位符「未知教师」（上游写法） | `weeks-bitmap`（1） |
| 12 | 周次的极大段一律写成 `ALL`（单双周标记丢掉） | `basic`(1) `index-forms`(1) |
| 13 | 每周的节次不再切成连续段（不合并 1-2 + 3-4） | `basic`(7) `units-default`(5) |
| 14 | 位图第 0 位为 1 时不再计数出声 | `weeks-bitmap`（1） |
| 15 | 学期名写死成适配器名（不判「教务给了就用」） | 七个用例全部（各 2 处） |
| 16 | 脏活动被跳过时不再计数出声 | `dirty-activities`（2） |
| 17 | `warnings` 上限判断改成永假 | **七个用例都是绿的** —— 用例里的 warnings 没到 20 条；这条分支的护栏是载荷校验（喂 25 条说明时输出 29 条 → `JwPayloadCodec` 以「核对提示太多（29 条，上限 20 条）」拒整包，已实测）。**如实记录，不假装它被测到了** |

---

## 8. 用例与自验

| 用例 | 打什么 |
|---|---|
| `basic` | unitCount 读到 5、作息从表头读到 10 节（第 1/5 节与内置表不同）、学期日历给 19 周与 2026-09-07、同一门课跨两天、连续节次并成一段、单周（步长 2 → ODD）、教师字面量与 `actTeacherName.join(",")` 两种写法、完全重复的 TaskActivity 去重 |
| `weeks-bitmap` | 位图基准对照：第 0 位为 1（不出「第 0 周」，只写 warnings）、第 1-2 位为 1、只有第 5 位为 1、没有教师声明的活动 → `teacher: null`；没有日历 → 推算最近的周一 2026-09-14 |
| `weeks-over-limit` | 位图排到第 54 位 → 第 1-30 周 / 第 20-30 周，48 个周次被 clamp，报出的最大周次 54 |
| `index-forms` | 三种 index 写法：`5*unitCount+2`、裸数字 `62`、`2*14+1` |
| `units-default` | unitCount 读不到（缺省 14 + warning）、表头时间与内置表不同、课表用到第 11/12 节（按空课内建表补时间）、第 14 节（补不出来只能说明）、第 20 周把总周数抬到 20 |
| `first-day-nonmonday` | 学期日历起始日 2026-09-09（周三）+ `firstDayOfWeek` = 7 → 开学日必须回退到 2026-09-06（周日），并写一条说明回退的 warnings |
| `dirty-activities` | 位图一位都没有 / index 落在第 8 天 / 没有 index 赋值 / 参数不足 7 个 → 各计一条并出声；TaskActivity 数量对账 9 vs 4；extract 交来的说明原样进载荷 |

自验方式（用 **Node**，没有跑 `./gradlew`，也没有跑任何进程级命令）：

- 七个用例在 `vm` 里实跑 `parse.js`，与 `expected.json` 逐字段比对（键顺序无关、数组顺序有关）：
  **全部 MATCH**。
- 另按 `JwPayloadCodec.validate` 的规则逐条核过七个 `expected.json`
  （`totalWeeks` ∈ 1..30、`endWeek ≤ totalWeeks`、`weekType ∈ {ALL,ODD,EVEN}`、`periodIndex ≥ 1`、
  时间 `HH:mm` 且结束晚于开始、`warnings` ≤ 20 条且每条 ≤ 200 字、`firstDay` 是 `yyyy-MM-dd`）：
  **全部通过**。
- **端到端契约测试**（CI 里跑不到 `extract.js`，所以这一步是我们自己做的最强验证）：
  用桩页面（`window.location` = `http://jwglxt.zua.edu.cn/eams/courseTableForStd.action`）+
  桩 `fetch`（四条路由按路径分发）跑 `extract.js`，把输出**原样**喂给 `parse.js`：
  - 请求顺序与 §1 的表一致（`courseTableForStd.action` → `dataQuery.action` →
    `courseTableForStd!courseTable.action` → `base/calendar-info.action`），请求体与上游逐字一致；
  - `extract.js` 交出的十个键与 `parse.js` 的读法对得上；
  - 端到端产出的课程与 `periodTimes` 与 `basic.expected.json` **一致**；
  - 交出的内容里**不出现**「密码 / 学号 / 身份证 / token」字样。
- `extract.js` 的纯 JS 部分（`sliceBalanced` / `propertyValue` / `fieldOf` / `parseSemesters` /
  `parseParameters` / `looksLikeLogin`）在桩数据上逐项验过：学期列表按学年分组、按数组两种形态都认，
  单引号属性名与无引号键名都认，`ids` 非纯数字也认，登录页会被 `looksLikeLogin` 拦下。
- **对抗输入**（18 组，`parse.js`）：空 `courseHtml` / 没有 TaskActivity / `courseHtml` 是数字 /
  `semesters` 不是数组 / `semester` 全空 / `today` 乱写 / `today` 为空 / `calendarHtml` 乱写 /
  `calendarHtml` 是对象 / `tableHtml` 是 null / 位图是字面量 `"null"` / 位图长 400 位 /
  `unitCount` 是 0 / `taskActivityCount` 是负数 / `extractWarnings` 是字符串 / `firstDayOfWeek` 是 0 /
  `firstDayOfWeek` 缺失 / 学期名是纯数字 —— **没有一处抛出意外异常或产出非法载荷**。
  其中「读了课但一条都没解析出来」与「教务说这个学期没课」给的是**不同**的报错：
  前者带上「解析到 N 个 TaskActivity / 响应里有 M 个 / 参数不足 x 个 / 超范围 y 处 /
  位图为空 z 个 / 没读到 index w 个」的分项计数并提示「多半是教务系统改了课表页的结构」，
  不会被误读成「假期还没排课」。

---

## 9. 没能确定的事（交接给下一位）

- **fixture 是合成的**：按上游 `ZUA/zua.js` 实际读取的字段形状（`ids` / `tagId` / 当前学期 id、
  `unitCount`、`new TaskActivity(...)` 的七个参数、`var teachers = [{name:…}]`、
  `index = 星期*unitCount+节次`、`开始 / 结束日期：… ~ … (N)`）编出来的**形状正确**的数据，
  **不是真实抓取**（`docs/jw-adapter-testing.md` §3）。课名 / 教师 / 教室 / 日期全部虚构，
  无真实个人信息。它只保证「同样的输入永远得到同样的输出」，**不保证真实页面上解析正确**。
  拿到真实 dump 后请替换 fixture 并重跑门。
- **`ids` 在真实部署里未必是纯数字**：上游的正则是 `["'](\d+)["']`，本件放宽成 `[^"']+`。
  真机上若 `ids` 带字母或前缀，本件照样能取到并原样发回去。
- **`firstDayOfWeek` 的真实值未知**：上游把它写死 1（周一），本件优先读页面上的声明、
  读不到才用 1。如果郑航实际是周日开学（像同平台某些学校），**已导入的学期会在学期管理里
  差一天** —— 改这一处即可（`firstDayOfWeek` 的取值），用户也可以在学期管理里直接改开学日。
  fixture `first-day-nonmonday` 用的是 7，专门钉住这条分支的实现正确性。
- **作息那 10 节表是上游作者对学校的了解，我们没有向教务核对过**（表头读得到时以表头为准，
  这一点在 warnings 里对用户说明）。真机上若发现时间对不上，先看表头是不是真的带 `(HH:mm-HH:mm)`。
- **表头取时间的解析认两种写法**：`th[id="0_N"]` 的 id，或表头格里的「第N节」文字；
  两种都读不到就是读不到（回落内置表 + warning），不会猜。
- **位图短于最长周次时不做判断**：位图里没有的位置当 0（不产出周次）。也就是说**短位图只会漏周次、
  不会多出来** —— 若某校的位图长度小于实际学期周数，那几周的课会缺，而这一条**不会有 warning**。
  真机上若发现整体少几周，先查这里（把 `weeksOfBitmap` 打印出来看长度即可）。
- **`warnings` 上限分支没有被 fixture 覆盖**（见 §5 第 11 条与 §7 第 17 条）。
  要覆盖它得造一个能产出 20 条以上说明的用例，代价是那条用例的绝大多数断言都在测「噪声」，
  所以选择不做，靠载荷校验兜底。
- **`semesterId` 兜底**：`dataQuery` 响应里若既没有学期元素上的 `value`、也没有 `semesterId`，
  本件取学期列表的第一个并写进 warnings（上游此处是弹窗让用户挑）。真机上如果发现导入了
  另一个学期，用户的第 1 步动作是「在教务页面切到目标学期再点提取」。
- **需要真实环境才能验的**：登录后的会话是否被这四条接口接受、`#semesterBar…Semester` 这个元素 id
  是否仍然存在、课表 HTML 里是否真有 `var unitCount = N` 与 `index = 星期*unitCount+节次`、
  `loginUrl` 给的是 http（应用会显示「不安全连接」标记；学校如果也开了 https，维护者可以直接换）。

---

## 10. 签名

- 上游作者：`xBefore`（shiguang_warehouse，MIT；见上游 `resources/ZUA/adapters.yaml` 的 `maintainer`）
- 移植：`0x7E-2023`
- 日期：2026-09-17
- 文件指纹（审计时）：`extract.js` sha256 `e9594d9ad38fc6e8ab7a57d5cad648f7b67183f2024c5255893c8c21eff3df14`（318 行）、
  `parse.js` sha256 `e0d0b95d73bc20095495458cf16e45e1799fcd5b792698357208a12be7798a19`（933 行）
