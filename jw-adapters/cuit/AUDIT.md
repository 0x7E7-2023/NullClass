# 安全审计与移植说明：`cuit`（成都信息工程大学）

- **平台**：树维 EAMS（接口路径 `/eams/`），上海树维信息科技有限公司（SupWisdom，新开普子公司）。
  判据是**脚本实际请求的接口路径**（`courseTableForStd.action` / `dataQuery.action` /
  `courseTableForStd!courseTable.action`），不是上游注释。**本件不写「强智」**。
- **上游出处**：`shiguang_warehouse` 的 `resources/CUIT/cuit_bk_old.js`，
  commit `e62554a4034386b893bcd6813c7b2b64f8c730a3`（2026-09-12），MIT，作者 **igugyj(Pfolg)**。
  同目录 `resources/CUIT/adapters.yaml` 里 CUIT 有**两条**适配器：

  | id | 名称 | 脚本 | import_url |
  |---|---|---|---|
  | `CUIT_01` | 成都信息工程大学**本科实践教学平台** | `cuit_bk_new.js` | `https://ywtb.cuit.edu.cn/` |
  | `CUIT_02` | 成都信息工程大学**教务管理系统** | `cuit_bk_old.js` ← **本件** | `https://jwc.cuit.edu.cn/` |

  本件取的是 `CUIT_02`，`loginUrl` 用 `https://jwc.cuit.edu.cn/`。
- **移植者**：0x7E7-2023　**日期**：2026-09-17
- **取数方式**：接口三连（同源相对路径）→ 课表页 HTML 里课程是**内嵌的 JS 块**
  （`activity = new TaskActivity(...)` + `index = 星期 * unitCount + 节次`），
  与同族 `hpu` / `uestc` / `zua` / `zzvcae` / `dlmu` / `neuq` / `hfnu` / `xatu` / `tjau` 同一路数。
- **上游脚本自带的危险写法**：`Function("return (" + 学期响应 + ")")()`
  —— 把**网络取回的字符串**当代码执行（移植手册 §5 第 6 条）。
  **本件没有移植它**：`extract.js` 改用 `JSON.parse`（带「按第一个 `{` 到最后一个 `}` 截取」的兜底），
  并且**上游的 `Function` 用在取数侧**（解析 `semesterCalendar` 响应），
  这一段本来也不会进 `parse.js`。见下面「移植时删掉 / 改掉的上游行为」第 1 条。

---

## 1.（专门）位图基准：为什么没有照抄上游的 `i + 1`

### 上游写的是什么

`cuit_bk_old.js` 第 36-43 行，一字不改地抄下来是：

    function parseValidWeeksBitmap(bitmap) {
        if (!bitmap || typeof bitmap !== "string") return [];
        const weeks = [];
        for (let i = 0; i < bitmap.length; i++) {
            if (bitmap[i] === "1") weeks.push(i + 1);
        }
        return weeks;
    }

也就是「位图下标 `i` = 第 `i + 1` 周」，等价于说**位图第 0 位是第 1 周**。

### 同族的证据全都指向相反的一面

同批 12 件里，另有 **5 件**在代码或注释里写死了相反的口径，原文如下：

| 上游件 | 原文（逐字） |
|---|---|
| `UESTC/uestc.js:94` | `// EAMS 二进制串: position i = week i（position 0 始终是 0，忽略）` |
| `UESTC/uestc.js` | `weeks.push(i)` 之后 `.filter(w => w > 0)` —— 把第 0 位滤掉 |
| `HPU/hpu.js` | `if (text[i] === "1" && i >= 1) weeks.push(i);` |
| `HUNNU/hunnu.js:34` | `* 位置0固定为0（占位符），位置1=第1周，位置2=第2周...`，循环是 `for (let i = 1; ...)` |
| `ZUA/zua.js:69` | `for (let week = 1; week < value.length && week <= MAX_SUPPORTED_WEEK; week++)`，`weeks.push(week)` |
| `ZZVCAE/zzvcae.js:89` | 同 `zua`：`for (let week = 1; ...)`，`weeks.push(week)` |

也就是说：**12 件里只有 `cuit` 一件写了 `+ 1`**，另外 5 件明确写「位图下标就是周次、0 位是占位符」，
其余各件（`dlmu` / `neuq` / `hfnu` / `xatu` / `tjau`）也都是 `weeks.push(i)` 不带 `+ 1`
（它们的 0 位**会**产出「第 0 周」，只是本批统一口径在这一点上收紧，见批次四计划）。

