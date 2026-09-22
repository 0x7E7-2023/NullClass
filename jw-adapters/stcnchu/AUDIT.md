# 安全审计：`stcnchu`（南昌航空大学科技学院）

- **适配器**：`jw-adapters/stcnchu/`（`manifest.json` / `extract.js` / `parse.js` / `fixtures/`）
- **移植者**：`0x7E7-2023`
- **移植日期**：2026-09-13
- **审计对象**：上游 `STCNCHU/stcnchu.js` 全文 + 本适配器 `extract.js` / `parse.js` 全文
- **结论**：**通过**。只请求本校教务服务器上的一个课表接口（同源相对路径），不碰凭据、
  不写页面、不埋点、不外发、不读课表以外的任何数据。逐条见 §5、§6。

---

## 1. 上游出处

| 项 | 值 |
|---|---|
| 仓库 | `https://github.com/XingHeYuZhuan/shiguang_warehouse`（MIT） |
| 文件 | `resources/STCNCHU/stcnchu.js`（11528 字节） |
| 同目录 `adapters.yaml` | `adapter_id: STCNCHU`／`adapter_name: 南昌航空大学科技学院强智教务`／`maintainer: 星河欲转`／`import_url: http://qzjwxt.stcnchu.edu.cn:800/jsxsd/` |
| 快照 commit | `e62554a4034386b893bcd6813c7b2b64f8c730a3` |
| commit 日期 | `2026-09-12 12:50:29 +0800` |
| 上游作者 | 星河欲转（维护者在 yaml 的 `maintainer` 字段） |

本适配器是**该 commit 的快照**，不追上游更新（见测试方案 §5）。

## 2. 平台与取数方式（按脚本实际请求的路径判，不按上游注释）

- 接口路径 `/jsxsd/xskb/xskb_list.do`，返回**服务端渲染的 HTML**（不是 JSON）→ 强智科技
  「高校综合管理教务系统」学生端，与 `cqrk`/`hynu`/`hniu` 同族。
- 取数方式：**DOM 抓取**。课表是一张真 `<table>`（`#kbtable`），课程明细在
  `div.kbcontent` 里，字段是 `<font title="老师|教师|教室|周次(节次)">`。
- 学期号走 `xnxq01id`（形如 `2026-2027-1`），上游让用户手输学年 + 手选学期拼出来。

上游静态扫描（移植手册 §5 给的扫法，原文照录）：

```
$ grep -oE "https?://[A-Za-z0-9.:-]+" stcnchu.js | sort -u
http://qzjwxt.stcnchu.edu.cn:800          ← 只有一个域，且是本校教务自己

$ grep -nE "fetch\(|XMLHttpRequest|sendBeacon|new WebSocket|\.src\s*=|localStorage|eval\(" stcnchu.js
235:        const response = await fetch("http://qzjwxt.stcnchu.edu.cn:800/jsxsd/xskb/xskb_list.do", {

$ grep -nE "成绩|学籍|xsxx|缴费|密码|password" stcnchu.js
238:            body: `jx0404id=&cj0701id=&zc=&demo=&xnxq01id=${semesterId}`,
```

两点说明：

- 第 238 行的 `cj0701id` 看着像「成绩」（cj = 成绩拼音），实际是**课表查询接口自己的一个
  过滤参数**，上游把它传**空串**；请求打的是 `xskb_list.do`（课表查询），不是任何成绩接口。
  本适配器逐字照搬同一份表单体，同样传空。
- 全脚本只有**一处** `fetch`，没有 `XMLHttpRequest`／`sendBeacon`／`WebSocket`／`new Image().src`／
  `localStorage`／`eval`。唯一的绝对 URL 就是本校教务那一台。

## 3. 本适配器请求什么

**主机**：`qzjwxt.stcnchu.edu.cn`（只有这一个）
**路径**：`/jsxsd/xskb/xskb_list.do`（POST，同源相对路径）

