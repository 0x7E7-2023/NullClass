# 移植上游教务适配器（批次二十五 · 5 所新校）

上游快照仍为 `03d161a`。内置 170 → 175，库索引版本 `2026.11.02.2`。

| key | 学校 | 上游（作者） | 平台 / 模板 | 本校特有行为 |
|---|---|---|---|---|
| `njfu` | 南京林业大学 | `NJFU/njfu_01.js`（Mercury，`1fe584c`） | 强智旧版 / `jsnu` | 多行课名、教室清理；白马校区前四节不同 |
| `lidapoly` | 上海立达学院 | `LIDAPOLY/lidapoly.js`（Haooz，`63d7600`） | 强智旧版 / `huse` | 独立 `/shldzyjsxy_jsxsd/`；班级字段周次；完整 16 节 |
| `sdufe` | 山东财经大学 | `SDUFE/sdufe_01.js`（Kurisu90，`e9edb51`） | 强智旧版 / `huse` | 中文数字节次、教师职称清理、POST 周历 |
| `wust` | 武汉科技大学 | `WUST/wust.js`（xsddszrc，`4e99525`） | 强智旧版 / `jsnu` | 同源 iframe、POST 取数、黄家湖/青山校区选择 |
| `hzu` | 菏泽学院 | `HZU/hzu.js`（yukoimi，`74c26ff`） | 强智新版 / `csuft` | `#dataTable` 周历、学期一致性、5 月 1 日至 10 月 4 日夏季节次 |

## 复核与取舍

- 五件均只读当前学期，不弹窗选学年学期；教师、教室的「未知」「待定」「暂无」留空并汇总提醒。
- `njfu` 多行课名识别只在已有课名行后截断元数据，避免误删「跨节课」等正常课名。
- `wust` 的校区提问只影响下标与节次时间；桥不可用或取消时默认黄家湖并提醒。
- `hzu` 的空课载荷不能表达上游组合作息，按提取日选择冬/夏季并提醒跨季节重新导入。不连续节次不填中间空档，跳过并点名。
- `lidapoly`、`sdufe` 使用 HTTP；`hzu` 使用 HTTPS IP 地址，真机可达性与证书未验证。

## 验证

- 合成 fixture：`njfu` 7 对、`lidapoly` 7 对、`sdufe` 7 对、`wust` 8 对、`hzu` 8 对，共 37 对，单件自检均 PASS。
- 本地假教务站浏览器冒烟 28/28 OK：真实执行 `extract.js` 与 Rhino `parse.js`，覆盖 iframe、POST 请求体、周历失败、学期不一致、空课表、登录页与个人信息隔离。
- 临时副本变异测试 15/15 被预期用例捕获；正式文件恢复后五件再次 PASS。
- `./gradlew :importer:test --rerun-tasks`：BUILD SUCCESSFUL。
- 五件均没有本校账号，未做真机验证。
