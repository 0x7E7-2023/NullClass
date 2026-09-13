# 安全审计 —— neu（东北大学教务）

- 上游：`shiguang_warehouse` 的 `NEU/neu.js`（MIT，上游作者 `Vera-zero`，上游 yaml 里
  `adapter_id: NEU_1` / `import_url: http://jwxt.neu.edu.cn/`）
  <https://github.com/XingHeYuZhuan/shiguang_warehouse>
- 上游快照：`main` @ `e62554a4034386b893bcd6813c7b2b64f8c730a3`（2026-09-12 12:50:29 +0800）
- 移植者：`0x7E-2023`　移植日期：2026-09-13
- 审计结论：**通过**。请求域只有一个（本校教务主机，且全部走**当前页同源相对路径**），
  除取课表必需的接口外没有别的网络行为，不碰凭据、不读令牌、不埋点、不写页面。

## 〇、平台判定（按脚本实际请求的接口路径，不看上游注释、也不看相似度榜）

**金智教育 jwapp（WIS）平台。与已移植的 `dlutci`、`niit` 同平台。**

依据是上游脚本里写死的接口路径与响应读法：

- `https://jwxt.neu.edu.cn/jwapp/sys/kbapp/api/wdkbcx/getMyScheduleDetail.do`，
  响应信封 `{ code: "0", datas: { getMyScheduleDetail: { arrangedList: [...] } } }`，
  行字段 `courseName` / `dayOfWeek` / `beginSection` / `endSection`；
- 同平台旁证：上游 `CAPU/capadap.js` 请求**完全相同**的路径与信封；
  上游 `XJZFU/xjzfu.js` 请求 `homeapp/api/home/student/getMyScheduleDetail.do`（同族）。
  这两个上游件的 `arrangedList` 行里还有 `week` **位串**字段，本移植件因此也认它（见下）。

> **相似度榜的坑（本批检查表的前言）**：按 token 余弦相似度排序，本件最像的是**强智**的
> `upc`（0.820）。那是上游脚手架造成的文本相似 —— 弹窗、`shiguangBridge` 回调、
> `savePresetTimeSlots` 的作息表数组，强智件和金智件是同一套写法。**不是同平台**，
> 移植模板用的是金智的 `niit` / `dlutci`，没有照抄 `upc`。
>
> **同平台不等于同接口**：`dlutci` / `niit` 走 `/jwapp/sys/wdkb/modules/...`（老模块），
> 东北大学走 `/jwapp/sys/kbapp/api/...` + `/jwapp/sys/homeapp/api/...`。接口路径、行字段名
> （`KCM`/`SKXQ`/`SKZC` vs `courseName`/`dayOfWeek`/`week`）、取数路径**都不一样**，
> 所以 `parse.js` 是按东北大学自己的编码单独写的用例，没有沿用 `niit` 的 fixture 期望。

## 一、请求了哪些域与路径

**只有一个域：`jwxt.neu.edu.cn`（= manifest 的 `loginUrl` 主机）。**

`extract.js` 里所有请求都是**相对路径**（`/jwapp/sys/...`），落在用户当前打开的教务页面上，
所以主机由页面决定、脚本没有把主机名写死（这也是移植手册 §5 对代理/WEBVPN 场景要求的写法）。

| # | 接口 | 方法 | 用途 | 必需性 |
|---|---|---|---|---|
| ① | `/jwapp/sys/homeapp/api/home/kb/xnxq.do` | GET | 学年学期列表，取 `selected === true` 那一项当**当前学期**（同时拿到学期名 `itemName`） | 降级链的第一环 |
| ② | `/jwapp/sys/homeapp/api/home/getTermWeeks.do` | POST | 学期周次表：**开学日** + **总周数** | 尽力而为 |
| ③ | `/jwapp/sys/kbapp/api/wdkbcx/getMySectionList.do` | POST | 作息时间（节次 ↔ 起止时间） | 尽力而为 |
| ④ | `/jwapp/sys/kbapp/api/wdkbcx/getMyScheduleDetail.do` | POST | 课表行（**上游唯一的请求**） | 必须成功 |