```
POST /jsxsd/xskb/xskb_list.do            ← 相对路径，落在当前页面所在的服务器上
Content-Type: application/x-www-form-urlencoded
credentials: same-origin
jx0404id=&cj0701id=&zc=&demo=&xnxq01id=2026-2027-1
```

### 3.1 非标准端口 `:800` —— 本件的重点

这台教务跑在 **`:800`**（`http://qzjwxt.stcnchu.edu.cn:800/jsxsd/`），不是 80/443。
三处都按「只写主机名、路径保持相对」处理，理由是逐条查过宿主实现的：

| 环节 | 实现 | 端口怎么办 |
|---|---|---|
| `allowHosts` 校验 | `JwHostAllowlist.errorOf()` 的正则是 `^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[…])+$` | **写 `qzjwxt.stcnchu.edu.cn:800` 会被判成非法域名直接拒装**（正则里没有冒号）。所以只写主机名 |
| 请求放行（提取期闸门 + JS 沙箱 `__ncAllowed`） | `JwHostAllowlist.matches(host, …)`、沙箱里 `new URL(url).hostname` | **只比主机名，端口不参与**。所以请求 `…:800` 与白名单里的 `qzjwxt.stcnchu.edu.cn` 是匹配的 |
| 桥注入（`JwOriginRules.forAdapter`） | 取自 `loginUrl` / `scheduleUrlHint`，`uri.port > 0` 时写成 `scheme://host:port` | manifest 里这两个 URL **都带 `:800`**，所以注入的 origin 规则是 `http://qzjwxt.stcnchu.edu.cn:800`，与页面真实 origin 一致 |

结论：**主机名进 `allowHosts`、端口进 `loginUrl`/`scheduleUrlHint`、请求路径写相对**。
只要把带端口的绝对地址写进脚本（上游就是写死的），同源这条线就形同虚设 —— 脚本会绕过
「当前页面所在的那台服务器」这个约束去点名一台主机。

顺带：`loginUrl` 是 `http`（不是 https），应用会在安装/导入前显示「不安全连接」标记。
这是学校自己的部署形态（校内入口就是 http），不改成 https 是因为改成 https 会连不上。

### 3.2 `allowHosts` 的诚实说明

```json
"allowHosts": ["qzjwxt.stcnchu.edu.cn"]
```

这一条与 `loginUrl` 同主机，按 `JwAdapterPackage.allowedHosts()`（`loginHost + manifest.allowHosts`）
的算法它**是冗余的** —— 列出来是为了把「这台服务器会被请求」写明在安装前展示的那张清单里，
而不是因为它额外放行了什么。没有通配、没有第二个域、没有 CDN、没有统计域。

## 4. `manifest.json` 取值

| 字段 | 值 | 依据 |
|---|---|---|
| `key` | `stcnchu` | 上游目录名小写 |
| `name` | `南昌航空大学科技学院` | 上游 yaml 的 `adapter_name` 去掉平台后缀（与 `cqrk`/`hniu` 同一处理） |
| `version` | `1.0.0` | 首版 |
| `author` | `上游 星河欲转（MIT）；移植 0x7E7-2023` | 移植手册 §3 第 6 步 |
| `loginUrl` | `http://qzjwxt.stcnchu.edu.cn:800/jsxsd/` | 上游 yaml `import_url` |
| `scheduleUrlHint` | `…:800/jsxsd/xskb/xskb_list.do` | 上游请求的那一页 |
| `minAppVersionCode` | `11` | 移植手册 §5.1：本件只用可加字段（`warnings`）与内置能力，没用 `kind:"boxes"/"image"`，写 11 —— 老版本忽略未知字段、导入照样成功 |
| `allowHosts` | `["qzjwxt.stcnchu.edu.cn"]` | 见 §3 |

## 5. 读取面（`extract.js` 到底读了页面上的什么）

全部读取点，逐条列出（**没有别的**）：