### 本件按哪个口径实现，为什么

**按同族统一口径**（批次四计划里定的硬结论）：

> 位图下标 `i` 就是第 `i` 周，下标 0 是占位符。
> `bitmap[i] === '1'` 且 `i >= 1` → 第 `i` 周；`bitmap[0] === '1'` → **不产出周次，写一条 `warnings`**。

代码位置：`parse.js` 的 `weeksOfBitmap()` ——

    if (s.charAt(i) !== '1') continue;
    if (i === 0) { placeholder = true; continue; }   // 占位符：不产出周次，计入 warnings
    weeks.push(i);                                   // 下标 i 就是第 i 周

理由（照计划，逐条）：

1. **上游那个 `+ 1` 是孤例**：同一个平台、同一个接口、同一段循环骨架
   （`for (i = 0; ...) if (bitmap[i] === '1') push(...)`），只有 `cuit` 多写了个 `+ 1`；
2. **没有任何证据**表明 cuit 的位图格式与同族不同 —— 上游脚本里既没有注释说明这点，
   也没有任何一处代码为它辩护；
3. **真机核对成本极高**（维护者手上没有账号，见 `docs/jw-adapter-testing.md` §3），
   而「照抄一个孤例」的风险大于「照统一口径」的风险：口径错了是整体差一周，
   孤例错了也是整体差一周，但前者的证据基础强 5 倍；
4. 批次四的一致性要求：这一族 12 件必须收敛成一种读法，否则同一个 bug 会在 12 件里各修一次。

### 真机核对怎么做（交给有账号的人 / 用户反馈）

1. **最快的办法**：导入后打开任意一门**已知周次**的课，看它的起止周。
   若课表上写着「第 1-16 周」而空课里显示「第 2-17 周」（或「第 0-15 周」），就是基准错了。
2. **第二快的办法**：看导入预览里的 `warnings`。本适配器对**位图第 0 位为 1 的课**
   会写一条「有 N 门课的周次位图第 0 位是 1……已按忽略处理」。
   如果真机上**这条几乎每门课都出现**，说明「第 0 位=第 1 周」才是对的（0 位是真实周次，不是占位符）。
   如果它**从不出现**（0 位恒为 0），说明占位符约定成立，本件的口径正确。
3. **修法（计划里点名要求写出来）**：如果确认整体差一周，**只改一处** ——
   把 `parse.js` 里 `weeksOfBitmap()` 这一行的 `weeks.push(i);` 改成 `weeks.push(i + 1);`，
   并把上面那行 `if (i === 0) { placeholder = true; continue; }` 一起改成
   `if (i === 0) { placeholder = true; }`（让 0 位产出第 1 周），
   同时把 `fixtures/weeks-bitmap` 的期望值按新口径重算。**除此之外不需要动任何地方**。

### 这一条被 fixture 钉住了

`fixtures/weeks-bitmap` 是**专门**为这个口径造的，用的是「编码基」位图
（第 0 位是占位符，1 落在奇数下标上，与上游 `cuit` 自己在同族里的奇数开头一致）：

| 课程 | 位图 | 期望 |
|---|---|---|
| 计算机网络 | 只有第 0 位是 `1`（长度 1） | **不产出周次** → 课程被跳过，进 `warnings` |
| 操作系统 | 第 1、3、5 … 15 位是 `1` | 第 1、3 … 15 周（`ODD`）→ **「第 1 位 = 第 1 周」** |
| 编译原理 | 第 2、4、6 … 58 位是 `1` | 第 2 … 30 周（`EVEN`，超 30 截断并 warn） |

变异测试（见第 12 节 M1 / M2）证明：**照抄上游的 `i + 1` 或去掉 `i >= 1`，
`weeks-bitmap` 这条用例都会变红**。

---

## 2.（专门）`loginUrl` 与脚本请求的主机不同源，以及 `allowHosts` 为空的前提

### 事实

| | 主机 | 出处 |
|---|---|---|
| 登录域（`loginUrl`） | `https://jwc.cuit.edu.cn/` | 上游 `adapters.yaml` 的 `import_url`（`CUIT_02`） |
| 上游脚本里写死的教务域 | `http://jwgl.cuit.edu.cn` | `cuit_bk_old.js` 第 5 行 `const BASE = "http://jwgl.cuit.edu.cn";` |

