# 安全审计 —— 河南理工大学教务适配器（`hpu`）

- **审计对象**：`jw-adapters/hpu/extract.js` + `jw-adapters/hpu/parse.js` + `manifest.json` + `fixtures/`。
- **上游出处**：`shiguang_warehouse` 的 `resources/HPU/hpu.js`
  （`https://github.com/XingHeYuZhuan/shiguang_warehouse`，MIT）。
  上游快照 commit **`e62554a4034386b893bcd6813c7b2b64f8c730a3`**（2026-09-12 12:50:29 +0800）。
- **上游 `adapters.yaml`**：`adapter_name: 河南理工大学教务系统`、`maintainer: ca1q1an`、
  `import_url: https://zhjw.hpu.edu.cn/eams/login.action`、
  `description: 河南理工大学树维教务系统(eams架构)，统一身份认证CAS登录`。
  `manifest.json` 的 `author` / `loginUrl` 都取自这里。
- **平台**：树维 EAMS（`/eams/`，上海树维信息科技有限公司 / SupWisdom，新开普子公司）。
  上游自己就写「树维教务系统(eams架构)」。**不是强智** —— 强智走 `/jsxsd/`；此前批次把 `/eams/`
  写成强智的地方，本批统一订正（详见 `docs/impl/2026-09-16-port-adapters-batch4.md`「平台名订正」）。
- **移植者**：0x7E-2023　**审计日期**：2026-09-17
- **审计依据**：[移植手册 §5](../docs/jw-adapter-porting.md) 八条 + [批次四专项检查表 12 条](../docs/impl/2026-09-16-port-adapters-batch4.md)。
- **同族参照**：`jw-adapters/masu`（同一批次的 `/eams/` 结构，本件按它的两段式骨架写，
  但**编码逐条按 hpu 自己的脚本与用例重写**，没有照抄它的 fixture 期望）。

---

## 1. 请求了哪些域与路径（手册 §5 第 3 条、检查表第 10 条）

上游 `hpu.js` 的三个请求**全部**是 `window.location.origin` + 相对路径，没有写死主机名。
移植件沿用这一点，并把 `window.location.origin` 缺失时的兜底写成
`location.protocol + '//' + location.host`：

| 谁 | 方法 | 地址 | 干什么 |
|---|---|---|---|
| extract.js | GET | `<当前 origin>/eams/courseTableForStd.action?sf_request_type=ajax` | 读学号 `ids`、学期栏 `tagId`、页面标出的当前学期 id |
| extract.js | POST | `<当前 origin>/eams/dataQuery.action?sf_request_type=ajax` | 学期列表；body `tagId=<tagId>&dataType=semesterCalendar`（与上游逐字一致） |
| extract.js | POST | `<当前 origin>/eams/courseTableForStd!courseTable.action?sf_request_type=ajax` | 课表 HTML；body `ignoreHead=1&setting.kind=std&startWeek=&semester.id=<id>&ids=<ids>`（与上游逐字一致） |

- **请求主机只有一个**：用户当前打开的那个教务主机（`zhjw.hpu.edu.cn`）——
  三个地址都是同源相对路径拼出来的，脚本里没有任何绝对请求地址。
- 两个脚本的绝对 URL 扫描（`grep -oE "https?://[A-Za-z0-9._:%-]+" jw-adapters/hpu/*.js`）
  只有三处命中，**没有一处是请求**：`extract.js` 里注释中的上游仓库地址与文件头说明里的
  本校登录地址 `https://zhjw.hpu.edu.cn`、`parse.js` 里注释中的上游仓库地址。
- 请求头只有 `Content-Type` / `X-Requested-With` / `Accept` 三个固定值，**不带任何自定义令牌**；
  Cookie 由 WebView 按同源规则自己带上，脚本不读也不写它。

### `allowHosts` = `[]`，为什么

1. **三个请求都是同源相对路径**，主机由用户实际打开的那个地址决定，脚本不替教务系统做主
   （上游原本就是这么写的，不是移植时的取舍）。契约里「与 `loginUrl` 同源」是自动放行的，
   所以一个额外的域名都不需要。
