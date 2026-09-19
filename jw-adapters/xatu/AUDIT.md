# 安全审计 —— 西安工业大学（树维 EAMS 平台）

审计对象：`jw-adapters/xatu/` 下的 `extract.js` / `parse.js`，以及它们移植自的上游脚本
`shiguang_warehouse` 的 `XATU/myschool.js`（快照 commit `e62554a4034386b893bcd6813c7b2b64f8c730a3`，
2026-09-12，MIT，上游 `adapters.yaml` 的 `maintainer` 是 `晨熯`）。

- 移植者：`0x7E-2023`
- 移植日期：2026-09-17
- 适配器 key：`xatu`
- 平台：**树维 EAMS**（上海树维信息科技有限公司 / SupWisdom，新开普子公司；登录路径 `/eams/`）。
  **不写「强智」** —— 强智的路径是 `/jsxsd/`，与本平台不是一套（详见批次四文档的平台名订正一节）。

审计方式：逐行读上游脚本 + 逐行读移植件，并跑移植手册 §5 给的静态扫描：

```
grep -oE "https?://[A-Za-z0-9.:-]+" XATU/myschool.js | sort -u
grep -nE "fetch\(|XMLHttpRequest|sendBeacon|new WebSocket|\.src\s*=|localStorage|
          sessionStorage|document\.cookie|password|eval\(|new Function|Function\(" XATU/myschool.js
```

上游两个扫描的结果：

- 绝对 URL 只有**一个**：`http://jwgl2018.xatu.edu.cn`（三条 `fetch` 全把主机名写死）；
- 网络调用只有一处 `fetch`（被 `request()` 复用）；另有 **一处 `Function(\`return (${raw});\`)()`**
  —— 把 `semesterCalendar` 接口返回的字符串当代码执行（见 §4 第 6 条）；
- 没有 `XMLHttpRequest` / `sendBeacon` / `WebSocket` / `.src =` / `localStorage` / `sessionStorage` /
  `document.cookie` / `password` 命中。

## 1. 请求域与请求清单（本节必须与代码一致，不许写得比代码窄）

上游三条 `fetch` 全部写死 `http://jwgl2018.xatu.edu.cn/eams/…`。移植件**不沿用绝对地址**，改成
当前页面的 origin + 上下文路径 `/eams` 拼（`originOf()` + `eamsBase()`），所以两个脚本请求的主机
只有**用户当前所在的那一个教务主机**（`http(s)://jwgl2018.xatu.edu.cn`，即 `loginUrl` 的主机）。
**`allowHosts` 留空**，不写通配。

`eamsBase()` 会在当前地址里找 `/eams/` 这一段并保留它之前的前缀 —— 万一学校把教务挂在门户 /
WebVPN 前缀下（例如 `/webvpn/eams/...`），请求跟着用户实际打开的那个前缀走，脚本不替教务系统
写死主机。

| 谁 | 方法 | 地址 | 干什么 | 取不到时 |
|---|---|---|---|---|
| extract.js | GET | `<origin><前缀>/eams/courseTableForStd.action?sf_request_type=ajax` | 探测学号 `ids`、学期栏 `tagId`，以及学期栏那个元素的 `value`（当前学期 id）。与上游同一条、同一组正则 | 报错并提示重新登录 |
| extract.js | POST | `<origin><前缀>/eams/dataQuery.action?sf_request_type=ajax` | 取学期列表（`tagId=…&dataType=semesterCalendar`，请求体与上游逐字一致）。**原文原样交出，不在取数期解析** | 交空串，parse.js 回落到推算并在 warnings 里说明 |
| extract.js | POST | `<origin><前缀>/eams/courseTableForStd!courseTable.action?sf_request_type=ajax` | 取课表页全文（`ignoreHead=1&setting.kind=std&semester.id=…&ids=…`，与上游逐字一致） | 报错并提示重新登录 |

- 三条请求**全部来自上游** XATU/myschool.js：路径、方法、请求体逐字一致，本件**没有新增任何接口**。
  唯一差别是主机由 `window.location` 拼出来，不再写死。
- 请求头只带 `Content-Type` / `X-Requested-With` / `Accept`，**不带任何自定义令牌**；
  Cookie 由 WebView 自己按同源规则带上，脚本不读也不写它。
- `parse.js` 不发任何请求（CI 里用 Rhino 实跑，是纯函数）。

**读到的数据里有什么**：

