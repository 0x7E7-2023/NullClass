# 安全审计 —— whut（武汉理工大学教务）

- 上游：`shiguang_warehouse` 的 `WHUT/whut_01.js`（MIT，上游维护者 **星河欲转**）
  <https://github.com/XingHeYuZhuan/shiguang_warehouse>
- 上游快照：`main` @ `e62554a4034386b893bcd6813c7b2b64f8c730a3`（2026-09-12 12:50:29 +0800）
- 上游 `resources/WHUT/adapters.yaml`：`adapter_id: WHUT_01` / `adapter_name: 武汉理工大学金智教务`
  / `import_url: https://jwxt.whut.edu.cn/` / `maintainer: 星河欲转`
- 移植者：**0x7E7-2023**　移植日期：**2026-09-13**
- 审计结论：**通过**。请求域只有一个（本校教务主机），四个接口全部是取课表所必需，
  不碰凭据、不埋点、不写页面、不读成绩学籍。

## 一、学校与平台（按脚本实际请求的接口路径判定）

**金智教育 jwapp 平台**（`/jwapp/sys/` 接口族）。判据三条，都来自脚本正文而不是上游注释：

1. 请求路径是 `/jwapp/sys/kcbcxby/...` 与 `/jwapp/sys/homeapp/...`（金智的模块化教务）；
2. 响应信封是 `{ code, datas: { <键>: { rows: [...] } } }`（金智的标准信封）；
3. 周次编码是 `SKZC` 的 **0/1 位串**（第 i 位 = 第 i+1 周），这是金智学生课表的标准写法。

**与哪个已移植件同平台**：`niit`（南京工业职业技术大学）与 `dlutci`（大连工程学院），
两个都是金智 jwapp。**本件按这两件当模板，不是相似度榜上最像的那个强智件 `hhtc`（0.901）**
—— 0.901 来自上游共用的脚本脚手架（弹窗、桥回调、作息表），不是同平台证据。

同平台但**不能照抄**的地方（所以在 `parse.js` 里单独做了、单独测了）：

| 差异 | `niit` / `dlutci` | 本件（whut） |
|---|---|---|
| 模块 | `wdkb` | `kcbcxby`（班级课表模块） |
| 课表接口 | `xskcb/cxxszhxqkb.do`（只需 XNXQDM）／`xskcb/xskcb.do`（要 XH，分页） | `xskcb/cxxskcb.do`，要 `XNXQDM` + `XH` |
| 节次编码 | 教务直接给节次号 | **多一层节次字典** `dzkz/jcjcx.do`：DM 是不连续的编码（6/7/13 是空档），要按「名称带『节』的行」的顺序换算成第几节 |
| 学期校历 | `xskcb/cxxljc.do` | `bjkcb/cxjcs.do`，按 `XN` + `XQ` 查 |

## 二、请求了哪些域与路径

**只有一个域：`jwxt.whut.edu.cn`（https，即 manifest 的 `loginUrl` 主机）。**

| # | 接口 | 方法 | 参数 | 用途 |
|---|---|---|---|---|
| ① | `/jwapp/sys/kcbcxby/modules/dzkz/jcjcx.do` | POST | 无 body（上游口径） | 节次字典（`DM` 节次编码 / `MC` 名称） |
| ② | `/jwapp/sys/homeapp/api/home/currentUser.do` | GET | 无 | 当前登录人（取 `datas.userId` 当学号 `XH`） |
| ③ | `/jwapp/sys/kcbcxby/modules/bjkcb/cxjcs.do` | POST | `XN` + `XQ` | 学期校历（`XQKSRQ` 开学日 / `ZZC` 总周数） |
| ④ | `/jwapp/sys/kcbcxby/modules/xskcb/cxxskcb.do` | POST | `XNXQDM` + `XH` | 课表行 |

- 四个请求的路径都是**同源相对路径**（`extract.js` 里唯一的绝对串是 `var BASE = '/jwapp/sys'`），
  脚本里**没有写死主机名**，也没有内网地址、WebVPN 前缀或硬编码令牌。
