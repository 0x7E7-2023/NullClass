# 移植上游教务适配器（批次十五 · 5 件混合平台）

日期：2026-10-20　模式：sonnet 子代理移植 / 审计；任务卡与公共说明在 `.test/port-batch15/`（gitignore），各件冒烟 `.test/port-batch15/<key>/smoke.py`

| key | 学校 | 上游（作者） | 平台 / 模板 | 要点 |
|---|---|---|---|---|
| `gxmu` | 广西医科大学 | `GXMU/gxmu.js`（Haooz） | 乘方智慧教务 / `shxy` | cas 登录、教务主机 `jwxt.gxmu.edu.cn` 进 allowHosts；上游 12 节表；不读 `jxcdmc2` |
| `sdipct` | 山东石油化工学院 | `SDIPCT/sdipct.js`（星河欲转） | 乘方 / `hnsf` | 只经 WebVPN（前缀照上游写死 + `vpn-12-o2-…`）；10 节单表；`jxcdmc2` 拆课补周 |
| `smxy` | 三明学院 | `SMXY/smxy.js`（Haooz） | 正方 V9 / `xhsysu` | 门户进教务；教务主机按公开搜索定为 `zhjw.fjsmu.edu.cn`（别名 `zhjw.smxy.cn`），`/jwglxt` 未核实；上游无节次时间 → 内置 12 节并提醒 |
| `aufe` | 安徽财经大学 | `AUFE/aufe_01.js`（星河欲转） | URP / `lnu` + `swu` | 课表地址从页面读、只认固定形状路径（令牌段可选）；节次时间读 `getSectionAndTime`，取不到用空课默认 |
| `syist` | 沈阳科技学院 | `SYIST/syist_01.js`（星河欲转） | 金智 jwapp / `gscmxy` + `nuist` | 端口 30334（不进 allowHosts）；合入实验课表；`jc.do` 节次时间不合法整表回落 |

## 审计

- gxmu / sdipct / syist reviewer 无发现。
- smxy B1：AUDIT §1 的 allowHosts 写窄了 → 已改。
- aufe B1：课表地址校验只查关键词、可被站内其它路径（含查询串）冒充 → 收紧为固定路径形状，冒烟加反例；B2：allowHosts 多列门户与 WebVPN 网关 → 删掉。

## 发布

- `:importer:test --rerun` 全绿（116 件）；五件冒烟全部通过。
- 适配器库提交 `76ff303`，`index.json` 版本 `2026.10.20.6`。
- CI run `39409057215` 通过，发出 `v2026.10.20.6`。