**这是两台不同的主机**（`jwc` ≠ `jwgl`，而且上游还是 `http`）。同族的学校也普遍如此：
登录页在校级门户或 CAS 上，真正的教务服务挂在 `jwgl` / `jwxt` / `jwglxt` 这类子域上。

### 本适配器怎么处理

**只发当前页面同源相对路径**，主机从 `window.location.origin` 拼，上下文路径取当前地址里的 `/eams`
（挂在校级门户前缀下也能跟着走）：

    var EAMS = '/eams';
    function eamsBase() {
        var path = String(window.location.pathname || '');
        var at = path.indexOf(EAMS + '/');
        if (at > 0) return originOf() + path.substring(0, at + EAMS.length);
        return originOf() + EAMS;
    }

于是：

- 用户**在教务课表页上点「提取课表」**（正常路径）→ 请求全部同源 → `allowHosts` **留空即可**；
- 用户**在校级门户 / 登录页上点**（少见，但可能）→ 相对路径解析到门户域，会被应用的
  白名单闸门拦下（闸门只放行 `loginUrl` 同源 + `allowHosts`）。

### `allowHosts: []` 的前提（写清楚，不写得比代码宽）

**前提是「必须从教务课表页发起提取」。** 这是本件与同族 `ccit`（WebVPN 学校）相同的取舍：
不把主机名写死，就不会在校外 / 校内、网关 / 直连两条路上各写错一次。

为了让这个前提失败时用户能照做，`extract.js` 在**连不上**时的报错里带一句可操作提示
（`hostHint()`）：当前主机名不像教务域（不含 `jwgl` / `jwxt` / `jwc`）时，提示
「请先在教务系统里打开课表页再点提取课表」。

**如果真机上教务不在当前源**（即用户在 `jwc.cuit.edu.cn` 上点提取、而课表接口只在
`jwgl.cuit.edu.cn` 上），修法是**在 `manifest.json` 的 `allowHosts` 里补上教务主机名**：

    "allowHosts": ["jwgl.cuit.edu.cn"]

并同时把 `extract.js` 的 `eamsBase()` 改成用绝对主机（或保留同源但仍需白名单放行）。
**不要写通配**（`*.cuit.edu.cn` 会把这个域下所有服务都放行，违反 §5 第 3 条「通配要克制」）。
这一处**没有证据**支持任一方，所以本件选了「同源 + 不写死」这条更能扛住变化的做法，
并把风险摆在这里。

---

## 3.（专门）`args[2]` 的用法（同族只有本件用它）

### 上游怎么用

`cuit_bk_old.js` 第 95-104 行：

    let teacherExpr = args[1];
    const courseFull = unquoteJsLiteral(args[2]);
    let courseNameRaw = unquoteJsLiteral(args[3]);
    const classroom = unquoteJsLiteral(args[5]);
    const weekBitmap = unquoteJsLiteral(args[6]);

    let courseName = courseNameRaw || courseFull.replace(/\(.*\)/, "");
    courseName = cleanCourseName(courseName);
    if (!courseName) continue;

也就是：**`args[3]` 是课程名（首选），`args[2]` 是课程全称（`args[3]` 为空时的回落）**；
回落时还会先把全称里第一个括号段剥掉（`replace(/\(.*\)/, "")`），再交给 `cleanCourseName`。

同族比对（同一批的其余 11 件）：`hpu` 用 `args[3]`、`uestc` 用 `args[3]`、
`zua` / `zzvcae` 用 `args[3]` —— **没有第二件用 `args[2]`**。

### 本件怎么移植（原样，但去掉一处已知的破坏性副作用）

    var courseFull = unquoteJsLiteral(args[2]);
    var courseNameRaw = unquoteJsLiteral(args[3]);
    ...
    var courseName = cleanCourseName(courseNameRaw || courseNameFromFull(courseFull));

- **优先级原样**：`args[3]` 优先、为空才用 `args[2]`；
- **剥编号的规则统一成一条**：`cleanCourseName()` 只剥末尾形如 `(10 位数字.2 位数字)`
  的教学班编号（上游的规则）。上游回落分支里那句 `courseFull.replace(/\(.*\)/, "")`
  会把「计算机网络(卓越班)」这种**真名字**里的括号段也剥掉，
  同一门课在「args[3] 有值」和「args[3] 为空」两条路径下会得到不同的课名 ——
  本件把它换成同一条规则（`courseNameFromFull()`），**只剥编号、不剥真名**。
  这是移植改动里唯一一处「上游会算出两个不同结果」的地方，已写进文件头第 ⑩ 条。
