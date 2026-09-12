# 安全审计：`xjie`（新疆工程学院）

- 上游：`shiguang_warehouse` 的 `XJIE/xjie_01.js`（MIT，作者 **星河欲转**），快照 2026-09-12 的副本
  （`/tmp/sgw/resources/XJIE/`，8416 字节；同目录 `adapters.yaml` 的 `adapter_id` 是 `XJIE_01`，
  `maintainer` 是「星河欲转」，`import_url` 是 CAS 登录地址）。
- 移植者：NullClass（0x7E-2023）　日期：**2026-09-12**
- 平台：**强智科技「高校综合管理教务系统」（湖南强智科技）学生端 `/jsxsd/`**，不是正方。
  依据：上游请求 `/jsxsd/xskb/xskb_list.do` + `#timetable` + `div.kbcontent` +
  `font[title=教师|周次(节次)|教室]`；同平台的 `hynu`（已移植、已人工审计）用同一套结构与同一条接口路径。
  上游 `adapters.yaml` 自称「强智适配」，这次**是对的**（手册 §1 提醒过：平台标签别信，要按实际请求的
  接口路径与选择器聚类；这条路径与 `xskb_list.do` 都是强智学生端的写法）。

**结论：通过。** 手册 §5 的 8 条逐条过完，没有命中；适配器只做「取课表」一件事。

## 请求域（CAS 域与教务域分开列）

| 域 | 谁在用 | 请求 | 说明 |
|---|---|---|---|
| `authserver.xjie.edu.cn` | **CAS 单点登录域** | **脚本从不请求它** | 只出现在 `manifest.loginUrl` 里，给应用打开登录页用；桥与网络白名单会自动带上这个主机（`JwAdapterPackage.allowedHosts(loginHost)`），但 `extract.js` 里没有任何指向它的 fetch |
| `jwxt.xjie.edu.cn` | **教务域**（同一台服务器） | `GET /jsxsd/xskb/xskb_list.do` | 教务默认的当前学期。只在当前页面（含同源 iframe）里没找到课表时才发 |
| `jwxt.xjie.edu.cn` | 同上 | `POST /jsxsd/xskb/xskb_list.do` | 取指定学年学期的课表，body 与上游一字不差：`cj0701id=&zc=&demo=&xnxq01id=<学期>` |

`allowHosts` = `["jwxt.xjie.edu.cn"]`（精确主机，不用通配）。CAS 域不需要写进 `allowHosts`：它是
`loginUrl` 的同源主机，应用会自己加；写进去反而是多余的放行面。
**为什么不用 `*.xjie.edu.cn`**：通配项会被 `JwOriginRules` 跳过（桥注入不到，`__ncCapabilities` 全 false），
而且这里只需要一台主机，没有理由放宽到全校子域。

```bash
$ grep -oE "https?://[A-Za-z0-9.-]+" xjie_01.js | sort -u
https://jwxt.xjie.edu.cn                      # 脚本正文里唯一的绝对 URL
$ grep -oE "https?://[A-Za-z0-9.-]+" adapters.yaml | sort -u
https://authserver.xjie.edu.cn                # CAS 域只出现在这里（import_url），脚本从不请求
$ grep -nE "fetch\(|XMLHttpRequest|sendBeacon|new WebSocket|\.src\s*=|localStorage|eval\(|new Function|password|pwd" xjie_01.js
193:        const response = await fetch("https://jwxt.xjie.edu.cn/jsxsd/xskb/xskb_list.do", {
```

即上游全脚本只有一个绝对 URL（fetch 那一处）、没有存储、没有 `eval`、没有凭据相关标识符。

## 逐条（移植手册 §5）

1. **不碰凭据** — 不读 `password` / `pwd` / 登录表单 / `localStorage` / 令牌。上游的登录是用户在
   CAS 页面上手工完成的；上游脚本只让用户手输**学年**（不是账号密码），移植后连这也不问了。
2. **不外发** — 两处请求都指向本校教务域，`parse.js` 完全不联网（纯转换，CI 里连 `fetch` 都没有）。
   没有 `sendBeacon` / `WebSocket` / `EventSource` / `new Image().src`，没有重定向后二次外发。