| 来源 | 读什么 | 交出去什么 |
|---|---|---|
| 探测页 HTML | `bg.form.addInput(form,"ids","…")` 里的学号、`id="semesterBar…Semester"` 的 tagId 与它的 `value` | tagId、当前学期 id、以及 `url`（当前页面地址，便于排错）。**学号只用于拼请求体，不进交出去的 JSON** |
| 学期列表响应 | `semesters` 里的 `id` / `schoolYear` / `name` / `startDate` / `endDate`；`semesterId` | 整份原文（**它是学校的公共校历**：学期号 + 起止日期，不含个人信息） |
| 课表页响应 | 全文 HTML（内嵌 `var teachers = […]` 与 `activity = new TaskActivity(…)` 脚本块） | 全文原样交出；parse.js 只取课名 / 教师 / 教室 / 周次位图 / `index` |

**载出的载荷里不出现学号**：`parse.js` 只读 `data.semesterRaw` / `data.courseTableHtml` /
`data.today` / `data.currentSemesterId`，`ids` 连交出去都没有（桩测试实测：交出的 JSON 里
不含 `20230001` 这类值）。

## 2. 移植手册 §5 八条逐条

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 不碰凭据 | **过**。两个脚本都没有 `password` / `pwd` / 登录表单读取，也没有 `localStorage` / `sessionStorage` / `document.cookie` 访问（静态扫描无命中，见上）。唯一的「凭据」是浏览器自带会话 Cookie，脚本既不读它也不把它发到别处。学号 `ids` 是探测页里公开的表单参数（接口自己要求的值），只进请求体，不落进任何交出去的数据 |
| 2 | 不外发 | **过**。全部 `fetch` 都在 `extract.js` 里（一处，被 `request()` 复用），地址全部由 `window.location.origin` + 上下文路径拼成；没有 `sendBeacon` / `WebSocket` / `EventSource` / `new Image().src` / 隐藏表单，也没有任何第三方域 |
| 3 | 请求域可控 | **过**。请求主机只有一个：`loginUrl` 的主机 `jwgl2018.xatu.edu.cn`（`loginUrl` 给的是 http，`extract.js` 不在页面里跳转、只按当前 origin 拼地址，所以走 http 还是 https 由用户实际打开的那个地址决定）。`allowHosts` 留空，不写通配 |
| 4 | 只读课表 | **过**。三条请求全部落在 `/eams/courseTableForStd*.action` 与 `/eams/dataQuery.action`（课表页、学期列表、课表数据），**不碰成绩、学籍、个人信息、缴费**等任何其它接口。学期列表是学校的公共数据 |
| 5 | 不埋点 | **过**。没有统计 / 上报 / 遥测，也没有 `img` 打点 |
| 6 | 不 eval 远程代码 | **过，而且这里修了一处上游的违规**。上游 `XATU/myschool.js` 第 212 行是 `Function(\`return (${raw});\`)()` —— 把 `semesterCalendar` 接口返回的字符串**当代码执行**，命中本条第 6 条（表达式来自网络）。移植件**没有搬这一行**：`extract.js` 把原文原样交出去，`parse.js` 用「按 `id` 锚点做花括号配平切块 + 逐字段正则」只**读字段**，一行取回来的代码都不执行（`parse.js` 里连 `eval` / `new Function` 都没有，见静态扫描）。两个脚本里对网络数据的唯一解析手段是 `JSON.parse`（只用于 `__ncInput`，那是宿主给的字符串） |
| 7 | 不写页面 | **过**。`extract.js` 只**读**页面字符串（探测页的 HTML 是 `fetch` 回来的文本，不在当前文档上；脚本从不碰 `document`）。上游的 `showToast` / `showSingleSelection` / `notifyTaskCompletion` / `saveImportedCourses` / `savePresetTimeSlots` 这类桥调用**全部没有移植**，既不写 DOM、不改表单、也不触发提交或点击 |
| 8 | 不依赖用户输入之外的秘密 | **过**。没有硬编码密钥、令牌或他人的学号。脚本里的常量只有上下文路径 `/eams`、`sf_request_type=ajax` 与几条 `.action` 路径，都是公开的页面参数 |

**结论：可以进内置库。** 全部请求落在本校教务主机上，只读课表与学期列表，不碰凭据、不外发、
不埋点、不写页面、不执行从网络取回来的代码。

## 3. 与同族 `tjau` / `hfnu` 是逐字克隆 —— 本件如何避免「照抄邻件的期望值」