- **空串保护**：`unquoteJsLiteral` 把 `null` / `undefined` / 空串统一成 `''`，
  与上游一致；两条路都空则不进 `courses`，计入 `emptyName` 并写进 `warnings`。

### 拿不到证据的地方

「`args[2]` 到底是课程全称还是教学班名」**没有真机样本**，本件只能按上游的用法与命名推断。
`fixtures/edge-bare-index` 故意造了一条 **`args[2]` = 「大学物理实验(2700120020.02)」、
`args[3]` = 「中国文化概论(2500120110.05)」** 的 activity，期望课名是**后者**剥编号的结果
（「中国文化概论」）—— 变异测试 M3 把两者调换，这条用例立刻变红（见第 12 节）。
真机核对办法：导入后看课名是不是**教学班编号之前的那一段**（本件期望的形状）。

---

## 4. 脚本实际请求了哪些域与路径（全部）

`extract.js` 里**只有一处 `fetch` 调用点**（`request()` 函数），全部 URL 由
`window.location.origin` + `/eams` 拼出，**源码里没有任何绝对 URL**（连注释里也没有可执行的地址）。
三条请求（上游同款，逐字照搬路径与请求体）：

| # | 方法 | 路径（相对当前源） | 请求体 | 用途 |
|---|---|---|---|---|
| 1 | GET | `/eams/courseTableForStd.action?&sf_request_type=ajax` | — | 读学号 `ids` 与当前学期组件 `semesterBar…Semester` |
| 2 | POST | `/eams/dataQuery.action?sf_request_type=ajax` | `tagId=<学期组件 id>&dataType=semesterCalendar`（有当前学期 id 时再加 `&value=<id>`） | 学期列表（含起止日期） |
| 3 | POST | `/eams/courseTableForStd!courseTable.action?sf_request_type=ajax` | `ignoreHead=1&setting.kind=std&startWeek=&semester.id=<学期 id>&ids=<学号>` | 课表页 HTML |

请求头只有 `Content-Type` / `X-Requested-With` / `Accept`，`credentials: 'include'`
（用用户当前的会话 cookie，本脚本不读、不存、不上报任何 cookie 或令牌）。

`parse.js` **一个网络调用都没有**：没有 `fetch` / `XMLHttpRequest` / `WebSocket` /
`sendBeacon` / `new Image()` / `document` / `window`，也没有 `eval` / `new Function`。

**请求体里带出去的东西**：只有学期 id 与**用户自己的学号**（`ids`，上游同款，教务接口的必需参数）。
学号**不会写进输出**：`extract.js` 交出去的 JSON 里 `term` / `semesters` / `raw.tableHtml` 三块
都不含学号，fixture 里也没有。

### `allowHosts`

    "allowHosts": []

依据：请求全部是同源相对路径，主机只能是 `loginUrl` 那一个（或用户实际打开的那一个）。
代价与失败的修法见第 2 节。

---

## 5. 读取面（`extract.js` 到底读了页面上的什么）

| 读的东西 | 从哪读 | 用来干什么 |
|---|---|---|
| `bg.form.addInput(form,"ids","<数字>")` | 第 1 条请求的响应 HTML | 取学号构造第 3 条请求（**不写进输出**） |
| `id="semesterBar<数字>Semester"` 元素（以及它前面的 `value="<数字>"`） | 同上 | 取当前学期 id（自动取当前学期，替代上游的问用户） |
| 学期列表 JSON 的 `semesters` / `semesterId` | 第 2 条请求的响应 | 取当前学期的 id、名字、`schoolYear`、**起止日期** |
| 课表页 HTML | 第 3 条请求的响应 | **原样**交给 `parse.js`（`raw.tableHtml`），本脚本不解析课程 |

**没有读**：成绩、学籍、个人信息、缴费、选课、`localStorage` / `sessionStorage` /
`document.cookie`、密码框、任何表单值（除了上面那个学期组件的 `value`）。
`window.location` 只用来取 `origin` / `pathname` / `hostname`（拼相对地址 + 那句提示），
不读 `search` / `hash`。

---

## 6. 有没有绕过白名单的写法 —— 逐类点名

