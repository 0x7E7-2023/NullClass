# 安全审计 —— 东北大学秦皇岛分校教务适配器（`neuq`）

审计对象：`jw-adapters/neuq/extract.js` + `parse.js`
（移植自拾光社区 `NEUQ/neuq.js`，上游作者 **aryunm**，MIT；上游快照
`e62554a4034386b893bcd6813c7b2b64f8c730a3`，2026-09-12）

审计人（移植者）：**0x7E-2023**　日期：**2026-09-17**

## 0. 先说清楚：这不是「东北大学」那个适配器

| | 本校（本目录 `neuq`） | 已在库的 `neu`（东北大学主校区） |
|---|---|---|
| 学校 | 东北大学**秦皇岛分校** | 东北大学（沈阳主校区） |
| 教务系统 | **树维 EAMS**（`/eams/`） | **金智 jwapp**（`/jwapp/sys/`） |
| 主机 | `jwxt.neuq.edu.cn` | `jwxt.neu.edu.cn` |
| 取数 | 三个接口 + 课表 HTML 里内嵌的 `TaskActivity` 脚本块 | 四个接口，返回 JSON 行对象 |
| 周次 | **位图字符串**（下标就是周次） | 位串 `SKZC` + 文本 `ZCMC` |
| 作息 | 适配器内置 12 节 | 教务接口给 |

两边只是学校名像，系统、接口、编码、作息全都不同 —— **别混用，也别互相照抄**。

`/eams/` 是**上海树维信息科技有限公司（SupWisdom，新开普子公司）**的产品，
不是湖南强智科技（强智走 `/jsxsd/`）。本目录按批次四订正后的说法写。

## 1. 结论

**通过。** 本适配器在用户已登录的教务页面里同源请求四个只读接口（探测页、备选探测页、
学期列表、课表），**不碰凭据、不外发、不埋点、不 eval、不写页面**。

## 2. 请求了哪些域（`allowHosts` 的依据）

**请求主机只有一个：用户当前所在的那个教务主机。** 上游把地址写死成
`https://jwxt.neuq.edu.cn`（`BASE` 常量），本适配器改成用 `window.location.origin` +
上下文路径拼，所以：

- 用户从 `https://jwxt.neuq.edu.cn/eams/...` 打开 → 请求全部打在 `jwxt.neuq.edu.cn`，与
  `loginUrl` **同源**；
- 学校哪天换成 http、或把 `/eams` 挂在门户 / WebVPN 前缀下 → 请求跟着当前地址走，
  不需要改适配器，也不会把请求打到别的域。

静态扫法（与手册 §5 里那两条同款，审计时实跑）：

```bash
grep -nE "https?://|fetch\(|XMLHttpRequest|sendBeacon|new WebSocket|\.src\s*=" jw-adapters/neuq/*.js
```

命中只有这些，**没有一处是第三方域**：

| 命中 | 是什么 |
|---|---|
| `parse.js:5` / `extract.js:5` | 上游仓库出处链接（`https://github.com/XingHeYuZhuan/shiguang_warehouse`），纯注释 |
| `extract.js:20` | 文件头说明「上游写死的地址是 `https://jwxt.neuq.edu.cn`，我们改成了同源相对路径」，纯注释 |
| `extract.js:72` | `'X-Requested-With': 'XMLHttpRequest'` —— 一个**请求头名字**，不是接口调用 |
| `extract.js:77` | `fetch(url, options)` —— 唯一的网络出口，`url` 由 `originOf()/eamsBase()` 拼出（见上） |

没有别的出口：全文没有 `XMLHttpRequest` 对象、`sendBeacon`、`new WebSocket`、`EventSource`、
`new Image().src`、`<script src=...>` 注入。

所以 `allowHosts` 是 **空数组**（`[]`），**没有通配**，更没有 `*.edu.cn`：
连额外域名都不需要放行，只靠 `loginUrl` 的那一个同源主机。
（为什么不用 `allowHosts: ["jwxt.neuq.edu.cn"]`：白名单一放行就写死了主机名，
学校换域或走网关前缀时适配器会直接失效；同源相对路径比白名单更窄也更耐用 ——
移植手册 §5「代理域」那一条。）

### 四个接口与请求体（全部只读、全部同源）

