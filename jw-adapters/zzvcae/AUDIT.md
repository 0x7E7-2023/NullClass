# 安全审计 —— 郑州汽车工程职业学院教务适配器（`zzvcae`）

- **审计对象**：`jw-adapters/zzvcae/extract.js` + `jw-adapters/zzvcae/parse.js`（+ `manifest.json`）。
- **上游出处**：`shiguang_warehouse` 的 `resources/ZZVCAE/zzvcae.js`
  （`https://github.com/XingHeYuZhuan/shiguang_warehouse`，MIT）。
  快照 commit **`e62554a4034386b893bcd6813c7b2b64f8c730a3`**（2026-09-12），
  同目录 `adapters.yaml` 的 `maintainer` 是 **Gr11nJ**，`adapter_id` 是 `zzvcae_01`。
- **平台**：树维 EAMS（`/eams/`，上海树维信息科技有限公司 / SupWisdom，新开普子公司）。
  **不是强智** —— 强智的路径是 `/jsxsd/`、登录页写「湖南强智科技发展有限公司」。
- **上游 `adapters.yaml` 的 `import_url`**：`https://cas.zzvcae.edu.cn/cas/login`，
  `description` 原文：「本教务禁止从 jw.zzvcae.edu.cn 直接登录，请先在统一身份认证（CAS）页面登录，
  随后在【服务大厅】→【学生课表查询】打开过课表页面后，点击执行导入。」
  —— `loginUrl` 取自这里。
- **移植者**：0x7E-2023　**审计日期**：2026-09-17
- **审计依据**：[移植手册 §5](../docs/jw-adapter-porting.md) 八条 +
  批次四计划里的 12 条检查表。

---

## 1. 请求了哪些主机与路径（手册 §5 第 3 条、检查表第 10 条）

上游 `zzvcae.js` 里硬编码了 `BASE_URL = "https://jw.zzvcae.edu.cn"`，三个接口都打在它下面。
移植件沿用同一批路径，另外补了一个**可选的**校历接口（上游用 `base/calendar-info.action` 兜底
学期日期，这里同样用来兜底**总周数**）：

| 谁 | 方法 | 地址 | 干什么 |
|---|---|---|---|
| extract.js | GET | `<教务主机>/eams/courseTableForStd.action` | 读课表页：学号 `ids`（`bg.form.addInput(form,"ids","…")`）、学期标签 `tagId`（`id="semesterBarNNNSemester"`）、以及表头里的节次时间块（`th id="0_节次"`） |
| extract.js | POST | `<教务主机>/eams/dataQuery.action` | `tagId=…&dataType=semesterCalendar`，取学期列表（JSON，上游同款） |
| extract.js | POST | `<教务主机>/eams/courseTableForStd!courseTable.action` | body 与上游一致：`ignoreHead=1&setting.kind=std&startWeek=&semester.id=…&ids=…`，取课表 HTML |
| extract.js | POST | `<教务主机>/eams/base/calendar-info.action` | `version=1&semesterId=…`，取学期校历（**取不到不算失败**，交给 parse.js 回落到学期起止日期 / 内置 20 周并写进 warnings） |

- `<教务主机>` = `jwBase()`：地址里带 `/eams/` 时用**当前页面同源**，否则回落到常量
  `https://jw.zzvcae.edu.cn`。**实际请求的主机只有一个**：`jw.zzvcae.edu.cn`。
- 静态扫描（`grep -oE "https?://[A-Za-z0-9._%-]+"`）在两个脚本里的全部命中：
  `extract.js` 里的上游仓库地址（注释）、`extract.js` 里的课表主机常量 `https://jw.zzvcae.edu.cn`、
  `parse.js` 里注释中的上游仓库地址。**没有第三方域**。
- 请求头只有 `Content-Type` / `X-Requested-With` / `Accept` 三个固定值，
  **不带任何自定义令牌**；Cookie 由 WebView 按同源规则自己带上，脚本不读也不写它。
- 端到端自验（假 `fetch` 跑一遍 `extract.js`，记录每一次请求）：
  4 个请求全部落在 `jw.zzvcae.edu.cn` 的 `/eams` 路径上，**主机统计 `{"jw.zzvcae.edu.cn": 4}`**；
  脚本里没有 `sendBeacon` / `WebSocket` / `new Image().src` / 隐藏表单的调用点（`extract.js`
  里 `XMLHttpRequest` 只作为请求头的值出现，不是构造器调用）。

### `allowHosts` = `["jw.zzvcae.edu.cn"]`，为什么是这一个（本批的一类坑）