上游 xatu 的文件头自己写着「基于天津农学院适配脚本」。我 `diff` 过 `XATU/myschool.js`、
`TJAU/tjau.js`、`HFNU/hfnu.js` 三份（快照同一 commit），结论与批次四文档一致：

| 层 | 三件是否相同 |
|---|---|
| `powerSplit` / `cleanArg` | **逐字相同** |
| `mergeContinuousLessons` | 同骨架；xatu 多一个 `isTeachingBuilding3` 字段（见 §4 第 12 条） |
| `parseTaskActivities`（正则、位图循环、idxRegex） | **逐字相同** |
| 主机 | 不同（tjau 是 `jjwxt.tjau.edu.cn`、hfnu 是 `jw.hfnu.edu.cn`） |
| 作息表 | 不同（tjau 首节 08:30、hfnu 两套校区表、xatu 首节 08:20） |
| 提示语 | 不同 |

**所以「同平台所以一样」的假设在本件上只成立到「解析骨架」这一层**，而骨架恰恰是最不该照抄期望值的
地方。本件的 fixture 全部按**本校自己的事实**另造：

1. **主机**用的是 `jwgl2018.xatu.edu.cn`（`fixtures/*.extracted.json` 里的 `url` 与 `tagId`
   都是本校形态），不是 tjau / hfnu 的域名；
2. **作息表**用的是 xatu 自己那张（第一行 `{"number":1,"startTime":"08:20","endTime":"09:05"}`），
   `fixtures/*.expected.json` 里 12 条 `periodTimes` 逐条按这张表独立写出（**不是** tjau 的 08:30、
   也**不是** hfnu 的 09 节表）；`fallbacks` 用例另加一条顺推出来的第 13 节 21:45–22:30；
3. **期望值独立推出**：`%TEMP%` 下的 `independent.js` 把五条用例的完整期望载荷
   （学期名、开学日、总周数、12 条作息、每门课每条 block 的星期/节次/周次/单双周/教室、以及
   warnings 逐条）按手册 §4 与载荷规范 §4 **手写死**，再与 `parse.js` 的实际输出比，
   五条全部 MATCH（见 §6）。`*.expected.json` 是这份独立期望值的落盘版本，不是 `parse.js` 输出的
   复制品；
4. **没有给 tjau / hfnu 目录写过一个字节**，也没有从它们的 fixture 里取任何数字或字符串。

## 4. 移植时对上游做的删改（逐条）

1. **ES6 → ES5**：`async/await` 改成 `.then()` 链（规范 §3.1 允许 `Promise`），去掉模板串、
   箭头函数、展开运算符、块级声明关键字，以及 `Set` / `Array.from` / `Array.prototype.forEach`
   这类上游用到的非 ES5 结构（上游把周次摊平成一个 50 元素的 `Set` 矩阵）。**合并语义一行没改**，
   实现换成等价的「对象当集合 + `var` 循环」。
2. **切两段（手册 §3 第 1 步）**：上游在同一个自执行脚本里探测参数、取课表、解析课程、推作息、
   存数据；本件把取数留在 `extract.js`（只交探测结果、学期列表原文、课表页全文），
   解析、周次切段、合并、开学日、总周数、作息全部落在 `parse.js` —— 那一段 CI 里跑得到。
3. **不问用户选学期**：上游 `showSingleSelection("选择学期", …, -1)` 弹窗。本件改成自动用教务的
   当前学期：优先探测页学期栏元素的 `value`，其次学期列表里的 `semesterId`，再退到列表最后一条；
   载荷里写 warning 说明「只导入了当前学期」。`parse.js` 里没有任何交互（CI 里没有桥也没有用户）。
4. **周次位图口径（本批统一口径）**：见 §5 第 1 条。
5. **课程名保真**：上游 `(args[3] || "未知课程").split("(")[0]` 会把「高等数学A(一)」砍成
   「高等数学A」；本件只在**尾部括号是明显课程代码**时才去掉（见 §7）。
6. **教室原样保留**：上游 `.replace(/\(.*?\)/g, "")` 去掉教室里的全部括号；本件只做空白归一
   （手册 §4.7：教室可以原样带）。
7. **教师 / 教室拿不到就留空**：上游写「未知教师」/「未知地点」——那会被课表当成真名、真地点显示。
   本件交 `null`。