| 读什么 | 用在哪 | 是否含个人信息 |
|---|---|---|
| `document.getElementById('kbtable')`（兜底 `#timetable`、再兜底「含 `.kbcontent` 的那张表」） | 找课表容器 | 否 |
| 表格每个 `td`/`th` 的 `textContent`、`rowspan`/`colspan`、`col` | 还原网格列号 | 否 |
| 格子里 `div.kbcontent` 的 `innerHTML`（**优先取 `display:none` 的那份**，没有隐藏的就全取） | 交原始明细给 `parse.js` | 否（课名/教师/教室） |
| `select` 中 `id`/`name` 含 `xnxq` 的那个的 `option.value` / `option.text` / `selected` | 取当前学年学期号与学期名 | 否 |
| `input` 中 `id`/`name` 含 `xnxq` 的 `value`（课表页隐藏域里的学期号） | 同上 | 否 |
| `window.location.href` 与 `window.location.search` 里的 `xnxq01id` 参数 | 同上（诊断 + 学期号） | 否（只是 URL） |

**不读**：账号密码、任何登录表单的值、`localStorage`/`sessionStorage`/Cookie、成绩、学籍、
个人信息表格、页面上的其它 `select`/`input`。`extract.js` 里没有 `localStorage`、没有
`document.write`、没有 `postMessage`。

## 6. 移植时删掉/改掉的上游行为

| # | 上游行为 | 本适配器 | 理由 |
|---|---|---|---|
| 1 | `window.validateYearInput = function …` **往教务页面写一个全局函数** | 删掉 | §5 第 7 条（不写页面）。它只是上游弹窗输入框的校验器，我们不再弹窗 |
| 2 | `showAlert`/`showToast`/`notifyTaskCompletion`/`showPrompt`/`showSingleSelection` | 全删 | 空课的导入流程自己会确认；`parse.js` 必须纯函数（规范 §5.2） |
| 3 | 弹窗问「起始学年」+「第一/第二学期」再拼 `xnxq01id` | 从页面的「学年学期」下拉框**读选中项**；页面没有就用教务自己的默认学期 | 移植手册 §3 第 1 步：能用页面解决的就别问。用户此刻就开在那一学期上 |
| 4 | 弹窗问校区（共青城 / 上海路） | **不问**，默认共青城校区，写进 `warnings` 让用户核对 | 校区只影响作息表，拦在提取流程里问一句代价更大；拿不准的一律出声（硬约束 6） |
| 5 | `saveAppConfig()` 写死 `semesterTotalWeeks: 20`、`firstDayOfWeek: 1` | 总周数 20 起算、`firstDay` 取提取时刻所在周的周一（§4.3） | 语义等价，值原样照搬 |
| 6 | `saveAppTimeSlots(campusIndex, semesterIndex)` 三段作息表 | 原样搬进 `parse.js`，按学期号选冬/夏令时 | 时间串逐条核对过都是合法 `HH:mm` |
| 7 | `mergeAndDistinctCourses()` 按课名排序并把相邻节次合并成一整块 | **不排序、不合并** | 原样保留教务给的节次边界；排序是应用的活 |
| 8 | `teacher || "未知教师"`、`position || "未知地点"` | 拿不到就留 `null` | 移植手册 §4.7：「未知」会被当成真名显示 |
| 9 | 静默丢块（课名空、节次 `startSection <= 0`、周次读空） | 全部计数 + 点名进 `warnings` | 硬约束 6 |

## 7. 字段映射与三处最容易错的语义