**闸门有且只有两套语义，两者读的白名单来源不同，`scheduleUrlHint` 两套都不参与：**

1. **提取期的网络闸门**（真正的执行点）
   `feature/settings/src/main/kotlin/com/nullclass/feature/settings/jw/JwWebViewStep.kt:174-176`：
   `allowedHosts += adapter.allowedHosts(hostOf(adapter.manifest.loginUrl))`，
   而 `JwAdapterPackage.allowedHosts`（`importer/.../JwAdapterPackage.kt:42-43`）=
   **`loginHost`（`hostOf(loginUrl)`）+ `manifest.allowHosts`**。
   提取期间 `JwNetworkGate.intercept`（同文件 `:119-126`）把白名单之外的**一切**请求
   （子资源、导航、JS 层 fetch/XHR/WebSocket/sendBeacon）拦掉，判定用 `:99-102` 的 `allows()`。
   **这套闸门完全不知道 `scheduleUrlHint` 的存在。**
2. **桥的 origin 白名单**（决定 `__ncInput` / `__ncDone` 注入到哪些页面）
   `importer/src/main/kotlin/com/nullclass/importer/jw/JwOriginRules.kt:15-27`：
   取 `loginUrl` + `scheduleUrlHint` + `allowHosts`；写**精确主机名**才注入，
   `*.` 通配项会被 `:26` 跳过（手册 §5「通配 `*.` 拿不到桥」）。

**本件的实情**：`loginUrl` 是 CAS 域 `cas.zzvcae.edu.cn`，而教务本体在 `jw.zzvcae.edu.cn` ——
**两者不同源**，且上游明确说教务禁止从 `jw.zzvcae.edu.cn` 直接登录。于是：

- 若把请求写成**相对路径**（`/eams/...`），它们打到的是**当前页面的 host**。
  用户按上游指引在「服务大厅」里打开课表页时页面在 `jw.zzvcae.edu.cn` 上
  （CAS 只是登录入口，登录后会跳回教务域），相对路径没问题；但**只要当前页面还在 CAS 域**
  （用户没跳过去、或一键刷新打开的地址不对），相对路径就会打到 `cas.zzvcae.edu.cn` ——
  那里没有 `/eams/`，请求 404；而且闸门按当前页判断也拦不到它（同源）。
- 所以本件**显式写绝对地址**：请求主机由常量 `https://jw.zzvcae.edu.cn` 钉死，
  不看当前页面是谁。**代价**：闸门必须放行这一个主机，否则取课表的 POST 会被自己的宿主拦掉，
  适配器 100% 失败。
- **因此 `allowHosts` 必须是 `["jw.zzvcae.edu.cn"]`**：
  - **不写 CAS 域**：适配器**从不请求** `cas.zzvcae.edu.cn` —— 登录全程由用户在 WebView 里
    手工完成，脚本不碰登录表单、不提交、不读令牌。它作为 `loginUrl` 的主机**已经被自动放行**
    （上面第 1 条的 `loginHost`），再写一条只是重复。
  - **不写通配** `*.zzvcae.edu.cn`：那会一并放行服务大厅 `hall.zzvcae.edu.cn` 之类
    本适配器一个都不请求的主机；而且通配项拿不到桥。
- `scheduleUrlHint` 写的是 `https://jw.zzvcae.edu.cn/eams/courseTableForStd.action`：
  它**只影响一键刷新打开哪一页**（以及桥会不会注入到那一页），**不参与闸门**。
  写它是因为它顺手让桥在教务页上可用；**就算删掉它，`allowHosts` 也仍然必须写教务主机。**

`parse.js` **不发任何请求**（CI 用 Rhino 实跑，是纯函数），所以上面这些只与 `extract.js` 有关。

## 2. 读了什么

- **页面**：只读课表页 HTML 里的三样 —— 学号 `ids`、学期标签 `tagId`、表头节次时间块。
  **不读** `localStorage` / `sessionStorage` / `document.cookie` / 登录表单 / 任何密码字段
  （静态扫描无命中）；**不写 DOM、不改表单、不点提交、不注入脚本**。