8. **教师取值加固**：`args[1]` 有的部署写成 `teachers.join(",")` 这类**表达式**，上游把它整串当
   教师名（课表里会出现 `teachers.join(",")`）。本件识别表达式并回退到同一块 `teachers` 数组里的
   姓名；上游只取第一个 `actTeachers` 的 name，本件取该块全部姓名（一个块挂多位老师时不丢人）。
9. **`index` 两种写法都认**：`index = 5*unitCount+2` 与已算好的裸数字 `index = 62`（同族 hpu 两种
   都认）。**禁止 `eval` / `new Function`**，只用正则（手册 §5 第 6 条）。
10. **`unitCount` 读不到时出声**：上游静默用缺省 14；本件用同一个缺省值，但**同时**写一条 warning。
11. **开学日 / 总周数 / 学期名**：上游不管这三件事。本件用 `semesterCalendar` 的学期起止日期
     回退到那一周的周一（手册 §4.3）、按起止日期算总周数；拿不到就推算，**推算值一定进 warnings**。
    学期名用教务的学年学期（「2026-2027学年第一学期」），拿不到用「西安工业大学 + 学年学期」——
    不用适配器名当学期名。
12. **没有搬上游的「教 3 楼 3-4 节改自定义时间」那段**：`adjustTeachingBuilding3Courses` 会把
    `/教3/` 且恰好 3-4 节的课标成 `isCustomTime` + 10:10–11:40。我们的载荷没有 `isCustomTime`
    这一层（手册 §4.4 的两种情况都不适用：这些课有明确的节次，只是时间被学校单独改过），
    悄悄改时间会让课表与教务不一致，所以本件不搬、也不静默丢这条信息 —— 它记在 §8 的待确认项里。
13. **排序确定性**：上游 `merged.sort` 用 `localeCompare` 排中文课名，Rhino 与 V8 的结果未必一致，
    而 fixture 是逐数组比对的。本件改成按码位比较，并把次级键（结束节、起始周、教师、教室）补齐，
    不依赖排序算法的稳定性。

## 5. 批次四 12 条检查表逐条

1. **周次位图的下标基准**：**过，而且这是本件改动最大的一处**。上游原文是

   ```js
   for (let j = 0; j < weeksBitmap.length; j++) {
       if (weeksBitmap[j] === '1') weeks.push(j);
   }
   ```

   —— `bitmap[0] === '1'` 时它会 `push(0)`，产出**「第 0 周」**（我们的载荷校验会拒掉整包，
   而且 `firstDay` 周次语义也会整体错位）。本件按批次四的**统一口径**实现：
   `bitmap[i] === '1'` 且 `i >= 1` → 第 `i` 周；`bitmap[0] === '1'` → **不产出周次**，
   改为写一条 warning。依据是同族五件共同声明了同一个约定：`uestc`（「position 0 始终是 0，
   忽略」）、`hpu`（循环写 `i >= 1`）、`hunnu`（从 `i = 1` 起）、`zua` / `zzvcae`（都从 `week = 1` 起）。
   fixture：`weeks-bitmap` 一条「第 0 位为 1」（位图 `110000000000000000000` → 只产出第 1 周，
   并写 warning）、一条「第 1 位为 1」（位图第 12 位为 1 → 第 12 周）、一条「第 31 位为 1」
   （超出 1..30 丢弃并出声）。**变异测试 ①、②** 都让这条用例变红（见 §6）。
2. **`TaskActivity` 的参数位**：`args[3]` 课程名、`args[5]` 教室、`args[6]` 周次位图 —— 与上游一致。
   `args[1]` 的两副面孔（字面量 / `join` 表达式）都处理（§4 第 8 条）。`args[2]` 本件不读（上游也
   不读）。fixture：`basic` 的「大学英语(三)」用 `teachers.join(",")`、其余用字面量。
3. **`index` 的两种写法**：**过**。`index = N * unitCount + M` 与裸数字 `N`（反推
   `day = floor(N/unitCount)+1`、`section = N%unitCount+1`）都认；**全用正则，没有 `eval` /
   `new Function`**（上游 DLMU 用了，本件没有照抄那个写法）。fixture：`fallbacks` 同一门课里
   同时用 `62`（裸数字）与 `2*14+3`（字面乘数）。
4. **`unitCount` 要真的读**：**过**。从课表页读 `unitCount = N`；读不到或值不合法（<1 或 >30）
   时用上游的缺省 14，并**同时**写一条 warning（上游静默）。fixture：`fallbacks` 的页面里没有
   这一行，实测输出含「已按缺省值 14 节/天解析…」这条 warning。变异 ⑦ 让这条用例变红。