- **周次**（§4.1）：教务给的是「周次串」而不是显式数组。本平台形态是
  `1-9,11-17(周)[01-02节]`，方括号里是节次、前面是周次。切成
  `(startWeek, endWeek, weekType)` 的极大段（连续→`ALL`、步长 2→`ODD`/`EVEN`）。
  四种写法全部认（`1-16周(单)`／`(双)2-16周`／`1-16(单周)`／`1-3,5-9周`），
  上游的 `weekStr.split('(')[0]` 一种都认不全，见 §8 检查表第 1 条。
  两点补充（2026-09-13 验收后修复，见 §11.1）：**一个块里写了多段周次就产出多段**
  （两个 `font`，或一个 `font` 里连着写 `1-8周[01-02节]10-16周[03-04节]`）；
  区间分隔符**先归一**（全角波浪 ～／全角减号 －／数学减号 −／短破折 –／长破折 —／「至/到」
  都当连字符），不收的话 `1～16周` 会静默退化成「只上第 1 周」。
- **开学日**（§4.2）：教务不给。按提取时刻所在周的周一推算，并**如实写进 `warnings`**。
- **每周起始日**（§4.3）：上游 `firstDayOfWeek = 1`（周一），我们的 `firstDay` 就是周一 —— 同义。
- **学期名**（§4.7）：用教务下拉框里的 `option.text`（如「2026-2027学年第一学期」），
  拿不到就用学期号拼「2026-2027学年第一学期」，再拿不到才用「南昌航空大学科技学院课表」占位并出声。

## 8. 逐条签：移植手册 §5 安全审计八条

| # | 检查项 | 结论 | 依据 |
|---|---|---|---|
| 1 | **不碰凭据** | ✅ | 全脚本没有 `password`/`pwd`/登录表单/`localStorage`。唯一读的 `select`/`input` 是学年学期字段（`xnxq…`），只取 value 与 option 文字 |
| 2 | **不外发** | ✅ | 只有一处 `fetch`，打的是本校教务的课表接口，同源相对路径。无第三方域、无 `WebSocket`/`sendBeacon`/`new Image().src`/`postMessage` |
| 3 | **请求域可控** | ✅ | 请求主机只有一个：`qzjwxt.stcnchu.edu.cn`，已写进 `allowHosts`（只写主机名，见 §3.1）。无通配 |
| 4 | **只读课表** | ✅ | 唯一接口 `xskb_list.do`（课表查询）。不请求成绩、学籍、个人信息、缴费任何接口；表单体照抄上游（`cj0701id` 是课表查询的空过滤参数，见 §2） |
| 5 | **不埋点** | ✅ | 无统计、无上报、无遥测、无「匿名」采集 |
| 6 | **不 eval 远程代码** | ✅ | 无 `eval`、无 `new Function`；`parse.js` 是纯字符串 + 正则处理，连 DOM 都不碰 |
| 7 | **不写页面** | ✅ | 删掉了上游写进页面的 `window.validateYearInput`；`extract.js` 只读 DOM，不 `innerHTML=`、不改表单、不触发提交。解析用的 `innerHTML` 只发生在 `parse.js` 里对**字符串**做正则（没有 DOM） |
| 8 | **不依赖用户输入之外的秘密** | ✅ | 没有硬编码密钥/令牌/他人学号。唯一的「固定值」是学校作息表与总周数 20 |

## 9. 逐条签：本批（批次三）专项检查表 10 条