- **接口**：只有课表接口的返回体（TaskActivity 参数、周次位图、节次寻址）与学期列表 / 校历的返回体。
- **输出**：`extract.js` 交出的是一份原始数据清单，但**不是整页 HTML** ——
  课表 HTML 由 `coursesHtmlOf()` **按白名单挑段**（`unitCount` 定义、`var teachers = [...]`、
  `actTeachers = [...]`、`new TaskActivity(...)`、`index = ...`），
  **保持原有先后顺序**拼成一小段再交出去。页面导航栏里的姓名、学号、学院都不带出去。
  端到端自验：交出的 JSON 里 `张三` / `汽车工程学院` / 导航栏 `div` 全部为 0 命中；
  学号只作为请求参数出现过，**不进 JSON、不进 fixture**。
- **脚本里读学号这件事本身**：`ids` 是树维 `courseTableForStd` 接口的**必需参数**
  （上游同样读它）。它不写入载荷、不写进 warnings、不外发；`parse.js` 根本不读这个字段。
- **载荷里只有排课信息**：课名 / 教师 / 教室 / 星期 / 节次 / 周次 / 学期名 / 开学日 / 总周数 / 节次时间。

## 3. 手册 §5 八条逐条

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 不碰凭据 | ✅ 没有 `password` / `pwd` / 登录表单读取，也没有 `localStorage` / `sessionStorage` / `document.cookie` 访问（grep 无命中）。唯一的「凭据」是浏览器自己带的会话 Cookie，脚本既不读内容也不把它发到别处。上游的 `showAlert` / `showPrompt` / `showSingleSelection` / `showToast` / `notifyTaskCompletion` 一律没有移植 |
| 2 | 不外发 | ✅ 全脚本只有一处 `fetch`（`extract.js` 的 `request()` 包装），地址全由 `jwBase()` 拼成，主机只有 `jw.zzvcae.edu.cn`；没有 `sendBeacon` / `WebSocket` / `EventSource` / `new Image().src` / 隐藏表单。绝对 URL 扫描只命中注释里的上游仓库地址与课表主机常量（§1） |
| 3 | 请求域可控 | ✅ 请求主机只有一个，`allowHosts` 写精确主机名、无通配；CAS 域不写（判断依据见 §1） |
| 4 | 只读课表 | ✅ 只请求 `/eams/courseTableForStd.action`（课表页）、`/eams/dataQuery.action`（学期列表）、`/eams/courseTableForStd!courseTable.action`（学生课表）、`/eams/base/calendar-info.action`（学期校历），全在课表查询模块内。**不碰成绩、学籍、个人信息、缴费**。课表响应里夹带的页面导航栏内容不带走 |
| 5 | 不埋点 | ✅ 没有任何统计 / 上报 / 遥测，没有 `img` 打点；两个脚本连 `console.log` 都没有 |
| 6 | 不 eval 远程代码 | ✅ 两个脚本里都没有 `eval` 与 `new Function` / `Function(...)` 的**调用**（注释里说明「上游怎么写的、这里为什么不照抄」的那几句除外，见 §5）。课表 HTML 里的 `index = 5*unitCount+2` 与 `index = 62` **都用正则解析**（`sectionsOfActivity`）；`dataQuery` 的返回体用 `JSON.parse`（包在 try 里，失败返回 null） |
| 7 | 不写页面 | ✅ 不插入或修改 DOM、不改表单、不触发提交或点击。读页面只读字符串（`extract.js` 里连 `DOMParser` 都不用，全是正则；parse.js 更是纯函数） |
| 8 | 不依赖用户输入之外的秘密 | ✅ 没有硬编码密钥、令牌或他人学号。脚本里的常量只有上下文路径 `/eams`、课表主机 `jw.zzvcae.edu.cn`、上游那张 10 节作息兜底表（公开信息），以及 `DEFAULT_UNIT_COUNT = 14`（与上游同值） |

**结论：可以进内置库。** 全部请求落在本校教务主机上，只读课表，不碰凭据、不外发、不埋点、不写页面。

## 4. 批次四检查表 12 条逐条

