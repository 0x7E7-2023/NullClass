# 跟进 xatu 上游改动 + 移植上游教务适配器（批次十 · 5 件正方）

日期：2026-10-20　BASE：主仓库 `f2f3fdd`、适配器库（`jw-adapters/` 子模块）`dbd72cc`
模式：/stage 轻量模式 —— **xatu 由主 agent 改；五件新校 haiku 并行移植；每件 fresh reviewer → adversary 审计**

> 用户 2026-10-20 要求：「跟进xatu的改动，然后再完成五个适配器移植。」
> 纯适配器改动，只发适配器库，应用本体不发版；用户已经明确要求开工，本文档视同点头。

## 一、xatu 跟进上游

上游 `XATU/myschool.js`（晨熯）在我们移植（快照 `e62554a`，2026-09-12）之后改了两次：
`b91e3fa`（10-10：学分、按当前教学周推开学日、可选导入考试）与 `983137a`（10-18：精简重构，**删掉了考试导入**）。
以 `80e30cf`（09-23，与移植时的脚本一致）对 `19a65bf` 的净差异为准，逐条处理：

| 上游改动 | 处理 |
|---|---|
| 从教务首页 `homeExt!main.action` 的「当前教学周 N / 共 M 周」+ 服务器日期推第 1 周周一与总周数；推出的学年学期与所选学期不符时不用 | **跟进**：extract.js 多一条可选 GET，只交出周次两个数字与服务器日期（不交首页全文）；parse.js 在学期日历没给起止日期时用它，学年学期对不上就不用，推算值写进提醒 |
| 合并键加课程序号（`args[2]` 末尾括号），同名不同序号的课不再并到一起 | **跟进**（只进合并键，不像上游那样拼进课名） |
| 每条活动取它前面最近的 `actTeachers`（同一块里多条活动各有各的老师） | **跟进**：按活动取教师，块里找不到再退回整块 |
| 教 3 楼第 3–4 节改 10:10–11:40（上游两版都保留） | 载荷仍没有单节自定义时间；按手册 §4.4 **改为写提醒**（原来只记在 AUDIT），并修正 AUDIT 里「早 10 分钟」的错误描述 |
| 学分（课表页「学分」列，按课程序号对） | 不跟：载荷没有学分字段 |
| 学期选择弹窗显示「秋季 / 春季」、默认选当前学期 | 不涉及：我们不弹窗，直接用教务当前学期 |
| 请求 15 秒超时 | 不跟：宿主有统一超时 |
| 考试导入（10-10 加、10-18 删） | 不涉及 |

顺带：xatu 是批次四的件，提醒里还有「unitCount」「位图」「上游脚本」「适配器」这类开发者用语，按 `docs/ux-writing.md` 一并改成大白话。
版本 `1.0.0` → `1.1.0`，fixture 按新口径补齐（开学日从当前教学周推、教 3 提醒、同块多教师、课程序号分组）。

## 二、批次十：五件新校

上游快照仍是 `19a65bf`（10-20 复核无新提交）。按 `.test/port-batch8/survey.py` 重排剩余脚本（`.test/port-batch8/survey10-out.txt`），
挑正方件里主机明确、模板相近的五所：

| key | 学校 | 上游（作者） | 本校脚本提交 | 模板 | 要点 |
|---|---|---|---|---|---|
| `czimt` | 常州机电职业技术学院 | `CZIMT/czimt.js`（ShallowY） | `80e30cf` | `gdust` | 「校内通」WebVPN `/http/<hex>/` + `vpn-12-o1-jwc.czmec.cn`，校内直连 `jwc.czmec.cn`；教室带校区、课名带学时类型标记 |
| `swu` | 西南大学 | `SWU/swu.js`（小漫君） | `91c79f1` | `hueb` | 办事大厅门户进 `jw.swu.edu.cn/jwglxt`；重修 / 选课备注进 note；周次写法宽；`qsxqj` 每周起始日；14 节 |
| `shufezj` | 上海财经大学浙江学院 | `SHUFEZJ/shufezj.js`（ZnonEn） | `80e30cf` | `gdust` + `gzmtu` | 根路径；先 `cxXsKb` 后 `cxXsgrkb`；不带学号参数；`{…}` 周次；13 节 |
| `gzist` | 广州理工学院 | `GZIST/gzist_01.js`（YT-O5） | `ccd43dd` | `hueb` | 页面改写了 `filter` / `trim`；WebVPN 明文前缀 `/https/jw.gzist.edu.cn`；**不请求**作息接口（上游定的）；12 节 |
| `hualixy` | 广州华立学院 | `HUALIXY/hualixy.js`（小漫君） | `80e30cf` | `hueb` | 教务任意页可提取；菜单号 `N2151` / `N253508` 依次试；节次时间只来自教务（`{data:[…]}`、`sksj`），取不到用空课默认 |

没选的近邻：`GXCME`（教务主机上游没给）、`DLU`（WebVPN 主机映射不明）、`QDU` / `HNU` / `GZST`（强智，留给强智批）、`LYU`（EnLink VPN）。

任务卡在 `.test/port-batch10/card-<key>.md`，公共说明 `.test/port-batch10/prompt-common.md`（已 gitignore）。

## Files

- 改 `jw-adapters/xatu/`（extract.js、parse.js、AUDIT.md、manifest.json、fixtures/）；
- 新增 `jw-adapters/{czimt,swu,shufezj,gzist,hualixy}/`；
- `jw-adapters/index.json`：xatu 版本 1.1.0，加 5 条，`version` 升到 `2026.10.20.1`（同日第二次发版；若版本号格式不允许就用下一天）。

## 审计

stage 轻量模式：xatu 与五件各一个 fresh reviewer → 非空清单交 fresh adversary → CONFIRMED 修、主 agent 复跑自检、冒烟与 `:importer:test`。

## Out of scope

真机验证（没有六校账号）；强智族；模板 `lcudcc` 回填。

## Done when

- xatu 1.1.0 与五件新校自检全部 PASS，浏览器冒烟通过，`./gradlew :importer:test` 全绿；
- reviewer + adversary 跑完，CONFIRMED 修掉；
- 适配器库推送并发 release（应用不发版）。

## 实施与审计记录（实测，2026-10-20）

- xatu 1.1.0：8 对 fixture PASS（新增 3 对，期望手推后比对全部 MATCH），变异 5 处全红；浏览器冒烟（`.test/xatu-110/smoke.py`）2/2；reviewer 无发现。
- 五件新校（haiku 移植）：自检全部 PASS（czimt 6、swu 8、shufezj 6、gzist 7、hualixy 7 对）；浏览器冒烟 `.test/port-batch10/smoke10.py` 22/22；`:importer:test --rerun` 全绿（91 件）。
- 审计：czimt / shufezj / gzist / hualixy reviewer 无发现；swu B1（`第1-第8周` 被拆成两端两周）adversary CONFIRMED，已修并加用例 `range-di`。
- 主调度裁定：swu / hualixy 总周数照模板（校历优先，不跟上游「至少 20 周」）；shufezj 附加请求只认顶层数组（教务若套外壳则回落内置节次时间并提醒，不会导错）；gzist 去掉空课默认 12 节（本校表就是 12 节）。
- 集成时把三件模板遗留的「适配器内置」提醒改成「学校公布的节次时间」。
- 适配器库提交 `1b6f569`；CI run `39381780939` 通过，发出 `v2026.10.20.1`。