- **学期编号只走 ①，拿不到就按本机日期推算**：曾经有一个「当前登录用户」接口（金智 `currentUser.do`）
  作降级第二环，2026-09-13 的安全审查判定它落在「不请求个人信息类接口」的字面上、且后面本就有
  日期推算兜底，**已删除**（证伪者复核：删掉后降级链仍成立，零代价）。本适配器现在**不请求任何
  与登录人身份有关的接口**。
- 请求都带 `credentials: 'include'`，用的是**用户自己在 WebView 里登录后**的会话 Cookie
  （规范允许：读本校令牌用于本校接口是正常的；脚本不读、不存、不外发任何令牌）。
- ①②③ 是移植时按**同平台参照**补的（上游 NEU 只请求 ④）：上游 `CAPU/capadap.js` 在同族接口上
  串的正是这几步（`getTermWeeks.do` 拿开学日与总周数、`getMySectionList.do` 拿节次表），
  上游 `XJZFU/xjzfu.js` 另外证明了 `kb/xnxq.do` 这个学期列表接口
  （`itemCode` / `itemName` / `selected`）。四项都在同一主机、同一 `/jwapp/sys/` 命名空间下，
  **没有引入新的域**。任一失败都不影响导入：开学日拿不到由 `parse.js` 按最近的周一推算并写进
  `warnings`，作息表拿不到就不写 `periodTimes`（应用会补默认节次表），两者都不会静默。
- `allowHosts` 为 `[]`：脚本请求的主机与 `loginUrl` 同源（应用会把 `loginUrl` 的主机自动并入
  白名单，见 `JwAdapterPackage.allowedHosts`），没有「额外的域名」需要声明 ——
  与同平台的 `dlutci` / `niit` 取同一个口径。
- 没有 CAS 登录域、CDN、统计域、WebVPN 网关。
- `loginUrl` 是 **http**：这是上游 yaml 给的地址（`import_url: http://jwxt.neu.edu.cn/`），
  该校教务只在校园网/校内 VPN 内可达（上游 yaml 的 `description` 也这么写）。
  应用会对 http 显示「不安全连接」标记 —— 这是实情，不掩盖。

## 二、读了什么

- **接口数据**：课表行（`courseName` 课名、`dayOfWeek` 星期、`beginSection`/`endSection` 节次、
  `titleWeekTeacherClassroomDetail` 上课安排文本、`week` 周次位串、`placeName`/`campusName` 地点、
  `beginTime` 上课时间）、学期列表（`itemCode`/`itemName`/`selected`）、学期周次（`serialNumber`/
  `startDate`）、节次表（`name`/`startTime`/`endTime`）。都是排课信息。
- **不读页面 DOM**：与同平台的 `niit` 不同，本件**一处页面元素都不读**（不读 `#dqxnxq2`、
  不读 `document.cookie`、不读 `localStorage` / `sessionStorage`）。取数全走接口。
- **输出前剔除个人字段**：`extract.js` 用一张表把学号 / 姓名 / 身份证 / 用户 id 之类的键
  **大小写不敏感地**滤掉（`XH`/`XM`/`XH_ID`/`SFZH`/`USERID`/`USERNAME`/`USER_NAME`/
  `STUDENTID`/`STUDENTNUMBER`/`REALNAME`），交给 `parse.js` 的行里没有这些字段，载荷里也不会有；
  fixture 因此天然脱敏。
- 不读成绩、学籍、缴费、个人信息页；不读考试接口（见第四节）。