| # | 检查项 | 结论 | 落地位置 / 用例 |
|---|---|---|---|
| 1 | **周次四写法** | ✅ | `weeksIn()` 按逗号分段、**各段自判单双**。用例 `week-forms`：`1-16周(单)`→`ODD 1-15`；`(双)2-16周`→`EVEN 2-16`；`1-16(单周)`→`ODD 1-15`；`1-3,5-9周`→两段 `1-3 ALL` + `5-9 ALL`。单双不被丢、不塌成每周、不静默 |
| 2 | **括号内纯数字不是周次** | ✅ | `SERIAL_PAREN` 把 `(1)`/`(1-2)` 整段抹掉（`(周)` 是周字的正常写法，另判）。用例：`(2)5周`→只上第 5 周、`(1-2)7周`→只上第 7 周（若误当周次会读成第 2 周 / 第 1-2 周） |
| 3 | **无星期表头兜底** | ✅ | **没有任何 `row.length - 7`**：`extract.js` 交出网格列号 `col` 与跨列数 `span`（`gridOf()` 处理 `rowspan`/`colspan`），`parse.js` 用 `dayOfGridCol(colOf(cell))` 对齐；表头认不出时按**表宽**推「最后 7 列 = 周一到周日」**并进 `warnings`**，推不出来的天进 `warnings` 且不猜。用例 `no-header`：首列 rowspan=2，第二行 td 整体前移一格 —— 网格列号下 4 门课全部落在正确星期 |
| 4 | **时间合法性** | ✅ | 内置作息全部是合法 `HH:mm`（逐条核对）；补占位写成 `pad2(7+节次):00–:45`，且节次在解析阶段就夹进 `MAX_PERIOD = 16`（第 16 节 = 23:00-23:45，最后一个当天档位）。越界一律按「读不出节次」跳过 + 点名，**不会**产出 `24:00`/`85:45`。用例 `dirty-limits`（含 `[17-18节]`、`[12345678节]`） |
| 5 | **`warnings` 上限** | ✅ | `pushWarning()` 统一出口：每条按码位截断到 200 字（末尾加省略号）、去重、总数 ≤20 条。用例 `dirty-limits` 里那条点名警告实测 200 字整（原始 217 字被截断）。六个 fixture 的警告最长为 91/91/91/200/91/91 字 |
| 6 | **分页** | ✅ | 该接口不分页：上游只发**一次** POST 就拿到整张课表（服务端渲染的 HTML 表格），参数里也没有任何分页字段（只有 `jx0404id`/`cj0701id`/`zc`/`demo`/`xnxq01id`）。所以本件不存在「取完第一页就停」的风险；`extract.js` 拿到空表会明确报错（可能没排课 / 登录失效），不会当成「0 门课」静默成功 |
| 7 | **学期名** | ✅ | 用教务下拉框的 `option.text`；拿不到用学期号拼「2026-2027学年第一学期」；再拿不到才用「南昌航空大学科技学院课表」占位并进 `warnings`。**没有**拿适配器名当学期名 |
| 8 | **`teacher` 拿不到就留空** | ✅ | `teacherOf()` 去掉「任课教师:」前缀后为空即返回 `null`；不写「未知」「暂无」。用例 `basic` 的「大学物理」 |
| 9 | **`allowHosts`** | ✅ | 非标准端口 `:800`：**只写主机名** `qzjwxt.stcnchu.edu.cn`（写端口会被 `JwHostAllowlist` 判非法），端口留在 `loginUrl`/`scheduleUrlHint`，请求路径保持相对。没有用 OCR/提问桥，所以不存在「通配拿不到桥」的问题；也没有通配项 |
| 10 | **变异测试** | ✅ | 见 §11：七处故意改坏，各自让对应用例变红，其余用例保持绿 |

## 10. 已知不确定（交给真机抽验与用户反馈）

1. **真机没跑过**（我们手上没有该校账号，测试方案 §3 已写明这是接受的代价）。
   `extract.js` 的选择器（`#kbtable`、`div.kbcontent`、`font[title=老师\|教师\|教室\|周次(节次)]`）
   与接口路径都来自上游脚本，**没有在真实页面上验证过**。
2. **fixture 是合成的**：形状按上游选择器编造，只保证「同样的输入永远得到同样的输出」，
   不保证解析在真实页面上是对的。谁拿到真实 dump，替换 fixture 并重跑门是第一优先级的贡献。
3. **校区**：默认共青城校区。上海路校区的第 5-8 节比共青城晚 40 分钟（14:00 起），
   夏令时比冬令时晚 20 分钟（13:40 起）。已进 `warnings`。
4. **学期**：只导当前页面所在的那一学期。载荷支持多学期，但目前没有为「一次导两个学期」
   发两次请求 —— 上游也只导一个，先在真机上确认真实行为再考虑。
