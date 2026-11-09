# 移植上游教务适配器（批次三十一 · 5 所新校）

上游快照 `baf83fa`（2026-11-09 拉取）。自上批快照 `29498b0` 以来上游只新增 `HBUE`、`WIT` 两所，
没有改动已移植件，本批**没有跟进件**。内置 201 → 206，库索引版本 `2026.11.10.1`。
移植由 haiku 子代理完成，主调度复核、复审修正、集成。

| key | 学校 | 上游（作者） | 平台 / 模板 | 本校特有行为 |
|---|---|---|---|---|
| `tongji` | 同济大学 | `TONGJI/tongji_01.js`（Linho1219） | 1 系统自研 REST / 骨架借 `hueb` | 主机由当前页 `origin` 拼出；登录判断用 `POST currentAuthId`；学期不弹窗直接用 `currentTermCalendar`；节次时间取 `noWeekendWorkTimes`；教师剥「(1001)」工号；教室 `roomIdI18n` → 上游拼错的 `roomLable` 兜底；周次是数字数组 |
| `cqu` | 重庆大学 | `CQU/cqu.js`（茵符草） | 智慧教务自研 REST / 骨架借 `hueb` | Bearer 令牌取自 `localStorage.cqu_edu_ACCESS_TOKEN`，只进 `my.cqu.edu.cn` 同源请求头；学号只当课表接口请求体，不进交出对象；周次是 `teachingWeek` 位串；`periodFormat` 单节与区间两种写法；总周数用 `maxWeek` 接口 |
| `jlu` | 吉林大学 | `JLU/JLU_01.js`（Leimeng52mc） | 金智 `jwapp/sys/wdkb` / 模板 `sany` | 基址从当前页 `/jwapp/sys/` 之前取，经 Sangfor WebVPN 时是 `/https/<会话 hex>`；六个同源 POST（学期、校历、节次、课表、调课停课、未定时间）；调课按 `KBID` 改周次并落新时间；未定时间课程只提醒不导入；删掉模板的本地存储 JWT 扫描（本校只需会话 Cookie） |
| `scnu` | 华南师范大学 | `SCNU/scnu.js`（YewFence） | 正方 V9 / 模板 `nbut` | 部署在站点**根路径**（不拼 `/jwglxt`）；校区作息按课表行数最多自动挑；校历一行一周 `{zs, rq}`，第 1 周日期直接当开学日（含每周起始日）；作息不连续 / 重叠整份不用并说明；课程类型加「（实验）/（实践）」后缀 |
| `nankai` | 南开大学（研究生） | `NANKAI/nankai.js`（Cure） | JSP 服务端渲染逐周抓 / 模板 `upc-grad` | 1 次无参 GET 读学年 / 学期 / 周次下拉框与节次时间，再按 `?xn=&xj=&zc=N` 逐周 GET（并发 3）；rowspan / colspan 还原网格；「无独立教师行时第一行其实是地点」；开学日按学期代号推断并如实提醒；单请求 8 秒超时 + 逐周 22 秒总预算 |

## 复审与修正

每件一个 reviewer 复审（tongji 复审判为无 bug），主调度逐条核对代码后确认是真问题才动手，
每条修正都配「独立手推期望的用例 + 能捕获它的变异 / 冒烟场景」：

- `jlu`：调课、停课记录没像课表行那样去重，同一条记录出现两次时「已调整了 N 处」被放大（5 → 6）。
  加 `seenChanges` 去重，新增 `duplicate-changes` 用例（输入多一条重复记录，输出必须与 `changes` 逐字相同）。
- `scnu`（3 条）：①`jwBase()` 在页面路径不含 `/jwglxt/` 时补 `/jwglxt`，而本校部署在站点根路径，
  真机上四个请求会全部 404——这是整件不可用级别的问题，基址改回根路径；②`scheduleUrlHint` 同样写错成
  `/jwglxt/kbcx/…`，改成根路径课表页；③校历第 1 周的日期被一律回退到周一，丢掉上游由该日期隐含的每周起始日，
  改为直接用校历第 1 周日期当 `firstDay`（手册 §4.3）。冒烟的路径断言同时收紧到根路径，
  三个 fixture 的 `firstDay` 与提醒同步成 2026-09-02（周三）。
