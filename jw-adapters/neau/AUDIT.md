# 东北农业大学 适配器安全审计（URP 综合教务 · 教务在 WebVPN 后面）

| | |
|---|---|
| 上游 | [shiguang_warehouse](https://github.com/XingHeYuZhuan/shiguang_warehouse) `NEAU/NEAU_01.js`（MIT，上游作者 **dezige131**）；同目录 `adapters.yaml` 的 `import_url` = `https://webvpn.neau.edu.cn/` |
| 平台 | URP 综合教务系统（同平台上游还有 IMU / LNU / TCU / TUST / YZU / AUFE / QQHRU，接口路径同一个） |
| 移植者 | NullClass 适配器移植（Claude Code agent） |
| 日期 | 2026-09-12 |
| 结论 | **通过**：不碰凭据、不外发、只读课表、无第三方域、无埋点。下面逐条留证。 |

## 1. 脚本实际请求了哪些域

全脚本**只有一个网络调用点**，且用的是**相对路径**：

```
GET /student/courseSelect/thisSemesterCurriculum/ajaxStudentSchedule/callback
```

| 静态扫描 | 结果 |
|---|---|
| `grep -oE "https?://[A-Za-z0-9.-]+"` | **无输出** —— 上游脚本里没有任何绝对 URL |
| `grep -nE "fetch\(\|XMLHttpRequest\|sendBeacon\|WebSocket\|\.src\s*="` | 只有 1 处：`fetch(apiUrl, {method:'GET'})`（我方加 `credentials:'same-origin'`） |
| `eval` / `new Function` / `localStorage` / `document.cookie` / `document.write` | 一处都没有 |

所以「脚本请求的是哪个主机」= **它跑在哪个页面的域上**，而不是脚本写死的：

- **校外（本适配器的主场景）**：教务在 WebVPN 网关后面。教务处公布的学生端入口是
  `https://zhjwxs.webvpn.neau.edu.cn`（网关把内网站点重写成 `<站点>.webvpn.neau.edu.cn`，
  选课通知里还出现过带端口后缀的写法 `zhjwxs-443.webvpn.neau.edu.cn`）；网关与统一身份认证
  在 `https://webvpn.neau.edu.cn/`。用户登录网关、打开教务系统后，页面本身就在那个子域上，
  这条相对路径请求**仍然同源**，落在 `*.webvpn.neau.edu.cn` 上，由网关转发给内网教务。
- **校内直连**：学生端公网名是 `https://zhjwxs.neau.edu.cn`（教务处「学生选课 / 成绩查询」
  的链接），同一条相对路径照常工作。
- **内网地址（172.x / 10.x 之类）脚本里一次都没出现**：内网主机由网关在服务端解析，
  脚本看不见也不需要知道。这与同平台走 WebVPN 的另两所学校不同 ——
  齐齐哈尔大学的上游脚本把 `172-20-139-153-7700.webvpn.qqhru.edu.cn` 硬编码在脚本里，
  扬州大学则从 `location.href` 里正则抠出深信服式的 `/http/<encrypted>/` 前缀；
  本适配器两者都不用，只保留了一个前缀兜底（见 §5）。

**移植时加的兜底**：`extract.js` 里 6 行 `vpnBase()` —— 若当前页路径形如
`/http[s]/<token>/…`（深信服那套把内网站点挂在路径前缀下），就把这个前缀拼回请求上，
否则用相对路径。它只影响**同一个域**上的路径，不会把请求指向别的主机（这不是绕过白名单的写法）。

## 2. manifest 取值

- **`loginUrl` = `https://webvpn.neau.edu.cn/`** —— 用户实际要打开的那一页：先在这里登录
  （统一身份认证），再进教务系统。上游 `import_url` 也是它，教务处通知给学生的入口也是它。
  - **不填** `https://zhjwxs.webvpn.neau.edu.cn/`：那个地址需要 WebVPN 会话，且校方通知里
    出现过 `zhjwxs` / `zhjwxs-443` 两种写法，写死一个可能落到 404；从网关入口进最稳。
  - **不写 `scheduleUrlHint`**：同上（不猜）。「一键刷新」会打开上次**成功提取**的那一页
    （宿主记的是 `webView.url`），比一个猜出来的课表页地址准。
- **`allowHosts` = `["*.neau.edu.cn"]`**，理由：

  1. 脚本请求的目标是**当前页面同源**，而页面可能是
     `webvpn.neau.edu.cn`（网关）、`zhjwxs.webvpn.neau.edu.cn` 或 `zhjwxs-443.webvpn.neau.edu.cn`
     （网关重写的教务），校内直连时是 `zhjwxs.neau.edu.cn`。**这几个都在 `neau.edu.cn` 下面**
     —— 学校自己的域，一个后缀通配正好覆盖「用户在哪个入口进去都能用」。
  2. 规范允许「至少三段」的后缀通配（`*.neau.edu.cn` 正好三段，`*.edu.cn` 会被拒），
     内置的 `ustc` 用的是同一个写法（`*.ustc.edu.cn`）。
  3. **没有更窄又能工作的写法**：只写 `webvpn.neau.edu.cn` 会让「网关重写成子域」这一路
     （正是本适配器的主场景）落空；只写 `*.webvpn.neau.edu.cn` 又漏掉校内直连的
     `zhjwxs.neau.edu.cn`。再宽就是 `*.edu.cn` 这类，规范明确拒绝，这里也不会写。
  4. **不含任何第三方**：统计、CDN 上的业务接口、加速器 —— 一个都没有。

  > **需要点名的代价**：白名单是**主机级**的，而 WebVPN 网关是个代理 ——
  > 放开 `*.neau.edu.cn`，等于放开「网关能把请求转发到的任何校内服务」。
  > 本适配器只发一个课表路径、脚本全文对用户可见，
  > 但**这条口子天然比非 WebVPN 学校粗**：以后移植 WebVPN 学校时，
  > 审计要盯的是「脚本要了哪条路径」，而不是「域名是不是本校的」。

## 3. 手册 §5 八条逐条

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 不碰凭据 | **通过**。全脚本不读 `password` / 登录表单 / `localStorage` / cookie，不向外发任何令牌；网关登录由用户在 WebView 里手工完成。fetch 只带 `credentials:'same-origin'`（本站 cookie 交给本站接口）。 |
| 2 | 不外发 | **通过**。无 `sendBeacon` / `WebSocket` / `EventSource` / `new Image().src` / 隐藏 iframe / 表单提交；除课表接口外没有任何目的地。 |
| 3 | 请求域可控 | **通过**。请求域 = 当前页面同源（校外即 WebVPN 网关域及其重写子域），已写进 `allowHosts: ["*.neau.edu.cn"]`（理由见 §2，通配克制、不含第三方）。 |
| 4 | 只读课表 | **通过**。只读 `xkxx`（课程名/教师/时间地点）与 `jcsjbs`（作息时间）。不请求成绩、学籍、个人信息、缴费；上游脚本里也没有这些接口。 |
| 5 | 不埋点 | **通过**。无任何统计 / 上报 / 遥测 / console 之外的上报行为（移植时还删掉了上游的调试 `alert` 与环境探测分支）。 |
| 6 | 不 eval 远程代码 | **通过**。无 `eval` / `new Function` / `document.write`；取回的字符串只经 `JSON.parse`。 |
| 7 | 不写页面 | **通过**。移植后的两个脚本不碰 DOM（不写节点、不改表单、不触发提交）；上游的 `shiguangBridge` 调用与「切到课表页」点击在移植时全部去掉。 |
| 8 | 不依赖用户输入之外的秘密 | **通过**。无硬编码密钥 / 固定令牌 / 他人学号；唯一新增的输入是「抓取日期」（浏览器本地时间，用于推算开学日与学期名，写在 warnings 里）。 |

## 4. 有没有绕过白名单的写法 —— 逐类点名

| 写法 | 有没有 |
|---|---|
| 把目标地址塞进 URL 参数（`?url=` / 网关的 `/proxy?target=` 之类） | **没有**。请求只有一条固定路径，无参数。 |
| 非同源 iframe / `<img>` / `<script>` 拉取 | **没有**。脚本不创建任何元素。 |
| 重定向把数据带走 | **没有**。没有导航、没有 `location=` 赋值；也不依赖重定向做取数。 |
| `fetch(u, {mode:'no-cors'})` / `credentials:'include'` 暗示跨域 | **没有**。唯一一处 fetch 是相对路径 + `same-origin`。 |
| 把主机名拼出来（域名分片、base64 拼串） | **没有**。脚本里连主机名字面量都没有，只有路径。 |
| WebSocket / Service Worker 侧信道 | **没有**。 |
| 借 `vpnBase()` 把请求引向别的主机 | **没有**。它只从 `location.pathname` 取 `/http[s]/<token>` 前缀，结果永远是**同源**相对路径。 |

> 补充（写在这里省得以后踩）：宿主规范 §3.3 明说「重定向后续 URL、WebSocket、Service Worker
> 不经过请求拦截」——所以这四条只能靠**读脚本**来审，本适配器四类都不涉及。

## 5. 已知不确定 / 只有真机能验的

1. **fixture 是合成的**（测试方案 §3 的既定代价）：按上游实际读取的字段形状编的，
   只保证「同样输入永远同样输出」，**不保证真实页面上的解析正确**。拿到真实 dump 请替换
   `fixtures/*.extracted.json`（顺带核对 `xkxx` 是否真在数组第 0 个、`weekDescription`
   与 `classWeek` 到底哪个有值）。
2. **WebVPN 重写形式是按公开信息推断的**：校方通知里出现过 `zhjwxs.webvpn.neau.edu.cn`
   与 `zhjwxs-443.webvpn.neau.edu.cn` 两种写法，实机上是哪一种（或两种都通）没有账号验证过。
   `allowHosts` 的后缀通配对两者都成立，所以**不影响能不能用**；影响的是 §2 里
   `scheduleUrlHint` 的取舍。
3. **开学日/学期名/总周数都是推算值**（接口只返回「当前学期」，没有校历与学年学期字段），
   三条都已经写进载荷 `warnings`，导入预览里会逐条显示 —— 不许静默是硬要求。
4. **`warnings` 的显示要新版应用**：本适配器 `minAppVersionCode` 取 11（与 `dlutci` / `ustc`
   一致，老版本能装、能导入，只是不显示核对提示）。若希望「推算的开学日」必定被看到，
   集成时可把它提到带 warnings 通道的那个 versionCode。
5. **OCR / 提问桥在重写子域上不可用**：宿主的桥 origin 规则**跳过通配**
   （`JwOriginRules.forAdapter` 里 `*.` 直接 continue），所以 `__ncOcr` / `__ncAsk` 只注入到
   `loginUrl` / `scheduleUrlHint` 的精确 origin。本适配器不用这两个能力，没影响；
   但**以后要移植需要在 WebVPN 子域上用 OCR 的学校，得写精确主机名**（或改宿主的 origin 规则，
   那是 Kotlin，不在本次改动范围内）。

## 6. 上游 → 本适配器的行为差异（不涉及安全也记一下）

- 教师：上游 `|| "未知"`，这里留 `null`（空课里「未知」会当成真名显示）；上游会把教师名里的
  `*` 全部去掉（URP 用 `*` 打码），这里保留同一处理。
- 教室：上游空场所写 `"待定"`，这里留 `null`。
- 周次：上游的单双周标志是**整串**生效（`"1-8周(单),10-16周"` 会把后半段一起过滤掉），
  这里按逗号分段、各段自判；上游只读 `weekDescription`，这里在它缺失/为空时退回 `classWeek` 位串。
- 上游「无时间地点的课程」是静默 `continue`，这里会写进 `warnings` 点名。

签名：NullClass 适配器移植（Claude Code agent）　2026-09-12