## 三、手册 §5 八条逐条

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 不碰凭据 | ✅ 脚本里没有 password / pwd / 登录表单 / 验证码；不读 `localStorage` / `document.cookie`。只用 WebView 现有会话 Cookie 请求本校接口，令牌既不读入内存也不外发 |
| 2 | 不外发 | ✅ 全部网络行为就是上表四个请求，都是**相对路径** → 落在 `jwxt.neu.edu.cn`；无 `sendBeacon` / `WebSocket` / `EventSource` / `new Image().src` / 动态 `<script>` |
| 3 | 请求域可控 | ✅ 只有一个精确主机名（= `loginUrl` 主机），无通配；`allowHosts` 空数组，不多声明。请求路径**全是相对的**、主机名不写死；代码里没有任何绝对 URL（`grep -nE "https?://"` 只命中三处，全部是注释里的出处说明） |
| 4 | 只读课表 | ✅ 四个接口分别是学期列表 / 学期周次（校历）/ 节次表（作息）/ 课表行，都是取课表所必需；**没有成绩、学籍、缴费接口，也没有任何与登录人身份有关的接口**（原先作降级兜底的 `currentUser.do` 已按本节口径删除）。课表响应里若夹带学号/姓名，`extract.js` 的 `withoutPersonal()` 会在交出前剔除（大小写不敏感） |
| 5 | 不埋点 | ✅ 无统计、上报、遥测，无第三方域名；脚本里连 `console` 都没有，没有任何输出通道 |
| 6 | 不 eval 远程代码 | ✅ 无 `eval` / `new Function`；脚本自包含，无动态 `import` |
| 7 | 不写页面 | ✅ **一处 DOM 都不碰**：无 `innerHTML` / `appendChild` / 表单赋值 / `submit` / 点击 / 滚动。取数全走接口，不要求用户在页面上做任何操作 |
| 8 | 不依赖用户输入之外的秘密 | ✅ 无硬编码密钥、无他人学号、无固定令牌。学期编号来自教务的学期列表接口；它问不到时才按本机日期推算，且推算值会在载荷 `warnings` 里说明 |

## 四、没有移植的东西（审计应知情）

- **考试接口 `homeapp/api/home/student/exams.do`**（上游脚本第 9 步的「导入考试时间」）**没有移植**。
  三个理由：① 空课载荷里**没有考试这个概念**，上游的做法是把考试编成一门叫
  「课程名_考试_日期」的课、硬塞进第 15 周，那是**编造课表数据**；
  ② 上游自己把它标成「测试功能，考试固定在第 15 周，出错请反馈」；
  ③ 它在原脚本里是**用户点确认才走**的可选步骤，而我们的提取是自动的。
  因此本适配器**不会请求这个接口**，也不会往课表里塞任何非课程的行。
- **校区选择（南湖 / 浑南）没有移植**。上游选校区**只用来套一份写死的作息表**
  （两张 12 节的常量数组），课表请求本身并不带校区 —— 上游把 `XQDM` 留空，注释写「神秘参数，
  设为空可获取课表数据」。这里改成：课表请求保持 `XQDM=` 空（上游口径），作息时间改用教务自己的
  节次接口（④，用同一个 `XQDM` 查，两边看到的是同一套节次）。**不再问用户、也不写死作息表** ——
  写死一份可能属于另一个校区的作息，比不给更糟。
- **上游的 2 次重试 + 2 秒 sleep 收紧了**：只在**必须成功**的课表请求上留一次立刻重试。
  宿主给整段脚本 30 秒，sleep 会把预算烧光（现在四次请求分两轮并发，正常 1~2 秒）。
- **`firstDayOfWeek: 7`（周日）没有采用**。空课的载荷靠 `firstDay` 的星期几决定列对齐，
  而 `dayOfWeek` 是**绝对**星期（1 = 周一，金智平台口径；东北大学自己的研究生适配器
  `NEU/neuyjs.js` 用的是同义的 `XQ` 字段）。若按上游把 `firstDay` 退到周日，
  每门课都会整体偏一天。详见 `parse.js` 头部移植改动 ⑥。