3. **请求域可控** — 三个域逐一列在上面；`allowHosts` 只有一个精确主机。
4. **只读课表** — 只请求课表页。不碰成绩、学籍、考勤、缴费、个人信息。读的页面内容逐条列在
   下面**「读取面」**一节（这是本文件里**唯一**该被当作读取面依据的地方）。
5. **不埋点** — 无统计、无上报、无遥测，也没有任何「回传失败原因」的请求。
6. **不 eval 远程代码** — 无 `eval` / `new Function`；取回的 HTML 只交给 `DOMParser`
   （惰性文档，不执行脚本）。
7. **不写页面** — 不写 DOM、不改表单、不触发提交、不点页面元素。上游往页面挂过一个
   `window.validateYearInput`（给它自己的输入校验用）外加弹窗与 toast，移植后**全部删掉**，
   `extract.js` 对页面只读（`harvestFrames` 也只读同源 iframe 的文档）。
8. **不依赖用户输入之外的秘密** — 没有硬编码密钥、令牌、他人学号；`xnxq01id` 来自页面下拉框
   （认不出时按第一项取并**在 warnings 里出声**，见下）。

## 读取面（`extract.js` 到底读了页面上的什么）

第 4 条说的是「读什么」，这里说「怎么读、读到哪一层」。

| # | 读什么 | 代码位置 | 取出的值 | 去处 |
|---|---|---|---|---|
| 1 | 课表表格：`#timetable` / `#kbtable`，都没有时含 `.kbcontent` 的那张表 | `findTable()` → `rowsOfTable()` | 每个单元格的文字 + `div.kbcontent`（优先带 `display:none` 的那份）的原始 HTML | 载荷 `rows[]`，由 `parse.js` 解释 |
| 2 | **同源 iframe**（强智的 jsxsd 是框架页）：深度 ≤2 层，跨源读会抛异常、直接跳过 | `harvestFrames()` | 同上（读法完全一样） | 同上 |
| 3 | 「学年学期」下拉框：id 或 name 含 `xnxq` 的 `<select>` | `readTerm()` 主路径 | 选中项的 value（`2026-2027-1`）与选项文字 | 载荷 `term`（含 `source`） |
| 4 | 兜底①：`document.title` | `termTextHints()` | 只做一次正则匹配 | 见下 |
| 5 | 兜底②：页面上**每个** `<select>` 的 option 文字 | `termTextHints()` | 同上 | 见下 |
| 6 | 兜底③：id 或 class 含 `xnxq` 的元素的**短**文本（≤120 字） | `termTextHints()` | 同上 | 见下 |
| 7 | `window.location.hostname`（**只读主机名，不读路径与查询串**） | `requestHtml()` 的失败分支 | 拼进报错文案「连不上教务系统（当前页面在 xxx）」 | `__ncError` → 应用落盘到 `files/jw-last-error.log` 并展示给用户（可复制转给维护者） |

兜底（4-6）只在下拉框认不出学期时才走，实现在 `termTextHints()` 一个函数里：

- 只在这三处取，**不读 `document.body`**（第 6 项还带长度上限，避免命中一个包着整页的容器）；
- 取到的整串文字只在本页内存里做一次正则匹配
  `(20\d{2})\s*-\s*(20\d{2})\s*学年\s*第?\s*([一二三123])\s*学期`；
- 匹配到了，**只把匹配到的那一小段**（如 `2026-2027学年第一学期`）当学期名，`term.code` 由正则的
  三个捕获组拼出来；没匹配到的文字不留引用、不记日志；
- 无论匹配与否都**不外发、不进载荷**、不写页面。

**本节 2026-09-12 与代码逐条对齐过**（给的是函数名，不给行号 —— 行号会漂）。已知的读取面边界：
读「学年学期」下拉框时会把**该 select 的全部 option 文字**读进内存（强智的学期下拉框就是全校学期名，
不是个人信息）；`document.title` 在强智是「学生课表查询」这类页面名。这两处即便夹着姓名/学号，
也没有任何一条路径会把它们放进载荷或发出去。