2. **写死主机名反而是错的**：本校既有 `https://zhjw.hpu.edu.cn/eams/login.action`，
   也可能从门户 / WebVPN 前缀下打开；写死一个主机名会让后一种情况下的请求被宿主闸门拦掉。
   手册 §5 对「代理域」那一条的要求正是「脚本应当请求当前页面同源，别把主机名写死」。
3. **拿不到桥不影响本件**：`JwOriginRules.forAdapter` 会把 `loginUrl` 与 `scheduleUrlHint`
   的主机加进 `addWebMessageListener` 的 origin 规则（`importer/…/JwOriginRules.kt:15-32`），
   本件两者都是 `zhjw.hpu.edu.cn`，桥照常注入。本件不用 OCR、不用提问，
   `allowHosts` 为空也不影响 `__ncInput` / `__ncDone`。
4. **不写通配**：没有任何理由放行 `*.hpu.edu.cn`（那会连带放行本适配器一个都不请求的其它主机，
   而且通配项在 `JwOriginRules` 里会被跳过）。
5. `parse.js` **不发任何请求**（CI 用 Rhino 实跑，是纯函数），`allowHosts` 只与 `extract.js` 有关。

## 2. 读了什么

- **页面**：只读 `window.location`（拼请求地址、记录当前页 URL）。
  **不读** `localStorage` / `sessionStorage` / `document.cookie` / 登录表单 / 任何密码字段
  （静态扫描无命中，见 §1 的 grep）；**不写 DOM、不改表单、不点提交、不注入脚本**。
- **接口**：三个课表相关接口的返回体 —— 入口页 HTML（只取 `ids` / `tagId` / 当前学期 id 三个值）、
  学期列表响应全文（学年、学期序号、起止日期）、课表 HTML 全文（内嵌的 TaskActivity 课程块
  与表头的节次时间）。
- **学号**：`bg.form.addInput(form,"ids","…")` 里的那个数字**只用来填课表接口的 `ids` 参数**，
  它本来就是该学生自己课表页上的值；会随原始数据交给 `parse.js`，但**不进载荷、不进 fixture 的真值**
  （fixture 里是编的号）。姓名、成绩、学籍、缴费一概不读。
- **输出**：`extract.js` 交出的是一份原始数据清单 —— `detect`（ids / tagId / 页面标的学期 id）、
  `semesterRaw`（学期列表响应原文）、`courseHtml`（课表 HTML 全文）、`semesterPick`
  （要导入哪个学期、依据是什么）、`today`（日期字符串，只在教务不给学期起止日期时用于推算）、
  `entryUrl` / `pageUrl`（便于排查）。
- **载荷里只有排课信息**：课名 / 教师 / 教室 / 星期 / 节次 / 周次 / 学期名 / 开学日 / 总周数 / 节次时间。