| 接口 | 方法 | 作用 | 请求体 |
|---|---|---|---|
| `/eams/courseTableForStd.action?sf_request_type=ajax` | GET | 探测页（拿学号与学期选择框） | — |
| `/eams/courseTableStd.action?sf_request_type=ajax` | GET | 备选探测页（上游没有这条，是本适配器加的降级路径） | — |
| `/eams/dataQuery.action?sf_request_type=ajax` | POST | 学期列表 | `tagId=<tagId>&dataType=semesterCalendar`（没有 tagId 时只带 `dataType`） |
| `/eams/courseTableForStd!courseTable.action?sf_request_type=ajax` | POST | 课表 HTML | `ignoreHead=1&setting.kind=std&startWeek=&semester.id=<id>&ids=<学号>` |

请求体与上游 `neuq.js` 逐字一致；课表页地址优先用探测页里读到的表单 `action`，
读不到才用上面这条上游同款路径。

> **`ids` 是学号**，作为课表接口的必要参数带出去（教务自己要求，不给就查不到课表）。
> 它是**发给本校教务主机**的，不是外发；**不会**进入 `extract.js` 的输出、`fixtures/`，也不进载荷 ——
> 上游把学号写进输出对象（`studentId`），本适配器只把探测页 HTML 交出去、由 `parse.js` 就地读，
> 而且 `fixtures/` 里那份探测页 HTML 是**合成**的（见 §8）。

## 3. 逐条对照手册 §5

| # | 检查项 | 结论 |
|---|---|---|
| 1 | **不碰凭据** | ✅ 全文不读 `password` / `pwd` / `document.cookie` / `localStorage` / `sessionStorage`，不读登录表单的值，不监听输入。只读探测页 HTML 里的 `ids`（学号）与学期选择框的 `value` —— 两者都是页面已经渲染出来的业务参数；学号只用于本校课表接口，不外发、不进输出。 |
| 2 | **不外发** | ✅ 除当前同源的教务主机外没有第二个请求目标（见 §2 的逐行核对）。没有 `sendBeacon` / `WebSocket` / `EventSource` / `new Image().src` / 脚本注入。 |
| 3 | **请求域可控** | ✅ 请求地址全部由 `window.location.origin` + 路径拼出，`allowHosts: []`，没有通配。 |
| 4 | **只读课表** | ✅ 四个接口分别是「课表页探测 ×2」「学期列表」「课表」。**没有**成绩 / 学籍 / 个人信息 / 缴费 / 培养方案；`dataQuery.action` 只带 `dataType=semesterCalendar`，不带别的 dataType。 |
| 5 | **不埋点** | ✅ 没有统计 / 上报 / 遥测；连 `console.log` 都没有（上游的 `console.error` 也没移植）。 |
| 6 | **不 eval 远程代码** | ✅ 全文没有 `eval` / `new Function`（`grep -nE "eval\(|new Function" jw-adapters/neuq/*.js` **无输出**；`parse.js` 第 19 行有一句注释说明上游用了什么、我们为什么没照抄）。详见 §6。 |
| 7 | **不写页面** | ✅ 只 `fetch` + 正则读文本；不写 DOM（无 `innerHTML` / `appendChild` / `document.write`）、不改表单、不 `submit()`、不 `click()`、不注入脚本、不打开窗口。 |
| 8 | **不依赖用户输入之外的秘密** | ✅ 没有硬编码密钥 / 固定令牌 / 他人学号；`fixtures/` 里的 ids 与课名全是**虚构**的合成数据。 |

### 附带核对的移植手册 §1「不移植的判据」

- 不依赖浏览器插件、原生 App 私有接口、证书安装 —— ✅；
- 请求域里没有非本校第三方（统计、CDN 上的业务接口、加速器）—— ✅；
- 不只读课表页之外的内容（成绩、学籍、个人信息）—— ✅。

## 4. 读到了什么数据

全部来自用户当前打开的那一页 / 本校教务的四个只读接口：

1. **探测页 HTML**：`bg.form.addInput(form,"ids","…")` 里的学号（只在本校请求里用）、
   `id="semesterBar…Semester"` 的 `value`（当前学期 id）；