5. **作息时间从哪来**：**内置**。取自上游 XATU/myschool.js 里那张 12 节预设表，原样搬进载荷；
   所有时间都过 `timeOf()`（`^([01]?\d|2[0-3]):([0-5]\d)`，即 `HH:mm` 且在 `00:00–23:59`），
   并要求结束时间晚于开始时间 —— 不合法的那一节被丢弃（`timeOf` 返回 `null` 时不进数组）。
   **载荷里带一条固定 warning 说明「作息来自适配器内置表、非教务读取，请对照教务处公布的作息核对」**。
   只到第 12 节；课表用到的更晚节次按「上一节结束 + 5 分钟、每节 45 分钟」顺推补上并写 warning
   （fixture：`fallbacks` 的第 13 节 21:45–22:30）。变异：把表里第 1 节的开始时间写成 `85:45`
   → **五个用例全部变红**（每个载荷都带 `periodTimes`，这道闸真的在拦）。
6. **开学日**：**过**。优先用 `semesterCalendar` 当前学期的 `startDate`，**回退到那一周的周一**
   （手册 §4.3；非周一时写 warning 说明回退了）；拿不到就按学期序号推算（第一学期 = 9 月 1 日
   所在周的周一、第二学期 = 2 月 20 日所在周的周一；连学期信息都没有时按导入日期所在季节算），
   **推算值一定出现在 warnings 里**。fixture：`second-term`（起始日 2026-02-25 星期三 →
   firstDay 2026-02-23）与 `weeks-bitmap`（没有学期列表 → 推算 2026-08-31）。变异 ⑩ 让
   `second-term` 变红。
7. **周次上限**：**过**。载荷上限 30（`MAX_TOTAL_WEEKS`）。位图里下标 > 30 的位**不产出周次**，
   记数并写 warning；总周数超过 30 时不写进载荷（回落到课表最晚周次或 20）并出声；课表里更晚的
   周次会抬高总周数（否则那条 block 会超出 `totalWeeks`、整包被 `JwPayloadCodec` 拒掉）并出声。
   fixture：`weeks-bitmap`（第 31 位）+ `fallbacks`（教务说 12 周、课表到第 16 周 → 抬到 16）。
   变异 、⑮ 分别让这两条用例变红。
8. **`teacher` / `location` 拿不到就留空**：**过**。上游的「未知教师」/「未知地点」没有搬；
   fixture：`basic` 的「体育(三)」（`teacher: null`、`location: null`）。变异 ⑥ 让它变红。
9. **学期名**：**过**。用教务的 `schoolYear` + 「第 N 学期」拼成「2026-2027学年第一学期」；
   拿不到用「西安工业大学 2026-2027学年第一学期」。**没有拿适配器名当学期名**。变异  让四个
   用例变红（改坏后学期名变成「西安工业大学」）。
10. **`allowHosts`**：**过**。本件不是 WebVPN 学校，但同样**不把主机名写死** —— 地址由
    `window.location.origin` + 上下文路径拼出来，`allowHosts` 留空（不写通配，也不写
    `*.xatu.edu.cn`）。没有非标准端口。脚本**不使用** OCR / 提问桥（源码里不出现 `__ncOcr` /
    `__ncOcrGrid` / `__ncSelect` 等全局名），所以「`*.` 通配会被 `JwOriginRules` 跳过、
    拿不到桥」这条对本件不构成影响。
11. **`warnings` 上限**：**过**。`warn()` 逐条截断到 200 字（超出补「…」），去重，条数到 19 条时
    改推一条「另有说明因为超出上限没有显示…」。五条用例实测（详见 §6.1 表）：
    **3 / 7 / 2 / 8 / 3 条**，最长 71 字，都在限内。
12. **每件都要做变异测试**：**过**。18 处变异里 17 处让用例变红，第 18 处（`timeOf` 那一侧）
    无法从黑盒观察、已如实记录，清单见 §6.2。

## 6. 自验与变异测试

### 6.1 用例