5. **`xnxq01id` 的读取**：如果该校的课表页既没有下拉框、又没有隐藏域、URL 上也没有这个参数，
   `extract.js` 会退化成「POST 空学期号，由教务用它自己的默认学期」，此时学期名会退到
   用学期号拼（拿不到学期号则用占位名 + `warnings`）。这属于「页面结构假设没验过」的一部分。
6. **`div.kbcontent` 的隐藏/可见两份**：强智的通行形态是「`display:none` 的 `kbcontent` 装完整明细，
   可见的那份是简写」。`extract.js` 据此在有隐藏项时**只取隐藏项**，没有隐藏项时才全取
   （与同族 `cqrk` 一致；上游 `stcnchu.js` 用 `querySelectorAll('div.kbcontent')`，按 CSS 类
   选择器的语义也只命中 `kbcontent` 这个类名，实际拿到的是同一份）。万一该校恰好反过来
   （隐藏的是简写），表现是课程块**读不出节次而被跳过并点名进 `warnings`**，不会静默丢课。

## 11. 本地校验与变异测试记录（2026-09-13）

自验用 node（**没有跑 gradle**，本批 12 个 agent 并发，gradle 会抢锁）：

```
$ node check.js  （读 fixtures/*.extracted.json 当 __ncInput，与 *.expected.json 逐字段比对）
basic        MATCH
week-forms   MATCH
no-header    MATCH
dirty-limits MATCH
multi-spec   MATCH   ← §11.1 新增
```

**变异测试**（把 `parse.js` 里对应逻辑故意改坏 → 该用例必须变红）：

| 变异 | 改坏哪里 | 结果 |
|---|---|---|
| M1 单双标记丢掉 | `if (oddOnly && week % 2 === 0) return;` → 前面加 `false &&` | `basic` **DIFF**、`week-forms` **DIFF**；`no-header`/`dirty-limits` MATCH。实测：「认识实习」的 `1-16周(单)` 塌成 `1-16 ALL`（每周都上）——正是测试方案 §3.1 记的那类 bug |
| M2 按数组下标定星期 | `dayOfGridCol(colOf(cell, ci))` → `dayOfGridCol(ci)` | `no-header` **DIFF**（其余 MATCH）。实测：首列被 rowspan 跨掉的那一行里，「线性代数」整门课**消失**（只剩 4 门课）——证明网格列号就是救它的那处 |
| M3 节次上限不夹 | `if (periods.end > MAX_PERIOD)` → 加 `&& false` | `dirty-limits` **DIFF**（其余 MATCH）。实测：`[17-18节]` 的「数据库原理」与 `[12345678节]` 的「体育（一）」被收进载荷，`periodTimes` 补到 `85:00–85:45` ——**非法时刻，整次导入会被载荷校验拒收** |
| M4 括号纯数字当周次 | `if (SERIAL_PAREN.test(body)) return ' ';` → 加 `false &&` | `week-forms` **DIFF**（其余 MATCH）。实测：`(2)5周` 读成第 2 周、`(1-2)7周` 读成第 1-2 周 |

M1 还做过一次**就地**改坏 → 跑门变红 → 还原 → `diff`/`cmp` 确认与原文件**逐字节一致**
（sha256 `908b9b52a0989931685d662006c0792a2f79f0f0cc2d2b14c3346731298ca820`）、四对用例全部回到 MATCH。

### 11.1 验收后修复 B7/B10（2026-09-13 追加）

对抗式验收**实跑**出两处缺陷（同平台 `ccit`/`hnie` 同源，各由各的人修），已在本件修掉：