| # | 检查项 | 落实 |
|---|---|---|
| 1 | **周次位图的下标基准**（本批最大的坑） | ✅ 按本批统一口径：`bitmap[i] === '1'` 且 `i >= 1` → 第 `i` 周；下标 0 是占位符。上游 zzvcae 原文就是 `for (let week = 1; week < value.length && week <= MAX_SUPPORTED_WEEK; week++)`，**与统一口径一致**。实现落在 `bitmapWeeks()` 里唯一那句 `if (week === 0) { stat.zeroBit++; continue; }` —— **删掉它就会产出一个「第 0 周」**，变异 M1 证明这一点。`fixtures/weeks-bitmap.extracted.json` 的第一门课位图第 0 位就是 `1`、第 1 位也是 `1`：既钉住「不许有第 0 周」，也钉住「第 1 位 = 第 1 周」（段从第 1 周起算） |
| 2 | **TaskActivity 的参数位** | ✅ `args[3]` 课名、`args[5]` 教室、`args[6]` 周次位图（与同族 12 件一致）。`args[1]`（教师）是 `xxx.join(",")` 这类**表达式**时，`cleanArg()` 先剥出其中的字符串字面量再拼；其余表达式（数组 / 对象 / 含小括号 / 纯标识符）一律当空，**绝不 eval**。上游 zzvcae 的教师不是从 `args[1]` 取的，而是和 `HFNU`/`XATU` 一样从 `var teachers` 块里的 `actTeachers` 取 —— 本件保持同一路（`teachersOf()`）。**这一条有个真发现**：上游的正则 `\bname\s*:` 只认不带引号的键名，而树维内嵌脚本是 JS 对象字面量、键名可能写成 `"name":` —— 本件改成同时认带引号与不带引号两种；变异 M9 证明这条路径有用例看着 |
| 3 | **`index = 星期 * unitCount + 节次` 的两种写法** | ✅ 两种都认：`index = 5*unitCount+2`（带变量，变量名不管是 `unitCount` 还是已算好的数字都行）与 `index = 62`（已算好的**线性下标**）。两种先归到同一个线性下标，再 `floor(linear / unitCount)` / `linear % unitCount` 拆回星期与节次（与同族 HPU 同一算法）。`fixtures/basic` 的计算机应用基础有一条 `index =18;`（= 星期 2 * 8 + 节次 2）、`fixtures/term-and-total` 有一条 `index =35;`（= 星期 4 * 8 + 节次 3），变异 M8 证明这两条真的在看着这条路径。**没有 `eval` / `new Function`**（上游 DLMU/HAUST 那两个坑，手册 §5 第 6 条，本件不沾） |
| 4 | **`unitCount` 要真的读** | ✅ 从课表 HTML 用 `/\bunitCount\s*=\s*(\d+)\s*;/` 读；**读不到（或读出 ≤0、>30 这种不可能的值）时用上游的缺省值 14 并写一条 warnings**，不静默。上游只 `console.warn`，用户看不见 —— 这条差异在 `fixtures/fallback-and-calendar` / `fixtures/no-semester` 里有用例（变异 M6/M7 证明）。读出数字后还要过范围校验：`unitCount ∈ 1..30`，否则同样回落到缺省 |
| 5 | **作息时间从哪来** | ✅ **优先用课表表头读到的**（上游同款：`th[id="0_节次"]`，文本形如 `(08:00-08:45)`，见 §6）。表头读不到时回落**上游脚本里那张 10 节兜底表**（`ZZQCC_TIME_SLOTS_FALLBACK`），**并且一定写 warnings**（含「这是上游按常见高职作息写的占位值，可能与学校实际作息不符」）。所有时间都过 `HH:mm` 与 `00:00-23:59` 校验、且要求结束晚于开始，越界的整节丢掉并计数（定向验证里塞过 `25:00` / `10:75` 这类越界值）。表头读到的节次编号**必须从 1 开始连续**，否则整份回落并说明 |
| 6 | **开学日** | ✅ 上游用 `showPrompt` 问用户（`promptSemesterStartDate`）。移植后**不再问用户**：优先用学期列表里的 `startDate`（拿不到就用校历的起始日），按手册 §4.3 **回退到那一周的周一**（上游写死的 `firstDayOfWeek` 也是 1，两者同义）；连学期日期都拿不到时按**最近的周一**推算。**三条路径都写 warnings**，推算那条明确说「如果学校不是这一天开学，整学期的课都会错位」 |
| 7 | **周次上限** | ✅ 载荷上限是 30（`MAX_WEEK`）。位图超过第 30 位的部分**逐个计数并丢弃**，写一条 warnings；位图里**全部**的 1 都在第 30 周之后的块单独计数（「周次只落在超出上限的周」），与「位图里一个 1 都没有」**分开说**。总周数同样夹到 30：教务校历给 40 周时夹到 30 并**单独一条**说明（`fixtures/weeks-over-limit` 里就是 40 周，变异 M4 证明） |
| 8 | **`teacher` / `location` 拿不到就留空** | ✅ 上游写 `"未知教师"` / `"未知地点"`（会被当成真姓名、真地点显示）。本件一律 `null`，并分别写两条 warnings（教师缺失 / 地点缺失分开计数）。`fixtures/no-semester` 里那一块的 `actTeachers` 缺失、教室参数是空串，期望值里 `teacher` 与 `location` 都是 `null`（变异 M10-M13 证明） |
| 9 | **学期名** | ✅ 教务给了就用「`schoolYear` + 学期序号」拼成 `2026-2027学年第一学期`；学期列表里没有这一条时**按最晚周次猜**（`2026-2027学年（第 1 学期）`）并写 warnings；连学年都没有时回落到 `郑州汽车工程职业学院当前学期` 并写 warnings。**任何一条路径都不会用适配器 key（`zzvcae`）当学期名**（变异 M20/M21 证明） |
| 10 | **`allowHosts`** | ✅ 精确主机名 `jw.zzvcae.edu.cn`、无通配、不写 CAS 域，理由与两套闸门语义的代码依据见 §1 |
| 11 | **`warnings` 上限** | ✅ **条数**：`warn()` 调用点静态 18 处、无一处写在循环里，结构上界 18 < 20。**长度**：`warn()` 里对每条做 200 字兜底（超长截断并补省略号）。定向验证：把一条 519 字的教务原文塞进 warnings 时，有兜底最长 200 字、**删掉兜底会涨到 622 字（整包会被应用拒绝）**；注入 25 条警告时有截断分支输出 20 条 + 一条「另有 N 条没有显示」，**删掉分支会输出 28 条（同样被拒）**。六对 fixture 里最长的一条是 123 字 |
| 12 | **变异测试** | ✅ 见 §7：22 处变异，每处都报出「改坏哪一处 → 哪条用例红了」；其中 2 处（`warn-cap`、`sorted-locale`）如实标为「fixture 盖不住」，另附定向验证 |