## 五、本批（批次三）专项检查表 10 条逐条

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 周次编码四写法 | ✅ 四种写法各有用例，且**都不是把 `parse.js` 输出贴回去的**：`(单)1-16周`（标记在前，basic 第 2 门）、`2-16周(双)`（标记在「周」后）、`1-16(单周)`（标记在括号里，basic 第 4 门）、`1-3,5-9周`（混排，basic 第 3 门）。上游只认后两种里的「1-8周」「2-6周(双)」，逗号串里不带「周」的前几段会**被静默丢掉**（`1,3,5周` → 只剩 `[5]`），本移植件修掉了这个坑。**位串形态另有一整对 fixture**：连续段（`11111111111111110000` → `{1,16,ALL}`）、奇数隔周（`10101010101010100000` → `{1,15,ODD}`）、偶数隔周（`01010101010101010000` → `{2,16,EVEN}`）、单周落单（`00000000010000000000` → `{10,10,ALL}`） |
| 2 | 括号内纯数字不是周次 | ✅ 周次解析只认「数字 / 数字区间 + 可选的单双标记」；`(1)`、`(1-2)`、`（1）` 这类**没有周次语义的纯数字 / 数字区间括号组，在拼周次数字之前整组删掉** —— 不是只删括号符号（那会把序号粘进周次数字：「(1)1-16周」粘成 11-16、「1-16周(1)」粘成 1-161），也不是拼完再删（那时数字已经粘上去了）。括号里是**单双标记**的（`1-16(单周)`）照旧解析。删完剩下的串认不出周次时，该条排课不导入并进 `warnings`（`notes.bad`），**不静默** —— fallback fixture 的「单周」与 bracketindex fixture 里单独成段的 `(1)` 走的是同一条路径。新增专项 fixture `bracketindex` 覆盖序号在前 / 序号在后 / 数字区间 / 全角括号四种位置，能否拦住错见第七节 M9、M10 |
| 3 | 无星期表头兜底 | 不适用（本适配器走接口、不解析表格，没有 `row.length - 7` 这类按数组长度猜列的写法）。星期直接取接口的 `dayOfWeek`，取不到或不在 1..7 的记录进 `warnings`（fallback fixture 的「形势与政策」就是缺星期被挡下的） |
| 4 | 时间合法性 | ✅ 写进 `periodTimes` 的时间统一过 `hhmm()`：必须匹配 `HH:mm`（也接受 `HH:mm:ss`）且落在 `00:00–23:59`，`start < end`。**本适配器不计算任何时间**（时间全部来自教务节次接口），所以不存在「算出 24:00」的情形；非法的节次**整条丢掉**并进 `warnings`，绝不写进载荷（basic fixture 里的「第 9 节 24:00」就是这条用例） |
| 5 | `warnings` 上限 | ✅ 每条经 `clip()` 截到 200 字（超出加 `...`），整组最多 20 条（`MAX_WARNINGS`）。当前四个 fixture 的 `warnings` 分别 1 / 0 / 8 / 2 条，全部 ≤200 字 |
| 6 | 分页 | 不适用：`getMyScheduleDetail.do` 一次返回整个学期的 `arrangedList`（上游口径），没有 `totalSize` / `pageNumber`，不存在「取完第一页就停」。**已知的完整性缺口**见第六节 |
| 7 | 学期名 | ✅ 优先用教务给的（`kb/xnxq.do` 的 `itemName`，bitstring fixture 用的是「2026-2027学年秋季学期」）；拿不到才按学期编号拼「2026-2027学年第一学期」。**没有拿适配器名当学期名** |
| 8 | `teacher` 拿不到就留空 | ✅ 全部是 `null`，没有「未知」「待定」「暂无」。`parse.js` 里唯一给 `teacher` 赋值的分支只可能写进文本里真实存在的那个字段 |
| 9 | `allowHosts` | ✅ `[]` + 全部相对路径（本件不涉及 WebVPN，也不使用 OCR / 提问桥，所以没有「通配拿不到桥」的问题） |
| 10 | 变异测试 | ✅ 十处，逐条见第七节。**改坏哪一处 → 哪条用例变红**，且每条都确认能红。M9、M10 是本批修「括号里的教学班序号」时补的，两条都**只让新用例 `bracketindex` 变红**，原有三份 fixture 全绿 —— 这条路径此前确实没被盖住 |

