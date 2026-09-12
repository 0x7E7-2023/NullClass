# 安全审计 —— niit（南京工业职业技术大学教务）

- 上游：`shiguang_warehouse` 的 `NIIT/niit.js`（MIT，上游作者 `nifs1729`）
  <https://github.com/XingHeYuZhuan/shiguang_warehouse>
- 上游快照：`main` @ `e62554a`（2026-09-12）
- 移植者：NullClass（Claude Code 代跑）　移植日期：2026-09-12
- 审计结论：**通过**。请求域只有一个（本校教务主机），除取课表必需的接口外没有别的网络行为，
  不碰凭据、不埋点、不写页面。

## 一、请求了哪些域

**只有一个域：`jwxt.niit.edu.cn`（https，即 manifest 的 `loginUrl` 主机）。**

| 接口 | 方法 | 用途 |
|---|---|---|
| `/jwapp/sys/wdkb/modules/xskcb/cxxszhxqkb.do` | POST | 课表行（上游唯一的请求） |
| `/jwapp/sys/wdkb/modules/jshkcb/dqxnxq.do` | POST | 当前学年学期（**降级路径**，只在页面读不到学期编号时才发） |
| `/jwapp/sys/wdkb/modules/xskcb/cxxljc.do` | POST | 校历：开学日、总周数（可选，失败不影响导入） |

- 三个请求都带 `credentials: include`，用的是**用户自己在 WebView 里登录后**的会话 Cookie
  （规范允许：读本校令牌用于本校接口是正常的；脚本不读、不存、不外发任何令牌）。
- 上游脚本静态扫描（手册 §5 的扫法）只有一条绝对 URL：`https://jwxt.niit.edu.cn/jwapp/sys/wdkb/modules/xskcb/cxxszhxqkb.do`；
  后两个接口是移植时按**同平台参照**（`jw-adapters/dlutci/`，同为金智 jwapp，走 `/jwapp/sys/wdkb` 同一套模块）
  补的降级路径，仍在同一主机、同一模块下，没有引入新的域。
- `allowHosts` 为 `[]`：脚本请求的主机与 `loginUrl` 同源（应用会把 `loginUrl` 的主机自动并入白名单，
  见 `JwAdapterPackage.allowedHosts`），没有「额外的域名」需要声明。与同平台的 `dlutci` 取同一个口径。
- 没有 CAS 登录域、CDN、统计域、WebVPN 网关。

## 二、读了什么

- **接口数据**：课表行（`KCM` 课名、`SKXQ` 星期、`KSJC`/`JSJC` 节次、`SKZC` 周次位串、`SKJS` 教师、
  `JASMC` 教室等排课字段）、当前学年学期（`DM`/`MC`）、校历（`XQKSRQ` 开学日 / `ZZC` 总周数）。
- **页面**：只读一处 —— 课表页上 `#dqxnxq2` 的 `value`（学年学期编号），这是上游的做法。
- **输出前剔除 `XH`（学号）与 `XM`（姓名）**：`extract.js` 交给 `parse.js` 的行里没有这两个字段，
  载荷里也不会有；fixture 因此天然脱敏。
- 不读 `localStorage` / `sessionStorage` / `document.cookie`，不读成绩、学籍、缴费、个人信息页；
  同平台的 `dlutci` 要先请求 `cxxsjbxx.do` 拿学号，**本适配器不需要那一步**（接口按会话身份取数）。

## 三、手册 §5 八条逐条

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 不碰凭据 | ✅ 脚本里没有 password / pwd / 登录表单 / localStorage 令牌；只用 WebView 现有会话 Cookie 请求本校接口，不外发 |
| 2 | 不外发 | ✅ 全部网络行为就是上表三个 POST，都在 `jwxt.niit.edu.cn`；无 `sendBeacon` / `WebSocket` / `EventSource` / `new Image().src` |
| 3 | 请求域可控 | ✅ 只有一个精确主机名 `jwxt.niit.edu.cn`（= `loginUrl` 主机），无通配；`allowHosts` 空数组，不多声明 |
| 4 | 只读课表 | ✅ 三个接口分别为课表行 / 当前学年学期 / 校历，都是取课表所必需；没有成绩、学籍、个人信息、缴费接口 |
| 5 | 不埋点 | ✅ 无统计、上报、遥测，无第三方域名 |
| 6 | 不 eval 远程代码 | ✅ 无 `eval` / `new Function`；脚本是自包含的（也没有动态 `import` / 注入 `<script>`） |
| 7 | 不写页面 | ✅ 只读 `#dqxnxq2` 的 value；无 innerHTML / appendChild / 表单赋值 / submit / 点击。取数全走接口，不依赖页面被改动 |
| 8 | 不依赖用户输入之外的秘密 | ✅ 无硬编码密钥、无他人学号、无固定令牌；学期编号来自页面 / 本校接口 / 本机日期推算（推算会在 `warnings` 里说明） |

## 四、已知的取舍（与安全无关，但审计时应知情）

- `minAppVersionCode` 写 11（与同批移植件、`dlutci` 一致）：本适配器不用 OCR / 提问能力位。
  但载荷里的 `warnings`（推算的开学日等）是 0.8.2 之后才有的通道，**更旧的应用会忽略它**——
  若本适配器随带该通道的版本发布，可把这一项提到对应 versionCode。
- 校历接口（第 3 个请求）是**尽力而为**：拿不到时 `firstDay` 由 `extract.js` 按「最近的周一」推算，
  `parse.js` 会把它写进 `warnings`，不会静默当成真值。
- fixture 是**合成**的（形状取自上游脚本读取的字段），只保证「同样的输入永远得到同样的输出」，
  不保证真实教务上的解析正确性 —— 详见 `fixtures/` 与移植报告。
