# 移植上游教务适配器（批次十四 · 5 件混合平台）

日期：2026-10-20　模式：sonnet 子代理移植 / 审计；任务卡与公共说明在 `.test/port-batch14/`（gitignore），各件冒烟 `.test/port-batch14/<key>/smoke.py`

| key | 学校 | 上游（作者） | 平台 / 模板 | 要点 |
|---|---|---|---|---|
| `gscmxy` | 甘肃财贸职业学院 | `GSCMXY/gscmxy.js`（ShallowY） | 金智 jwapp / `ctgu` | 与 ctgu 同源；教室拼校区；10 节 40 分钟 |
| `wzzy` | 梧州职业学院 | `WZZY/wzzy_01.js`（星河欲转） | 青果（重写取数） | 裸 IP:805；端口不进 allowHosts（宿主只比主机名，请求同源）；GBK 整页 |
| `scut` | 华南理工大学 | `SCUT/SCUT_01.js`（dezige131） | 正方 V9 / `xhsysu` + `hueb` | 只经 WebVPN 子域名；前缀读 `window._path`（只认安全形状）；新增校历 |
| `shxy` | 绥化学院 | `SHXY/shxy.js`（Haooz） | 乘方智慧教务（重写取数） | 校区按教室名「厚德/崇德」自动判，判不了问一次；`jxcdmc2` 拆课三处防护 |
| `hnsf` | 河南师范大学 | `HNSF/hnsf_01.js`（星河欲转；02 不做） | 乘方智慧教务（重写取数） | 三张节次表问一次，未答按排课行时间自动判；第三张表上游叫「创新港校区」，未核实 |

## 审计

五件 reviewer 均无发现。`gscmxy` / `wzzy` / `shxy` 不写 `scheduleUrlHint`（上游没给可验证的课表页地址）。

## 发布

- `:importer:test --rerun` 全绿（111 件）；五件冒烟全部通过。
- 适配器库提交 `13365eb`，`index.json` 版本 `2026.10.20.5`。
- CI run `39405005071` 通过，发出 `v2026.10.20.5`。