## 六、已知的取舍与缺口（审计应知情）

- **fixture 是合成的**：形状取自上游脚本读取的字段 + 同平台 `CAPU` 对**同一接口**的读法，
  只保证「同样的输入永远得到同样的输出」，**不保证真实教务上的解析正确性** —— 见
  `docs/jw-adapter-testing.md` §3。拿到真实 dump 请替换 fixture 并重跑 `:importer:test`。
- **完整性缺口（已核实、有意保留）**：上游 `CAPU/capadap.js` 在**同族接口**上会按
  `ZC=1..totalWeeks` **逐周请求**再合并去重，它自己的日志写明这样才拿得到「短期实验 / 实习」课
  （不带 `ZC` 的空请求会漏）。东北大学的上游脚本只发一次不带 `ZC` 的请求，本移植件**保持上游口径**
  （只有它才是本校的实测依据）。也就是说：**如果东北大学存在只在某几周上课的短期课程，
  本适配器可能漏掉它们**。日后若收到「少了短期课」的反馈，这里是第一嫌疑，
  修法是照 `CAPU` 那样按周补请求。这一条没有写成 `warnings` —— 它对每次导入都会出现，
  而它不是「用户能去核对的东西」，写成提示只会变成噪音。
- **`XQDM` 的含义未完全确认**：上游注释称留空才取得到数据，`CAPU` 传 `XQDM=01`。
  本件按上游留空（= 不按校区过滤），作息表也用同一个 `XQDM` 查，两者一致。
- **作息表与课表节次编号的自洽性是运行时校验的**：若行里带 `beginTime`，会与节次表交叉比对，
  对不上就丢掉整张表并进 `warnings`（同平台 `CAPU` 正是栽在这里：它的 `beginSection` 与节次表编号
  不是一回事，得按 `beginTime` 反查）。没有 `beginTime` 时退化为「覆盖范围检查」：
  课表用到的节次超出作息表范围就整张丢掉。
- `minAppVersionCode` 写 11（与同批移植件、`dlutci`/`ustc` 一致）：本适配器不用 OCR / 提问能力位，
  只多写了 `warnings`（老版本会忽略未知字段，导入照样成功，只是少一句提醒）。
- **上游更新了怎么办**：这里搬的是 `main @ e62554a` 的快照，不追上游。转换逻辑全部在
  `parse.js`，重搬一遍成本很低 —— 上游修了 bug 时优先重跑移植，而不是打补丁。

## 七、变异测试记录

用 `parse.js` 的**当前版本**做基线（四个用例全绿），逐条把逻辑改坏，跑同一组用例：

