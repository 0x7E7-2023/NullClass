# 江苏旅游职业学院（`jstc`）适配器 —— 安全审计

| | |
|---|---|
| 上游 | [shiguang_warehouse](https://github.com/XingHeYuZhuan/shiguang_warehouse) → `resources/JSTC/jstc_01.js`（MIT，Copyright 2025 星河欲转） |
| 上游快照 | commit `e62554a4034386b893bcd6813c7b2b64f8c730a3`（2026-09-12）；学校名与登录地址取自同目录 `adapters.yaml`（maintainer 星河欲转） |
| 上游平台 | 树维 `for-std`（`jwxt.jstc.edu.cn/student/for-std/...`），与上游 `ZZU` / `CUP` / `CUPK` 同一套接口 |
| 本适配器 | `jw-adapters/jstc/`，按[适配器规范](../../docs/jw-adapter-spec.md) v1 切两段（`extract.js` 只取数 / `parse.js` 纯转换）+ 降 ES5 |
| 移植者 | NullClass（Claude Code 执行） |
| 审计日期 | 2026-09-12 |
| 审计范围 | `extract.js`、`parse.js` 全文，`manifest.json` 的 `allowHosts` / `loginUrl` |
| 结论 | **安全性通过**（逐条见 §2，请求域见 §1）。**正确性未验**：fixture 是合成的，见 §4 |

---

## 1. 请求了哪些域

脚本一共发 **3 个 GET**，主机全部写在同一常量 `BASE = https://jwxt.jstc.edu.cn` 下：

| # | 方法 | URL | 拿什么 |
|---|---|---|---|
| ① | GET | `https://jwxt.jstc.edu.cn/student/for-std/course-table` | 课表页 HTML，只为读 `#allSemesters` 的学期选项（学期 id 与名称） |
| ② | GET | `https://jwxt.jstc.edu.cn/student/ws/semester/get/{semesterId}` | 学期元数据 JSON：`startDate` / `endDate` / `weekStartOnSunday`。**拿不到会降级**（交 `null`，由 `parse.js` 推算开学日并写进 `warnings`），不阻断导入 |
| ③ | GET | `https://jwxt.jstc.edu.cn/student/for-std/course-table/semester/{id}/print-data?semesterId={id}&hasExperiment=true` | 排课记录 `studentTableVms[0].activities` + 作息表 `timeTableLayout.courseUnitList` |

出现的主机只有两个，都在学校自有域 `jstc.edu.cn` 下：

- **`jwxt.jstc.edu.cn`** —— 上面 3 个请求（教务系统本体）；
- **`authserver.jstc.edu.cn`** —— CAS 登录页，写在 `manifest.loginUrl` 里（取自上游 `adapters.yaml` 的
  `import_url`），**脚本不请求它**，只是 WebView 的打开地址。

`allowHosts` 只写一条 **`*.jstc.edu.cn`**：两个子域都在该域下，且教务页面自己可能从同域其它子域取静态资源
（提取结束后 JS 层白名单继续生效，写死两个主机名会把页面自身的子资源也挡掉）。通配止于三段域名
`jstc.edu.cn`，没有使用 `*.edu.cn` 这类跨校通配。上游脚本里没有别的域（`grep -oE "https?://[A-Za-z0-9.-]+"` 只有
`https://jwxt.jstc.edu.cn`）。

## 2. 移植手册 §5 八条

| # | 检查项 | 结论 |
|---|---|---|
| 1 | **不碰凭据** | ✅ 不读登录表单、不读 `localStorage` / `sessionStorage`、不读任何令牌或密码字段，也没有把任何东西发回去。取数只用用户已登录页面自带的会话 Cookie（`fetch(..., {credentials: 'include'})`，同源）。 |
| 2 | **不外发** | ✅ 全文只有 3 个 `fetch`，全部指向本校教务域 `jwxt.jstc.edu.cn`；没有 `XMLHttpRequest`、`sendBeacon`、`WebSocket`、`EventSource`、`new Image().src`，也没有第三方脚本/CDN 引用。 |
| 3 | **请求域可控** | ✅ 见 §1：主机逐一列出并写进 `allowHosts`（`*.jstc.edu.cn`，学校自有域，覆盖 CAS + 教务两个子域），未使用过宽通配。 |
| 4 | **只读课表** | ✅ 3 个接口依次是课表页、学期元数据、课表 `print-data`；没有成绩、学籍、个人信息、缴费、选课（写操作）类接口。元数据接口只读学期起止日期。 |
| 5 | **不埋点** | ✅ 无任何统计／上报／遥测，无「匿名」上报，无外部资源引用。 |
| 6 | **不 eval 远程代码** | ✅ 无 `eval`、`new Function`、`document.write`、`setTimeout("...")`；`JSON.parse` 只解析教务返回的 JSON，解析失败即报错。 |
| 7 | **不写页面** | ✅ 不写 DOM、不改表单、不触发提交、不点击页面元素。`DOMParser` 只在内存里解析取回的 HTML 字符串（不挂到文档上），拿 `#allSemesters` 的选项；**连「切到课表页」这种点击都没有**——学期取自 URL 或页面当前状态。 |
| 8 | **不依赖用户输入之外的秘密** | ✅ 无硬编码密钥、无他人学号、无固定令牌；`BASE` 是学校公开域名。唯一需要的运行时信息是当前页面里的会话 Cookie（用户自己登录得来的）。 |

补充（不是 §5 的条目，但审计时会看）：

- **没有交互**：不使用 `__ncSelect` / `__ncConfirm` / `__ncPrompt`（上游的选学期弹窗改成了「取页面上当前打开的学期」），
  更没有用弹窗索要任何账号信息。
- **没有用 OCR 桥**：不调 `__ncOcr` / `__ncOcrGrid`（课表是接口给的，不需要识别）。
- **只做取课表这一件事**：`parse.js` 是纯函数（CI 用 Rhino 跑），不访问网络、不读页面。

## 3. 读了什么数据（含边界）

| 数据 | 用途 | 去向 |
|---|---|---|
| `#allSemesters` 的 `option`（id + 名称 + 是否 selected） | 选出要导入的学期、给用户看学期名 | 进提取结果 `semesters` / `term` |
| 学期元数据 JSON（整个对象） | `parse.js` 只读 `startDate` / `endDate` / `weekStartOnSunday`（外加可选 `nameZh` / `name` / `firstDayOfWeek`） | 原样放在提取结果 `semesterMeta` 里，不上传、不落库 |
| `studentTableVms[0].activities` | 课程名、教师、教室/校区、星期、节次、周次数组 | 原样放在提取结果 `activities` 里 |
| `timeTableLayout.courseUnitList` | 学校真实作息表（节次 → 起止时间） | 原样放在提取结果 `courseUnitList` 里 |

**没有取**：`print-data` 响应里除上面两个子对象外的其它字段（顶层若带学生标识一类字段也不会进提取结果）；
成绩、学籍、培养方案、考试、缴费等任何接口。提取结果只在本机内部从 `extract.js` 交给 `parse.js`，
**不发送到任何地方**；`parse.js` 只读取上表列出的字段。

## 4. 没验的部分（重要）

- **fixture 是合成的**：`fixtures/basic.extracted.json`（基本表格）与 `fixtures/over-calendar.extracted.json`
  （课表周次超出校历）都按上游脚本实际读取的字段形状**编造**，不是真实抓取（我们没有该校账号，
  测试方案 §3 已说明移植件只能到这个档位）。它们只保证「同样的输入永远得到同样的输出」，
  **不保证真机上解析正确**。谁拿到真实 dump，替换 fixture 并重跑 `:importer:test` 是最高优先级的贡献。
- **接口路径/字段名未在真机验证**：`/student/ws/semester/get/{id}` 与 `print-data` 的
  `studentTableVms[0].activities` 形状来自上游脚本（同平台 ZZU/CUP/CUPK 一致），
  `timeTableLayout.courseUnitList` 的 `indexNo/startTime/endTime` 取自同平台 CUP 的用法。
  真机上若不符，`parse.js` 会走「推算 + warnings」或直接报错，不会静默产出错课表。
- **`minAppVersionCode` 写 11**（与同批其它 7 个移植件、以及 `dlutci` / `ustc` 一致）：按移植手册 §5.1，
  这一项卡的是**结构性能力** —— 老版本会**整个不认**的载荷种类（`kind:"boxes"` / `kind:"image"`）才需要抬高
  （内置 `universal` 写 16 就是为此）。本适配器输出的仍是 `kind:"schedule"`，只是多写了 `warnings` 字段
  （推算的开学日、按课表抬高的总周数、跳过的排课记录、作息表来源）：`warnings` 是**可加字段**，
  versionCode 18（0.8.2）之前的老版本会忽略这个不认识的字段，导入照样成功，只是少几句核对提示 ——
  这属于「忽略即可」，不是「行为诡异」，照 §5.1 不该因此拒绝安装。
- **`scheduleUrlHint` 故意没写**：直接打开 `/student/for-std/course-table` 是否会在新会话里 403 未验证
  （`dlutci` 就踩过模块页 403 的坑），所以「一键刷新」走 `loginUrl`（CAS），登录后正常跳转。
- **`extract.js` 里的任何分支 CI 都跑不到**（它要浏览器，见测试方案 §2）：学期怎么选出来、
  `#allSemesters` 的选项怎么解析，只能靠 §6.2 的手验办法 + 用户反馈。

## 5. 移植改动（与上游的差异）

1. **去掉选学期弹窗**（上游 `showSingleSelection`）：改取用户此刻在教务页面上打开的学期
   （URL 里的 `/semester/{id}`），其次取 `#allSemesters` 里**写了 `selected` 属性**的那一项
   （判定见 §6.2），最后才取列表第一个 —— 后一种情况会在 `warnings` 里如实说明「没标出当前学期，取的是列表第一个」。
2. **上游写死的两处改成能取就取**：开学日期（上游 `semesterStartDate` 来自元数据但没做周起始日回退；
   这里按 `weekStartOnSunday` 回退到那一周的起始日）与**作息表**（上游把 11 节常量直接当学校作息存；
   这里优先用教务接口给的 `courseUnitList`，没有才用上游那 11 节，且用了就进 `warnings`）。
3. **不静默丢数据**：上游 `if (!act.courseName || ... ) continue` 悄悄跳过不完整的排课记录；
   这里逐条计数后写进 `warnings`（缺课程名/星期/节次/周次各几条），全跳过则直接报错。
4. **降到 ES5**：上游是 `async/await` + 模板串，改成 Promise 链；去掉拾光桥（`showToast` /
   `notifyTaskCompletion` / `saveImportedCourses`），改为 `return` 载荷（空课的拉取式契约）。
5. **拆两段**：上游把取数与转换写在一个函数里并直接存库；这里 `extract.js` 只取数、`parse.js` 做转换
   （周次切段、课程聚合、开学日与总周数推断都在 `parse.js`，CI 能真跑）。
6. **总周数**：上游不算这个值（它直接把课程交给拾光）。这里按校历的起止日期数周，
   课表里出现更晚的周次就按课表抬上去，**抬升必写 `warnings`**（见 §6.1）。

## 6. 本轮修掉的两处（2026-09-12）

两条都是「值改了但没说」的同一种毛病：覆盖了教务给的口径，却不像开学日、作息表那样留下线索。

### 6.1 课表周次比校历更晚时，总周数的抬升现在会进 `warnings`

`parse.js` 用校历的 `startDate`/`endDate` 数出周数，若课表里最晚的周次更大就按课表抬上去
（校历给短了，抬升本身是对的）——但原来这一抬是静默的：`warnings` 里会写开学日是推算的、
会写跳过了几条记录、会写作息表是哪来的，唯独不写「学期总周数已经被抬过」，
用户看到的总周数与教务处给的对不上，却没有任何线索。

现在抬升时写一条：

> 教务给的学期起止日期（2025-12-21）只能数出 16 周，但课表里有安排落在第 20 周，
> 学期总周数已按 20 周导入，请在学期管理里核对。

（校历本来就没给结束日期时，走的是另一条已有的提示「学期总周数按课程里出现的最大周次取为 N 周」，
两条互斥，不会同时出现。）

**怎么验（CI 跑得到）**：fixture `fixtures/over-calendar.*` 就是这条路径 —— 合成数据，
校历 `2025-09-03 ~ 2025-12-21`（= 16 周）配上排到第 18~20 周的「专题讲座」，
期望载荷的 `totalWeeks` 是 20 且 `warnings` **只有**那一条。`./gradlew :importer:test` 即验。

### 6.2 `#allSemesters` 的「页面标出了当前学期」改看 `selected` 属性

原实现读 `options[i].selected`。浏览器会把「一个 `selected` 属性都没写」时的**第一个** option 的
`.selected` 置为 `true`，于是默认情况下每个页面都被判成 `pickedBy:"selected"`，
`parse.js` 里那条「教务页面没有标出当前学期，已取学期列表里的第一个…」几乎发不出来；
而同一个页面走正则兜底（它看的是属性）却给出相反的结论 —— 两条路径自相矛盾。

现在两边都看属性：`options[i].hasAttribute('selected') === true`。
（首项是 `<option value="">请选择</option>` 这种空占位时，两条路径本来就都给 `first`；
改的是「一个 option 都没标 selected」这个默认情况，那时旧代码会误报成 `selected`。）

**怎么手验（CI 跑不到 `extract.js`，测试方案 §2）**：

1. 先在控制台确认「DOM 的默认选中态」和「页面真写了什么」是两回事：

   ```js
   var doc = new DOMParser().parseFromString(html, 'text/html');
   var o = doc.getElementById('allSemesters').getElementsByTagName('option');
   o[0].selected;                  // true  —— 浏览器补的默认选中态，不代表页面标了
   o[0].hasAttribute('selected');  // false —— 页面真正写了什么
   ```

2. 端到端：把课表页 HTML 抓下来，**删掉学期下拉里所有 `selected` 属性**，存成本地页面，
   按测试方案 §4 把测试适配器的 `loginUrl` 指过去（本地 HTTP 服务 + `adb reverse`），
   点「提取课表」，导入预览里应当出现「教务页面没有标出当前学期，已取学期列表里的第一个「…」」；
   再把 `selected` 属性加回第 2 个学期，预览里就**不该**再有这条（且导入的是那个学期）。
   第二条尤其重要：它同时证明「真的标了 selected 时仍然认得出来」—— 只测第一条会漏掉反向的回归。

本地已用 headless Chromium（`chrome-headless-shell` + 桩 `fetch` 与桩课表页 HTML）跑过 4 组对照：
「一个都没标 selected」旧版给 `pickedBy=selected`（错）、新版给 `first`（对）；
「第 2 个标了 selected」新旧都给 `selected`；空占位那组新旧都给 `first`；
同一份 HTML 走正则兜底旧版给 `first`、与旧版的结构化路径结论相反（就是上面说的自相矛盾），新版两条路径一致。

---

移植者签名：NullClass（Claude Code）　日期：2026-09-12