- 请求次数：①②④ 各一次 + ③ 一到四次（先问最可能的那个学期，今天不在它的学期里才补问另外三个候选）。
- 三个 POST/GET 都**不显式写 `credentials`**：同源请求 fetch 默认就带 Cookie，
  用的是**用户自己在 WebView 里登录后**的会话（规范允许；脚本不读、不存、不外发任何令牌）。
- `allowHosts` 为 `[]`：请求的主机就是 `loginUrl` 的主机，应用会把它自动并入白名单
  （`JwAdapterPackage.allowedHosts` = 同源主机 + 清单声明），没有「额外的域名」需要声明。
  与同平台的 `niit`/`dlutci` 取同一个口径。
- 没有 CAS 登录域、CDN、统计域、WebVPN 网关；两段脚本里没有任何指向第三方的 URL。
- **不写 `scheduleUrlHint`**：金智的模块页 URL（`/jwapp/sys/.../*default/index.do#/...`）当跳转目标
  会落到 403（`dlutci` 的实测结论）。取数只走接口，停在门户首页也能提取。

静态扫描（移植手册 §5 的扫法）：

```
grep -oE "https?://[A-Za-z0-9.-]+" extract.js parse.js   # 只有头部注释里的 github.com
grep -nE "fetch\(|XMLHttpRequest|sendBeacon|WebSocket|EventSource|\.src *=|cookie|localStorage" extract.js parse.js
# → 只有 extract.js 的一处 fetch(BASE + path, ...) 与请求头里的 'XMLHttpRequest' 字符串
```

## 三、读了什么

**接口数据**

- 节次字典行：`DM` / `MC`；
- 当前登录人：`datas.userId` —— 这是**当前登录人的标识**（平台首页接口），
  本适配器只把 `userId` 当学号 `XH` 传给课表接口，**不读、不存、不外发**姓名、性别、证件号等
  其它身份字段（`currentUser.do` 若一并返回了这些，脚本也不碰）。
  同平台的 `dlutci` 同样要先取一次学号（走 `xskxb/cxxsjbxx.do`）才能查课表，是同一口径；
  `niit` 因为接口按会话身份取数而省掉了这一步。
- 校历行：`XQKSRQ`（开学日）/ `ZZC`（总周数）；
- 课表行：`KCM` 课名、`SKXQ` 星期、`KSJC`/`JSJC` 节次编码、`SKZC` 周次位串、`SKJS` 教师、
  `JASMC` 教室，以及 `XNXQDM` / `KCH` / `XXXQDM_DISPLAY` 等排课字段。

**页面**：**什么都不读**。本适配器不读 DOM、不读 `localStorage` / `sessionStorage`、
不读 `document.cookie`（同平台的 `niit` 要读课表页上 `#dqxnxq2` 的元素值来定学期，本件不需要）。

**输出前剔除 `XH`（学号）与 `XM`（姓名）**：`extract.js` 交给 `parse.js` 的行里没有这两个字段，
载荷里也不会有；fixture 因此天然脱敏。

**没读的**：成绩、学籍、缴费、个人信息页、考试、培养方案、任何第三方接口。

