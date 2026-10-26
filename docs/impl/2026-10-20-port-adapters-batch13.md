# 移植上游教务适配器（批次十三 · 5 件混合平台）

日期：2026-10-20　模式：sonnet 子代理移植 / 审计；任务卡与公共说明在 `.test/port-batch13/`（gitignore），各件冒烟 `.test/port-batch13/<key>/smoke.py`

| key | 学校 | 上游（作者） | 平台 / 模板 | 要点 |
|---|---|---|---|---|
| `ctgu` | 三峡大学 | `CTGU/ctgu.js`（星河欲转） | 金智 jwapp / `nuist` | 调课停课（`xsdkkc.do`，按 KBID 对回原课）；上游三种静默情形改为计数提醒 |
| `njnu` | 南京师范大学 | `NJNU/njnu.js`（aryunm） | 金智 jwapp / `nuist` | 同上调课；`allowHosts` 只留 `ehallapp.nnu.edu.cn` |
| `sdwcvc` | 山东水利职业学院 | `SDWCVC/sdwcvc_01.js`（小漫君） | 树维 EAMS / `xatu` 1.1.0 | `calendar-info` 英文日期定开学日并与所选学期核对；只导入当前学期 |
| `jyvtc` | 济源职业技术学院 | `JYVTC/jyvtc.js`（星河欲转） | 青果（重写取数） | 双季作息按导入月份自动选（5–9 月夏季），另一季写提醒 |
| `xhsysu` | 广州新华学院 | `XHSYSU/xhsysu.js`（Haooz） | 正方 V9 根路径 / `gzist` | 联奕统一认证；上游「整串含单双就全局套用」改为按位置归属 |

## 审计

- ctgu / xhsysu / sdwcvc reviewer 无发现。
- jyvtc B1（推出的学期没课就静默改取上一学期）、B2（空学期按 mykb 行判断、报错说错）→ 主调度决定去掉回退、按格子内容判断、整学期没课报大白话提示，已改。
- njnu B1：`allowHosts` 多列了脚本从不请求的门户 `hsnnu.nnu.edu.cn` → 删掉。
- ctgu / njnu / jyvtc 不写 `scheduleUrlHint`（金智模块页作跳转目标会 403；青果上游没给课表页地址）。

## 发布

- `:importer:test --rerun` 全绿（106 件）；五件冒烟全部通过。
- 适配器库提交 `9700a47`，`index.json` 版本 `2026.10.20.4`。
- CI run `39401455284` 通过，发出 `v2026.10.20.4`。