| 用例 | 打什么 | warnings |
|---|---|---|
| `basic` | 连堂两节由合并拼成 1-2 节、全周 / 单周 / 双周 / 落单的一周（第 20 周）、同一块里 `args[1]` 是 `teachers.join(",")` 表达式、教室带括号原样保留（`实验楼C-208(东区)`，上游会砍成 `实验楼C-208`）、教师与教室都拿不到的「体育(三)」、`unitCount = 14` 从页面读到、学期列表是教务的裸 JS 对象字面量（键不带引号）、当前学期 id 20261 | 3 |
| `weeks-bitmap` | **位图基准对照**：第 0 位为 1（只出声、不产出第 0 周）、第 12 位为 1（下标即周次）、第 31 位为 1（越界丢弃并出声）；没有学期列表 → 开学日 / 总周数 / 学期名全部推算并逐条出声 | 7 |
| `course-name` | 课程名括号的四种形态（见 §7） | 2 |
| `fallbacks` | 页面没写 `unitCount`（缺省 14 + 出声）、`index` 是裸数字 62 与字面乘数 `2*14+3`、`index` 算出星期 13 越界丢弃、第 13 节超出内置作息表顺推补齐、`semesterRaw` 是合法 JSON（另一种形态）、教务说 12 周但课表到第 16 周 → 抬总周数、位图里混了 2 个非 0/1 字符 | 8 |
| `second-term` | 第二学期 + 学期起始日 2026-02-25 是星期三 → `firstDay` 必须是 2026-02-23（手册 §4.3）、总周数由起止日期算出 19 周、学期名「2025-2026学年第二学期」 | 3 |

### 6.2 变异测试记录（18 处，全部实跑）

改坏是**在内存里**做的（读 `parse.js` → 字符串替换 → `vm` 求值），工作区文件只读不写；
改坏前先跑基线：`basic=MATCH　weeks-bitmap=MATCH　course-name=MATCH　fallbacks=MATCH　second-term=MATCH`。

| # | 把哪一处改坏 | 变红的用例（差异点） |
|---|---|---|
| ① | 位图 0 位不再特判（等于上游的 `weeks.push(j)`） | `weeks-bitmap`（`warnings.length 6 != 7` —— 第 0 周仍被拦，但「0 位是占位符」这条说明消失） |
| ② | 位图基准整体 +1（0 位当第 1 周） | **五个用例全部**（`endWeek 17 != 16`、`weeks-bitmap` 的 `2 != 1`） |
| ③ | 课程名：尾部括号一律砍掉 | `basic`、`course-name`（`"高等数学A" != "高等数学A(一)"`） |
| ④ | 课程名：先 `split("(")` 再取第一段（上游写法） | `basic`、`course-name`（同上） |
| ⑤ | 教室：去掉所有括号内容（上游写法） | `basic`（`"实验楼C-208" != "实验楼C-208(东区)"`） |
| ⑥ | 教师拿不到时写「未知教师」（上游写法） | `basic`（`"未知教师" != null`） |
| ⑦ | `unitCount` 读不到时不写 warning（静默用缺省值） | `fallbacks`（`warnings.length 7 != 8`） |
| ⑧ | `index` 只认带 `unitCount` 的写法（不认裸数字） | `fallbacks`（`blocks.length 1 != 2`） |
| ⑨ | 周次段不再分单双周（一律 ALL） | `basic`、`course-name`（`weekType "ALL" != "ODD"`） |
| ⑩ | 开学日不回退到周一 | `second-term`（`firstDay "2026-02-25" != "2026-02-23"`） |
| ⑪ | 学期名改用适配器名 | `basic`、`course-name`、`fallbacks`、`second-term` |
| ⑫ | 越界周次静默丢弃（不写 warning） | `weeks-bitmap`（`warnings.length 6 != 7`） |
| ⑬ | `args[1]` 是表达式时不剥（上游写法） | `basic`、`fallbacks`（教师变成 `teachers.join(",")`） |
| ⑭ | 超出内置作息表的节次不再补时间 | `fallbacks`（`periodTimes.length 12 != 13`） |
| ⑮ | 总周数不被课表里更晚的周次抬高 | `fallbacks`（`totalWeeks 12 != 16`） |
| ⑯ | 位图里非 0/1 的字符静默跳过（不计数） | `fallbacks`（`warnings.length 7 != 8`） |
| ⑰ | 作息表里写坏的整点（`85:45`）不再被拦下 | **五个用例全部**（`periodTimes.length 11 != 12`、`fallbacks` 的 `end "10:00" != "09:05"`） |
| ⑱ | `args[1]` 是表达式时不再出声 | `basic`、`fallbacks`（`warnings.length 2/7 != 3/8`） |