## 3. 手册 §5 八条逐条

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 不碰凭据 | ✅ 没有 `password` / `pwd` / 登录表单读取，也没有 `localStorage` / `sessionStorage` / `document.cookie` 访问（grep 无命中）。唯一的「凭据」是浏览器自己带的会话 Cookie，脚本既不读内容也不把它发到别处。上游的 `showToast` / `showSingleSelection` / `notifyTaskCompletion` / `shiguangBridgePromise.*` 一律没有移植 |
| 2 | 不外发 | ✅ 全脚本只有一处 `fetch`（`extract.js` 的 `request()` 包装），三个地址都由 `originOf() + '/eams' + path` 拼成，主机只有用户当前打开的那个教务主机；没有 `sendBeacon` / `WebSocket` / `EventSource` / `new Image().src` / 隐藏表单，没有任何第三方域 |
| 3 | 请求域可控 | ✅ 请求主机只有一个且同源，`allowHosts` 留空、无通配（判断依据见 §1） |
| 4 | 只读课表 | ✅ 只请求 `/eams/courseTableForStd.action`、`/eams/dataQuery.action`、`/eams/courseTableForStd!courseTable.action`，全在课表模块内。**不碰**成绩（`/eams/score*`）、学籍（`stdInfoApply` —— 同族的 HIIT 上游请求了它，本件不请求）、个人信息、缴费、考勤 |
| 5 | 不埋点 | ✅ 没有任何统计 / 上报 / 遥测，没有 `img` 打点；两个脚本里连 `console.log` 都没有（上游的 `showToast` 也一并去掉） |
| 6 | 不 eval 远程代码 | ✅ 两个脚本都没有 `eval` / `new Function`（grep 无命中）。**这一条是本件与上游最主要的安全差异**：上游 `fetchSemesters` 用 `Function("return (" + raw + ")")()` 求值学期列表响应、`fetchCourseHtml` 的解析里也有求值路径，那段字符串来自网络；移植件把响应**原样**交给 `parse.js`，由它按字段名做定向取值（`semesterRecords` 里用「不含花括号的花括号块 + 字段正则」读记录），全程不执行取回来的任何字符串。同族的 `DLMU` 上游用 `new Function("return " + expr)` 求值 `index` 表达式、`HAUST` 用 `eval("(" + text + ")")` 解析响应，本件也都没有照抄（`index` 用正则解析，见 `parse.js` 的 `indexRe`） |
| 7 | 不写页面 | ✅ 只读 `window.location` 与 `fetch` 的响应文本；不插入或修改 DOM、不改表单、不触发提交或点击、不注入脚本 |
| 8 | 不依赖用户输入之外的秘密 | ✅ 没有硬编码密钥、令牌或他人学号。脚本里的常量只有 EAMS 的上下文路径 `/eams` 与三条接口的相对路径，都是公开的页面参数；唯一出现的绝对地址是文件头注释里说明出处与登录页用的 |

**结论：可以进内置库。** 全部请求落在用户当前打开的本校教务主机上，只读课表，
不碰凭据、不外发、不埋点、不写页面、不求值远程字符串。

## 4. 批次四专项检查表 12 条逐条

