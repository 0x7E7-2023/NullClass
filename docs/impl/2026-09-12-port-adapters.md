# 移植上游教务适配器（第一批 · 平台覆盖）

日期：2026-09-12　BASE：`2a31472`（**点头后记录**）　模式：完整模式 / 并行（8 个 agent）

> **结果（2026-09-12 完成）**：8 个全部落地，`./gradlew :importer:test` 全绿（库里 11 个适配器 / 23 个用例）。
> 两轮对抗式验收（安全 + 正确性各一个 reviewer，独立证伪者用真实 headless 浏览器跑 `extract.js`）
> 找出 13 条、去重 12 条、裁决 10 条成立并全部修掉。三条与计划不符的：
> ① **平台画像不可信** —— 计划表里 `MASU`（自称青果，实为强智 eams）与 `HYNU`（走 `jsxsd`
> 路径，实为湖南强智）都标错了，后续批次要按接口路径 + 选择器结构聚类；
> ② **「同平台可批量」是分层的** —— 信封与字段名可复用，取数路径与周次/节次编码未必
> （`HBMU⇄GLMU` 逐字克隆可零成本，`GDUT` 同接口但编码不同，`for-std` 的两个部署连接口都不一样）；
> ③ **fixture 自证是真隐患** —— `hbmu` 把 `1-16周(双)` 解析成每周都上而 CI 抓不到，因为
> fixture 里没有「单」「双」二字。后续批次必须为「最容易写错的写法」单独造用例。
> 另外发现一处宿主 bug（`usesOcrBridge()` 判据过宽，见下）已一并修掉。

## Goal