**没能被用例挡住的一处（诚实记录）**：把 `timeOf()` 从作息表那一行去掉（直接写原值）时用例仍然
全绿 —— 因为内置表里的值本来就是合法 `HH:mm`。这一处的真正防线是**表被写坏时**（⑰ 已验证有效），
而「表写坏了但 `timeOf` 还在」这一侧无法从黑盒用例观察（`parse.js` 不打印被丢弃的节次）。
不为此加一个只为凑变异数存在的分支。

### 6.3 自验方式（全部用 node，没有跑 `./gradlew`）

- **fixture 比对**：五条用例在 `vm` 里实跑 `parse.js`，与 `*.expected.json` 逐字段比对
  （键顺序无关、数组顺序有关）：**全部 MATCH**。
- **期望值独立性**：另有一份**手写的**独立期望值（§3 第 3 点），与 `parse.js` 输出比：
  **五条全部 MATCH**。`*.expected.json` 是这份独立期望值的落盘版本。
- **载荷合法性**：五条期望载荷按 `JwPayloadCodec.validate` 的规则逐条核过
  （`totalWeeks` 1..30、`endWeek ≤ totalWeeks`、`weekType ∈ {ALL,ODD,EVEN}`、时间 `HH:mm`
  且不递增、`warnings ≤ 20` 条且每条 ≤ 200 字、`firstDay` 是 `yyyy-MM-dd`、`periodIndex ≥ 1`）：
  **全部通过**。
- **`extract.js` 桩测试**（CI 里跑不到的那一段）：桩页面 + 桩 `fetch`，三种页面地址各跑一遍 ——
  **三条请求**（GET 探测页 → POST 学期列表 → POST 课表），顺序与 §1 一致；挂在 `/webvpn` 前缀下时
  路径跟着前缀走（`https://portal.xatu.edu.cn/webvpn/eams/…`）；老 WebView 没有 `location.origin`
  时退回 `protocol + "//" + host`；**交出的 JSON 里不含学号**。
- **对抗输入 18 组**：空输入、`courseTableHtml` 缺失 / 是数字、页面完全不是课表、`TaskActivity`
  参数不足 7 个、位图全 0、位图满是垃圾字符、`index` 是负数与巨大值、`unitCount` 是 0 / 999、
  课名是数字、`semesterRaw` 是乱码 / 空对象 / 坏日期、学期跨度 3 年、`today` 缺失 / 是垃圾、
  课表页为空串：**没有一处产出非法载荷**。失败时给的是**带定位的报错**
  （「识别到 N 条排课活动：X 条参数不足、Y 条找不到节次、Z 条没有周次…」），
  不会被误读成「假期还没排课」。
- **ES5**：CI 同款检查（整文件字符串里找 `=>`、反引号、`let `、`const `）：
  **`parse.js` / `extract.js` 都是 0 命中，一行都没有**。没有 `eval` / `new Function`。
- **内存**：两个脚本 + `manifest.json` + 十个 fixture 的 NUL 字节计数均为 **0**，无 BOM；
  `parse.js` 与 `extract.js` 都是 LF 行尾（混合行尾会让含 `\n` 的变异锚点失效）。

## 7. 课程名里的括号：本件的决定与依据（本条是任务书点名要写清楚的）

**决定：保留，只在「尾部括号是明显的课程代码」时才去掉。**

上游的写法是 `(args[3] || "未知课程").split('(')[0]` —— 在**第一个**左括号处截断。后果：

| 上游输入 | 上游结果 | 本件结果 |
|---|---|---|
| `高等数学A(一)` | `高等数学A`（**丢信息**） | `高等数学A(一)` |
| `大学英语(双语)` | `大学英语` | `大学英语(双语)` |
| `体育(3)` | `体育` | `体育(3)` |
| `数据结构(2024.01)` | `数据结构` | `数据结构`（代码去掉，与上游同结果） |

**判断依据**：

1. **手册 §4.7 的取向是保真**：「课程名」是用户认课的主要线索，砍掉的若是课程名的一部分就找不回；
   教室那一条明确说「原样带过去即可」；教师那一条说「空着比写『未知』好」——同一节的三条都指向
   「宁可多留、不可改写」。
2. **同族六件的上游自己就认为该只去代码，而不是全砍**：`UESTC` 去的是 `(MATH1001.01)` 这种
   课程代码、`NEUQ` 去的是 `([\d.]+)`、`CUIT` 去的是 `(2024123456.01)`、`HIIT` /
   `ZZVCAE` 去的是**尾部**的一个括号说明。也就是说「去尾部代码括号」是这一族的共识做法，
   而 `XATU` 的 `split('(')[0]` 是这六件里**最激进的一个**（它连中间的括号都砍）。