- `cqu`（2 条）：①教务给的学期名不含「学期」二字（如「2026夏」）时整段丢弃，还反过来提醒「教务没有给学期名称」，
  改为只要原文非空就照用；②按开学日推学期名时 2-7 月一律算第二学期，与本校 7 月 1 日的夏季学期锚点冲突，
  7 月改为第三学期。新增 `term-name-summer`、`start-july` 两对用例。
- `nankai`（3 条）：①开学日提醒写「X 月 X 日**所在周**的周一」，而算法（与上游 `guessStartDate` 一致）取的是
  当天或之后最近的周一，2027-02-20 是周六时口径差一周，改措辞；②`readOptions` 用 `option.selected` 判断预选，
  浏览器会把第一个 option 默认置为选中，导致「没有预选学年学期」永远检测不到、静默导入列表第一项，改为只看
  `selected` 属性；③逐周抓取没有超时，一个卡住的页面会把整次导入拖到宿主 30 秒上限，加单请求 8 秒超时与
  逐周 22 秒总预算，超预算的周按「没取到」提醒。新增冒烟场景 `no-preselect`、`hang-week`。

## 验证

- 五件单项 Rhino 自检均 PASS（`tongji` 6 对、`cqu` 9 对、`jlu` 7 对、`scnu` 9 对、`nankai` 6 对），
  全部为合成数据，期望先手推再比对。
- 本地假教务站浏览器冒烟（Playwright route 全拦截，真实跑 extract.js 再交 Rhino 跑 parse.js）：
  `tongji` 9/9、`cqu` 10/10、`jlu` 10/10、`scnu` 13/13、`nankai` 9/9 OK。本机 Playwright 自带 Chromium 缺失，
  harness 走系统 Edge（`NC_PW_CHANNEL=msedge`）。
- 变异测试：`tongji` 4 处、`cqu` 6 处、`jlu` 8 处、`scnu` 6 处、`nankai` 4 处全部被捕获，改坏后均已还原并按
  SHA-256 复核原文件未变。`nankai` 的「去掉单请求超时」不做删码式变异（harness 自身会卡在浏览器清理），
  只保留 `hang-week` 冒烟场景。
- `./gradlew :importer:test --rerun-tasks`：BUILD SUCCESSFUL，含「索引与目录一致」「manifest 合法且 key 唯一」
  「全部 fixture 实跑比对」三件库级回归门。
- 索引共 206 件；无控制字节、无 ES5 违规；`allowHosts` 无通配。
- 适配器库提交 `afc40a5`，CI（harness + release）通过，发布 `v2026.11.10.1`（release 正文「206 个适配器」；`nullclass-adapters.zip` 9,251,406 字节，SHA-256 `f54d20cbec7bdf147f50a44d5ff6fdc61d895a80d439150f6c5aae73b6a8b3ff`，随附 `.sig` 为 CI 用 `ADAPTERS_SIGNING_KEY` 对该 zip 做的 ECDSA P-256 签名；`latest.json` = `{"version":"2026.11.10.1"}`）。

## 未验证风险

- 五件都没有本校账号，未访问真实教务 / 统一认证 / WebVPN，未做 Android 真机抽验；合成 fixture 与假站只能证明
  转换逻辑与防回归能力，不保证真实页面结构与接口外壳一定一致。
- `tongji` 的总周数字段名（`schoolCalendar/detail` 里）没有账号可验，代码按常见键名兼容读，读不到回落 20 周并在提醒里说明。
- `cqu` 的 `curSessionId` 层级、`session/info` 的 name/year/term 字段是按上游代码与同平台推的兼容读法。
- `jlu` 的 `scheduleUrlHint` 用 `/jwapp/sys/wdkb/*default/index.do`，模块首页的真实地址需真机确认；
  WebVPN 的会话 hex 只能从当前页带出，不能硬编码。
- `scnu` 的课时表与校区作息依赖教务返回的 `xqh_id`，多校区只保留行数最多那份作息（其余校区的课仍导入，
  节次时间可能对不上上课安排）；本校页面仅校园网 / 校内 VPN 可访问。
- `nankai` 一学期 20 周会产生 1 + 20 次同源请求，慢网下预算用完时后面的周会显示为「没有取到」；本校需校园网。
- `jlu`、`nankai` 的上游脚本最后修改提交与任务卡写的不一致（任务卡写的是多目录合看的哈希），
  各件 AUDIT 已按快照实际 `git log` 结果订正。