| # | 检查项 | 落实 |
|---|---|---|
| 1 | **周次位图的下标基准** | ✅ 按本批统一口径实现：`bitmap[i] === '1'` 且 `i >= 1` → 第 `i` 周；`bitmap[0]` 是占位符（为 `1` 时不产出「第 0 周」，改写一条 warnings）。上游 `hpu.js` 原文本来就是 `if (text[i] === "1" && i >= 1)`，与统一口径一致 —— 本件**没有改动**这个口径。对照用例见 §5 的 `weeks-bitmap`，变异 M1 证明它真的看着这条路径 |
| 2 | **`TaskActivity` 的参数位** | ✅ `args[1]` 教师 / `args[3]` 课名 / `args[5]` 教室 / `args[6]` 位图，与同族一致。教师是 `xxx.join(",")` 表达式时**先剥**：往前 4000 字符里找最近的 `var teachers/actTeachers/taskTeachers = [{…name:"…"}]`，只取**最靠近这一块**的那份（不是往前第一个）。上游的 `splitJsArgs` 会把**空参数位整个丢掉**（逗号连逗号时后面的参数整体前移，教师/教室/周次全错位），本件保留空位；参数位为空即「这门课没有教师」，**不借**前面课程的数组（变异 M11 的实证）。课程名收尾括号：上游 `cleanCourseName` 一律删，会把「高等数学A(一)」变成「高等数学A」；本件只删「含数字且不含汉字」的那种（`(1)`、`(1-2)`、`(2026.1)`），变异 M12 |
| 3 | **`index` 的两种写法，禁止 eval** | ✅ `index = 5*unitCount+2` 与 `index = 62` 都认（`indexRe` 正则 + `unitCount` 换算），**没有任何 `eval` / `new Function`**（§3 第 6 条）。截参数用「跳过引号、按括号深度找配对」而不是上游的 `/\(([^]*?)\)\s*;/` —— 后者遇到参数里的 `)`（正是 `join(",")` 那种写法）会截歪 |
| 4 | **`unitCount` 要真的读、读不到要 warn** | ✅ 从课表 HTML 读 `var unitCount = N;`（合法区间 1..20）；**读不到用 11**（上游注释里 HPU 的实测值），并且一定会写出一条 warnings 说明节次数是猜的。上游读不到时写 `0`，随后 `Math.floor(linear / 0)` 得到 `Infinity`、整张课表被**静默丢空** —— 不照抄。变异 M3 证明这条路径有用例看着 |
| 5 | **作息时间从哪来** | ✅ **首选课表表头**（上游同款：`第N节` + `(HH:mm-HH:mm)`）。`parse.js` 在 Rhino 里跑、没有 `DOMParser`，所以改成按单元格切 HTML，再在单元格文本里找同样的两条特征；另外认一种同族写法（`id="0_N"` 给编号、时间在文本里 —— ZZVCAE 上游用的那种）。表头**没读齐**（没有从第 1 节起连续）就整份不用，回落**空课内置的 12 节作息表**并在 warnings 里说明那是内置的。所有写进 `periodTimes` 的时间都过 `HH:mm` 与 `00:00–23:59` 校验（越界会让整包被拒），结束不晚于开始的一并剔除并出声 |
| 6 | **开学日** | ✅ 从 `semesterCalendar` 的学期起止日期推，并按手册 §4.3 回退到那一周的周一（`startDate` 是周日 → 回退到周一），回退过就在 warnings 里说清原值。教务两个字段都给不出时按学期序号推算，并且**推算值一定出现在 warnings 里**（手册 §4.2）。上游完全不写开学日（只弹提示让用户自己去 App 里设）。变异 M4 |
| 7 | **周次上限** | ✅ 位图里超过 30 周的位直接丢弃并写一条 warnings（`clampedWeeks`）；`totalWeeks` 由学期起止日期算得后同样夹到 30，课表里最晚的周次更高时抬到 30 并大声说明。变异 M5（`clamp-off`）、M10（`totalweeks-fixed`） |
| 8 | **`teacher` / `location` 拿不到就留空** | ✅ 上游写死 `"未知教师"` / `"待定"`，本件改成留空（`null`），并把解析不出来的处数写进 warnings。变异 M2。`location` 只折叠空白（不删括号内容） |
| 9 | **学期名** | ✅ 用教务自己的「学年 + 学期序号」拼（`2026-2027学年第一学期`）；学年或学期序号缺一个就只写有的那个，两个都没有写「当前学期」。**没有**拿适配器名当学期名 |
| 10 | **`allowHosts`** | ✅ `[]` —— 三条请求都是当前页面同源相对路径（上游本来如此），依据见 §1。无通配、无绝对地址 |
| 11 | **`warnings` 上限** | ✅ 条数上限 20、每条 200 字，超出时最后一条如实说明「另有 N 条没有显示」；单条过长先按 200 字截断加省略号，不静默丢 |
| 12 | **变异测试** | ✅ 12 处改动 → 对应用例变红，记录见 §5 |

## 5. 回归用例与变异测试记录

三对 fixture（`basic` / `weeks-bitmap` / `edge-cases`），**期望值都是按规范手推的**
（周次由位图逐位推、节次由 `index` 除 `unitCount` 推、开学日与总周数由日期算），
不是把 `parse.js` 的输出贴进去；写完用 Node + `vm` 跑 `parse.js` 逐字段比对，三对全部 `MATCH`。

自验与变异分两轮做：

1. **内存副本**（`%TEMP%/hpu-trace/trace.js`，不进仓库）：把源码读进来、在内存里改坏再跑，
   工作区文件不动。
2. **工作区实改一轮**（证明「改工作区文件也会红」，且还原后逐字节一致）：
   把 `parse.js` 的位图基准改成 `if (false)`、把 `teacher: entry.teacher || null` 改成 `|| '未知教师'`
   → `basic` / `weeks-bitmap` / `edge-cases` 三对全部 `FAIL`；用备份覆盖回来，
   `diff` 输出为空（逐字节一致），再跑一遍三对全部 `MATCH`。

