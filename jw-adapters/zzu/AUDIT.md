# 郑州大学（`zzu`）适配器 —— 安全审计与同构性对照

| | |
|---|---|
| 上游 | [shiguang_warehouse](https://github.com/XingHeYuZhuan/shiguang_warehouse) → `resources/ZZU/zzu.js`（MIT，Copyright 2025 LilyCarry） |
| 上游快照 | commit `e62554a4034386b893bcd6813c7b2b64f8c730a3`（2026-09-12）；学校名与登录地址取自同目录 `adapters.yaml`（`adapter_name` 郑州大学，`maintainer` LilyCarry） |
| 上游平台 | 树维 `for-std`（`jwxt.zzu.edu.cn/student/for-std/...`），与上游 `JSTC` / `CUP` / `CUPK` 同一套接口 |
| 本适配器 | `jw-adapters/zzu/`，按[适配器规范](../../docs/jw-adapter-spec.md) v1 切两段（`extract.js` 只取数 / `parse.js` 纯转换）+ 降 ES5 |
| 移植者 | NullClass（Claude Code 执行） |
| 审计日期 | 2026-09-12 |
| 审计范围 | `extract.js`、`parse.js` 全文，`manifest.json` 的 `allowHosts` / `loginUrl` |
| 结论 | **安全性通过**（逐条见 §2，请求域见 §1）。**正确性未验**：fixture 是合成的，见 §7 |

---

## 1. 请求了哪些域

**登录是跨域的 CAS**：`adapters.yaml` 的 `import_url` 是
`https://cas.s.zzu.edu.cn/cas/a/login?service=https%3A%2F%2Fjwxt.zzu.edu.cn%2Fstudent%2Fsso%2Flogin`
—— CAS 在 `cas.s.zzu.edu.cn`，登录后跳回教务 `jwxt.zzu.edu.cn`。两个域分开列：

### 1.1 CAS 域（`cas.s.zzu.edu.cn`）—— 只有 WebView 打开，脚本不请求

| 谁 | 什么 | 说明 |
|---|---|---|
| `manifest.loginUrl` | `/cas/a/login?service=…` | 一键刷新/首次打开的地址，取自上游 `adapters.yaml` |
| **脚本** | **不请求** | `extract.js` 里没有任何一行指向 CAS；登录全程由用户在 WebView 里手工完成，适配器不碰表单、不碰验证码 |

### 1.2 教务域（脚本真正请求的）

`extract.js` 一共发 **3 个 GET**，路径固定、主机来自 `origin()`（见 §5.3）：

| # | 方法 | URL | 拿什么 |
|---|---|---|---|
| ① | GET | `/student/for-std/course-table` | 课表页 HTML，只为读 `#allSemesters` 的学期选项（学期 id 与名称、以及页面标了 selected 的那一项） |
| ② | GET | `/student/ws/semester/get/{semesterId}` | 学期元数据 JSON：`startDate` / `endDate` / `weekStartOnSunday`。**拿不到会降级**（交 `null`，由 `parse.js` 推算开学日并写进 `warnings`），不阻断导入 |
| ③ | GET | `/student/for-std/course-table/semester/{id}/print-data?semesterId={id}&hasExperiment=true` | 排课记录 `studentTableVms[0].activities` + 作息表 `timeTableLayout.courseUnitList` |

主机取值只有两种，**都在 `zzu.edu.cn` 下**：

- **`jwxt.zzu.edu.cn`** —— 常态（页面已在教务应用里时用当前页面的源，也是 `FALLBACK_ORIGIN`）；
- **当前页面的源**，仅当页面 URL 的域名是 `zzu.edu.cn` 或其子域**且路径含 `/student/`** 时才采用（见 §5.3）。别的域会被拒绝并退回 `https://jwxt.zzu.edu.cn`。

`allowHosts` 只写一条 **`*.zzu.edu.cn`**：CAS 子域（`cas.s.zzu.edu.cn`）与教务子域（`jwxt.zzu.edu.cn`）都在该域下，
且教务页面自己可能从同域其它子域取静态资源（提取结束后 JS 层白名单继续生效，写死两个主机名会把页面自身的子资源也挡掉）。
通配止于学校自有域 `zzu.edu.cn`（规范允许的「至少三段的后缀通配」形态），**没有** `*.edu.cn` 这类跨校通配。

上游 `ZZU/zzu.js` **一个绝对 URL 都没有**（`grep -oE "https?://[A-Za-z0-9.:%_/-]+"` 无输出）：它用的是
`/student/...` 这种根相对路径，即「请求当前页面的源」。本适配器保留了这一点（§5.3），
并把「当前页面的源」限制在本校域名内 —— 上游那条路在任何页面上都会把请求打到当前页面的域。

## 2. 移植手册 §5 八条

| # | 检查项 | 结论 |
|---|---|---|
| 1 | **不碰凭据** | ✅ 不读登录表单、不读 `localStorage` / `sessionStorage`、不读任何令牌或密码字段，也没有把任何东西发回去。取数只用用户已登录页面自带的会话 Cookie（`fetch(..., {credentials: 'include'})`）。**脚本不请求 CAS 域**，登录全程由用户手工完成。 |
| 2 | **不外发** | ✅ 全文只有 3 个 `fetch`（全在 `extract.js`），目标由 `origin()` 决定且**只可能是 `zzu.edu.cn` 下的主机**；`parse.js` 是纯函数，一行网络代码都没有。没有 `XMLHttpRequest`、`sendBeacon`、`WebSocket`、`EventSource`、`new Image().src`，也没有第三方脚本/CDN 引用。 |
| 3 | **请求域可控** | ✅ 见 §1：主机逐一列出，`allowHosts` 写 `*.zzu.edu.cn`（学校自有域，覆盖 CAS + 教务两个子域），通配止于三段域名，未使用跨校通配。 |
| 4 | **只读课表** | ✅ 3 个接口依次是课表页、学期元数据、课表 `print-data`；没有成绩、学籍、个人信息、缴费、选课（写操作）类接口。元数据接口只读学期起止日期。 |
| 5 | **不埋点** | ✅ 无任何统计／上报／遥测，无「匿名」上报，无外部资源引用，`console` 都没有。 |
| 6 | **不 eval 远程代码** | ✅ 无 `eval`、`new Function`、`document.write`、`setTimeout("...")`；`JSON.parse` 只解析教务返回的 JSON，解析失败即报错（附响应片段，便于用户反馈）。 |
| 7 | **不写页面** | ✅ 不写 DOM、不改表单、不触发提交、不点击页面元素。`DOMParser` 只在内存里解析取回的 HTML 字符串（不挂到文档上），拿 `#allSemesters` 的选项；**连「切到课表页」这种点击都没有**。 |
| 8 | **不依赖用户输入之外的秘密** | ✅ 无硬编码密钥、无他人学号、无固定令牌；常量只有接口路径与学校作息表。唯一需要的运行时信息是当前页面里的会话 Cookie（用户自己登录得来的）。 |

补充（不是 §5 的条目，但审计时会看）：

- **没有交互**：不使用 `__ncSelect` / `__ncConfirm` / `__ncPrompt`（上游的选学期弹窗改成了「取页面上当前打开的学期」），
  更没有用弹窗索要任何账号信息。
- **没有用 OCR 桥**：不调 `__ncOcr` / `__ncOcrGrid`（课表是接口给的，不需要识别）。
- **只做取课表这一件事**：`parse.js` 不访问网络、不读页面。

## 3. 与 jstc / ustc 的同构性对照

上游四份 for-std 脚本（`ZZU` / `JSTC` / `CUP` / `CUPK`）与本仓库两个 for-std 样本（`jstc`、`ustc`）
按[测试方案 §3.2](../../docs/jw-adapter-testing.md) 的分层比对。**逐层结论**：

| 层 | ZZU vs JSTC | ZZU/CUP/CUPK vs JSTC | 我们既有的 `ustc` | 本适配器复用了没有 |
|---|---|---|---|---|
| **接口信封**（路径前缀、请求头、响应结构） | **同构**：`/student/for-std/course-table`、`/student/ws/semester/get/{id}`、`.../semester/{id}/print-data`，都带 `x-requested-with: XMLHttpRequest`；响应都是 `studentTableVms[0].activities` | **路径同名**，但 CUP/CUPK 的 `print-data` **不传任何查询参数**（ZZU/JSTC 传 `?semesterId=…&hasExperiment=true`），CUP/CUPK 的学期下拉 id 是 `#semesters` **或** `#allSemesters` | **不同构**：`/for-std/course-table/get-data?bizTypeId=2&semesterId=…&dataId=…` + `POST /for-std/course-table/datum`（要 `lessonIds`） | 路径与请求头照用；**查询参数按 ZZU 自己的写法**（不跟 CUP） |
| **行字段名** | **同构**：`courseName` / `teachers` / `room` / `weekday` / `startUnit` / `endUnit` / `weekIndexes` | **同构**（CUP/CUPK 逐字相同，另用 `campus`） | **不同构**：`lessonList`（`courseName`/`teacherAssignmentList[].name`）+ `scheduleList`（`weekIndex`/`periods`/`startTime`/`endTime`/`date`/`room.building.nameZh`） | 字段映射按 ZZU/CUP 的字段名写；`campus` 的用法借自 CUP |
| **取数路径**（要哪些参数、要不要先取学号） | **同构**：3 个 GET，不需要学号 | **基本同构**（只差 print-data 的参数） | **不同构**：先解析 `studentId` → `get-data` 拿 `publishLessonIds` → `POST datum` 拿完整数据 | 3 个 GET 的流程照用 |
| **周次 / 节次编码** | **同构**：周次是**整数数组** `weekIndexes`，节次是整数区间 `startUnit..endUnit` | **同构** | **不同构**：`weekIndex` 是单周序号，节次是 `periods` + 具体时刻 `startTime/endTime` + `date` | 周次切段（步长 1/2 → ALL/ODD/EVEN）与「区间即节次」照用 |
| **分页** | **都没有分页**：print-data 是单次 GET、无 page 参数（四份脚本一致） | 同 | 不同（`datum` 是 POST 批量） | 见 §6 第 5 条 |
| **写死的值**（作息表、总周数口径、兜底链） | **不同构**：ZZU 内置 **12 节**作息、JSTC 内置 **11 节**；ZZU `Math.max(totalWeeks,18)`、JSTC 直接写死 `semesterTotalWeeks: 20`；ZZU 的 activities 有**三级兜底**、JSTC 只看 `studentTableVms[0]` | **不同构**：CUP/CUPK 读教务的 `timeTableLayout.courseUnitList`，ZZU/JSTC 不读；CUP 按 `Math.ceil(相差天数/7)` 算总周数且**无上下限** | 不同 | **一个都没复用**：12 节作息表、18 周下限都是 ZZU 自己的数；courseUnitList 的读法借自 CUP |

**「上游 ZZU/CUP/CUPK 与 JSTC 完全同构」成立吗？** —— **在「接口信封 + 行字段名 + 取数路径 + 周次节次编码」这四层成立**，
四份脚本调的是同一批路径、读的是同一批字段、周次节次是同一套整数编码（`diff` 之后差异只在注释、主机写法、
作息表常量、兜底分支和总周数口径上）。**在「写死的值」这一层不成立**，而这一层恰恰决定输出的对错：
作息表节数（12 vs 11 vs 教务接口）、总周数口径（`20` 固定 / `ceil(相差/7)` / `ceil(相差/7)` 夹到 `[16,30]` 再取 `18` 下限）
三所学校三种写法。所以本适配器**复用了骨架、没有复用常量**，并按 ZZU 自己的编码单独造了 fixture。

**`ustc` 那一支不能复用**：它是同一个平台家族的另一套取数接口（`get-data` + `datum`），
与 `print-data`/`activities` **接口不同构、字段不同构、周次编码也不同构**，只有「周次数组切段」这类纯算法思路可以借鉴。
jstc 移植时给出的结论「ZZU/CUP/CUPK 与 JSTC 同构、与 USTC 不同构」**经本次逐行核对成立**（见上表）。

## 4. 读了什么数据（含边界）

| 数据 | 用途 | 去向 |
|---|---|---|
| `#allSemesters` 的 `option`（id + 名称 + **是否写了 `selected` 属性**） | 选出要导入的学期、给用户看学期名 | 进提取结果 `semesters` / `term` |
| 学期元数据 JSON（整个对象） | `parse.js` 只读 `startDate` / `endDate` / `weekStartOnSunday`（外加可选 `nameZh` / `name` / `firstDayOfWeek`） | 原样放在提取结果 `semesterMeta` 里，不上传、不落库 |
| `studentTableVms[0].activities`（其次 `studentTableVm.activities`、再其次顶层 `activities`） | 课程名、教师、教室/校区、星期、节次、周次数组 | 原样放在提取结果 `activities` 里 |
| `timeTableLayout.courseUnitList` | 学校真实作息表（节次 → 起止时间） | 原样放在提取结果 `courseUnitList` 里 |
| 响应顶层的数字字段里名字是 `total` / `totalCount` / `totalElements` / `totalSize` / `recordCount` / `count` 的那几个 | 只用来看接口有没有截断（§6 第 5 条）；读不到就什么都不带 | 原样放在提取结果 `pageInfo` 里 |

**没有取**：`print-data` 响应里除上面几个子对象外的其它字段（顶层若带学生标识一类字段也不会进提取结果）；
成绩、学籍、培养方案、考试、缴费等任何接口。提取结果只在本机内部从 `extract.js` 交给 `parse.js`，
**不发送到任何地方**；`parse.js` 只读取上表列出的字段。

## 5. 移植改动（与上游 `ZZU/zzu.js` 的差异）

1. **去掉选学期弹窗**（上游 `showSingleSelection`）：改取用户此刻在教务页面上打开的学期
   （URL 里的 `/semester/{id}`），其次取 `#allSemesters` 里写了 `selected` **属性**的那一项，最后才取列表第一个
   —— 后一种情况会在 `warnings` 里如实说明「没标出当前学期，取的是列表第一个」。
2. **上游写死的两处改成能取就取**（取不到才回退，且回退必进 `warnings`）：
   - **开学日期**：上游从 `/student/ws/semester/get/{id}` 拿 `startDate` 后直接当开学日用（`firstDayOfWeek: 1` 写死）；
     这里按 `weekStartOnSunday`（**取自同平台 CUP 的用法**）回退到那一周的起始日。教务没给 `weekStartOnSunday` 时仍是 1，与上游一致。
   - **作息表**：上游把 12 节常量直接当学校作息存；这里优先用教务接口给的 `courseUnitList`
     （**字段名 `indexNo` / `startTime` / `endTime` 取自同平台 CUP/CUPK 的用法** —— 上游 ZZU 自己不读它），
     没有才用上游那 12 节，且用了就进 `warnings`。
3. **主机名不写死**：当前页面已经站在教务应用里（域名在 `zzu.edu.cn` 下且路径含 `/student/`）时用当前页面的源，
   否则退回 `https://jwxt.zzu.edu.cn`。上游用的是相对路径（好处：换一个本校教务入口也能用），
   但相对路径在**任何**页面上都会把请求打到那个页面的域 —— 这里加了域名判断，保留上游的好处又不让它变成外发通道。
4. **不静默丢数据**：上游 `if (!act.courseName || … ) continue` 悄悄跳过不完整的排课记录；
   这里逐类计数后写进 `warnings`（缺课程名/星期/节次/周次各几条），全跳过则直接报错。
   上游 `weekIndexes.map(Number).filter(Number.isInteger)` 丢掉看不懂的周次值也不出声，这里计数后写进 `warnings`。
5. **不做「未知地点」兜底**：上游 `position: act.room || act.building || "未知地点"`，那个串会被当成真教室显示；
   这里教室没有就留空，并顺带按 CUP 的写法带上校区。
6. **`teachers` 两种形态都认**：上游只处理数组（字符串分支存在但没去掉序号），这里字符串也认并同样去掉教学班序号。
7. **总周数**：上游用 `ceil(相差天数/7)` 夹到 `[16,30]`、默认 20，最后再取 `Math.max(totalWeeks, 18)`。
   这里按**含首尾天数**数周（`ceil((相差+1)/7)`）—— 相差正好整周时上游会少算一周（见 §6 第 6 条与 `week-boundary` 用例）；
   校历、课表、18 周下限每一步改动都写进 `warnings`。
8. **降到 ES5**：上游是 `async/await` + 模板串 + 包裹 IIFE，改成 Promise 链；去掉拾光桥（`showToast` /
   `notifyTaskCompletion` / `saveImportedCourses`），改为 `return` 载荷（空课的拉取式契约）。
9. **拆两段**：上游把取数与转换写在一个函数里并直接存库；这里 `extract.js` 只取数、`parse.js` 做转换
   （周次切段、课程聚合、开学日与总周数推断都在 `parse.js`，CI 能真跑）。

### 5.1 `selected` 的判据（上游有 bug，没跟着抄）

上游 `ZZU/zzu.js` 写的是 `selected: opt.hasAttribute("selected") || opt.selected`。
浏览器会把「一个 `selected` 属性都没写」时的**第一个** option 的 `.selected` 置为 `true`，
于是这条判据几乎恒真：每个页面都会被判成「标出了当前学期」，
`parse.js` 里那条「教务页面没有标出当前学期，已取学期列表里的第一个…」就永远发不出来，
而同一个页面走正则兜底（它看的是属性）却给出相反结论 —— 两条路径自相矛盾。
这里只看属性：`options[i].hasAttribute('selected') === true`（与同批 `jstc` 的修法一致，
那次的结论见 `jw-adapters/jstc/AUDIT.md` §6.2）。

### 5.2 复合键的分隔符

`parse.js` 用「课名 + 分隔符 + 教师」当聚合键，分隔符取 `String.fromCharCode(0)`。
**不写成源码里的转义序列**：本仓库的工具链会把那种转义落成真的 NUL 字节，文件就被 `grep`/`diff` 当二进制看
（见[移植手册 §3 第 2 步](../../docs/jw-adapter-porting.md)）。自检过：`jw-adapters/zzu/` 下所有文件的 0x00 字节数为 0。

## 6. 第一批审查挖出来的坑，逐条对照

| # | 坑 | 本适配器的结论 |
|---|---|---|
| 1 | 周次里「单/双」写在「周」字后面（`1-16周(双)`）不能被吞掉、不能退化成每周都上、也不能不报警 | **形态不适用，风险已被等价覆盖**：ZZU 的周次不是文本而是 `weekIndexes` 整数数组，单双周由**数组步长 2** 表达。`runsOf` 按步长切段：步长 1 → `ALL`，步长 2 → 首周奇偶决定 `ODD`/`EVEN`，落单一周 → `ALL`。`basic` 用例同时盖住三种（`[1,3,…,15]`→ODD 1-15、`[2,4,…,16]`→EVEN 2-16、`[1,3]`→ODD 1-3）。变异「单双周塌成每周」（`weekType` 恒为 `ALL`）→ `basic` 变红。 |
| 2 | 括号里的纯数字序号 `(1)` **不是**周次，当成周次会吃掉教师/教室/真实周次 | **周次不走文本，这条对周次不适用**；但同一个反模式在**教师名**上真实存在：上游明确 `replace(/\(\d+\)/g,'')`、`replace(/\[\d+\]/g,'')` 去掉教学班序号，本适配器照做（数组与字符串两种形态都去）。`basic` 里有 `王丽(1)`、`张敏[2]` 两个样本；变异「教师名不去教学班序号」→ `basic` 变红。 |
| 3 | 读不出「第N节」标签的行不许静默丢课（认出来或进 warnings） | **节次是接口给的整数**（`startUnit`/`endUnit`），没有标签要读；对应风险是**整条记录缺字段**。`parse.js` 把缺课程名/星期/节次/周次**逐类计数**进 `warnings`，一条都解析不出时直接报错。`basic` 里有 5 条脏记录（缺课程名 1、缺星期 1、缺节次 1、缺周次 2），期望 `warnings` 里逐类报出；变异「脏记录不计数（静默丢）」→ `basic` 变红。 |
| 4 | 连堂标签下 `periodTimes` 要覆盖每一节，缺的补出来或回落内置表并说明 | **节次区间天然覆盖连堂**（`startUnit..endUnit` 直接就是 block 的节次区间，没有「连堂标签」这种解析输入）；真正的缺口在**作息表**：教务的 `courseUnitList` 可能只定义到第 6 节而课表排到第 12 节。`parse.js` 会把课表用到的、而作息表里缺的节次**从内置 12 节补出来**并进 `warnings`，两边都没有的节次另写一条说明。`basic` 的 `courseUnitList` 只到第 10 节、课表用到第 12 节 → 期望 `warnings` 有「第 11、12 节…已补上」；变异「作息表缺的节次不补」→ `basic` 变红。 |
| 5 | 分页：接口没回记录总数时不能只取第一页；取不全要出声 | **print-data 是单次 GET、无任何分页参数**（上游四份脚本都是这么调的），所以不存在「只取第一页」。但这一点没有真机确认，所以 `extract.js` 会把响应里可能表示总数的数字字段（`total`/`totalCount`/`totalElements`/`totalSize`/`recordCount`/`count`）原样带出去，`parse.js` 发现**总数 > 拿到的条数**就写 `warnings`（只在这个方向上报警：总数少于条数不报，避免误报）。`fallbacks` 用例（总数 5、拿到 2 条）盖住这条；变异「接口总数多于记录条数不报警」→ `fallbacks` 变红。 |
| 6 | 总周数被课表里更晚的周次抬高时要进 `warnings`（**jstc 就是在这里出过错**） | `over-calendar` 用例专测这条：校历 `2025-09-01 ~ 2025-12-21` 只数出 16 周，课表里有第 18~20 周的课 → `totalWeeks` 抬到 20 **且唯一一条 `warnings` 就是这次抬升**。另外上游还有一道 `Math.max(totalWeeks, 18)` 的下限，**它一旦真的改动了数出来的周数也照样报警**（`fallbacks` 用例：只数出 16 周 → 按 18 周导入并说明）。两条变异（「抬升不报警」「下限不生效」）分别让 `over-calendar` / `fallbacks` 变红。**顺带修掉上游的一处算错**：`ceil(相差天数/7)` 在相差正好整周时会少算一周（`week-boundary` 用例：相差 126 天 = 含首尾 127 天 = 19 周，上游口径给 18 周），变异「总周数按相差天数算（上游口径）」→ **只有 `week-boundary` 变红**，证明这条用例不是摆设。 |
| 7 | `AUDIT.md` 声明必须与代码一致（读了什么、请求了哪些域，不许写得比代码窄） | 本文 §1、§4 与 `extract.js` 逐行对齐：3 个 GET（路径 + 主机来源 + 参数）、`loginUrl` 的 CAS 域、`allowHosts`、以及 `pageInfo` 里那个「总数嗅探」。脚本里**没有**任何其它请求目标（无 XHR / beacon / WebSocket / `Image.src` / 第三方脚本）。 |
| 8 | fixture 期望值独立推出，并做变异测试证明新用例真能拦住 | 4 个用例的 `expected.json` 全部**按规范手工推导**（周次切段、块排序、总周数、作息表补全、warnings 的条数与文案都先写下来再跑），跑出来即 MATCH，没有「跑完把输出贴回去」。变异测试见 §6.1。 |

### 6.1 变异测试结果（把逻辑改坏 → 用例必须变红）

命令：对 `parse.js` 的源码做字符串变异（只在内存里），逐个用例重跑。

```
原始                          basic=MATCH  over-calendar=MATCH  fallbacks=MATCH  week-boundary=MATCH
单双周塌成每周                  basic=DIFF   over-calendar=MATCH  fallbacks=DIFF   week-boundary=MATCH
教师名不去教学班序号            basic=DIFF   over-calendar=MATCH  fallbacks=MATCH  week-boundary=MATCH
教室不带校区                   basic=DIFF   over-calendar=MATCH  fallbacks=MATCH  week-boundary=MATCH
同课两条记录不做并集            basic=DIFF   over-calendar=DIFF   fallbacks=DIFF   week-boundary=DIFF
脏记录不计数（静默丢）           basic=DIFF   over-calendar=MATCH  fallbacks=MATCH  week-boundary=MATCH
看不懂的周次不计数              basic=DIFF   over-calendar=MATCH  fallbacks=MATCH  week-boundary=MATCH
校历抬升总周数不报警            basic=MATCH  over-calendar=DIFF   fallbacks=MATCH  week-boundary=MATCH
校历抬升总周数不执行            basic=MATCH  over-calendar=DIFF   fallbacks=MATCH  week-boundary=MATCH
18 周下限不生效                basic=MATCH  over-calendar=MATCH  fallbacks=DIFF   week-boundary=MATCH
作息表缺的节次不补              basic=DIFF   over-calendar=MATCH  fallbacks=MATCH  week-boundary=MATCH
接口总数多于记录条数不报警        basic=MATCH  over-calendar=MATCH  fallbacks=DIFF   week-boundary=MATCH
总周数按相差天数算（上游口径）      basic=MATCH  over-calendar=MATCH  fallbacks=MATCH  week-boundary=DIFF
开学日不回退到周起始日            basic=MATCH  over-calendar=MATCH  fallbacks=DIFF   week-boundary=DIFF
```

13 个变异**每一个都被至少一个用例逮到**，且每个新用例都有「只有它（或极少数）会变红」的变异，
说明四个用例各自看着一条不同的路径：`over-calendar` 看「抬升 + 报警」，`fallbacks` 看「三处降级」，
`week-boundary` 看「整周边界 + 开学日回退」，`basic` 看「编码与清洗」。

## 7. 没验的部分（重要）

- **fixture 是合成的**：4 组 `*.extracted.json` 都按上游脚本实际读取的字段形状**编造**，不是真实抓取
  （我们没有该校账号，测试方案 §3 已说明移植件只能到这个档位）。它们只保证「同样的输入永远得到同样的输出」，
  **不保证真机上解析正确**。谁拿到真实 dump，替换 fixture 并重跑 `:importer:test` 是最高优先级的贡献。
- **接口路径/字段名未在真机验证**：3 个接口路径与 `activities` 的字段形状来自上游 `ZZU/zzu.js`（同平台 JSTC/CUP/CUPK 一致）；
  `courseUnitList` 的 `indexNo`/`startTime`/`endTime` 取自同平台 CUP/CUPK 的用法（**上游 ZZU 自己不读它**），
  ZZU 的真实作息表是不是这个形状没验过 —— 不符就会走「内置 12 节 + warnings」，不会静默产出错课表。
- **`endDate` 是否存在**：上游读了它，我们信这一点；真机上若没有 `endDate`，走的是「按课表最晚周次 + 18 周下限」两条 `warnings` 的路径（`fallbacks` 用例）。
- **`weekStartOnSunday` 是否存在**：ZZU 上游写死 `firstDayOfWeek: 1`，`weekStartOnSunday` 的用法是从同平台 CUP 借的。
  字段不存在时结果与上游一致（默认 1）；只有 ZZU 真的回了 `true` 才与上游不同。
- **`pageInfo` 的总数嗅探可能误报**：那几个字段名没有一个在真机上见过。它只在这个方向上报警（总数 > 拿到的条数），
  且只产出 `warnings`、不改数据 —— 万一 ZZU 的 `count` 是别的含义，用户会看到一条提示，而不是错课表。
- **`origin()` 的边界**：只处理「页面在 `zzu.edu.cn` 下且路径含 `/student/`」这一种情况。
  如果哪天走的是**改写路径前缀的入口**（深信服式 `/http/<token>/…`），根相对路径不会带上那个前缀，请求会 404 ——
  上游的相对路径写法在那种入口下同样不通，所以没有因此变差；ZZU 目前没有这种入口的证据。
- **`extract.js` 里的任何分支 CI 都跑不到**（它要浏览器，见测试方案 §2）：学期怎么选出来、
  `#allSemesters` 的选项怎么解析、`courseUnitList` 到底在不在响应里，只能靠真机抽验 + 用户反馈。
- **`minAppVersionCode` 写 11**（与同批其它移植件、以及 `dlutci` / `ustc` 一致）：按移植手册 §5.1，
  这一项卡的是**结构性能力** —— 老版本会**整个不认**的载荷种类（`kind:"boxes"` / `kind:"image"`）才需要抬高。
  本适配器输出的仍是 `kind:"schedule"`，只是多写了 `warnings` 字段（推算的开学日、抬升的总周数、
  跳过的记录、作息表来源）：`warnings` 是**可加字段**，老版本忽略未知字段、导入照样成功，只是少几句核对提示。
- **`scheduleUrlHint` 故意没写**：直接打开 `/student/for-std/course-table` 是否会在新会话里 403 未验证
  （`dlutci` 就踩过模块页 403 的坑），所以「一键刷新」走 `loginUrl`（CAS），登录后正常跳转。

---

移植者签名：NullClass（Claude Code）　日期：2026-09-12