3. **两个方向的错法哪个更难发现**：砍多了 = 用户看到「高等数学A」以为课表对了（周次、节次都正常），
   根本不会去报；留多了 = 用户在课名里多看到一段「(一)」，看一眼就能自己判断，也能反馈。
   **错在「多留一段」这一侧是可发现、可逆的。**
4. 所以判据写成**只有明显是代码才去**：`^[0-9]{4,}([.\-_][0-9]+)*$`（`2024.01`、`2024123456.01`）、
   `^[A-Z]{1,4}[0-9]{2,}([.\-_][0-9A-Za-z]+)*$`（`MATH1001.01`）、或「课程代码 / 课代码」字样。
   **短数字（`体育(3)`）与中文（`高等数学A(一)`、`大学英语(双语)`）一律保留**。
5. **fixture**：`fixtures/course-name.extracted.json` 用四条钉死两侧 —— 前三条必须**带括号输出**、
   第四条必须**去掉代码**。**变异 ③、④**（改成尾部括号一律砍 / 改成上游的 `split`）都让这条
   用例变红，说明它真的在看着这一处（`basic` 那条「高等数学A(一)」也一起红）。

**已知代价**：若本校教务在课名尾部写的是「教学班序号」而不是课程代码（本件没有真实样本，
无法排除），那些带序号的课名会多留一段括号。这是**有意的取舍**（依据第 3 点），
真机上若发现课名里普遍多出一段序号，改 `isCourseCode` 一处即可。

## 8. 没能确定的事（交接给下一位）

- **fixture 是合成的**：按上游实际解析的课表页形态（`var teachers = […]` + `activity = new
  TaskActivity(…)` + `index = …`）编出来的形状正确的数据，**不是真实抓取**，课名 / 教师 / 教室 /
  日期均为虚构（测试方案 §3）。它只保证「同样的输入永远得到同样的输出」，**不保证真实页面上解析
  正确**。拿到真实 dump 后请替换 fixture 并重跑门。
- **`semesterCalendar` 的字段名**：本件按 `id` / `schoolYear` / `name` / `startDate` / `endDate`
  读，并顺带认 `xn` / `xq` / `beginDate` / `dateBegin` / `ksrq` 等别名。上游只用 `id` / `schoolYear` /
  `name` 三个字段（它不问起止日期）。**真实部署若用别的字段名，开学日会退化成推算 —— 有 warning，
  不静默。** 这是本件最需要在真机上确认的一处。
- **开学日回退到周一这条只在「教务给的起始日不是周一」时出声**：若教务给的本来就是周一，
  不会写 warning（`basic` / `course-name` / `fallbacks` 就是这个情形）。
- **教务说「学期还没开始」时本件仍会导入**：`totalWeeks` 抬到课表最晚周次并写 warning，
  用户能在导入预览里看到。反过来（课表里全部课程都很早结束、教务给的周数更长）保持教务的值。
- **上游的「教 3 楼第 3-4 节改 10:10–11:40」没有搬**（§4 第 12 条）。本校 `/教3/` 的课时间若确实
  被学校单独调整过，课表上显示的会是内置作息表的 10:20–11:05，**比实际早 10 分钟**。这是**已知
  偏差**，真机上核对一次即可确认；若要补，位置在 `periodTimesFor` 之后、组装 block 之前。
- **`unitCount` 缺省 14 是从上游继承的**：页面读不到时用它（并出声）。若本校实际是别的节数，
  节次与星期会整体错位 —— warning 里已经点明「节次与星期可能整体错位」。
- **没有真机验证**：无账号。真实页面上要确认的是：探测页的 `bg.form.addInput(form,"ids","…")`
  与 `semesterBar\d+Semester` 是否仍然是这两种形态、三条接口的路径与请求体是否仍然有效、
  学期栏那个元素上是否真有 `value`（没有就退到 `semesterId` / 列表最后一条，有 warning）、
  以及 `unitCount` 到底是多少。`loginUrl` 给的是 http（应用会显示「不安全连接」标记）。

## 9. 签名

- 上游作者：`晨熯`（shiguang_warehouse，MIT）
- 移植：`0x7E-2023`
- 日期：2026-09-17