## 5. 与上游 `zzvcae.js` 的行为差异（逐条都有理由）

1. **不问用户任何事**：上游 `runImportFlow` 先 `showAlert` 要用户确认、再 `showSingleSelection`
   选学期、学期日期拿不到时还 `showPrompt` 要开学日。移植件三处全删：学期取教务**当前选中的**
   那个（页面上的学期标签值优先，其次 `dataQuery` 返回的 `semesterId`），开学日推算 + warnings。
   要别的学期，请用户在教务页面里切过去再点「提取课表」（手册 §3 第 1 步）。
2. **不搬桥调用**：`saveImportedCourses` / `saveCourseConfig` / `savePresetTimeSlots` /
   `notifyTaskCompletion` / `showToast` 全部没有移植 —— 我们这条链路是「拉」不是「推」。
3. **取数与解析切两段**：上游把两者写在同一段 async 函数里；本件把 TaskActivity 解析、
   周次位图、节次寻址、连堂合并、开学日与总周数推算全部搬到 `parse.js`
   （CI 只跑得动 `parse.js`，逻辑留在 `extract.js` 等于没有回归）。
4. **课名不再截掉尾部的纯括号说明**：上游 `cleanCourseName()` 会做
   `.replace(/\([^()]*\)\s*$/, "")`，把「高等数学(二)」变成「高等数学」——
   如果同一学期同时开「(一)」「(二)」，两门不同的课会被合成同一门。本件只去 HTML 标签与空白。
   **代价**：教务课名里如果真带「（选修）」「（实践）」这类尾注，本件会把它留在课名里。
   这是有意的取舍：**留着是可读的冗余，去掉是静默的错并** —— 见 §8。
5. **`unitCount` 与作息回落都出声**：上游只 `console.warn`，用户在导入预览里看不到。
6. **教师 / 教室留空**：上游写「未知教师」「未知地点」。
7. **位图里非 0/1 的字符不再静默**：上游对非「1」字符当作「不上」，教务必位图写成别的记号时
   会**静默少周**；本件发现非 0/1 字符就**整块跳过并计数**（宁可少一门并出声，也不要悄悄漏周）。
8. **上游的 `MAX_SUPPORTED_WEEK = 60` 收到 30**：载荷校验是 30，60 只是上游的内部数组大小。
9. **多带一个 `base/calendar-info.action` 请求**：上游用它的结果兜底「学期日期 + 总周数」，
   本件沿用（可选接口，失败不影响导入）。上游从它拿到 `firstDayOfWeek`（写死 1）——
   本件的每周起始日同样是周一。
10. **上游那几处 `console.log` / `console.warn` 没有移植**（本件两个脚本都不向控制台输出）。