## 四、移植手册 §5 八条逐条

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 不碰凭据 | ✅ 脚本里没有 `password` / `pwd` / 登录表单 / 令牌读取；只用 WebView 现有会话 Cookie 请求本校接口，不外发 |
| 2 | 不外发 | ✅ 全部网络行为就是上表四个接口，都在 `jwxt.whut.edu.cn`；无 `sendBeacon` / `WebSocket` / `EventSource` / `new Image().src` / `navigator.sendBeacon` |
| 3 | 请求域可控 | ✅ 只有一个精确主机名 `jwxt.whut.edu.cn`（= `loginUrl` 主机），无通配；`allowHosts` 空数组，不多声明 |
| 4 | 只读课表 | ✅ 四个接口分别是课表行 / 当前学年学期校历 / 节次字典 / 登录人标识（取学号用），都是取课表所必需；没有成绩、学籍、缴费、个人信息接口 |
| 5 | 不埋点 | ✅ 无统计、上报、遥测，无第三方域名、无 `navigator.sendBeacon` |
| 6 | 不 eval 远程代码 | ✅ 无 `eval` / `new Function`；脚本自包含，没有动态 `import` / 注入 `<script>` |
| 7 | 不写页面 | ✅ 完全不碰 DOM：没有 `document.querySelector` / `innerHTML` / `appendChild` / 表单赋值 / `submit` / `click`，连读都没有 |
| 8 | 不依赖用户输入之外的秘密 | ✅ 无硬编码密钥、无他人学号、无固定令牌；学号来自①/②的会话接口，学期编号来自本校校历接口或本机日期推算（推算会在 `warnings` 里说明） |

## 五、批次三专项检查表 10 条

| # | 检查项 | 落实情况 |
|---|---|---|
| 1 | **周次编码四写法** | 本平台不给中文写法（`1-16周(单)` 那种），周次只有 `SKZC` **位串**一种形态，所以四种形态按**位串**覆盖：连续段 `11111111111111110000`（1-16 每周）、单周段 `10101010101010100000`（1-15 单周）、双周段 `01010101010101010000`（2-16 双周）、落单 `00000000000100000000`（只第 12 周）、混排 `10101110100000000000`（1,3,5,6,7,9 → ODD 1-5 + ALL 6-7 + ALL 9）。全部在 `fixtures/weeks`，且 M1/M1b/M9 三个变异证明这些用例是活的（标记不会丢、不会塌成「每周都上」）。 |
| 2 | **括号内纯数字不是周次** | 本平台周次只有位串，**没有**任何中文/括号写法。`parse.js` 只认 `/^[01]+$/`，不做括号解析，所以不存在把教学班序号 `(1)` 当周次的风险；不是位串的值（如 `1-16周`）一律跳过并进 `warnings`（`fixtures/degraded` 有一条，M10 变异证明告警不会被静默吞掉）。 |
| 3 | **无星期表头兜底** | 本适配器**不解析表格**，星期直接取 `SKXQ` 字段，不存在「按行宽猜列」的写法 —— `row.length - 7` 之类的代码在文件里没有出现（`grep -n "length - 7"` 无输出）。`SKXQ` 不在 1..7 时跳过并进 `warnings`。 |
| 4 | **时间合法性** | `periodTimes` 是写死的常量表（13 条，全部 `HH:mm`、`00:00–23:59`、`end > start`），不由计算产生，不可能出现 `24:00` / `85:45`。节次越界（超过 13 节）一律 clamp 到第 13 节 + `warnings`，`fixtures/weeks` 有两条（结束节越界 / 首尾都越界），M8、M14 分别证明这两条路径都被盖住。 |
| 5 | **`warnings` 上限** | `pushWarning` 逐条截断到 200 字；到第 19 条后不再追加，改留一条汇总（「还有 N 条提示没有显示」），条数上限 20 不会被突破（超了会让整个载荷被拒）。本件最多产出 10 类提示。 |
| 6 | **分页** | 上游对 `cxxskcb.do` 没有分页参数，本件**不猜分页参数**（金智的 `pageSize`/`pageNumber` 只在 `dlutci` 那条链路上验证过，不能假定本校的 `kcbcxby` 模块也认）。改为把接口回的 `totalSize` 一起交给 `parse.js`，**拿到条数少于总数时进 `warnings`**（`fixtures/degraded` 覆盖：5 条 / 共 6 条，M12 变异证明它不是空转）。真机上接口若本来就不分页，这条永远不触发。 |
| 7 | **学期名** | 教务的 `cxjcs` 不给学期名（上游也没取），用 `XNXQDM` 编号拼「2026-2027学年第一学期」—— 即「学年学期」口径，**没有**用适配器名或学校名当学期名。`parse.js` 的 `termNameFrom` 认不出编号形状时退回「教务导入」。 |
| 8 | **`teacher` 拿不到就留空** | `teacher = text(row.SKJS) \|\| null`，不写「未知」「暂无」「待定」；`fixtures/basic` 的「体育（一）」就是 `teacher: null`。教室同理（上游的「待定」兜底被去掉，M11 变异证明 fixture 盯着它）。 |
| 9 | **`allowHosts`** | `[]`：所有请求都是同源相对路径，主机就是 `loginUrl` 的主机。无 `*.` 通配（也就没有「通配拿不到桥」的问题）；本件不使用 OCR / 提问桥，所以不需要精确主机名换桥能力。WebVPN、非标准端口都不涉及。 |
| 10 | **变异测试** | 见下表，14 处变异逐一验证，每处都至少让一条用例变红。 |

