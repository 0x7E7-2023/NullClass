# 复核并修复五件适配器的正确性问题（库 `2026.11.09.1`）

日期：2026-11-09　适配器库提交 `b6a552e`　库版本 `2026.11.09.1`（发布 `v2026.11.09.1`）

## 背景

对 `gxcme`（广西机电）、`dlu`（大连大学）、`zjbti`（浙江工商职院）、`hnsf`（河南师大）、`qau`（青岛农大）五件做正确性复核：一主四辅并行，逐件读 `extract.js` / `parse.js` / `manifest` / `AUDIT`，与各自上游快照对拍，并用 Node 直接实跑 `parse.js` 做探针。复核没有发现高危问题；确认的问题按「中 → 低」修了四件，`zjbti` 只有一条无法用只读脚本修的外链 CSS 限制，落到 AUDIT。

复核时的共同基线：五件 fixture 全部 MATCH（54 对）、`./gradlew :importer:test --rerun-tasks` 绿；安全面（请求同源、无凭据/存储/动态执行、无绝对 URL、`allowHosts` 与请求目标一致）未发现问题。fixture 全为合成数据，无本校账号，真实页面形状未验证。

## 修了什么

| 适配器 | 问题 | 修法 | 新增 fixture |
|---|---|---|---|
| `qau` | 开学日按「导入当周周一」推算，学生在第 N 周导入时整学期日期后移 N−1 周（与批次二十九给 `btbu` 修的是同一类问题） | `termStartFromCode()`：按 `term.code` 的 `2026-2027-1/2` 推 9 月 1 日 / 次年 2 月 20 日所在周周一；学期号认不出才退回提取当周周一，warning 写明锚点 | 既有 12 对全部更新 `firstDay` 与提示；`period-12`、`weeks-gong` |
| `qau` | 第 12 节起的占位作息写死 `pad2(7 + 节次)`，第 12 节补成 19:00-19:45，早于第 11 节 19:45-20:30（`TimelineGeometry` 会把 11-12 节画成 19:45-20:15） | 改为接上一节下课顺推（课间 5 分钟、每节 45 分钟），越过 23:59 就不补并单独提醒 | `period-12` |
| `hnsf` | 无逗号前置单双 `(单)1-8周(双)9-16周` 被读成 1-16 每周都上（模板 `swu` 已有修正分支，移植时漏掉） | 补上 `swu/parse.js:338-341` 的「本段以前置标记开头则归下一段」分支 | `weeks-prefix-glued` |
| `dlu` | `kbList` / `sjkList` 整行转交，与 AUDIT「只保留排课…学生信息不带出」不符；`xqh_id` 改为从原始响应取，白名单保持干净 | `pickFields()` 白名单：`kcmc/xm/cdmc/xqj/jcs/zcd`（实践课只 `kcmc`） | `weeks-gong` |
| `dlu` | 周末备注是课程级且无条件，工作日块也背周末备注；表外节次也照贴 | 文案改为「本课程含周末上课安排…周末节次不在上述范围时按周一至周五时间显示」 | `weekend`、`no-calendar` 更新备注 |
| `gxcme` / `hnsf` / `dlu` / `qau` | 「共 N 周」剥离在全角转半角之前：`共１６周` 漏网。gxcme 整行丢课（上游能读对）、dlu/qau 多算一周、hnsf 多算一周 | 剥离移到 `fullWidthToHalf` 之后；qau 另补全角数字转半角 | 各 1 对 `weeks-gong*` |
| `zjbti` | 外链 `<link rel=stylesheet>` 不被 DOMParser 加载，其中的隐藏规则不参与清洗；AUDIT 的「隐私回归已修」对这类页面不成立 | 只读脚本无法可靠加载外链 CSS，不写代码；AUDIT §4 限定为内联/内嵌样式，§9 记入风险 | 无 |

## 没有修的（已知取舍或无法只读修）

- `zjbti` 表头用 `<td>` 时多一条杂散格提醒、6 天表按 7 天错位、表尾多一列丢周一课、`MAX_PERIOD=999` 放行异常节次、周历首日非周一时取到后一个周一：均为上游同款启发式或条件触发，需真机页面形状确认后再定，未动。
- `qau` 隐藏 `kbcontent` 为空时整格课丢失、格内多段节次拉成 min..max、无逗号两段前置单双只留 1-8、首列 `rowspan` 续行误报节次标题：条件/语义取舍，本轮未改。
- `dlu` `第1-第2节` / `第1-第8周`、`(单) 1-8周`：与 `docs/impl/2026-10-27-fix-prefix-week-markers.md` 记录的既有取舍一致，未另开修。

## 验证

- 本地：`./gradlew :importer:test --rerun-tasks` BUILD SUCCESSFUL；五件 fixture 60 对（gxcme 14 / dlu 6 / zjbti 13 / hnsf 13 / qau 14）全部 MATCH；新增 fixture 的期望值先手推再比对（未从 parse 输出回填）。
- 适配器库 CI：run `42645358886` 通过（harness 1m16s + release 10s）。
- 发布 `v2026.11.09.1`：`nullclass-adapters.zip` 9,051,878 字节，SHA-256 `c1bd2c32dd35f32e26e3d544b69145f5e1598e973f9414ae1a62546f2c84bcda`；`nullclass-adapters.zip.sig` 为 CI 用 `ADAPTERS_SIGNING_KEY` 做的 ECDSA P-256 签名，已用 `JwOfficialLibrary.PUBLIC_KEY` 经 `openssl dgst -sha256 -verify` 校验为 `Verified OK`；`latest.json` = `{"version":"2026.11.09.1"}`；release 正文「201 个适配器」。
- 索引：`index.json` 版本 `2026.11.09.1`，`qau` / `hnsf` / `dlu` / `gxcme` 升 `1.0.1`；`zjbti` 仅文档修订，版本不变。

## 未验证

- 五件仍无本校账号与真实页面抓包：qau 的学期号形状（`2026-2027-1`）、学期锚点与学校实际开学日的偏差（一般在一周内，导入预览会提示核对）、dlu 真实 `kbList` 是否夹带学生字段、hnsf 真实 `zc` 写法、zjbti 外链 CSS 是否存在，都只能靠用户反馈迭代。
- `qau` 的开学日改动会改变所有既有 fixture 的期望值；真机导入后仍应在过渡期核对学期管理里的开学日。

签名：0x7E7-2023（2026-11-09）
