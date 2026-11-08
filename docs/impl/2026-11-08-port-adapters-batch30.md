# 移植上游教务适配器（批次三十 · 5 所新校）

上游快照 `29498b0`（与批次二十八、二十九相同，期间上游无新提交，本批没有跟进件）。内置 196 → 201，库索引版本 `2026.11.08.1`。
移植由 haiku 子代理完成，主调度复核、复审修正、集成。

| key | 学校 | 上游（作者） | 平台 / 模板 | 本校特有行为 |
|---|---|---|---|---|
| `gxcme` | 广西机电职业技术学院 | `GXCME/gxcme.js`（Haooz） | 正方 V9 Mobile / `hueb` | SSO 入口 `sso.gxcme.edu.cn`，教务主机 `nportal.gxcme.edu.cn`；课表走 `xskbcxMobile_cxXsKb.html`（`gnmkdm=N2154`），页面已有学期下拉框时跳过首页请求；本校 14 节表；周次校历可缺，缺时按学期锚点推开学日，校历第 1 周距锚点超 45 天整份作废 |
| `hhvtc` | 怀化职业技术学院 | `HHVTC/hhvt.js`（Haooz） | 青果（隐藏详情区）/ 骨架借 `hlju` | `.have-content` 可见格可能为空，真课在 `weekly0*` 隐藏详情；上课时间集中写成 `[周次]星期[起止节次]`；节次表按提取月份自动选，不问用户；同源 `allowHosts: []` |
| `sany` | 湖南三一工业职业技术学院 | `SANYEDU/sany.js`（Haooz） | 金智 `jwapp/sys/wdkb` / 骨架借 `hlju` | 两个同源 POST（学期 `cxjcs.do` + 课表 `xskcb.do`，均带 `?enlink-vpn`），支持 `/jwapp` 前的路径前缀；周次是 `SKZC` 位串；学期靠「最近已开学」启发式选；本地存储里的 JWT 只进同源请求头、绝不交出；10 节表 |
| `sddfvc` | 山东药品食品职业学院 | `SDDFVC/sddfvc.js`（星河欲转） | 移动端逐周 JSON / 骨架借 `wzzy` | CAS 登录、`fazhan` 门户只是回跳，两者零请求；`api_token` 只从当前课表页查询串读出，只拼进 `jwxt.sddfvc.edu.cn` 的三个同源 GET；逐周串行取数；槽位 `1/3/5/7/9/11` 映射六档大节，不是把原始槽号当节次 |
| `zjbti` | 浙江工商职业技术学院 | `ZJBTI/zjbti.js`（LanRhyme） | 青果 + WebVPN / `wzzy` | 入口 `https://webvpn.zjbti.net.cn`，任意教务页执行；响应按 GBK 解码；读周历定开学日；不可见课程格要判样式表，`DOMParser` 文档不插页面 |

## 复审与修正

每件一个 reviewer 复审，主调度逐条核对代码后确认是真问题才动手，每条修正都配「独立手推期望的 fixture + 能捕获它的变异」：