| 类别 | 有没有 | 说明 |
|---|---|---|
| `eval` / `new Function` | **没有** | 上游那处 `Function(...)` 没有移植（改用 `JSON.parse` + 截取兜底） |
| 动态 `import()` / `require` | 没有 | 两份脚本都不加载外部代码 |
| `sendBeacon` / `WebSocket` / `EventSource` | 没有 | — |
| `new Image().src` / `<img>` 探针 | 没有 | — |
| Service Worker / 缓存投毒 | 没有 | — |
| 写 DOM / 改表单 / 触发提交 | **没有** | 两份脚本都不碰 `document`（`extract.js` 也不写页面） |
| 读 `localStorage` / cookie / 令牌 | 没有 | 会话 cookie 由 `credentials: 'include'` 交给浏览器，脚本本身不读 |
| 第三方域 / 统计 / 埋点 | 没有 | 唯一请求点见第 4 节，全部同源；`console` 一次都没调用 |
| 重定向外发 | 没有 | 不构造重定向、不跟随到别的域 |
| 硬编码密钥 / 他人学号 / 固定令牌 | 没有 | 学号是运行时从用户自己的会话里读的 |

---

## 7. 逐条：移植手册 §5 八条

| # | 检查项 | 结论 | 依据 |
|---|---|---|---|
| 1 | 不碰凭据 | ✅ | 不读密码框 / 登录表单 / `localStorage`；请求靠 `credentials:'include'` 的会话 cookie；不在任何地方外发 |
| 2 | 不外发 | ✅ | 唯一请求点 `request()`，URL 由同源拼出；无第三方域、无统计、无埋点 |
| 3 | 请求域可控 | ✅ | 三条请求全部同源相对路径；`allowHosts: []`；未使用任何通配；主机与路径穷举见第 4 节 |
| 4 | 只读课表 | ✅ | 只读课表入口页、学期列表、课表页三处；不读成绩 / 学籍 / 个人信息 / 缴费 |
| 5 | 不埋点 | ✅ | 没有 `console` 之外的任何上报；`console` 也没有调用 |
| 6 | 不 eval 远程代码 | ✅ | 上游 `Function("return (" + 响应 + ")")()` 未移植；全仓禁用 `eval` / `new Function`（两份脚本 grep 计数为 0） |
| 7 | 不写页面 | ✅ | 两份脚本都不写 DOM、不改表单、不触发提交。唯一碰到 `window` 的地方是 `extract.js` 里取 `window.location` 的 `origin` / `pathname` / `hostname`（拼同源地址 + 那句失败提示），`parse.js` 里连 `window` / `document` 都没有 |
| 8 | 不依赖用户输入之外的秘密 | ✅ | 无硬编码密钥 / 令牌；学号来自用户自己的会话，且不写进输出 |

---

## 8. 逐条：本批（批次四）12 条检查表

1. **周次位图基准** ✅ 统一口径（`week = index`，`index 0` 不产出周次并 warn）。
   专门一节见第 1 节；对照用例是 `fixtures/weeks-bitmap`（0 位为 1 / 1 位为 1 两条都在）。
2. **`TaskActivity` 参数位** ✅ `args[3]` 课程名、`args[5]` 教室、`args[6]` 位图；
   `args[1]` 是 `actTeacherName.join(",")` 表达式时按 activity 之前**最近的一块** `teachers` 解析
   （字面量原样取）；`args[2]` 只有本件用，见第 3 节。
3. **`index` 的两种写法** ✅ 带变量的 `/^\s*(\d+)\s*\*\s*unitCount\s*\+\s*(\d+)\s*$/`
   与**裸数字**都认（裸数字 = 线性下标 → `floor(v/unitCount)+1` 星期、`v%unitCount+1` 节次，
   与同族 `hpu` 一致）；**没有 `eval` / `new Function`**。
   上游只认带变量的写法，裸数字分支是本件**补的**（见第 11 节第 3 条）。
4. **`unitCount` 要真的读** ✅ 正则 `var unitCount = (\d+);`，读到的值还会做 1..31 合理性检查；
   读不到用缺省 12 并**同时写进 `warnings`**（`fixtures/edge-bare-index` 覆盖）。
5. **作息时间从哪来** ✅ 上游内置表，原样搬（第 1 节 08:20-09:05 … 第 12 节 21:20-22:05）。
   所有时间都过 `HH:mm` + `00:00–23:59` 校验（越界的会写 `warnings`，不会进载荷）。
   **载荷不写 `periodTimes`**（理由见第 11 节第 5 条）：上游本来就是「作息与课程分两份」，
   而应用缺省用的 12 节表与本校作息同形（1-4 上午 / 5-8 下午 / 9-12 晚上），
   所以按「作息来自教务」处理，并在 `warnings` 里说明第 1 节 08:20 供核对。