把上游 [shiguang_warehouse](https://github.com/XingHeYuZhuan/shiguang_warehouse)（MIT，
207 所学校 / 226 个适配器）里的适配器搬到我们的 `jw-adapters/`，让空课能导入这些学校的课表。

**第一批只做 8 个，一个平台一个**，目的是把配方在**不同上游脚本风格**上跑通、标定单个成本，
再决定后面怎么批量。不追求数量。

搬运方法见 [移植手册](../jw-adapter-porting.md)，验证标准见 [测试方案](../jw-adapter-testing.md)。

## 为什么按平台、不按学校

上游按教务平台收敛得很厉害：正方（`jwglxt` 42 + `jsxsd` 30）**一个平台占 32.6%**，
金智 18、for-std 7。同平台的第二个开始，选择器/分页/周次编码几乎照抄，边际成本掉一大截。
剩下 102 个长尾自研（46%）一个学校一套接口，**不搬**，交给内置通用适配器 + OCR。

## 批次一：8 个候选（每个平台挑最简单的一个）

| # | 平台（推断） | 学校 | 上游路径 | 大小 |
|---|---|---|---|---|
| 1 | 正方 新版 `jwglxt` | 浙江工业大学 | `ZJUT/zjut_01.js` | 6.8K |
| 2 | 正方 老版 `jsxsd` | 衡阳师范学院 | `HYNU/hynu_01.js` | 7.4K |
| 3 | 金智 `jwapp`（我们已有同平台 `dlutci`） | 南京工业职业技术大学 | `NIIT/niit.js` | 4.2K |
| 4 | `for-std`（我们已有同平台 `ustc`） | 江苏旅游职业学院 | `JSTC/jstc_01.js` | 6.8K |
| 5 | 青果 | 马鞍山学院 | `MASU/masu.js` | 6.6K |
| 6 | URP | 东北农业大学（**走 WebVPN**） | `NEAU/NEAU_01.js` | 6.4K |
| 7 | 超星 | 山西工程职业学院 | `SXGCXY/sxgcxy_01.js` | 10.7K |
| 8 | Struts2 教务 | 湖北医药学院 | `HBMU/hbmu.js` | 7.6K |

学校名与 `loginUrl` **以上游目录里的 `adapters.yaml` 为准**，agent 自己读一遍再写 manifest；
上面「平台」一列是我按脚本内容推断的（例如 HYNU 上游自称「强智」却请求 `jsxsd` 路径），
**不算数** —— 以脚本实际请求的接口为准。

第 6 个（东北农业大学）的 `import_url` 是 `webvpn.neau.edu.cn`，说明它的教务在 WebVPN 后面：
`allowHosts` 要带上网关域，这也是**验证我们白名单机制**能不能吃下这类学校的好样本。

## Files

**新增**（每个 agent 只碰自己这一个目录）：

```
jw-adapters/<school-key>/
  manifest.json       specVersion/key/name/version/author/loginUrl/extract/parse/allowHosts/fixtures
  extract.js          只做取数，把教务原始数据原样交出去
  parse.js            纯转换：原始数据 → 课表载荷（移植的核心逻辑落这里）
  AUDIT.md            安全审计结论（请求域、读了什么、移植者、日期）
  fixtures/
    basic.extracted.json   合成：extract.js 会交出去的形状
    basic.expected.json    parse.js 的输出，与上面的输入配套
```

**改动**：`jw-adapters/index.json` 加 8 条 —— **由主 agent 统一加**，agent 不许碰（8 个 agent 同时改一个文件必冲突）。

**不改**：`docs/jw-adapter-spec.md`（规范已定）、任何 Kotlin、任何既有适配器。

## 前置修复（主 agent，开工前）

`jw-adapters/dlutci/parse.js` 第 142 行的复合键里有个**字面 NUL 字节**
（`name + '\0' + teacher`）。运行时没问题，但 `grep`/`diff` 会把这个文件当二进制 ——
它正是移植时被当模板抄的那份。改成源码里写 `\u0000` 转义（运行时值不变），重跑门确认输出一致。

## Task split（并行）

任务满足并行的全部条件：**8 个任务、文件互不相交、无先后依赖、墙钟收益明显**。

每个 agent 负责一个平台的一个适配器，指令里带：移植手册路径、测试方案路径、
它的上游脚本路径、它的目标目录、以及下面的硬约束。

**不用 worktree**：每个 agent 只新建自己那一个目录，没有共享文件，不存在写冲突；
worktree 反而会让 8 份产物散在 8 个工作树里，集成成本远大于它挡掉的风险。
冲突面用两条硬约束堵死（见下），集成时 `git status` 核一遍。

### 给每个 agent 的硬约束

1. **只允许新建、修改 `jw-adapters/<你的 key>/` 下的文件。** 其它任何文件（含 `index.json`、
   规范、Kotlin）一律不许动。
2. **不许跑 `./gradlew`。** 8 个 Gradle 并发会互相抢锁。自验用 Node：

   ```bash
   node -e "
   const fs=require('fs'),vm=require('vm');
   const src=fs.readFileSync('jw-adapters/<key>/parse.js','utf8');
   const sb={console,JSON,Number,String,Array,Object,Math,Date,Error,
             __ncInput: fs.readFileSync('jw-adapters/<key>/fixtures/basic.extracted.json','utf8')};
   vm.createContext(sb);
   const out=vm.runInContext(src,sb);
   const exp=JSON.parse(fs.readFileSync('jw-adapters/<key>/fixtures/basic.expected.json','utf8'));
   const a=JSON.stringify(JSON.parse(out)), b=JSON.stringify(exp);
   console.log(a===b ? 'MATCH' : 'DIFF\n'+a+'\n'+b);
   "
   ```

   外加 ES5 自检：`grep -nE "=>|\`|\\blet |\\bconst " jw-adapters/<key>/*.js` 必须无输出。
3. **ES5 是硬要求**（CI 会拦，Rhino 也不支持 async/await）。上游脚本用
   `npx @babel/cli --presets @babel/preset-env --targets "chrome>50"` 转，再手改桥调用。
4. **不许夹带**写 DOM、埋点、请求第三方域、读成绩/学籍 —— 命中移植手册 §5 任一条就停下来报告，
   别硬搬。
5. 拿不准的一律**在 `warnings` 里如实说**，不许静默丢数据（丢课、丢周次、猜开学日都得说）。

### 主 agent 集成

1. `git status` 核对：只多了 8 个目录，没有别的文件被改；
2. 加 `index.json` 8 条；
3. 跑 **`./gradlew :importer:test`**（真 Rhino 门，8 个一起）；
4. 反向对照：随手改坏一个 fixture，确认门会红，再改回来；
5. 绿了之后按 Small 流程走审查（reviewer → 有非空列表才 adversary）。

## Out of scope

- **其余 218 个适配器** —— 等第一批的实测成本出来再定批量策略；
- **上游的通用工具与通用适配器**（`GLOBAL_TOOLS/`、`zhengfang_jiaowu`、`chaoxing_jiaowu`）——
  定位与我们的 `universal` 重叠，理由见移植手册 §1；
- **102 个长尾自研** —— 不搬；
- **自动追上游更新** —— 不引入，理由见测试方案 §5；
- **真机验证** —— 第一批不做（没有账号）。正确性靠合成 fixture + 用户反馈，
  这是明确接受的代价，写在测试方案 §3。

## Done when

- `jw-adapters/` 下多出 8 个目录，每个含 `manifest.json` / `extract.js` / `parse.js` /
  `AUDIT.md` / `fixtures/`（一对）；
- `index.json` 有对应 8 条；
- `./gradlew :importer:test` 全绿，且**反向对照验证过门是活的**；
- 每个 `AUDIT.md` 逐条签过移植手册 §5 的 8 项，并写明请求域；
- 8 个适配器的 `parse.js` 里**没有** `=>` / 反引号 / `let` / `const`；
- 审查（reviewer + 必要时 adversary）跑完，CONFIRMED 的修掉。

---

# 批次二（12 个 · 近克隆）

## 选型依据

拿「与已移植的上游脚本的 token 相似度」给剩余 205 个排序，**相似度高 = 同平台克隆 = 单位成本最低、风险最小**：

| 目标 key | 学校 | 上游 | 最像 | 相似度 |
|---|---|---|---|---|
| `cfec` | 重庆财经学院（正方） | `CFEC/CFEC.js` | zjut | 0.983 |
| `hblgxy` | 淮北理工学院（强智） | `HBLGXY/hblgxy_01.js` | hynu | 0.980 |
| `glmu` | 桂林医科大学（Struts2） | `GLMU/glmu.js` | hbmu | 0.972 |
| `xjie` | 新疆工程学院（强智） | `XJIE/xjie_01.js` | hynu | 0.967 |
| `wenhua` | 文华学院（正方） | `WENHUA/wenhua_01.js` | zjut | 0.930 |
| `hniu` | 湖南信息职业技术学院（强智） | `HNIU/hniu_01.js` | hynu | 0.883 |
| `upc` | 中国石油大学(华东)（强智） | `UPC/upc.js` | hynu | 0.863 |
| `huel` | 河南财经政法大学（正方） | `HUEL/huel_01.js` | zjut | 0.833 |
| `hhtc` | 怀化学院（强智） | `HHTC/hhtc.js` | hynu | 0.775 |
| `cqrk` | 重庆人文科技学院（强智，WebVPN 非默认端口） | `CQRK/cqrk_01.js` | hynu | 0.760 |
| `gxdlxy` | 广西电力职业技术学院（强智） | `GXDLXY/gxdlxy_01.js` | hynu | 0.739 |
| `zzu` | 郑州大学（for-std） | `ZZU/zzu.js` | jstc | 0.726 |

12 个并发，每个 agent 一个目录，约束与集成流程同批次一（只碰自己那个目录、不跑 gradle、
ES5、安全红线、拿不准进 warnings）。

## 这批最大的风险：把第一批修掉的坑重新引入

克隆意味着**上游的缺陷也会一起被克隆**。第一批从审查里挖出来的这些，必须在移植时逐条对照：

1. **周次里「单/双」写在「周」字后面**（`1-16周(双)`）：标记不能被丢掉、退化成「每周都上」，
   也不能不报警。第一批有适配器在这里出错而 CI 全绿。
2. **括号里的纯数字序号**（`(1)`）不是周次。当成周次会把后面的教师、教室、真实周次一起吃掉。
3. **读不出「第N节」标签的行不许静默丢课**：要么认出来（`rowSpan` 继承、`第9,10节` 这类多节写法），
   要么进 `warnings`。
4. **连堂标签（`第1-2节`）下 `periodTimes` 要覆盖每一节**：缺的补出来或回落内置表，并说明。
5. **分页**：接口没回记录总数时不能只取第一页就停；取不全要出声。
6. **总周数被课表里更晚的周次抬高时要进 `warnings`**（其它推算值都写了，唯独这个最容易漏）。
7. **`AUDIT.md` 的声明必须与代码一致** —— 读了什么、请求了哪些域，不许写得比代码窄。
8. **fixture 的期望值必须独立推出来**，不许把 `parse.js` 的输出贴进去当期望；并且做一次
   **变异测试**证明新用例真的能拦住（把逻辑故意改坏 → 用例必须变红）。

## Done when

同批次一，外加：每个适配器的 `AUDIT.md` 里写明它与哪个已移植件同平台、**逐条对照上面 8 条**的结论。