## 与 hynu 的同平台对照片（「同平台」成立到什么程度）

上游两家的脚本做过**逐字比对**（忽略空白）：

```bash
$ diff <(sed 's/[[:space:]]//g' HYNU/hynu_01.js) <(sed 's/[[:space:]]//g' XJIE/xjie_01.js)
# 只有 4 处：文件头注释的校名、教师字段多一句 replace("任课教师:","")、作息表 11 节 vs 12 节、fetch 的主机
```

所以「近克隆」成立，但**能复用的层次是分层的**（测试方案 §3.2 的表格），逐层结论：

| 层 | 结论 | 证据 |
|---|---|---|
| 接口信封（`/jsxsd/xskb/xskb_list.do`、POST body `cj0701id=&zc=&demo=&xnxq01id=`） | **成立** | 两家的 URL 与 body 一字不差；返回都是服务端渲染的 HTML（不是 JSON） |
| 行字段名（`#timetable`、`div.kbcontent[style*=none]`、`font[title=教师\|周次(节次)\|教室]`） | **成立** | 两家的选择器一字不差 |
| 取数路径（要不要先取学号、POST 哪些参数、有没有分页） | **部分成立** | 上游两家都是「一次 POST 拿整张表」，**没有分页**；差别在登录入口：hynu 直接开 `jsxsd/`，xjie 走 CAS（`authserver`）再落到 `jwxt` |
| 周次 / 节次编码 | **不敢照抄，单独造了用例** | 上游两家**都**只写 `split('(')[0]` + `/\[(\d+)(?:-(\d+))?节\]/`；同平台的 BTBU 注释里却有 `1-16(周)[03-04-05节]`、`2-16(双)[01-02节]` 这些写法 —— 说明「周次写法各校不一样」正是这个平台最容易错的地方，fixture 专门覆盖 |
| 分页行为 | **不适用** | 课表是**整学期一张 HTML 表**，接口没有分页参数、也不回记录总数；`extract.js` 固定 GET→POST 两次请求，不存在「只取第一页」这一环（清单第 5 条在这条链路上没有对应物，不是忽略） |

**移植时逐条改掉的上游行为**（第一批审查挖出来的坑，克隆会一起克隆过来）：

1. **单/双写在「周」字后面**（`1-16周(双)`）：上游 `split('(')[0]` 把标记连括号一起丢掉，课程退化成
   「每周都上」。移植后按段认括号内外的 单/双/单双周，切成 `ODD`/`EVEN` 段，**并进 warnings**
   （`weeks-parity` 用例 + 变异测试 M1/M3 证明能拦住）。
2. **括号里的纯数字序号不是周次**：`(1)` 不当周次用；整条只有序号的块跳过并在 warnings 里说明
   （`weeks-parity` 用例 + 变异 M2）。
3. **读不出节次不许静默丢课**：上游最后一句是 `if (name && weekStr && start > 0)`，节次读不出来的块
   直接丢。移植后先回落**所在行的节次标签**，再不行计数进 warnings；一个块都解析不出来时**报错**
   并把失败计数写进错误消息（`sections` 用例 + 变异 M6/M11）。
4. **连堂标签下 `periodTimes` 覆盖每一节**：`[03-04-05节]` 这种三小节连排是 3..5 节，内置作息表
   （11 节）覆盖得到；用到第 12 节及以上时**出声**（这几节没有上下课时间），不替教务猜时间
   （`sections` 用例 + 变异 M4/M5/M8）。
5. **分页**：本接口不分页，见上。
6. **总周数被更晚的周次抬高时出声**：上游写死 20、超了也不说；移植后 `maxWeek > 20` 时把
   「已从 20 周抬高到 N 周」写进 warnings（`sections` 用例 + 变异 M7）。
