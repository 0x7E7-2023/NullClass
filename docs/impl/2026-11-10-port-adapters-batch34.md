# 移植上游教务适配器（批次三十四 · 5 所新校）

上游快照 `baf83fa`（与批次三十一～三十三相同；开工前 `git fetch` 确认 `origin/main` 无新提交，无上游跟进项）。
内置 216 → 221，库索引版本 `2026.11.10.4`。移植由 haiku 子代理完成，主调度复核、复审修正、集成。

| key | 学校 | 上游（作者） | 平台 / 模板 | 本校特有行为 |
|---|---|---|---|---|
| `hbmzu` | 湖北民族大学 | `HBMZU/hbmzu.js`（Haooz） | 自研 MVC 教务（`.mvc` 服务端渲染）/ 模板 `suda` | 理论课表零请求读 `#container`（含 frameset / iframe）；实验课表先读 `/report/myLabSchedule.mvc` 里的学号 / 学年 / 学期，再按周请求；12 节写死表；无开学日期，按今天所在周的周一推算并提醒 |
| `gdouyj` | 广东海洋大学（阳江校区） | `GDOU/gdouyj.js`（Yihe-ng，"原始 GDOU 适配" Mccurtain） | 正方 V9 + 学校零信任通道（`wfw.gdou.edu.cn/https://jw.gdou.edu.cn/`）/ 模板 `gdou-wfw` | 阳江校区 10 节作息；非慎思楼第 3-4 节实为 10:10–11:40 的连续块，按类型汇总提醒；上游新增的「学分」空课载荷没有对应字段，不移植 |
| `zjsru` | 浙江树人学院 | `ZJSRU/zjsru_01.js`（CagierAsh123） | 正方 ASP.NET WebForms（`table#Table1.schedule`）/ 模板 `suda` | 当前页不是课表页时自己同源拉一次（带 `xh`，学号只用于拼地址）；拱宸桥 / 杨汛桥两套 12 节表，杨汛桥虚拟第 5 节保证节次连续；校区判断不了才用 `__ncSelect` 问一次 |
| `njupt` | 南京邮电大学 | `NJUPT/njupt_01.js`（kemi-20） | 正方 V9 接口（`kbList` + 校历）/ 模板 `hueb` | 根相对路径（南邮 WebVPN 会二次改写带前缀地址）；学期取页面 `#xnm`/`#xqm` 选中项，不弹窗；集中实践课名单只提醒用户自己加 |
| `nwafu` | 西北农林科技大学 | `NWAFU/nwafu.js`（123zhuzhu61） | 金智 jwapp（APP_CODE `wdkbby`，「我的本研课表」）/ 模板 `sany` | 四请求（当前学期 → 学期列表 → 课表行 → 校历）；`ZCMC` 文字优先、`SKZC` 位串兜底；`cxjcs.do` 只有节数没有上下课时间，按今天日期在冬令 / 夏令两套写死表里选一套 |

## 复审与修正

每件一个 reviewer 复审，主调度逐条核对代码后确认是真问题才动手，每条修正都配用例：

- `gdouyj`（1 条）：括号备注原文（`noteSample`）没按 20 字截短就拼进提醒，长备注把「已忽略，请核对这几门课的周次」硬截断 →
  改走 `shortName`，`long-warnings` 用例里加一条约 100 字备注。
- `nwafu`（3 条）：①`ZCMC` 里出现「节」就整段截断，`第3-4节,1-16周` 只剩 3-4 周；②裸数字都当周号，`10-16周(3学分)` 多出第 3 周；
  ③单双标记整串只判一次，`(单)1-8周,9-16周` 的第二段被当单周。改为节次整段摘除、非周次括号整组摘掉、按逗号分段各自判单双。
- `njupt`（2 条）：①区间终点前的「第」没归一，`第1周至第8周` 被拆成 1、8 两个孤周（批次十 `swu` 同款）；
  ②括号备注原文没截短（与 `gdouyj` 同款）。
- `hbmzu`（2 条）：①登录页 / 门户页里恰好有个裸 `#container` 时，`findContainer` 的兜底会把它当课表页，理论课表为空却继续发实验课请求，
  用户拿不到「请先登录」提示；②实验入口页返回 200 但缺学号 / 学年 / 学期时，提醒却说「教务系统没有返回该页面」。前者改为「没有一行课 + 登录页 → 报错、零请求」，
  后者把 `http` / `structure` 两种原因分开报。冒烟新增 `login-bare-container` 场景。
- `zjsru`：无。主调度另把 `allowHosts` 从 `xk.jwc.zjsru.edu.cn` 放宽成 `*.zjsru.edu.cn`（经 WebVPN 打开时主机不同）。

## 验证

- 五件单项 Rhino 自检全 PASS：`hbmzu` 8 对、`gdouyj` 7 对、`zjsru` 7 对、`njupt` 8 对、`nwafu` 8 对，期望均先手推。
- 本地假站浏览器冒烟（Playwright 拦全部请求，本机走系统 Edge）：`hbmzu` 11/11、`gdouyj` 9/9、`zjsru` 12/12、
  `njupt` 9/9、`nwafu` 10/10 OK。
- 复审新增变异全部被用例抓住：`gdouyj` 1 处、`nwafu` 3 处（改回截断 / 不摘括号 / 不分段）、`njupt` 2 处、`hbmzu` 2 处。
- `./gradlew :importer:test --rerun-tasks`：BUILD SUCCESSFUL（210 个测试，含「索引与目录一致」「manifest 合法且 key 唯一」
  「全部 fixture 实跑比对」三件库级门）。
- 索引 221 件（库文件 4340 个，仍在客户端 8192 条目上限内）；无控制字节、无 ES5 违规；`allowHosts` 只有 `zjsru` 一条三段通配；
  集成检查（模板残留 / 开发者用语 / 提醒限长 / 署名与主机）`BAD 0`。

## 未验证风险

- 五件都没有本校账号，未访问真实教务 / 统一认证，未做 Android 真机抽验；合成 fixture 与假站只证明转换逻辑。
- 入口与 `scheduleUrlHint` 来自上游 yaml 与脚本推断：`hbmzu` 的 `mySchedule.mvc`、`zjsru` 的 `xskbcx.aspx`、
  `njupt` 的根相对课表页、`nwafu` 的 `wdkbby` 模块页都可能与本校实际不同，首次真机要核对。
- `hbmzu` 的实验页变量名（`id` / `openYear` / `openTerm`）与按周接口结构未在真实页核对；开学日与学期名在拿不到教务数据时是推算值。
- `gdouyj` 的「慎思楼」关键词与 10:10–11:40 错峰块来自上游，只有阳江校区真机能验；`zjsru` 的两套作息与「杨汛桥不设第五节」来自校历页面，
  校区判断按课表文字，判不出来会问一次，取消则按拱宸桥。
- `njupt` 的 `?xnxq=` / 根相对路径语义、`nwafu` 的 `ZCMC` 真实写法（未覆盖的格式会退回位串）都按上游代码推定。
- `nwafu` 冬令 / 夏令按今天日期选一套：学期中途换季时，前半学期的节次时间会用错，提醒里已说明。