**没有照抄的一处危险写法**（手册 §5 第 6 条，本批检查表第 3 条要求逐处说明）：
上游 `parseTaskActivities` 本身**没有**用动态求值（它用正则，这是树维族里少见的干净写法）——
本件保持；但上游 `parseSemesterResponse` 里有一句
`const data = Function("return (" + raw + ");")();`，把教务返回的字符串当代码执行。
同族的 `HPU`/`ZUA`/`NEUQ` 也是这个写法，`DLMU` 更直接 `new Function("return " + expr)`。
本件**换成 `JSON.parse`**（`jsonOf()`，包在 try 里失败返回 null），
既避开动态求值，也顺带得到一个「响应不是 JSON 就交 null」的明确分支。

## 6. 内置作息兜底表的来源（检查表第 5 条要求写清）

- **优先**：课表页 / 课表响应表头里的 `th[id="0_节次"]`，文本形如 `(08:00-08:45)`
  （`parsePeriodTimes()`，正则与上游逐字相同）。**编号必须从 1 开始连续**才认
  —— 编号错位的作息表比没有更糟（节次与时间的对应关系错了，用户看不出来），
  所以校验不过就整份回落并说明。用例：`basic` / `term-and-total` 走的是这条路（8 节，
  来自 `timeHeader(8)`）。
- **兜底**：上游 `zzvcae.js` 里的 `ZZQCC_TIME_SLOTS_FALLBACK`（10 节：08:00-08:45 /
  08:55-09:40 / 10:00-10:45 / 10:55-11:40 / 14:30-15:15 / 15:25-16:10 / 16:30-17:15 /
  17:25-18:10 / 19:30-20:15 / 20:25-21:10），**原样搬过来**。
  上游自己的注释写着「本校作息兜底（学校未发布公开作息表时使用…）
  这里暂按常见高职作息填写占位，**实测后以教务系统课表页解析出的作息为准**」
  —— 也就是说连上游作者都没在真机上核过这张表，**它是脚本作者的假设、不是学校数据**。
  所以本件用它时一定写 warnings，并且把「这是上游按常见高职作息写的占位值」写进去。
  用例：`fallback-and-calendar` / `no-semester` / `weeks-bitmap` / `weeks-over-limit` 走这条路。
- **`unitCount` 缺省 14 的来源**：上游 `parseTaskActivities` 的
  `const unitCount = unitCountMatch ? parseInt(unitCountMatch[1], 10) : 14;` —— 同一个数字。
- **真机怎么核对这两条**：
  - 导入预览里那条 warnings 会直说「已按缺省 14 节解析」。用户在教务页面上数一下
    课表左侧（或右侧）的节次列有几行，与 14 一对即可；对不上就是课表的星期与节次整体错位
    （因为 `unitCount` 决定了 `线性下标 → 星期 / 节次` 的整张映射表）。
  - 作息对不上时，导入预览里那条 warnings 会直说「已用适配器内置的 10 节作息表」，
    并给出第 1 节的时间；用户在学期管理里改成学校实际作息即可。

## 7. 变异测试记录（检查表第 12 条）

方法：**在内存里**对 `parse.js` 源码做定点替换后重跑全部六条用例（全程不落盘；
跑完用 sha256 核对文件未变，六条用例全 `MATCH` 为绿）。共 22 处变异（含 2 处 fixture 盖不住、另附定向验证的）。