6. **开学日** ✅ 优先 `semesterCalendar` 的学期起止日期 → 取学期第一天 → 回退到那一周的周一
   （手册 §4.3）；起止日期读不到、或**不属于本学期**（离锚点 > 45 天）时按最近的周一推算
   （第一学期 = 9 月 1 日所在周的周一，第二学期 = 2 月 20 日所在周的周一），
   两种情况**都写 `warnings`**。**没有保留问用户的路径**（上游是导入后让用户自己填日期）。
7. **周次上限** ✅ 位图能到第 54 周，本件把超 30 周的一律**截断到 30 并 warn**
   （段落在 30 以内的一部分保留）；总周数按上限 30 截断。
8. **`teacher` / `location` 拿不到就留空** ✅ 一律 `null`（不写「未知教师」「待定」）。
   上游同族 `hpu` 写的 `|| "未知教师"` / `|| "待定"` 没有移植。
9. **学期名** ✅ 用教务给的（`term.name`，形如「2026-2027学年第一学期」）；
   拿不到用「学年 + 第 N 学期」，再拿不到用「成都信息工程大学 + 第 N 学期」。**不用适配器名**。
10. **`allowHosts`** ✅ 本件请求全部同源相对路径 → `[]`（第 2 节写了前提与失败修法）。
11. **`warnings` 上限** ✅ 代码里 `MAX_WARNINGS = 20` / `MAX_WARNING_CHARS = 200`，
    超长的截断加省略号，超条数的在最后一条说明「另有 N 条……没有显示」。本件实际最多 9 条。
12. **变异测试** ✅ 8 组，记录见第 12 节。

---

## 9. 移植时删掉 / 改掉 / 没做的上游行为

1. **动态求值（`Function(...)`）没有移植**。上游用它解析 `dataQuery.action` 的学期响应
   （响应是「像 JSON 的 JS 对象字面量」）。这里用 `JSON.parse`，失败时按第一个 `{` 到最后一个 `}`
   截一段再试（去 BOM / 去包裹文本）；**仍然解析不了就报错**，不猜学期、不静默用空列表。
   附带效果：响应里如果真夹了 JS 表达式（而不是 JSON），本件会**明确失败**而不是执行它。
2. **弹窗问学期没有移植**（上游 `showSingleSelection` 列最近 8 个学期让用户挑）。
   改成自动取当前学期：学期接口的 `semesterId` → 入口页学期组件的 `value`（上游脚本自己
   就解析了这个值，只是没用它）→ 学期列表最后一项（**上游弹窗的默认项就是最后一项**）。
3. **开学日不再靠用户**：上游把 `semesterCalendar` 的起止日期丢掉了，用户只能导入后自己填。
   本件把 `startDate` / `endDate` 读出来交给 `parse.js` 定 `firstDay`（第 8 节第 6 条）。
4. **上游的 `Set` / `Map` / `for-of` / 扩展运算符全部改成 ES5 的数组与对象**。
   `parse.js` 里没有 `Set` / `Map`（连 Rhino 支持也不用），排序全部换成按码位比较 +
   完整次级键，避免中文排序随引擎变化（fixture 是逐数组比对的）。
5. **上游的正则 `/gs` 标志改掉了**。上游的教师块正则带 `s`（dotAll，ES2018），
   **Rhino 1.8 的 `RegExp` 不支持 `s` 标志，会直接抛语法错误** —— 必须换成 `[\s\S]`
   （同族 `hpu` 也这么做）。这一处如果照抄，`parse.js` 在 CI 的 Rhino 里会**整个跑不起来**。
6. **块切法改写**：上游的块正则是
   `TaskActivity\(([^]*?)\)\s*;([\s\S]*?)(?=activity\s*=\s*new\s+TaskActivity|$)`
   —— `[^]*?` 是「任意字符」的笔误写法，末尾的 `$` 又没有 `m` 标志，
   **最后一块可能被吃掉**。这里改成「先找全部 `new TaskActivity(` 的位置，再按位置切块」，
   语义相同（`index` 归属于它前面最近的 activity，同族 `uestc` 也是这么切的），行为更好懂。
   括号配对按深度找（引号里的括号不计数，上游与 `uestc` 都没有处理引号）。
7. **上游的桥调用全部没有移植**：`showToast` / `showAlert` / `showSingleSelection` /
   `saveImportedCourses` / `savePresetTimeSlots` / `notifyTaskCompletion`。
