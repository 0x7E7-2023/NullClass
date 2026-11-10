# 移植上游教务适配器（批次三十五 · 5 所新校）

上游快照 `baf83fa`（与批次三十一～三十四相同；开工前 `git fetch` 确认 `origin/main` 无新提交，无上游跟进项）。
内置 221 → 226，库索引版本 `2026.11.10.5`。移植由 haiku 子代理完成，每件一个 reviewer 复审，主调度逐条核对代码后修正、集成。

| key | 学校 | 上游（作者） | 平台 / 模板 | 本校特有行为 |
|---|---|---|---|---|
| `sustech` | 南方科技大学 | `SUSTECH/sustech.js`（yusancky） | TIS 教学管理与服务平台 + CAS，同源 POST 接口 / 模板只借 `nwafu` 的「登录页零请求 + 接口取数」骨架 | 四请求（当前学期 → 整学期课表 → 作息 → 当前教学周）；`ZC` 是 0/1 位图、`jc` 是网格行号，节次用 `KSJC`/`JSJC`；课名 / 教师 / 地点从 `SKSJ` 多行文本取（`SKSJ_EN` 回落）；无校历，总周数 20；上游成绩接口 `/cjgl/grcjcx/grcjcx` 不移植 |
| `scuec` | 中南民族大学 | `SCUEC/scuec.js`（Zellon0w0） | 正方 ASP.NET WebForms 课表页（`table.CourseFormTable`）+ WebVPN，零请求 / 模板 `zjsru` | 只读当前页；课程块按 `<hr>` 切、星期来自表头列；节次时间页面优先，缺的节按学校 11 节表逐节补、缺的才顺推；周次解析要求文字里真出现周 / 单 / 双 |
| `yzu` | 扬州大学 | `YZU/yzu.js`（Thanatos） | URP 综合教务 + 网瑞达 WebVPN / 模板 `lnu` | 单请求 `ajaxStudentSchedule/callback`；`classWeek` 0/1 位串、`continuingSession` 连续节；校历 `GET /indexCalendar` 只取 `var rq` / `var skzc`（同 `lnu`），取不到回落并提醒 |
| `cqust` | 重庆科技大学 | `CQUST/cqust_01.js`（yuexps） | 树维 EAMS 旧版课表页（`courseTableForStd.action` / `dataQuery.action` / `!courseTable.action`）/ 模板 `cupk`、取数沿 `sjzu` | 课程从内嵌 `TaskActivity` 脚本读；学期从学期栏 → `semesterCalendar(...)` → 学期列表推导，推不出就报错不猜；未安排时间的课程只提醒；WebVPN 前缀从当前地址拼 |
| `imut` | 内蒙古工业大学 | `IMUT/IMUT_01.js`（wuko233） | 自研 MVC/JSP 服务端渲染，GBK / 模板 `bjtu` | 三请求（`currcourse.jsdo` → `studentWeeklyTimetable.do` → `showTimetable.do`）；GBK 用 `TextDecoder` 解码；开学日用第 1 周页真日期；总周数用教务 `whichWeek` 最大周 |

## 复审与修正

每件一个 reviewer 复审，主调度逐条核对代码后确认是真问题才动手，每条修正都配用例：

- `yzu`（4 条，主调度直接修）：①缺教师 / 缺地点 / 缺连续节数的名单在处理循环里就记账，整门课后来被跳过也照样写「已照常导入」；
  ②重复安排的「缺连续节数」条数在去重前累加，3 条相同行报 3 条；③1 月落进「8–12 月」分支，2027-01 被推成 2027-2028 学年；
  ④校历没取到、选中的又不是当前学期时，提醒仍称「校历给的是当前学期的周数」。改为裁剪后按最终导入的课重算名单、去重后计数、
  `month === 1` 归上一学年、只有真取到校历值才说校历。新增 `dedupe-and-dropped` / `january-term-name` / `calendar-absent` 三对用例。
- `sustech`（3 条）：①`initial` 应为 `N`（任务卡笔误写成 S）；②缺 `scheduleUrlHint`，一键刷新会落到 CAS 登录页——TIS 课表页是 CAS 后的单页应用、
  没有稳定深链，填教务系统入口 `https://tis.sustech.edu.cn/` 并在 AUDIT 说明；③丢了上游 `SKSJ_EN` 英文回落，恢复并补 `english-text` 用例。