| # | 改坏哪一处 | 结果 | 红在哪（实际输出 vs 期望） |
|---|---|---|---|
| — | 原始（未变异） | 六条全绿 | `parse.js` sha256 `7246261f…`，`extract.js` sha256 `98fbea67…`（见 §9） |
| M1 | `bitmapWeeks` 里去掉「第 0 位是占位符」那一句（`if (week === 0) { stat.zeroBit++; continue; }`） | **`weeks-bitmap` 红**（2 处） | 第 0 位被当成第 0 周；「第 0 位是 1」那条 warnings 消失，warnings 由 5 条变 4 条。**其余五条全绿** |
| M2 | 位图超上限那一句删掉（不截断、也不计数） | **`basic` `weeks-bitmap` `weeks-over-limit` 三条红** | 第 40/50/60/70 周不再被丢弃，「有 N 个周次超出…」的 warnings 全部消失 |
| M3 | 位图超上限时计数但**不**丢弃（去掉 `continue`） | **`weeks-over-limit` 红** | 越界周次进了课表（那门课多出一条第 70 周以外的安排） |
| M4 | 总周数的 30 周夹取删掉 | **`weeks-over-limit` 红**（2 处） | `totalWeeks` 40（超出载荷上限，整包会被拒）而不是 30；那条「超过了空课能表示的 30 周」说明消失 |
| M5 | `weeksBetween()` 的周数换算 +1 | **`basic` `term-and-total` 红** | 总周数 18 → 19 |
| M6 | `DEFAULT_UNIT_COUNT` 14 改成 12 | **`fallback-and-calendar` `no-semester` 红** | 缺省节次数变了，`index = 4*unitCount+0` 拆出来的星期 / 节次随之错位 |
| M7 | `unitCount` 读不到时不写 warnings | **`fallback-and-calendar` `no-semester` 红** | warnings 里少一条，用户再也看不到「节次数是猜的」 |
| M8 | 线性下标那一支关掉（只认 `index = a*unitCount+b`） | **`basic` `term-and-total` 红** | `basic` 的计算机应用基础 `startPeriod` 由 3 变 4（`index =18;` 那条读不出、只剩 `index =2*unitCount+3`）；`term-and-total` 的 `endPeriod` 由 4 变 3 |
| M9 | `teachersOf()` 的正则退回上游的 `\bname\s*:`（树维内嵌脚本的键名带引号，退回后就一个都读不到） | **五条红**（`basic` / `weeks-bitmap` / `weeks-over-limit` / `fallback-and-calendar` / `term-and-total`；`no-semester` 那条本来就缺 `actTeachers`、期望值也是 `null`，所以绿） | 树维的字面量键名是 `"name":`，所有课的老师变成 `null`，并多出「有 N 门课没有读到教师姓名」 |
| M10 | 教师为空时写「未知教师」（上游原样） | **`no-semester` 红** | `teacher` 由 `null` 变 `未知教师`（会被当成真姓名显示） |
| M11 | 教室为空时写「未知地点」（上游原样） | **`no-semester` 红** | `location` 由 `null` 变 `未知地点` |
| M12 | 「教师缺失」那条 warnings 删掉 | **`no-semester` 红** | 少一条说明 |
| M13 | 「地点缺失」那条 warnings 删掉 | **`no-semester` 红** | 少一条说明（与 M12 独立） |
| M14 | 开学日不按周一回退（直接用教务给的日期） | **`weeks-bitmap` 红**（2 处；其余五条用例的 semesterStart 本来就是周一，所以绿） | `firstDay` 2026-09-07 → 2026-09-09（周三），整学期错位 2 天；说明文字里的日期也跟着错 |
| M15 | 位图的非 0/1 字符不再整块跳过（删掉校验） | **`weeks-over-limit` 红**（3 处） | 「心理健康教育」（位图含 `x`）被放进课表，还多出一条假的 4-4 周安排；那条告警消失 |
| M16 | 同上，换一处改（校验正则改成恒真） | **`weeks-over-limit` 红**（3 处） | 同 M15（两处独立证明） |
| M17 | `mergeLessons` 的分组不按课名/教师/教室/星期分组（`if (true)`） | **`basic` `weeks-bitmap` `weeks-over-limit` `term-and-total` 四条红** | 连堂不再合并：`startPeriod` 由 1 变 4（`basic`）、3 变 4（`weeks-over-limit`）等 |
| M18 | 「第 0 位是 1」那条 warnings 删掉 | **`weeks-bitmap` 红**（2 处） | warnings 5 → 4 条（M1 之外的独立证明：就算口径对，告警本身也有用例看着） |
| M19 | 总周数不因课表里的更晚周次抬高 | **`weeks-bitmap` `term-and-total` 红** | `totalWeeks` 30 → 16、26 → 18；那几周的 block 会越界 |
| M20 | 学期名拿不到时用适配器 key | **`no-semester` 红**（2 处） | 学期名由「郑州汽车工程职业学院当前学期」变成 `zzvcae` |
| M21 | 学期名的「按最晚周次猜」分支关掉 | **`term-and-total` 红**（2 处） | 学期名退回带校名的兜底名 |
| M22 | 排序键反序（`cmpNum(b.dayOfWeek, a.dayOfWeek)`） | **`basic` `term-and-total` 红** | block 顺序反了（fixture 逐数组比对，靠这条钉住顺序是确定的） |

**两处 fixture 盖不住的，如实记下来**（不假装盖住了）：

- **`warnings` 的 200 字兜底**（`warn()` 里那一句截断）：六条用例里的警告文本最长 123 字，
  截断分支跑不到。定向验证：往 `warn()` 的入参里塞一个 519 字的串，
  有兜底时最长 warnings 恰好 200 字，**删掉兜底涨到 622 字** ——
  而载荷校验单条超 200 字会让**整包被拒**（`JwPayloadCodec.validate`）。