8. **学号不写进输出**（上游也没有，这里明确保证 fixture 里不会出现）。

---

## 10. 已知边界与没做的事（诚实写）

- **fixture 是合成的**（4 对）。文件头（`_note`）写明了合成来源，代价是：
  真实教务页的 `TaskActivity` 块还可能有本件没覆盖的写法（例如 `args` 里带换行与注释、
  `activity` 变量带下标 `activity_1 =`、位图带引号转义）。**换成真实 dump 才算数**
  （`docs/jw-adapter-testing.md` §3 同口径）。
- **位图基准是推断的**（第 1 节），真机验证方法与「改一处即可」的修法已写明。
  这是本件**最大的不确定点**。
- **`args[2]` 的语义是推断的**（第 3 节）。
- **裸数字 `index` 分支是补的**（上游只认带 `unitCount` 的写法）：补它的依据是同族 `hpu`
  认这一支、且真机上出现时整门课会丢。代价是：万一 cuit 的裸数字是**别的编号体系**
  （不是线性下标），这门课会被放进错误的节次 —— 所以它不影响任何**已知**场景
  （真机上如果只有带变量的写法，这条分支永远不触发），只会让「一定会丢的课」变成
  「可能放错位置的课」，并写进 `warnings`（`unparsedIndex` / `dayOutOfRange` 计数）。
- **没有做真机验证**（没有账号）。没做的事还包括：只导入当前学期（不批量导历史学期）、
  不做 OCR、不做图片课表、不写任何 DOM。
- **`unitCount` 读到的值做了 1..31 的合理性检查**：超出范围按「没读到」处理（缺省 12 + warn）。
  依据：`unitCount` 是每天节次数，31 以上一定是脏数据。
- **`index` 只认单条赋值**：`index = a*unitCount+b` 里 `a` / `b` 只接受十进制数字字面量。
  写成 `index = day*unitCount+period`（变量）或带括号的表达式时，会计入 `unparsedIndex`
  并写 `warnings`，**不会静默丢**。
- **周次位图长度上限 60**（`MAX_BITMAP_WEEK`）：再长一定是脏数据，超出的位忽略。
  常见长度是 50-54 位。

---

## 11. fixture 与期望值怎么独立推出来的

合成本身：`%TEMP%` 下的生成脚本按上游读取的字段形状拼出课表页 HTML
（`activity = new TaskActivity("1001",actTeacherName.join(","),"课程全称","课程名(编号)","","教室","位图","",…)`
+ 若干 `index = 0*unitCount+0;`），再用 `JSON.stringify` 落地成
`.extracted.json` / `.expected.json`（**避免手写转义序列** —— 本批已知的坑是转义被落成真控制字符）。

期望值**不是**把 `parse.js` 的输出贴进去的，是按下面的规则逐字段算出来的：

| 项 | 怎么算的 |
|---|---|
| 周次 | 位图下标 = 周次（统一口径），0 位忽略；步长 1 → `ALL`、步长 2 → `ODD`/`EVEN`（手册 §4.1） |
| 节次 | `index = 星期(0 基)*unitCount + 节次(0 基)` → 星期 `+1`、节次 `+1`；连堂并成一段；不连的分两个 block |
| 开学日 | 学期第一天 `2026-09-07`（本身就是周一）→ 回退到周一 = `2026-09-07` |
| 总周数 | `2026-09-07` 至 `2027-01-17` = 133 天 → `ceil(133/7) = 19` 周 |
| 位图对照 | 全 `1` 的位图按上面规则能倒推出周次集合，再与「第 1 位=第 1 周」逐条对上 |
| 课名 | `args[3]` 剥掉末尾 `(10 位数字.2 位数字)`；`args[3]` 空时用 `args[2]` 同一规则 |
| 教师 | join 表达式 → activity 之前**最近一块** `teachers` 的 `name` 拼接；字面量 → 去引号 |
| 教室 | `args[5]`，空 → `null` |

四处 `warnings` 的文案也是按代码里的模板手算的（含数字），不是贴输出。

---

## 12. 变异测试记录（2026-09-17）

方法：把 `parse.js` **读进内存**后按点替换（工作区文件不动），用 Node `vm` 跑全部 4 对 fixture，
与 `expected` 逐字段比对。对照组（不改任何地方）全绿。