- `imut`（4 条）：①周次列表没读到时仍写「教务系统的周次列表只到第 20 周」，把兜底值说成教务给的；②当前页是逐周页时跳过学期页，
  课表地址丢了 `id=`；③教师行尾逗号没去掉，同一门课拆成两个人；④节次摘除只认半角且在「至/到」与全角归一之前，`第3至4节` 多出第 3 周。
  改为按来源分两支提醒、跳过条件收紧成「当前页就是 showTimetable 且 id 齐全」、去教师尾逗号、先把「至/到 / 全角」归一再整段摘节次。
- `cqust`（2 条）：①`courseDataOnly` 的兜底把整页 HTML 交出去，页头「学号 / 姓名」跟着泄露（绕过字段白名单）；
  ②连堂合并按「周次重合 ≥30%」并段，部分重合的相邻节被并成并集、多算节次。前者改为只交能定位的 `TaskActivity` 片段 + 交付前二次抹个人信息，
  取不到就按「页面结构不对」报错；后者改成周次集合完全相同才合并。冒烟新增 `leak` / `scrub` / `no-task` 三场。
- `scuec`（3 条）：①前置 `(单)/(双)` 没跟节次标记时被切进课名，单双丢失且不提醒；②节次补齐只覆盖「页面首个节次之后」，
  课表用到的更早节次不补；③教室编号正则必须有连字符，`A305` 这类会被当成教师。改为标记归属周次文本、补齐覆盖课表用到的全部节次、
  教室编号放宽到无连字符写法。
- 集成口径：`scheduleUrlHint` 确认非必填（库内已有 40 件没填，规范表里可缺省），任务卡的写法按实际能确认的地址处理；
  四件的 `author` 在任务卡里被我写成 `0x7E-2023`，统一改回全库一致的 `0x7E7-2023`。

## 验证

- 五件单项 Rhino 自检全 PASS：`sustech` 8 对、`scuec` 11 对、`yzu` 11 对、`cqust` 8 对、`imut` 8 对，期望均先手推。
- 本地假站浏览器冒烟（Playwright 拦全部请求，本机走系统 Edge）：`sustech` 8/8、`scuec` 9/9、`yzu` 11/11、`cqust` 12/12、
  `imut` 10/10 OK。
- 五件都做了变异测试并还原（`sustech` 7 处、`scuec` 7 处、`yzu` 7 处、`cqust` 5 处、`imut` 6 处），正式目录复跑 PASS。
- `./gradlew :importer:test --rerun`：BUILD SUCCESSFUL（210 个测试，含「索引与目录一致」「manifest 合法且 key 唯一」「全部 fixture 实跑比对」三件库级门）。
- 索引 226 件（库文件 4452 个，仍在客户端 8192 条目上限内）；无控制字节、无 ES5 违规；
  集成检查（模板残留 / 开发者用语 / 提醒限长 / 署名与主机）`BAD 0`。

## 未验证风险

- 五件都没有本校账号，未访问真实教务 / 统一认证，未做 Android 真机抽验；合成 fixture 与假站只证明转换逻辑。
- `sustech`：TIS 四种响应外壳（数组 / `content` / `data` / 纯数字）、`SKSJ` 行格式均按快照推定；无校历固定 20 周，18 周学期末尾会多两周空白；
  学期接口失败时按空参数查课表，依赖 TIS 默认当前学期。
- `scuec`：`table.CourseFormTable` 的课程块形状全部照上游；教室编号「字母 + 数字」的判断是猜的，两行顺序反了也可能读错；不支持 iframe。
- `yzu`：WebVPN 只覆盖 `/http<…>/<加密主机>/` 一种形状；校历正则照 `lnu`，结构不同会回落并提醒；学期只认下拉框选中项。
- `cqust`：`TaskActivity` 参数位置与学期栏形态按同族 EAMS 件推定；`scrubPersonal` 只认带标签的值与裸号码，脚本里无标签的姓名变量抹不到（靠只交片段避免带出）。
- `imut`：GBK 页面、`table#timetable`、`td0` 日期格式、夏学期 `term=2` 的开学日锚点都按推算处理并在提醒里写明；
  缺地点且只写教师时该行会被读成地点（与上游一致）。