2. **semesterCalendar 响应**：每个学期的 `id` / `schoolYear` / `name` / `startDate` / `endDate`。
   `extract.js` 用「花括号配平 + 键值对」把它读成字段（**不执行响应里的任何代码**），
   并且**不把原始响应交出去**（它会夹带其它学期的对象与其它字段）；
3. **课表 HTML**：整段响应文本原样交给 `parse.js`（课程是内嵌的 `new TaskActivity(...)` 脚本块，
   必须拿到全文才能解析）。这里含课名、教师、教室、周次位图、星期与节次 —— 就是课表本身。

以上经 `parse.js` 转成课表载荷后交给空课。**学号不进载荷，不进 fixture。**

## 5. 危险写法逐处交代（本批检查表第 3 条）

| 上游写法 | 在哪 | 我们怎么办 |
|---|---|---|
| `Function("return (" + raw + ")")()` 解析 semesterCalendar | `NEUQ/neuq.js` 的 `parseSemesterResponse` | **不照抄**。`extract.js` 改成「花括号配平切块 + 字段键值对」读字段；`parse.js` 里连原始响应都收不到，只收 `[{id, schoolYear, term, startDate, endDate}]`。全文无 `eval` / `new Function`。 |
| `eval` / `new Function` 求值 `index` 表达式 | 上游 neuq **没有**（同族的 `DLMU` 用 `new Function`、`HAUST` 用 `eval`，本批已按检查表 3 换成正则） | `index = D*unitCount+P` 用正则读；`index = 62` 这种预先算好的用 `Math.floor(62/unitCount)` 与 `62 - 天*unitCount` **算术反解**，不做任何求值。 |
| 往教务页面写 DOM / 触发提交 | 上游 neuq **没有**（只 fetch + 正则） | 本适配器同样不写页面。 |

## 6. 关于 `eval` / `new Function`：上游用了，我们没照抄（手册 §5 第 6 条）

上游 `NEUQ/neuq.js` 的 `parseSemesterResponse` 里有这一句：

```js
data = Function("return (" + String(rawText || "").trim() + ");")();
```

`rawText` 是 `POST /eams/dataQuery.action` 从**网络取回来的字符串**，整段塞进了 `Function`
构造器 —— 命中手册 §5 第 6 条「`eval` / `new Function` 里塞了从网络取回来的字符串」，
判不合格。同族的 `ZUA` / `ZZVCAE` / `HPU` / `DLMU` 也都这么干，只有 `HAUST` 用 `eval(...)`。

**我们的替代实现**（`extract.js` 的 `parseSemesterResponse` / `matchBraceGroup` / `fieldsOf`）：

1. 从左到右找 `{`，用带引号状态与反斜杠转义处理的**花括号配平**扫到配对的 `}`，切出这一块；
2. 块里**不含**内层花括号的，就是一个学期对象（最里层）；全都有内层花括号时才退回容器块；
3. 在这块文本上用键值对正则读 `id` / `schoolYear` / `name` / `startDate` / `endDate`
   （兼容裸键、单双引号、数字与字符串值），逐个字段，**一个字符都不执行**；
4. 学期 id 去重后交给 `parse.js`。

这个实现**读不出**的形态会退化成「学期列表为空」→ `parse.js` 写 warnings 并改用推算，
不会静默出错。`fixtures/*.extracted.json` 里的 `semesters` 就是这段解析的实际输出
（`basic` / `weeks-bitmap` / `null-teacher` 各验了一组多学期数据；`fallback` 验了
「学期没有起止日期」的形态），由临时脚本独立跑过（§9）。

**残留风险（如实说）**：手写的这套解析在面对教务真正改版（例如把学期列表改成严格 JSON、
或键名换成别的词）时会**读空**，而不是报错 —— 那时 `parse.js` 会推算学期名 / 开学日 / 总周数
并写出对应 warnings。这是**正确性**风险不是**安全**风险，不涉及执行远程代码。

## 7. 已知的能力边界