| # | 改坏哪里 | 变红的用例（首条差异） |
|---|---|---|
| **M1** | 去掉占位符分支（`if (i === 0) { placeholder = true; continue; }` → 去掉 `continue`），让第 0 位产出「第 0 周」 | `basic`（`startWeek 期望 1 实际 0`）、`weeks-bitmap`（warnings 8 ≠ 9；计算机网络产出第 0 周）、`edge-reject-dates`（`startWeek 期望 1 实际 0`） |
| **M2** | **照抄上游的 `i + 1`**（`weeks.push(i)` → `weeks.push(i + 1)`） | `basic`（`startWeek 期望 1 实际 2`）、`weeks-bitmap`（warnings 里「第 58 周」变「第 59 周」）、`edge-bare-index`（`startWeek 期望 1 实际 2`）、`edge-reject-dates`（总周数 15 → 16） |
| **M3** | 课程名取值优先级调换（`courseNameRaw \|\| courseNameFromFull(courseFull)` → `courseFull \|\| courseNameRaw`） | `basic`（课名「数据结构与算法」→「数据结构与算法(卓越工程师班)」）、`edge-bare-index`（课名「中国文化概论」→「大学物理实验」） |
| **M4** | `unitCount` 缺省 12 → 10 | `edge-bare-index`（warnings 文案「缺省 12 节」→「缺省 10 节」；裸数字 index 的换算结果也变） |
| **M5** | 删掉裸数字 `index` 分支（退回上游只认 `unitCount` 的写法） | `edge-bare-index`（warnings 7 条 → 9 条：裸数字的课整门丢掉 + `unparsedIndex` 计数） |
| **M6** | 教师块取「第一块」而不是「activity 之前最近的一块」 | `basic`（李慧敏,王海涛 → 陈立群）、`weeks-bitmap`（何冬梅 → 孙倩）、`edge-bare-index`（高鹏,陆晓岚 → 秦朗） |
| **M7** | 超 30 周的段整段丢掉（`run.end = MAX_WEEK;` → `continue;`）而不是截断 | `weeks-bitmap`（「编译原理」的 blocks 长度 0 ≠ 1） |
| **M8** | 不再校验学期起止日期是否属于本学期（`plausibleStart` 短路） | `edge-reject-dates`（warning 由「已忽略……推算为 2026-08-31」变成「开学日期取自教务……2026-02-23」） |

**M1 与 M2 是本批点名要求的两个方向**，两条都让 `weeks-bitmap` 变红 —— 口径被钉死了。

---

## 13. 自验记录

1. **Node `vm`**：4 对 fixture 逐字段比对（键顺序无关、数组顺序有关）→ **ALL MATCH**。
2. **真 Rhino 1.8.0（与 CI harness 同一个 jar）**：写了一个 10 行的 JVM 跑法
   （`Context.VERSION_ES6` + `optimizationLevel = -1` + `initStandardObjects`，与
   `JwLibraryHarnessTest.evalParseScript` 逐字一致）跑全部 4 对 → **ALL RHINO MATCH**。
   这一步验的是「没有用到 Rhino 不支持的语法」（上游那个带 `s` 标志的正则就是被它挡下来的）。
3. **语法**：`node --check` 两份脚本都通过。
4. **ES5 硬要求**：两份脚本里 `=>` / 反引号 / `\blet\s` / `\bconst\s` 计数**全为 0**；
   没有 `eval` / `new Function` / `async` / `await`。
5. **NUL / 控制字符**：`parse.js`、`extract.js`、8 个 fixture 全部 **NUL: 0、其它控制字节: 0**；
   `extract.js` 源码里也没有 BOM 字符字面量（去 BOM 用 `String.fromCharCode(0xFEFF)`）。

---

## 14. 出处与签名

- 上游：`shiguang_warehouse`（MIT，作者 **igugyj(Pfolg)**）
  `resources/CUIT/cuit_bk_old.js`，commit `e62554a4034386b893bcd6813c7b2b64f8c730a3`（2026-09-12）。
- 平台名订正：`/eams/` 是**上海树维信息科技有限公司**（SupWisdom）的产品，**不是强智**。
- 本目录只包含 `manifest.json` / `extract.js` / `parse.js` / `AUDIT.md` / `fixtures/*`，
  **没有改动任何其它文件**（`index.json` 由主 agent 统一添加）。
- **移植者**：0x7E7-2023　**日期**：2026-09-17　**签名**：本文件与代码逐条对照过，
  第 4 节的请求清单与 `extract.js` 的实际请求一致，第 7/8 节的结论与代码一致。