## 六、变异测试记录

做法：把 `parse.js` 读进内存、对源码做**字符串替换**后执行，**不落盘改写文件** ——
整轮跑完前后 `parse.js` 的 sha256 都是
`8ae70baa598658e7abaac801bc130a416ac734d690d74e4b87dba3bca0717d50`（未被改动）。

| 变异 | 改坏哪一处 | 变红的用例 | 不红的用例 |
|---|---|---|---|
| **M1（本批指定的那一处）** | `runsOf` 里「隔周段」的判定恒真 → 隔周段**塌成连续段、单双周标记丢失**（双周变每周都上） | `basic`、`weeks` | `degraded` |
| M1b | 步长 2 的判定恒假 → 认不出隔周，一周一段 | `basic`、`weeks` | `degraded` |
| M2 | 节次字典改用内置兜底表（不按接口顺序数） | `basic`、`weeks` | `degraded` |
| M3 | 不做同周节次并集（每行自成一组，上游 `mergeContinuousLessons` 失效） | `basic` | `weeks`、`degraded` |
| M4 | 开学日不回退到周一（手册 §4.3） | `weeks`（校历给的是周三） | `basic`、`degraded` |
| M5 | 教师按「/」截断（上游写法） | `basic` | `weeks`、`degraded` |
| M6 | 解析不了的记录静默丢弃（不进 `warnings`） | `basic` | `weeks`、`degraded` |
| M7 | 位串长度不算总周数（校历缺失时的兜底） | `degraded` | `basic`、`weeks` |
| M8 | 节次越界不 clamp（直接放出越界载荷） | `weeks` | `basic`、`degraded` |
| M9 | 位串下标差一（第 i 位当第 i 周） | 三个全红 | — |
| M10 | 非位串 `SKZC` 不告警（当空周次静默丢） | `degraded` | `basic`、`weeks` |
| M11 | 教室为空时兜底写「待定」（上游写法） | `basic` | `weeks`、`degraded` |
| M12 | 课表接口取不全时不提醒（分页截断静默） | `degraded` | `basic`、`weeks` |
| M13 | 节次字典「只数名称带『节』的行」改成「数全部行」 | `weeks` | `basic`、`degraded` |
| M14 | 节次越界时只 clamp 结束节、不 clamp 起始节 | `weeks` | `basic`、`degraded` |

`basic` 主打合并与常规课表，`weeks` 主打周次位串与节次字典，`degraded` 主打降级路径 ——
所以 M3/M4/M8/M13/M14 只在其中一条上变红是预期的（另一条盖不到那条路径）。

## 七、与上游的语义差异（逐条，越出上游的地方都写在这里）

1. **周次**：上游发显式 `weeks` 数组，这里按空课载荷切成极大段（步长 1 → `ALL`，
   步长 2 → `ODD`/`EVEN`，落单一周 → `ALL`）。移植手册 §4.1。