- **没有真机验证**。维护者手上没有东北大学秦皇岛分校的账号（测试方案 §3 已把这条代价写死）。
  下面把「哪些是上游正文、哪些是本适配器的判断」分开：
  - **来自上游正文（可信度较高）**：四个接口里前三个的路径、第四条的路径与请求体、
    课表 HTML 里课程以 `new TaskActivity(...)` 内嵌、`index = 星期*unitCount+节次` 的寻址、
    `args[1]` 教师 / `args[3]` 课名 / `args[5]` 教室 / `args[6]` 周次位图、`unitCount` 缺省 12、
    教师写成 `join(...)` 表达式时要回看 `actTeachers`；
  - **本适配器自行决定、真机必须复核**：同源相对路径（而非写死主机）、学期列表用什么形状交出去、
    学期的自动选择顺序、开学日与总周数的推法、作息表用法、位图第 0 位口径、
    教室写成变量时的还原、总周数 clamp 到 30。
- **作息来自上游脚本内置表，不是页面读取**。`parse.js` 的 `PERIOD_TIMES` 就是上游
  `getPresetTimeSlots()` 的 12 节（第 1 节 `08:00-08:45`）。上游没有向教务请求作息，
  这张表是脚本作者对学校的了解。**真机核对时请对照教务处公布的作息**：不一致就在学期管理里改，
  或改这张表并重跑门。所有时间都过 `HH:mm` 与 `00:00–23:59` 校验（越界会让整包被拒，不是跳过一节）。
- **位图口径按本批统一约定**：下标 `i` 就是第 `i` 周，**下标 0 是占位符**。上游 neuq 原文是
  `for (i = 0; …) if (bitmap[i] === '1') weeks.push(i)`（没有跳过 0 位），本适配器改成
  `i >= 1`；位图第 0 位为 `1` 时不产出「第 0 周」，改为写一条 warning。
  真机上若发现**整体差一周**，改这一个循环即可（`parse.js` 的 `weeksFromBitmap`）。
- **只导入「当前」那一个学期**。要导入别的学期：在教务页面里切到那个学期再点「提取课表」。
  不弹窗问 —— 用户此刻正开在教务页面上，他自己切过的学期比弹窗列表更清楚（手册 §3 第 1 步）；
  载荷的 `terms` 本来就是数组，以后要一次给多个学期也不用改契约。
- **教师 / 教室解析不出来就留空**（`null`），不写「未知教师」「未知地点」——
  那会被课表当成真姓名、真地点显示。上游写 `""`，同族别的件写「未知教师」，我们两个都不采纳，
  并单独写一条 warning 说明这件事。
- **本适配器不做 OCR、不发问、不写页面、不加任何空课的私有能力。**

## 8. fixture 与期望值的推导（本批检查表第 6 条）

`fixtures/*.extracted.json` 是**合成的**：按上游实际读到的三段数据的形状编造，
课名 / 教师 / 教室 / 学号 / 学期 id 全是虚构的。**代价**：合成 fixture 只保证
「同样的输入永远得到同样的输出」，不保证解析在真实教务页面上是对的。
谁拿到真实 dump，替换它并重跑门是最高优先级的贡献。

`fixtures/*.expected.json` 的每一条期望值都是**按规范独立推出来的**（不是把 `parse.js` 的输出贴进去）。
逐条：

### `basic`（学期起止日期可用；教师是字面量；第 0 位为 1 的位图）

| 期望值 | 怎么推出来的 |
|---|---|
| 学期名 `2026-2027学年第一学期` | `currentSemesterId=131` → 该学期 `schoolYear="2026-2027"`、`name="第一学期"` 直接拼 |
| `firstDay = 2026-09-07` | 该学期 `startDate=2026-09-07`（当天就是周一）→ 第 1 周从它开始（手册 §4.3） |
| `totalWeeks = 19` | `2026-09-07 ~ 2027-01-17` 含首尾 133 天 → `ceil(133/7) = 19` |
| `01111111111111111000` → 第 1-16 周 | 下标 1..16 是 `1`，第 0 位是 `0` |
| `00101010101000000000` → 第 2/4/6/8/10 周 → `EVEN 2-10` | 隔周、首周是偶数 → `EVEN`（手册 §4.1） |
| `01010101010100000000` → 第 1/3/5/7/9/11 周 → `ODD 1-11` | 隔周、首周是奇数 → `ODD` |
| 马克思主义 → `ALL 1-11` | 两条活动（`11111111111100000000` 第 0 位为 1、`01111111111100000000`）同课名/教师/教室/星期 → 周次并成 `{1..11}`；第 0 位不产出周次 |
| 高等数学A(一) → `1-3 节` | 两个活动块（周一 1-2 节、周一第 3 节）同键且节次相邻 → 合并 |
| warnings 6 条 | 开学日来自教务 / 只导入当前学期 / 总周数来自教务 / 作息是内置 / 位图第 0 位为 1 / 教师教室占位文案说明 |