7. **本文与代码一致**：上面每条都指到函数/位置；「读取面」一节就是代码实际的读取面。
8. **fixture 期望值独立推出 + 变异测试**：三个用例的 `expected.json` 都是先按规范手推再跑门的；
   14 个变异逐个跑过（去掉单/双过滤、把括号序号当周次、单双整串一起判、`0102` 不按两位切、
   三小节只取前两节、去掉行标签回落、去掉总周数抬高提示、去掉超表节次提示、星期按格子下标硬算、
   表头列映射整体偏移一天、不认逗号节次写法、读不出节次不计数、不去教师前缀、周次里的括号不剥离），
   每个变异至少让一个用例变红，且**基本课表用例在多数变异下保持绿色**（说明红的是它该红的那一条）。

## 移植时删掉的上游行为

- 弹窗与 toast（`showAlert` 确认已登录、`showPrompt` 输学年、`showSingleSelection` 选第一/第二学期、
  一串 `showToast` 与 `notifyTaskCompletion`）：空课的导入流程自己会确认；学期改成从课表页的
  「学年学期」下拉框里取**选中的那一项** —— 用户在页面上切学期，适配器就跟着他走，比弹窗更清楚。
- 上游写进页面的 `window.validateYearInput`（给它的年份输入校验用）：随弹窗一起删掉，`extract.js` 对页面只读。
- 上游硬编码的 `semesterTotalWeeks: 20` / `firstDayOfWeek: 1` / `savePresetTimeSlots`：
  20 周与 11 节作息在 `parse.js` 里保留为**载荷字段**（`totalWeeks` / `periodTimes`），并在 warnings 里
  说明它们是适配器内置值而不是教务给的。
- 上游没有开学日期（`config.semesterStartDate` 也没给）：移植后按手册 §4.2 的「最近的周一」推算，
  并在 warnings 里如实说明。
- 上游的 `weekStr.split('(')[0]`、`/\[(\d+)(?:-(\d+))?节\]/`、按格子下标算星期、丢读不出的块：
  见上一节 1-6 条。

## 已知不确定（交给真机抽验与用户反馈）

- **CAS 域与教务域是两个域**：登录后落到 `jwxt.xjie.edu.cn/jsxsd/`，`extract.js` 的两处请求都是
  同源（教务域）请求。如果用户停在 CAS 页（`authserver`）就点提取，跨域请求会被浏览器拦下，
  脚本会给出「连不上教务系统」并提示先打开教务页 —— 这条路径**没在真机上跑过**。
- 强智 jsxsd 的**框架页结构**（同源 iframe 扫描，`harvestFrames`）是照平台的常见形态写的兜底，
  没在真机上验过；扫描只是「能不能少发一次请求」的优化 —— 扫不到就走 GET/POST，不影响正确性。
- 课表容器 id（`#timetable`，兜底 `#kbtable` 与「含 `.kbcontent` 的表」）、学期下拉框 id 含 `xnxq`、
  行首的节次标签写法（fixture 里用了「第一大节」与「第1-2节」两种）都没在真机验证过。
- **周次文本的真实形态**只知道上游注释里的两种（`1-9,11-17(周)[01-02节]`、`12-15(周)`）；
  单/双的真实写法是本适配器按同平台 BTBU 的注释补的（`1-15周(单)[01-02节]`、`2-16(双)[01-02节]`）。
  如果这所学校实际写的是别的形态，会落进「周次段没读懂 / 周次读不出」的 warnings，不会静默变成每周都上。
- **括号里只有数字**（`(1)`）按「序号」处理：如果某学期教务真的用它表示第 1 周，那门课会被跳过并进
  warnings（**出声地丢**，不是静默丢），需要用户反馈后调整。
- 教师前缀（`任课教师:`）与「未知/待定」占位符的处理来自上游那行 `replace("任课教师:","")`；
  页面实际写别的标签（如「教师姓名:」）时前缀会留在教师名里 —— 不影响课程本身。
- fixture 是**合成的**（每个 `fixtures/*.extracted.json` 的 `_note` 都写明了）：只保证同一份输入
  永远得到同一份输出，**不保证**真实页面上的解析是对的。拿到真实 dump 请替换 fixture 并重跑门。
