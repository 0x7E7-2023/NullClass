# 移植上游教务适配器（批次十六 · 青果 + 强智 5 件）

日期：2026-10-20　模式：sonnet 子代理移植 / 审计；任务卡与公共说明在 `.test/port-batch16/`（gitignore），各件冒烟 `.test/port-batch16/<key>/smoke.py`

| key | 学校 | 上游（作者） | 平台 / 模板 | 要点 |
|---|---|---|---|---|
| `ahszu` | 宿州学院 | `AHSZU/ahszu_01.js`（星河欲转） | 青果 / `wzzy` | 裸 IP；上游无开学日请求 → 按学期推算并提醒；11 节 |
| `xmcu` | 厦门城市职业学院 | `XMCU/xmcu_01.js`（星河欲转） | 青果 / `wzzy` | WebVPN 子域名 `jws-443.webvpn.xmcu.edu.cn`；借 wzzy 的第 1 周日期请求（取不到推算） |
| `qfnu` | 曲阜师范大学 | `QFNU/qfnu_01.js`（Yumu-banxia） | 强智 / `gdcp` | 上游无节次时间 → 空课默认 12 节并提醒；简表 / 详表择优 |
| `hnptc` | 湖南邮电职业技术学院 | `HNPTC/hnptc_01.js`（小漫君） | 强智 / `gdcp` | 端口 10081；上游 10 节表（按 2025-2026 学年写死，每次提醒） |
| `hunau` | 湖南农业大学 | `HUNAU/hunau_01.js`（salt-fishes） | 强智 / `gzst` + `yssdufe` | 只经 WebVPN；节次时间从行标题反推 |

## 审计

- ahszu / xmcu reviewer 无发现。
- hunau B1（提示说校内可直连、实际直连会被宿主拦）、B2（停在班级 / 教室课表页会把别人的课表当本人导入）→ 只认 WebVPN 主机、只在 `xskb/` 页读屏幕课表，已改并加冒烟。
- hnptc B1–B4 全 CONFIRMED：归并键不含节次（丢课）、周历日期只取 title、简表缺教室多出重复课、隐藏元素文字随 extract 交出 → 全修；extract 改为严格白名单。
  同样输入在同族 qfnu 复现 B1（已修，并自查隐藏元素），已发布的 gdcp 不受影响。
- qfnu B1：行首标签只读两个数字（`(09,10,11小节)` → 10–11）→ 改为取全部数字的最小到最大。

## 发布

- `:importer:test --rerun` 全绿（121 件）；五件冒烟全部通过。
- 适配器库提交 `d41ece6`，`index.json` 版本 `2026.10.20.7`。
- CI run `39418827223` 通过，发出 `v2026.10.20.7`。
- 用户 10-20 要求本批 CI 完成后停止循环；下一批候选（未开工）：JXFU、NENU、CQTBI、AEPU、TCU、SANYEDU、HEBEU、CJLU。