### `fallback`（学期没有起止日期、unitCount 读不到、脏数据）

| 期望值 | 怎么推出来的 |
|---|---|
| `firstDay = 2026-08-31` | 学期 = 2026-2027 第一学期 → 锚点 9 月 1 日（**周二**）→ 回退到那一周的周一 = `2026-08-31` |
| `totalWeeks = 22` | 教务给不出 → 内置 20；课表里最晚第 22 周 → 抬到 22（不抬那几周的课放不下、整包会被拒） |
| 高等数学A 两条 block `1-10` 与 `22-22` | 位图 `011111111110000000000010000000` → 下标 1..10 与 22，不连续 → 两块 |
| 概率论两条 block（第 2 周与第 6 周） | 位图 `00100010000000000000` → 只第 2、6 周，**不连续 → 不许并成 2-6** |
| 共 2 条排课进 warnings | 参数不足 7 个的 `TaskActivity`、`index = 7*unitCount+0`（星期下标 7 越界，一天只有 0-6） |
| `unitCount` 用缺省 12 | 课表 HTML 里没有 `var unitCount` |
| 位图 5 个非 0/1 字符 | `"0x1a0a0a0a"` 里的 `x` 与四个 `a` |

### `weeks-bitmap`（位图基准与边界）

| 期望值 | 怎么推出来的 |
|---|---|
| `firstDay = 2027-09-06` | `currentSemesterId=132` → 该学期 `startDate=2027-09-06`（当天就是周一） |
| `totalWeeks = 30` | `2027-09-06 ~ 2028-04-18` 含首尾 225 天 → 33 周 → **超上限，clamp 到 30** |
| 数据结构 `1-18` | 位图 `11111111111111111110`：**第 0 位为 1 不产出第 0 周**，只取 1..18；0 基读法会同时多出「第 0 周」和第 19 周 |
| 操作系统 `ODD 1-15` | `01010101010101010000` → 第 1/3/5/…/15 周 |
| 离散数学 `ALL 1-30` | 位图里有第 1..42 周 → 先丢掉 12 个 >30 的，剩下的 1..30 连续 |
| 编译原理 `EVEN 2-16` | `00101010101010101000` → 第 2/4/6/…/16 周 |
| 大学英语(四) 整条消失 + 1 条 warning | 位图 `"00"` 没有任何 `1` → 进不了载荷，**靠 warnings 出声**（不许静默丢课） |

### `null-teacher`（教师 / 教室拿不到就留空；index 预先算好）

| 期望值 | 怎么推出来的 |
|---|---|
| 形势与政策 `teacher = null` | `args[1]` 是裸变量 `someUndefinedTeacher`，这门课之前没有任何 `actTeachers` → 回看不到 |
| 军事理论 `location = null` | `args[5]` 是裸变量 `roomVariable`，回看不到 `room` / `position` / `classroom` 变量 |
| 劳动教育 `dayOfWeek=6, startPeriod=3` | `index = 62`，`floor(62/12)=5` → 第 6 天（0 基 5 加 1）；`62-60=2` → 第 3 节 |
| 中国近现代史纲要 `2-3 节` | `5*unitCount+1` 与 `5*unitCount+2` → 周六第 2、3 节，同键且相邻 → 合并 |

### 变异测试记录（证明用例真的在看着这些逻辑）

改坏 `parse.js` 的**内存副本**（不动工作区文件），跑同一套 fixture。每条变异都有一个
「补丁没命中就报错」的守卫，所以不存在「改坏了但没生效、看着还是绿」的情况：