2. **教师 / 教室**：上游取 `SKJS.split('/')[0]`（多人只留第一个），教室拿不到时兜底写「待定」。
   这里教师原样保留（按「/」截断是悄悄丢人），拿不到就 `null`；教室同样 `null`。
3. **节次字典**：上游只在请求抛异常时用内置对照表，**接口返回空数组时会静默丢掉全部课程**；
   这里「字典为空」一律走内置对照表，并把这件事写进 `warnings`。
4. **解析不了的记录**：上游 `filter` 掉不留痕；这里分类计数写进 `warnings`（缺课名/星期/周次、
   节次对不上、周次非位串、周次越界、节次越界）—— 不许静默丢课。
5. **节次越界**：作息表只有 13 节，超出的并入第 13 节并进 `warnings` ——
   越界的节次会让整个载荷被拒（不是跳过一节），位置不准也强过让用户白跑一趟。
6. **块的顺序**：= 输入行顺序 × 周次升序（确定、可复现）。上游最后按 `(星期, 起始节, 课名)`
   排过一遍（还用了 `localeCompare`，与语言环境有关），那是展示顺序，交给应用排。
7. **学年学期**：上游弹窗让用户选学年和学期（默认「今年 / 第一学期」）。这里按本机日期定一个
   最可能的学期，再拿校历核对「今天在不在这个学期里」，不在就补问另外三个候选；
   四个候选都拿不到校历时退回日期推算，并把「学期编号是推算的」写进 `warnings`。
   （顺带修掉一个上游默认值问题：上游默认选第一学期，春季导入时用户不改就会导错学期。）
8. **开学日**：上游只在 `config` 里存 `semesterStartDate`；这里按手册 §4.3 回退到当周的周一
   （上游 `firstDayOfWeek` 缺省 1），拿不到就推算并写 `warnings`。
9. **交互**：上游的 `showAlert` / `showPrompt` / `showSingleSelection` / `showToast` /
   `notifyTaskCompletion` 全部去掉，换成「返回载荷」；`parse.js` 不调用任何宿主提问桥（它在 CI 里跑）。
10. **取数总和提醒**：接口回 `totalSize` 大于实际拿到的行数时进 `warnings`（上游没有这个检查）。

## 八、已知的取舍与未验证项（审计时应知情）

- **fixture 是合成的**：形状取自上游脚本读取的接口字段，只保证「同样的输入永远得到同样的输出」，
  **不保证真实教务上的解析正确**。拿到真实 dump 请替换 `fixtures/` 并重跑 `:importer:test`。
- **`extract.js` 永远不会在 CI 里执行**（它需要浏览器）：四个接口的路径、参数、
  「节次字典要按带『节』的行数」这些**只能靠真机抽验与用户反馈**验证。
  本地已用 Node + 模拟教务服务器跑过一遍取数链路（学期候选挑选、学号/姓名剔除、
  接口形状异常时报错而不是交空数据），但那只是自证，不等于真机。
- **分页行为未验证**（见检查表第 6 条）：接口若分页而 `totalSize` 不返回，症状是缺课且无提示。
- **13 节作息表**：取自上游脚本的预设值（与上游兜底字典的 13 个节次码一致）。
  若本校实际节次多于 13，超出的会被并入第 13 节并进 `warnings`（不会静默错位）。
- **`minAppVersionCode` 写 11**（与本批其它移植件、`dlutci` 一致）：本适配器不用 OCR / 提问能力位，
  只多写了可加的 `warnings` 通道，老版本忽略未知字段照常导入，不属于「行为诡异」。
  但载荷里的 `warnings`（推算的开学日等）是 0.8.2 之后才有的通道，**更旧的应用会忽略它**。
- **`currentUser.do` 是身份接口**：它用于拿学号 `XH` 查课表（上游就是这么做的），
  本适配器只读其中的 `userId`，其余字段不读不存。若日后判定「取学号」这一步可以省掉，
  删掉②并让④只带 `XNXQDM` 即可（`extract.js` 已经能在拿不到学号时照发请求）。