- **`warnings` 的 20 条上限截断分支**：结构上界 18 条（`warn()` 调用点静态计数，无一处写在循环里），
  跑不到 20。定向验证：注入 25 条后输出 20 条 + 「另有 9 条说明因为超出上限没有显示」，
  **删掉截断分支输出 28 条**（同样被拒）。

**M2/M3、M15/M16 是同一处逻辑的两种改法**，都红在同一条用例上 —— 记两遍是为了说明
「改坏方式不同、可观察面相同」，不是凑数。

## 8. 已知边界与没做的事

- **fixture 是合成的**：形状照上游 `zzvcae.js` 实际读取的字段编（TaskActivity 参数位、81 位周次
  位图、`index = 星期*unitCount+节次` 与已算好的线性下标、`th id="0_节次"` 表头），
  课程 / 教师 / 教室均为虚构。每份 `fixtures/*.extracted.json` 的 `_note` 都写明了这一点。
  它只保证「同样的输入永远得到同样的输出」，**不保证在真实教务页面上解析正确**。
  谁拿到真实 dump 请替换并重跑门。**代价**：合成位图是 81 位、且规律地取到第 50/60/70 周 ——
  真实位图长得多（40-54 位常见），截断逻辑在真机上可能触发在别的位置；
  合成数据也验不了「`var teachers` 块之间真的没有别的脚本片段」这类页面结构假设。
- **没有真机验证**（维护者没有该校账号）。以下都来自上游脚本与同族树维件的经验，
  真机上可能与假设不同，且**都会以带提示的错误暴露，不会静默导错**：
  - 四个接口的路径与请求体（前三个与上游同款，第四个是上游也用的可选接口）；
  - 课表页上 `ids` / `tagId` 的写法（上游有五种 ids 正则、两种 tagId 正则，本件一条没放宽 ——
    正则放宽到把别处的数字当学号比读不出来更糟，那会拿别人的课表去请求）；
  - 表头节次的 `th id="0_节次"` 形态与 `(HH:mm-HH:mm)` 文本（上游同款）。
- **课名不再截尾部括号**（§5 第 4 条）是取舍，不是修好了：保留了「（实践）」这类尾注的冗余，
  换掉了「把 `高等数学(一)/(二)` 合成一门」的静默错并。真机上若发现课名带脏尾注，
  应当加一条 warnings 说明，而不是悄悄截掉。
- **`extract.js` 不做解析**：周次位图、节次寻址、连堂合并、开学日推算全在 `parse.js`。
  `extract.js` 唯一「加工」过的是把课表 HTML 按白名单挑段（§2），**顺序保持原样**。
- **`ids`（学号）是取数必需的请求参数**（树维接口的 `service` 参数），
  但**不进载荷、不进 fixture、不写进 warnings**；`parse.js` 不读它。
- **学期只导当前那一个**：`terms` 是数组，多学期导入在协议上支持，但上游只导用户选中的
  一个、本件也照做 —— 要别的学期请在教务页面里切过去再提取（手册 §3 第 1 步）。
- **上游注释里说的 `courseTableForStd.action` 取作息那一步没有重复请求**：
  表头的节次块已经在取课表页 HTML 的响应里，再请求一次同一个地址是浪费
  （同一个会话下内容一致）。
- **没有碰**：`index.json`（由主 agent 统一加）、任何 Kotlin、任何既有适配器、任何文档。

## 9. 结论

**可以进内置库。** 全部请求落在本校教务主机 `jw.zzvcae.edu.cn` 上，只读课表，
不碰凭据、不外发、不埋点、不写页面；`allowHosts` 只放行这一个精确主机（§1 解释了
为什么 CAS 域不写、为什么必须写教务主机、`scheduleUrlHint` 为什么不参与闸门）。

自验（本机 Node，`vm` 里跑 `parse.js`）：**6 对 fixture 全部 MATCH**；
`extract.js` 端到端（假 fetch）4 个请求全落在 `jw.zzvcae.edu.cn`，
交出的 JSON 里 0 处页面个人信息。两个脚本 NUL 字节 0、无 BOM、
无箭头函数 / 反引号 / `let ` / `const `（注释里也没有）、无 `eval` / `new Function` 调用。

- 移植者签名：**0x7E-2023**
- 日期：**2026-09-17**
- 上游快照：`e62554a4034386b893bcd6813c7b2b64f8c730a3`（2026-09-12），MIT，上游作者 **Gr11nJ**