| 缺陷 | 现象 | 修法 |
|---|---|---|
| **B7 一段掩盖一段** | 一个块里写了**两段**周次时，第二段整段静默消失，`warnings` 里连一条与周次/跳过相关的都没有 | `specOf()` → `specsOf()` + `splitSpec()`：所有「周次(节次)」font 逐段切出来，调用方**逐段产出**（节次按各自那段走） |
| **B10 区间分隔符收窄** | `1～16周`（U+FF5E）、`1－16周`（U+FF0D）、`1−16周`（U+2212）、`1–16周`（U+2013）落进「单数字回退」，静默变成**只上第 1 周**（`w1-1 ALL`），无告警 | `weeksIn()` 开头先把 `[～－−–—~至到]` 归一成 `-` 再取区间；`1-16周` 标准形态结果不变 |

新增一对用例 `multi-spec`（覆盖 B7 的两种形态 + B10 的四种分隔符），期望值**按规范手推**，
不是把 `parse.js` 输出贴成期望：两段各自 `w1-8 ALL` + `w10-16 ALL`；四种全角/异体分隔符
与半角一样是 `w1-16 ALL`。修复前的实测（就是对 B7/B10 的复现）：

```
数据结构  day1 p1-2 w1-8  ALL     ← 第二段（10-16周）整个消失
操作系统  day2 p3-4 w1-8  ALL     ← 同一个 font 里的第二段也消失
计算机网络 day3 p1-2 w1-1 ALL     ← 1～16周 被读成只上第 1 周
数据库原理 day4 p3-4 w1-1 ALL     ← 1－16周 同上
软件工程  day5 p5-6 w1-1 ALL      ← 1−16周 同上
离散数学  day6 p5-6 w1-1 ALL      ← 1–16周 同上
（warnings 里一条与周次相关的都没有）
```

**变异记录**（在**修复后**的 `parse.js` 上就地改坏 → 新用例必须变红 → 还原 → sha256 逐字节一致；
四条旧用例全程 MATCH）：

| 变异 | 改坏哪里 | 结果 |
|---|---|---|
| M5 | `splitSpec()` 的 `while (m)` → `if (m)`（只看第一个方括号） | `multi-spec` **DIFF**（其余 MATCH）。实测：「操作系统」只剩 `w1-8 ALL` |
| M6 | `specsOf()` 的 font 循环里加 `break`（只读第一个「周次」font） | `multi-spec` **DIFF**（其余 MATCH）。实测：「数据结构」只剩 `w1-8 ALL` |
| M7 | 归一那行的字符类缩回 `[~至到]`（不收全角/异体） | `multi-spec` **DIFF**（其余 MATCH）。实测：四门用全角分隔符的课全部塌成 `w1-1 ALL`，且一条告警都没有 |

三次改坏各自还原后 `parse.js` 的 sha256 都是
`7fbf317a9e06c776626b6c4ab3f1fa1acbbbcf7ab3f571ad915ce280c7a1c1e7`（与改坏前一致）。

另外两项自动检查：

- **ES5**：`grep -nE "=>|\`|\\blet |\\bconst " *.js` **无输出**（`parse.js` 里 `Promise` 没用上，
  两段脚本都是同步返回；`extract.js` 的 POST 走 `.then()` 链）。
- **NUL / BOM**：`extract.js` `parse.js` `manifest.json` `fixtures/*.json`（含新增的
  `multi-spec` 一对）全部 NUL=0、无 BOM。（改写后由 node 逐字节复核，见 §11.1。）
- **载荷合法性**（规范 §4 的规则逐条自查）：五份 `expected.json` 全部通过 ——
  `totalWeeks ∈ 1..30`、`dayOfWeek ∈ 1..7`、`startPeriod ≤ endPeriod`、`weekType ∈ {ALL,ODD,EVEN}`、
  `startWeek/endWeek ∈ 1..totalWeeks`、`name` 非空、`periodTimes` 全是合法 `HH:mm`、
  `warnings ≤ 20` 条且每条 ≤ 200 字。

## 12. 签名

移植与审计：**0x7E7-2023**　日期：**2026-09-13**

上游为 MIT，本移植遵守其社区公约保留贡献者记录（文件头 + `manifest.author` 均写明
「上游 星河欲转」与快照 commit）。