| # | 改坏哪一处（怎么改的） | 变红的用例 | 说明 |
|---|---|---|---|
| M1 | 位图基准：`if (i < 1) { out.zeroBit = true; continue; }` → `if (false) {…}`（第 0 位也产出周次） | `weeks-bitmap`、`edge-cases`（`basic` 保持绿） | 位图用例里 5 门课一起变红：多出「第 0 周」、`周次基准三` 被撑成 0-4 周、`周次基准五` 从「整块跳过」变成第 0 周、warnings 条数也变了；`edge-cases` 的两门课 `startWeek` 全变 0。这正是本批要求的「第 0 位为 1 / 第 1 位为 1」对照 |
| M2 | 教师留空：`teacher: entry.teacher || null` → `|| '未知教师'` | `basic` | `basic` 的「体育」教师从 `null` 变 `"未知教师"`；另两对没有无教师的课，所以保持绿（如实记录） |
| M3 | `unitCount` 缺省：`var unitCount = DEFAULT_UNIT_COUNT;` → `= 0`（上游行为） | `edge-cases`（抛错） | 该用例没有 `var unitCount`，改成 0 后 `linear / 0` 得到 `Infinity`，节次全部解析不出来 → `parse.js` 抛「没有一个能解析成课程」。这正是上游会**静默丢空整张课表**的那条路径 |
| M4 | 开学日不回退周一：`var firstDay = mondayOnOrBeforeIso(startIso);` → `= startIso;` | `edge-cases` | `firstDay` 从 `2026-08-03` 变 `2026-08-09`（学期起始日是周日，差 6 天） |
| M5 | 周次上限：`if (i > MAX_WEEK) { out.clamped++; continue; }` → `if (false) {…}` | `weeks-bitmap` | 第 31 周的课没被丢弃，`周次基准四` 多出一条 `31-31 周` 的 block，且超出载荷上限 30（真机上整包会被拒） |
| M6 | 作息来源：`if (headerInfo.slots.length) {` → `if (false) {`（不再读表头） | `basic`、`weeks-bitmap`、`edge-cases` | 节次表从表头的 11 节变成内置 12 节（`edge-cases` 的表头是 8 节，同样被换掉），warnings 也换了措辞 |
| M7 | 教师表达式：`teacher = resolveNamesBefore(…)` → `teacher = text(args[1]);` | `basic`、`edge-cases` | 教师变成字面文本 `teachers.join(",")` —— 上游不剥表达式时就是这个结果 |
| M8 | 教师数组取法：`if (m.index >= bestAt)` → `if (bestBody === null)`（取往前第一个而不是最近的） | `basic`、`edge-cases` | `basic` 的教师从 `王强` 变 `张伟,李娜`、`edge-cases` 从 `陈晨,周琳` 变 `吴迪` —— 证明「往前找最近的一份」这条真的有用例看着 |
| M9 | `index` 的裸数字写法：`} else if (im[3] !== undefined) {` → `} else if (false) {` | `edge-cases`（抛错） | 该用例的节次全是 `index = 22 / 34 / 43` 这种已算好的数，改坏后一块都解析不出来 |
| M10 | 总周数不抬高：`if (maxWeek > totalWeeks) {…}` → `if (false) { }` | `weeks-bitmap` | `totalWeeks` 从 30 退回 19，第 30 周的课越界（载荷校验会拒） |
| M11 | 空参数位：`args.push(current.trim());` → `if (current.trim()) args.push(current.trim());`（上游行为） | `basic` | 「体育」那块的教师参数位是空的（`TaskActivity("0",,,"体育(1)",…)`），丢掉空位后参数整体前移：课程名变成 `null` 被跳过，`basic` 从 4 门课变 3 门 |
| M12 | 课程名收尾括号：只删「含数字不含汉字」的那两行判断 → 一律删 | `basic` | `高等数学A(一)` 变 `高等数学A`（上游 `cleanCourseName` 的行为） |

## 6. 已知边界与没做的事（诚实记录）