| # | 改坏了什么 | 变红的用例 | 说明 |
|---|---|---|---|
| M1 | `runsOf` 不再识别步长 2：`if (i + 1 < weeks.length && weeks[i+1]-weeks[i] === 2) step = 2;` → `if (false) step = 2;`（**把位串的隔周段当成连续段**） | `basic`、`bitstring` | 单双周塌成一堆独立的 `ALL` 段（`{1,15,ODD}` 变成 8 个 `{w,w,ALL}`） |
| M2 | 逗号串里不带「周」的前几段一律清空（**回退到上游行为**） | `basic`、`bitstring`、`fallback` | `1-3,5-9周` 只剩 `[5…9]`、`1-8,10-16` 只剩 `[10…16]`，静默少课 |
| M3 | 单双标记不再剥离：`/[单双]/g` → `/单双/g`（上游与最初的写法） | `basic`、`fallback` | `1-16(单周)` / `2-16周(双)` 整段认不出，`3周(双)` 也从「矛盾告警」变成「整条丢掉」 |
| M4 | 去掉位串兜底 `if (!weeks.length) weeks = keptBits;` | `bitstring` | 只有位串、没有安排文本的行全部消失 |
| M5 | 开学日不回退到周一：`(date.getUTCDay() + 6) % 7` → `0`（手册 §4.3） | `basic` | `firstDay` 停在 2025-09-03，整学期偏两天 |
| M6 | 两段文本不判教室：`if (tokens.length === 2 && /[0-9楼室馆场区]/.test(tokens[1]))` → `if (false)` | `basic` | 「军事理论」的教室「何世礼教学馆101」被当成教师 |
| M7 | `hhmm` 不做范围检查：`if (h > 23 \|\| mi > 59) return null;` → `if (false)` | `basic` | `24:00` 被写进 `periodTimes`（检查表第 4 条：这会让整个载荷被拒） |
| M8 | 不挡空课名：`if (!name \|\| !(day >= 1 && day <= 7)` → `if (!(day >= 1 && day <= 7)` | `fallback` | 空课名的记录被写成课程，`name` 非空是规范 §4 的**载荷级**必填项 —— 放进去会让**整个载荷**被拒，不是跳过这一门 |
| M9 | 括号组不再先删，回退到「删掉括号符号再拼数字」（`var withoutIndex = ...` + `var body = withoutIndex.replace(...)` → `var body = s.replace(...)`） | `bracketindex` | 教学班序号被粘进周次数字：`(1)1-16周` → `{11,16,ALL}`（**静默少掉 1-10 周**）、`1-16周(2)` → `{1,30,ALL}` 且 `totalWeeks` 被抬到 30、并产出一条假的「有 131 个周次超出 30 周」告警。`basic` / `bitstring` / `fallback` 全绿 —— 正是「有用例、但用例盖的不是这条路径」的样子 |
| M10 | 括号组删除只认纯数字、不认数字区间：`[ ]*[0-9]+([ ]*[-–—~至][ ]*[0-9]+)?` → `[ ]*[0-9]+` | `bracketindex` | `(1-2)3-8周` 的区间组留在原位，粘成 `1-23-8` → 整段认不出，`大学物理` 整条排课消失（课程数 5 → 4） |

还原核对：十次变异后 `parse.js` 与变异前基线**逐字节一致**（`sha256sum` 相同、`diff` 无输出），
四个用例恢复全绿（`basic=MATCH bitstring=MATCH fallback=MATCH bracketindex=MATCH`）。

**这份用例集不是「跑通就行」**：M3 是移植过程中真实踩到的 bug（`.replace(/单双/g, '')` 写成了
两字符字面量而不是字符类 `/[单双]/`，于是 `(单)1-16周`、`2-16周(双)`、`1-16(单周)` 三种写法
全部认不出）—— 期望值是**独立推出来的**，所以第一次跑就红了，不是事后补的。
M8 同理：空课名会顶掉整个载荷，是补用例时才发现的。

**基线结果**（`node` + `vm` 当 Rhino 的替身，`fixtures/*.extracted.json` 当 `__ncInput`）：

```
basic=MATCH    bitstring=MATCH    fallback=MATCH    bracketindex=MATCH
```

四个期望载荷另外单独过了一遍规范 §4 的校验规则（`totalWeeks∈1..30`、`dayOfWeek∈1..7`、
`startPeriod≤endPeriod`、`weekType∈{ALL,ODD,EVEN}`、`startWeek/endWeek∈1..totalWeeks`、
`name` 非空、`periodTimes` 的时间均为合法 `HH:mm` 且 `start<end`、`warnings` ≤20 条且每条 ≤200 字）
全部通过。

ES5 自检：`grep -nE "=>|\`|\blet |\bconst " jw-adapters/neu/*.js` 无输出；
NUL 字节计数为 0、无 BOM。