| # | 改坏哪一处 | 结果 |
|---|---|---|
| — | 不改（基准） | 4 条全 MATCH |
| 1 | `weeksFromBitmap` 的 `for (i = 1; …)` 改回 `i = 0`（去掉 0 位占位符约定） | `basic` **DIFF**（`startWeek=0`，载荷校验也报错）、`weeks-bitmap` **DIFF**（多出第 19 周） |
| 2 | `if (bitmapResult.zeroBit) zeroBitBitmaps++;` 删掉（第 0 位为 1 时不再出声） | `basic` **DIFF**、`weeks-bitmap` **DIFF**（各少一条 warning） |
| 3 | 载荷里 `teacher: course.teacher` 改成 `course.teacher \|\| '未知教师'` | `null-teacher` **DIFF**（教师变「未知教师」） |
| 4 | 载荷里 `location: unit.location` 改成 `'未知地点'` 兜底 | `fallback` **DIFF**、`null-teacher` **DIFF** |
| 5 | 去掉 `if (totalWeeks > MAX_TOTAL_WEEKS)` 的 clamp | `weeks-bitmap` **DIFF**（`totalWeeks=33`，载荷校验报错） |
| 6 | `DEFAULT_UNIT_COUNT` 由 12 改成 14 | `fallback` **DIFF**（缺省节次数与 warning 文案都变） |

四条用例在未变异时全部 MATCH，每条变异都至少红掉一条 —— 用例覆盖的是真路径。

## 9. 自验结果

用 Node 实跑（不进仓库的临时脚本；`extract.js` / `parse.js` 都先过 `node --check`，
`parse.js` 在 `vm` 里以 `__ncInput` 为输入执行）：

```
basic: MATCH          weeks-bitmap: MATCH
fallback: MATCH       null-teacher: MATCH
---  用例 4 个，DIFF/THROW 0 个
```

另外：

- **`extract.js` 的学期解析与探测页正则**单独验过：
  ```text
  basic: OK  学期=[{id:129,…第二学期…},{id:131,…第一学期…}] current=131
  weeks-bitmap: OK  学期=[{id:131,…},{id:132,…2027-2028…}] current=132
  fallback: OK  学期=[{id:131, schoolYear:"2026-2027", term:"1", startDate:"", endDate:""}] current=131
  null-teacher: OK  学期=[{id:129,…},{id:131,…}] current=131
  probeOf: {ids:"2023110101", tagId:"semesterBar123456789Semester", value:"131"}
  courseTableActionOf: /eams/courseTableForStd!courseTable.action
  ```
  （`fallback` 那一条是有意留的：学期响应里 `name` 是数字 `1` 且没有起止日期，
  解析出来的 `term` 就是原文 `"1"`，`parse.js` 再拼成「2026-2027学年第1学期」。）
- **每份 `expected.json` 都过了载荷校验**：`specVersion=1`、`totalWeeks ∈ 1..30`、
  `dayOfWeek ∈ 1..7`、`startPeriod ≤ endPeriod`、`weekType ∈ {ALL,ODD,EVEN}`、
  `endWeek ≤ totalWeeks`、`firstDay` 是 `yyyy-MM-dd`、warnings ≤ 20 条且每条 ≤ 200 字、
  节次时间匹配 `HH:mm` 且落在 `00:00–23:59` —— **4 份全部 OK**（这一层与
  `:importer:test` 里 `JwPayloadCodec.decode` + `JwScheduleNormalizer.normalize` 的检查项对齐；
  CI 里还会跑 Rhino 与整库索引一致性，那两道由维护者在合并时跑）。

## 10. 静态自检

```text
parse.js    NUL: 0   反引号: 0   箭头: 0   let/const: 0   eval/new Function: 0
extract.js  NUL: 0   反引号: 0   箭头: 0   let/const: 0   eval/new Function: 0
fixtures/*.extracted.json / *.expected.json   NUL 均为 0（6 个文件）
```

ES5 检查按 CI 的**整个文件字符串**扫法做的（`"=>"` / 反引号 / `\blet\s` / `\bconst\s`
出现在**注释里也算违规**），逐行扫过，没有一行命中。

工具入参里的转义序列会被落成**真控制字符**、让文件变二进制 —— 本目录的两段脚本
一个字面 NUL 都写不进去：复合键分隔符用 `String.fromCharCode(0)` 取，
注释与正则里也一律不写 unicode 转义序列。

## 11. 签名

移植、审计、自验：**0x7E-2023**，2026-09-17。
上游：`NEUQ/neuq.js`，作者 **aryunm**（MIT）；上游快照
`e62554a4034386b893bcd6813c7b2b64f8c730a3`（2026-09-12）。