1. **上游的 DOM 兜底路径没有移植。** 上游 `runImportFlow()` 第一步是
   `parseCoursesFromCurrentDom()`：当前页面已经是渲染好的课表页（`#manualArrangeCourseTable`
   + `td.infoTitle`）时直接读表格，读到了就不走接口。**本适配器只走接口链**，这条兜底明确不做。
   代价：用户如果已经手动切到课表页、而三条接口恰好都不可用（登录态半失效、
   学校改了接口路径），本适配器会报错而不是退一步从页面上读 —— 上游在这种情形下还能出课表。
   判断依据：同族的 `dlmu` / `uestc` / `zua` / `zzvcae` 上游也都只走接口链；
   而这条兜底要读 `td` 的 `rowspan` 与 `title` 的三种写法（`;;;` 分隔、`单3-17` 式周次文本），
   是一整套独立的解析路径，跨两层（DOM 在 extract、解析在 parse）实现成本与出错面都不小。
   **要么完整移植、要么不做**，本件选择不做，并在 `parse.js` 文件头第 ⑭ 条写明。
2. **只导入当前学期，一次一个。** 上游弹窗让用户挑（`showSingleSelection`）；本件改成自动取当前学期
   （教务页面标的 id → 学期列表响应里的 `semesterId` → 列表里 id 最大的那条），
   依据与原值都写进 `semesterPick` 与 warnings。要导入别的学期，用户在教务页面里切过去再点「提取课表」。
   「列表里 id 最大」这条是推断（EAMS 的学期 id 单调递增），所以**走到这条分支时一定会出声**；
   若教务页面既没标学期、响应里也没有 `semesterId`，还会在 warnings 里单独说一句。
3. **fixture 是合成的。** 维护者手上没有该校账号（也没有任何学校的真实 dump 可公开），
   三对 `*.extracted.json` 是按上游正文读出来的形状编的（HTML 骨架、`TaskActivity` 调用、
   `semesterCalendar` 响应的记录形状），姓名 / 学号 / 课程 / 教室全部虚构，
   文件头的 `_note` 里写明了这一点。**代价**：它们只能保证「同样的输入永远得到同样的输出」、
   以及「本件声称的处理分支确实被走到」，**不能**证明真实教务返回的形状与合成的一致；
   换成真实 dump 才算数（规范 §7 对 `universal` 的同类说明）。
   另外校名的学期起止日期是编的（2026-09-07 / 2027-07-04 之类），不是该校真实校历。
4. **`unitCount` 缺省 11 是上游注释里的实测值**（`hpu.js` 文件头「unitCount=11」），
   本件照用；读不到时一定出声，但**没有**第二个来源可以交叉验证（上游没写别的证据）。
5. **表头作息读不齐时整份不用。** `partial`（读到了节次但没有从第 1 节起连续）与「一格都没读到」
   都回落到空课内置的 12 节作息表；前者在 warnings 里用不同措辞说明，但两者给出的时间是一样的 ——
   如果该校某栋楼用的是另一套作息，本件读不到表头时给出的就是空课的内置表，必须靠用户在节次设置里改。
6. **教师 `xxx.join(",")` 表达式往前找数组的窗口是 4000 字符**（上游是 2500），
   并且只认 `var teachers` / `actTeachers` / `taskTeachers` 三种变量名、只认
   `[{…name:"…"}]` 与 `name:` 键的写法。教务换一种渲染方式（比如数组里用单引号混排、或者名字放在别的键上）时会解析不出来 ——
   这种情形会留空教师并写进 warnings，**不静默**。
7. **上游把 `args[5]` 当教室**，本件沿用；同族 NEUQ 那支会删掉教室里的括号内容，本件不删（只折叠空白）。
   若该校教室确实带「(分校区)」这类后缀，会原样带进载荷 —— 手册 §4.7 认可这种做法。
8. **没有做的还有**：上游的「切到课表页」之类的页面点击、上游的 `saveCourseConfig` 存开学日
   （我们的开学日走载荷的 `firstDay`）、任何统计与提示 toast。

## 7. 签名

- 移植者：**0x7E-2023**　日期：**2026-09-17**
- 上游作者：**ca1q1an**（MIT）—— 文件头与 `manifest.json` 的 `author` 都保留了这一记录。
- 本审计的每一条声明都与两个脚本的实际代码一致（请求的域、读的字段、warnings 的触发条件），
  没有写得比代码更窄；上面 §6 列出的都是**已知没做到或可能出错的**地方,不是待办清单。