- `zjbti`（安全，隐私回归）：新版 Chromium 因 CSS 嵌套给普通规则也挂了空 `cssRules`，`rulesHide()` 于是把每条 `display:none` 规则当成样式表分组跳过——隐藏详情区里的学生姓名、教室和SECRET 文本会照样导出。这是早先加 `groupApplies` 门时引入、且在加门之前就潜在的问题。改为先按 `selectorText` 派发、对嵌套子规则一律不递归（拿不准的规则一律不据此隐藏，宁可多导不隐藏），新增浏览器场景 `media-print-hide` 与浏览器侧变异 B1/B2。全仓只有 `zjbti` 碰 `cssRules`，不是系统性问题。
- `gxcme`：`第1-第2节` 这类区间两侧都带 `第` 的连写节次整行丢课（丢光时明确报「这个学期没有解析到任何课程」，不静默出空学期）；`plausibleStart` 的 45 天校历作废门未生效，错误学期的校历日期被直接采用。新增 `sections-glued`、`calendar-rejected`。
- `hhvtc`：一个格子里连写第二个节次段时只导入第一段且毫不出声（现计数提醒，原文请用户核对）；前置单双标记被逗号/空格分隔时归属丢失，`[单，9-16周]` 退成每周。新增 `multi-segment`、`marker-before-separator`。
- `sddfvc`：第二学期年份多减一年的分支删掉（第一学期 1～6 月跨自然年才是唯一需要 +1 的情形）；失败周提醒原先最多列 10 周却不说是几周，现补「等共 N 周」；排课项没有明细（`pkmx` 为空）时整条静默丢，现按内容去重后计入「有 N 条课程记录没能导入」。新增 `second-july`、`failed-many`、`empty-details`。
- `sany`：`ZZC` 为空时不读备用总周数 `ZJXZC`（退成默认 20）；`XQKSRQ` 非法时没回退到本机日期所在周周一；`ZZC=40` 超过载荷上限 30 未截断。新增 `alt-weeks`、`start-invalid`、`weeks-clamped`，并删掉 `extract.js` 里没有引用的 `SEMESTER_FIELDS` 常量。
- 台账同步：五件 `AUDIT.md` 的 fixture 计数、变异表、SHA-256、冒烟结论与签名全部按本轮结果重写；`sany`、`gxcme` 的变异脚本首次留档为 `.test/port-batch30/<key>/mutate.py`（改坏只发生在系统临时目录的副本，且要求目标串恰好命中一次）。

## 验证

- 五件单项 Rhino 自检均 PASS（`gxcme` 13 对、`hhvtc` 10 对、`sany` 9 对、`sddfvc` 11 对、`zjbti` 13 对），全部为合成数据，期望先手推再比对。
- 本地假教务站浏览器冒烟（Playwright route 全拦截，真实跑 extract.js 再交 Rhino 跑 parse.js）：`gxcme` 12/12、`hhvtc` 7/7、`sany` 10/10、`sddfvc` 14/14、`zjbti` 27/27 OK。本机随带的 Chromium 内核已缺失，harness 统一支持 `NC_PW_CHANNEL=msedge` 走系统 Edge，不设该变量时行为不变。
- 变异测试：`gxcme` 11/11、`hhvtc` 7/7、`sany` 7/7、`sddfvc` 8/8、`zjbti` 12/12 被捕获（其中 2 处在浏览器侧），改坏后均已还原并按 SHA-256 复核原文件未变。
- `./gradlew :importer:test --rerun-tasks`：BUILD SUCCESSFUL（206 个测试，0 失败），含「索引与目录一致」「manifest 合法且 key 唯一」「全部 fixture 实跑比对」三件库级回归门。
- 索引共 201 件；无控制字节、无 ES5 违规；`allowHosts` 无通配。
- 适配器库提交 `6efcc92`，CI（harness + release）通过，发布 `v2026.11.08.1`（release 正文「201 个适配器」；`nullclass-adapters.zip` 9,032,117 字节，SHA-256 `e22d3bf2aa491b487fda6c9a4daee58433a9e90b3acafd0d8178648f09f64389`，随附 `nullclass-adapters.zip.sig` 为 CI 用 `ADAPTERS_SIGNING_KEY` 对该 zip 做的 ECDSA P-256 签名；`latest.json` = `{"version":"2026.11.08.1"}`）。

## 未验证风险

- 五件都没有本校账号，未访问真实 SSO / CAS / WebVPN，未做 Android 真机抽验；合成 fixture 与假站只能证明转换逻辑与防回归能力。
- `sddfvc` 的 `scheduleUrlHint` 不带令牌，若学校必须由门户动态签发令牌，一键刷新仍需用户重新经门户进入。
- `zjbti` 的可见性判定依赖引擎的样式表形状；已把「规则拿不准就不隐藏」定为默认取向，代价是极端情况下多导一门课，而不是把别人的名字交出去。
- `sany` 沿用上游、不臆造分页；若真实课表分页，只能读到响应里已有的 `rows`。
- `gxcme` 的教务主机取自 SSO 的 service 地址，登录后若跳其他主机需按真实跳转调整 `scheduleUrlHint/allowHosts`。
- `hhvtc` 节次表按提取月份自动选择，学校调整作息或查询其他学期时需核对；`sany`、`sddfvc`、`gxcme` 的表外节次按课间 5 分钟、每节 45 分钟顺推并提醒。
