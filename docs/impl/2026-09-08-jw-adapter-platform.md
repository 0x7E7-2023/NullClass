# 教务适配器平台化 + 图片课表 OCR（v0.5.0）

日期：2026-09-08　BASE：`6ffdfc4`（点头后记录）　模式：完整模式 / 串行（含一个 OCR 决策门）

> 本文取代同日早先的草案。相比草案的实质变更：① 解析改为第三方 JS 脚本（不再设计声明式 DSL）；
> ② 脚本契约从「同步表达式」改为「异步回写」；③ 提取期不再冻结全部网络，改为同源/白名单放行；
> ④ 新增「保留登录态 + 一键刷新」；⑤ 新增图片课表 OCR 链路；⑥ 安全模型改为责任分层。
> 变更依据见文末「证据」。

## Goal

把「教务系统导入」从「写死一个 Kotlin 接口 + 编译期注册」改成**一份可被第三方维护的适配器包规范**，
同一份产物支持三种来源；并补上「课表本身就是图片」这一类学校的导入链路。

| 来源 | 载体 | 进入方式 | 安全责任 |
|---|---|---|---|
| 内置 | 本仓库 `jw-adapters/`，构建时打进 APK | 随版本发布 | **我们负责**：PR 逐个人工代码审计 + CI 校验 |
| 社区 | 任何人 fork/自建的同构仓库 | 应用内粘 `index.json` 链接拉取安装 | 用户自负 |
| 用户添加 | 单个学校目录的 zip | 应用内 SAF 选 zip 导入 | 用户自负 |

适配器 = **纯数据 + 两段 JS**，无 Kotlin、无原生代码，三者共用同一引擎。

现状缺陷：`JwAdapter` 是 Kotlin 接口（新学校必须改代码重新发版）；`parseExtracted` 是 Kotlin（第三方无法贡献）；
`JwAdapterRegistry` 是静态列表（无「用户添加」、无来源、无删除）；无规范、无版本、无兼容性声明；
`evaluateJavascript` 一次性同步取值，超长字符串可能被静默截断（代码注释已自认）。

## 设计

### 1. 适配器包格式（目录 / `.ncjw` zip，同构）

```
example-univ/
  manifest.json          必须
  extract.js             必须
  parse.js               可选（缺省 = extract 输出即课表载荷）
  fixtures/              可选；PR 进主线必须带
    basic.extracted.json   extract.js 的真实输出样例（脱敏）
    basic.expected.json    对应课表载荷的期望值
```

```json
{
  "specVersion": 1,
  "key": "example-univ",
  "name": "示例大学",
  "version": "1.0.0",
  "author": "NullClass",
  "homepage": "https://github.com/0x7E7-2023/NullClass",
  "loginUrl": "https://jw.example.edu.cn/",
  "scheduleUrlHint": "https://jw.example.edu.cn/kbcx.htm",
  "minAppVersionCode": 11,
  "extract": "extract.js",
  "parse": "parse.js",
  "allowHosts": ["jw.example.edu.cn", "cas.example.edu.cn"],
  "fixtures": [
    { "name": "基本表格", "extracted": "fixtures/basic.extracted.json", "expected": "fixtures/basic.expected.json" }
  ]
}
```

约束：`key` 匹配 `^[a-z0-9][a-z0-9-]{1,39}$`；`version` 为 `x.y.z`；`specVersion` 必须等于应用支持值（否则报「适配器规范版本过新，请升级空课」）；
`minAppVersionCode` 大于应用 versionCode 时明确报错；未知字段忽略（向前兼容）。
`loginUrl` 允许 http（国内教务现实）但打「不安全连接」标记；**库拉取只允许 https**。
`allowHosts` 是适配器声明的额外网络域（见 §5），安装前展示给用户。

### 2. 两段 JS 的执行契约（异步）

- **extract.js**：在**已登录的教务页面**里执行，做 DOM 抓取和/或同源请求。
- **parse.js**：在**同一页面的上下文里**执行，输入全局 `__ncInput`（extract 的原始输出），输出课表载荷。
  在同一页面执行是为了复用页面的加密/查询函数；解析失败不影响登录态，可原地重试。
- 脚本完成方式（三选一）：
  1. 表达式求值为字符串或可 JSON 序列化的对象 → 引擎自动采用；
  2. 表达式求值为 Promise → 引擎 await 其结果；
  3. 脚本自行调用 `__ncDone(jsonString)`（多步异步请求用这种）。
- 失败：调用 `__ncError(message)`，或抛异常，或超时（默认 30s，可配）→ 硬失败，给出可读原因。
- 要求：ES5 语法（旧 WebView 兼容，`Promise` 用原生，minSdk 26 的 WebView 都支持）；除 `allowHosts` 内同源/白名单请求外不得发网络；不得读写凭证。
- **不再要求同步**。这是相对草案的实质性放宽：真实适配器普遍是「先取学期/ids/sha1，再查课表」的多步异步，
  且 `sync XHR` 已被 Chrome 废弃、可被 `Document-Policy` 整页禁用；**开放 OCR 桥也必须异步**。

**宿主注入的全局 API（适配器可调用）**

| 全局 | 说明 |
|---|---|
| `__ncInput` | `parse.js` 的输入：`extract.js` 的原始输出字符串 |
| `__ncDone(json)` | 提交结果并结束脚本 |
| `__ncError(msg)` | 报错并结束脚本 |
| `__ncCapabilities` | 能力发现：`{ specVersion, ocr, ocrMaxPixels, ocrMaxCalls }` |
| `__ncOcr(input, options?)` | 调用应用内 OCR，返回 Promise（§7.3） |
| `__ncOcrGrid(input, options?)` | OCR + 应用的表格结构层，返回对齐后的网格（§7.3） |

桥通过 `WebViewCompat.addWebMessageListener` 按 **origin 白名单**注入，只有教务页面 origin 可调用；
桥上的方法只收 JSON、只做声明的动作，不做任何特权操作。

### 3. 课表载荷（适配器输出契约）

适配器作者只写这份人类友好的 JSON，UUID/时间戳/颜色/默认节次由应用补齐：

```json
{
  "specVersion": 1,
  "kind": "schedule",
  "terms": [
    {
      "name": "2026-2027 学年第一学期",
      "firstDay": "2026-09-07",
      "totalWeeks": 20,
      "periodTimes": [ { "periodIndex": 1, "start": "08:00", "end": "08:45" } ],
      "courses": [
        {
          "name": "高等数学A(一)", "teacher": "张三", "note": null,
          "blocks": [
            { "dayOfWeek": 1, "startPeriod": 1, "endPeriod": 2,
              "startWeek": 1, "endWeek": 16, "weekType": "ALL", "location": "教1-101" }
          ]
        }
      ]
    }
  ]
}
```

`terms` 为数组（天然支持多学期）；`periodTimes` 缺省用应用默认节次表。
校验：`totalWeeks∈1..30`、`dayOfWeek∈1..7`、`startPeriod≤endPeriod`、`weekType∈{ALL,ODD,EVEN}`、
`startWeek/endWeek∈1..totalWeeks`、`name` 非空；违规给出**带定位**的错误（「第 2 个学期的第 3 门课程缺少 name」）。
可选根字段 `ocrAssisted: true`：声明本载荷由图片识别生成，应用在导入预览里追加核对提示（见 §7.3）。

**非结构化返回**（图片课表，见 §7）：

```json
{
  "specVersion": 1,
  "kind": "image",
  "images": [ { "url": "https://jw.example.edu.cn/kb.png", "data": null, "hint": "教学安排表" } ]
}
```

`url` 由宿主带 WebView 的 Cookie 下载（受 `allowHosts` 约束）；小图可直接给 `data`（base64 data URL）。

### 4. 库索引（`index.json`，仓库根）

```json
{
  "specVersion": 1,
  "name": "某某的适配器库",
  "adapters": [ { "key": "example-univ", "name": "示例大学", "version": "1.0.0", "path": "example-univ" } ]
}
```

用户粘 GitHub 仓库地址或任意 `index.json` URL → 归一化（`github.com/o/r` → `raw.githubusercontent.com/o/r/HEAD/index.json`）
→ 拉取索引 → 列出适配器 → 选中后按 `path` 相对解析并拉 `manifest.json`/`extract.js`/`parse.js` → 校验 → 用户确认 → 落盘。
**只允许 https**；单文件 ≤256KB、总下载 ≤2MB、超时 10s；记录来源 URL 与内容 SHA-256。

### 5. 安全模型（责任分层）

**责任划分（写进规范与 UI）**

- 内置适配器：每个 PR 必须过**人工代码审计**（审 `extract.js`/`parse.js` 全文 + `allowHosts` + 请求目标），
  并过 CI fixture 校验（Rhino 实跑 `parse.js` 对期望值 + ES5 lint）。审计结论记进 PR。
- 用户自装适配器：**安全性自负**。安装前弹确认框，逐条列出：学校名、key、版本、来源 URL、脚本总大小、
  **该适配器可读取你已登录的教务页面内容**、**将请求的域名与方法**，并提供「查看脚本全文」。
  UI 文案不承诺任何安全保证。

**所有来源共用的基础卫生**

- 内置 key 不可被用户适配器覆盖：key 冲突直接拒绝并提示（防「伪造同名名校适配器」的社会工程）。
- 库/包拉取只允许 https；zip 解析防 zip-slip 与 zip 炸弹（条目 ≤64、单文件 ≤256KB、累计 ≤2MB、拒绝绝对路径/`..`/符号链接/嵌套 >2 层）。
- WebSettings 全量硬化：`allowFileAccess`/`allowContentAccess`/`allowFileAccessFromFileURLs`/`allowUniversalAccessFromFileURLs`=false、
  `supportMultipleWindows`=false、`javaScriptCanOpenWindowsAutomatically`=false、`mixedContentMode=MIXED_CONTENT_NEVER_ALLOW`、
  `saveFormData`=false、`geolocationEnabled`=false；`onReceivedSslError` 一律 cancel；`onPermissionRequest` deny；
  `onShowFileChooser` 返回 false；`setDownloadListener` 空实现；release 关闭 WebContentsDebugging。
  （现状只设了 `javaScriptEnabled` + `domStorageEnabled`，其余全是默认值。）

**网络姿态（按你的决定：放开同源请求）**

- 允许：与 `loginUrl` 同源，以及 `allowHosts` 中声明的域。
- 拦截：
  - 提取窗口内：`shouldInterceptRequest` 对非白名单子资源返回空响应；`shouldOverrideUrlLoading` 拦非白名单导航。
  - 提取后：JS 层的白名单包装继续生效（同源照常），子资源/导航不再拦截（否则会打断教务页面自身）。
- JS 层在提取时安装白名单版 `fetch`/`XMLHttpRequest`/`WebSocket`/`EventSource`/`sendBeacon` 包装（纵深防御，非边界）。

**明确的残余风险（写进规范，不假装防住了）**

| 通道 | 状态 |
|---|---|
| 同源读取（成绩/学籍/localStorage 令牌） | **防不住**。适配器在主世界运行，这是能力本身。已开 `domStorageEnabled`，令牌可直取。 |
| DNS prefetch / preconnect 外发 | 防不住（不回调拦截器，WebView 无开关） |
| WebSocket / Service Worker | 提取窗口内可拦，**存活期内防不住**；SW 可持久化在数据目录跨实例存活 |
| WebRTC / STUN | 防不住（无开关） |
| 302 / 开放重定向绕白名单 | **已确认防不住**：官方文档明写 `shouldInterceptRequest` 只对初始资源 URL 回调，重定向后续 URL 不再回调；Chromium 团队亦确认 WebSocket / Service Worker 从不经过该回调 |
| 登录期表单劫持 | 保留登录态 = 同一 WebView 长期存活，风险存在 |
| 运行时从同源 eval 攻击者可控内容 | 防不住；「查看脚本全文」只是可审计，不是安全承诺 |

> 结论：网络闸门能挡住「直接外发」，挡不住「借白名单域的重定向外发」。真正要根治需要进程级网络管控
> （VpnService / 只放行白名单 SNI 的本地代理），本轮不做。

### 6. 登录态与一键刷新

- **保留登录态**：不杀进程、不清 WebView 数据目录 → Cookie 跨启动保留，用户不必每次重新登录。
  （代价：Service Worker / 残留定时器 / localStorage 令牌长期存活，已列入残余风险。）
- **一键刷新**：记住「上次适配器 key + 上次课表页 URL + 上次成功时间」到 DataStore。
  TransferScreen 顶部出现「一键刷新课表」→ 进入导入页即自动加载课表页并自动提取（可在设置关闭自动提取），
  成功后走同一条预览→合并管线。这解决「学校改了课表」的重复导入问题。

### 7. 图片课表（OCR）

**为什么做**：青果系部分学校的课表页 DOM 里只有一个 `<img>`，取数接口也只返回图片，没有结构化数据。

**选型（体积已确认不是约束）**

- **首选：ONNX Runtime Android + PP-OCRv6 tiny ONNX 模型**。
  - ORT 1.29.0（MIT，Maven Central）实测：arm64 `libonnxruntime.so` **31MB**、armeabi-v7a 22MB、x86/x86_64 各 37MB。
    当前 release 无 ABI splits，四 ABI 全进包会到 **129MB** —— 必须配 ABI 拆分。
  - 选它的理由是**风险结构**而非体积：覆盖 x86_64（雷电/AVD 能测）、armeabi-v7a，微软维护，Maven 坐标。
    备选 `lw.PPOCR.C`（+6~10MB）是 v0.1.0-preview、单人维护、**仅 ARM64**——模拟器跑不了，只作「将来体积真成问题」的退路，
    封装在同一个 `OcrEngine` 接口后。
  - 模型：PP-OCRv6 tiny（det ≈1.7MB + rec ≈4.3MB）打进 APK assets，**完全离线**，首次使用零等待。
    已知风险：tiny 字典 6904 字，可能漏教师名/楼名；不够则加 small（29.6MB）作可选下载，但**绝不能成为前置条件**。
  - 必做项：关闭 ORT 的 1DS 遥测（否则与「无埋点」承诺冲突）；加 `-keep class ai.onnxruntime.** { *; }`；
    assets 里 `.onnx` 加 `noCompress` 并确认未被 resource shrinker 删除；`.so` 16KB 页对齐（ORT ≥1.27.0 已合规）。
- 排除：ML Kit（专有 ToS，与 GPL-3.0 冲突）、Paddle-Lite（无 Maven AAR、最后稳定版 2022-11）、
  Tesseract（中文 ≈82%，只作英文兜底）、云 OCR/VLM（破「无云」前提，Key 随 GPL 源码公开必被滥用）。

**结构层（纯 Kotlin，进 CI）**

OCR 只给文本框 + 坐标。课表是规则网格，**不需要通用表格结构模型**（SLANet 在中文真实表格上自评仅 59.5–63.7%）：

1. **表头锚定**：星期行、节次列**独立检测**，绝不从数据格反推；
2. **网格吸附**：检测框中心按 x/y 聚类 → 吸附到 7 列 × N 行；
3. **课块归属**：合并单元格/跨行大节作显式规则；
4. **周次正则**：`\d+(-\d+)?周|单周|双周|第\d+周`；解析失败阻断落库，要求用户填写；
5. **硬失败门**：检测到任何行列偏移、或整体置信度低于阈值 → **不展示可编辑草稿**，直接提示「识别不可靠，请手动录入（原图并排参照）」。

**校对 UI（关键，决定净收益）**

每个识别块以半透明高亮**叠回原图对应单元格**，点击放大可改；行列偏移整体高亮而非逐块提示；低置信块标记；**确认前不写库**。

### 7.3 暴露给第三方适配器的 OCR 接口

OCR 是应用内的原生异步能力，**通过桥暴露给适配器脚本**——适配器不只能「返回图片让应用代劳」，也可以自己调 OCR 做自定义结构。

```js
// 能力发现：先查再用（未打包 OCR 的 ABI / 低端设备上 ocr 可能为 false）
__ncCapabilities
// -> { specVersion: 1, ocr: true, ocrMaxPixels: 16777216, ocrMaxCalls: 8 }

// ① 原始识别结果：文本框 + 坐标 + 置信度
__ncOcr(image, options).then(function (r) { /* r.boxes: [{text,x,y,w,h,confidence}] */ })
// image: "https://…"            宿主带 WebView Cookie 下载，受 allowHosts 约束
//      | "data:image/png;base64,…"  适配器自己从页面 canvas / <img> 取到的图
// -> { width, height, boxes: [ { text, x, y, w, h, confidence } ] }

// ② 复用应用的表格结构层：表头锚定 + 网格吸附 + 硬失败判定
__ncOcrGrid(image, { rows, cols }).then(function (g) { /* g.cells[row][col] */ })
// -> { width, height, rowAnchors: [y…], colAnchors: [x…],
//      cells: string[][], reliable: boolean, warnings: [string] }
```

约束（写进规范，由宿主强制）：

- **限额**：单次提取最多 8 次调用、单图 ≤4096×4096、总像素 ≤16M、单次 ≤15s；超限 reject 并给可读原因。
- **能力窄**：桥只接受图片、**只回传文本与坐标**，不返回图片字节，也不能当通用 fetch 用——适配器无法借它读任意页面内容。
- **`reliable:false` 必须被尊重**：拿到 `reliable:false` 时不得据此生成课表，应改返回 `kind:"image"` 交给应用走叠图校对，或 `__ncError` 报错。
- 用 OCR 生成的载荷应在根对象加 `"ocrAssisted": true`，应用在导入预览里追加「本课表由图片识别生成，请核对」。
- 桥的可用性由 `__ncCapabilities.ocr` 声明；适配器必须处理 `false`（降级到 DOM 抓取或明确报错）。

**与适配器契约的关系**：适配器有两条路——① 返回 `kind:"image"`，由应用跑完 OCR + 结构层 + 叠图校对；
② 自己调 `__ncOcr` / `__ncOcrGrid` 做自定义结构。引擎与结构层都在应用侧，适配器只通过桥调用。

**决策门（先做 spike，再决定投入）**

拿 30–50 张真实青果课表截图（需你提供），人工标注后测三个数：**块级精确匹配率**、**系统性偏移发生率**、**静默错误率**。
门槛：块级 ≥95%（配叠图校对）且零系统性偏移 → 继续；不达标 → OCR 转辅助模式或走备选引擎。
理由：公开可查的数字全是通用中文 OCR 基准，**没有任何针对青果课表图片的端到端实测**；
而「OCR + 校对」净收益为正的门槛约 ≥95%——低于此，静默错块会让用户"每周跑错教室"，比手动录入更糟。

**与适配器契约的关系**见 §7.3。

### 8. 分块取回

`evaluateJavascript` 回调对超长字符串可能截断。运行器统一：先执行脚本把结果写进 `window.__ncResult`，
再读 `__ncError` → 读长度 → 按 128K 字符分片 `substring(a,b)` 取回拼接。总长上限 16M 字符，超限报「页面数据过大」而非静默截断。

## Files

**新建（`:importer`，纯 JVM，全部可单测）**
- `jw/JwManifest.kt` — manifest 模型 + 校验
- `jw/JwAdapterPackage.kt` — 包模型（manifest + 脚本 + 来源 + 安装信息）
- `jw/JwPackageReader.kt` — 目录/zip 读取 + zip-slip / 炸弹防护
- `jw/JwLibraryIndex.kt` — index.json 模型 + URL 归一化 + 相对路径解析
- `jw/JwSchedulePayload.kt` — 课表载荷 + 图片载荷模型 + 带定位校验
- `jw/JwScheduleNormalizer.kt` — 载荷 → `ScheduleDocument`
- `jw/JwBuiltinLibrary.kt` — 从 resources 加载内置库
- `jw/JwUserAdapterStore.kt` — 用户适配器落盘/列出/删除（`java.io.File` 根，可测）
- `jw/JwRemoteFetcher.kt` — 远端取文本接口
- `jw/JwScriptContract.kt` — 异步回写协议 / 沙箱前导 / 白名单包装 / 分块读取协议（运行器与测试共用）
- `jw/ocr/OcrBox.kt`、`jw/ocr/JwTableAligner.kt`、`jw/ocr/JwTableValidator.kt` — 结构层（聚类/锚定/硬失败门）
- 测试：`JwManifestTest`、`JwPackageReaderTest`、`JwLibraryIndexTest`、`JwSchedulePayloadTest`、
  `JwScheduleNormalizerTest`、`JwUserAdapterStoreTest`、`JwBuiltinLibraryTest`、`JwLibraryHarnessTest`（Rhino 跑 fixture）、
  `JwTableAlignerTest`（用录制的 OCR 输出 JSON 做 golden 回放）

**新建（`:ocr`，Android library，隔离原生依赖与 ABI 配置）**
- `OcrEngine.kt`（接口）、`OrtOcrEngine.kt`（ORT 实现 + 遥测关闭）、`OcrPreprocess.kt`（超分/二值化/水印遮罩）、`OcrResult.kt`
- `build.gradle.kts` — `onnxruntime-android` + `noCompress` + R8 keep + ABI splits

**删除**
- `importer/.../jw/JwAdapterRegistry.kt`、`jw/adapters/ExampleUniv.kt`、`importer/src/test/.../ExampleUnivTest.kt`（能力由 Harness 测试继承）

**改写（`:feature:settings`）**
- `jw/JwImportActivity.kt` — 分区列表（内置 / 用户添加）、导入 zip、从链接添加、详情/删除/查看脚本、安装确认、一键刷新
- 新建 `jw/JwImportViewModel.kt`、`jw/JwScriptRunner.kt`（异步回写 + 分块 + 白名单沙箱）、`jw/JwRemoteFetcherOkHttp.kt`、
  `jw/JwAdapterSheets.kt`（详情/脚本全文/安装确认）、`jw/JwImageProofingScreen.kt`（叠图校对）、
  `jw/JwOcrBridge.kt`（`__ncOcr` / `__ncOcrGrid` 桥：origin 白名单、限额、能力发现）

**库种子（仓库根）**
- `jw-adapters/index.json`、`jw-adapters/README.md`、`jw-adapters/example-univ/{manifest.json,extract.js,parse.js,fixtures/*}`

**文档与流程**
- `docs/jw-adapter-spec.md`（规范正文，权威；含贡献流程与审计要求）
- `README.md`、`CHANGELOG.md`、`CONTRIBUTING.md`（含「适配器审计清单」「引擎/模型升级清单」）
- `.github/PULL_REQUEST_TEMPLATE.md`（新增适配器自查清单）、`.github/ISSUE_TEMPLATE/jw-adapter-request.md`

**构建**
- `importer/build.gradle.kts` — Sync 任务把 `jw-adapters/` 打进资源（排除 `fixtures/`）+ Rhino 测试依赖 + 库目录系统属性
- `ocr/build.gradle.kts`、`settings.gradle.kts`、`feature/settings/build.gradle.kts`（+ okhttp、+ :ocr）
- `app/build.gradle.kts` — ABI splits（arm64-v8a / x86_64 分包 + universal debug）、versionName 0.5.0、versionCode 11
- `.github/workflows/release.yml` — 上传多个 ABI APK
- `gradle/libs.versions.toml` — + rhino（testOnly）、+ onnxruntime-android、+ androidx.webkit（`addWebMessageListener`）

## Task split（串行，OCR 有决策门）

1. **引擎**：manifest / 包读取 / 载荷 / normalizer / 索引 / 用户库 / 契约常量 + 单测。
2. **库种子 + 构建接线**：`jw-adapters/example-univ`（由现有 ExampleUniv 迁移）、`index.json`、Sync 任务、Rhino Harness。
3. **Android 侧**：`JwScriptRunner`（异步 + 分块 + 白名单沙箱）、`JwImportViewModel`、`JwImportActivity` 改造、一键刷新、OkHttp fetcher。
4. **OCR**：spike（决策门）→ `:ocr` 模块 + ORT + 模型 + 预处理 → `:importer` 结构层 →
   `JwOcrBridge`（暴露给适配器的 `__ncOcr` / `__ncOcrGrid` + 限额 + 能力发现）→ 叠图校对 UI → ABI splits。
5. **文档与流程**：规范文档、README/CHANGELOG/CONTRIBUTING、PR/issue 模板、版本号。

3 与 5 无文件交集，可在 1–2 定稿后并行；4 依赖 1 的载荷模型，且**必须先过 spike 决策门**。

## Out of scope

- 官方适配器库浏览/搜索页、适配器自动更新与版本回滚
- 应用内 fixture 自检（安装期只做结构校验；行为校验在 CI）
- 适配器签名/信任链
- 进程隔离 / 一次性 WebView（按你的决定保留登录态，改为一键刷新）
- 云 OCR / 视觉大模型；通用拍照导入（本轮只做「适配器返回图片 → OCR → 校对」这一条链路）
- Kotlin 原生适配器（第三方既然写解析，就不再保留第二条路）

## Done when

- `./gradlew test` 全绿：manifest 校验、zip-slip/炸弹拒绝、载荷定位报错、normalizer、索引归一化、用户库落盘/删除、
  内置库完整性、**Rhino 跑通每个内置适配器的 fixture**、表头锚定/网格吸附/硬失败门（golden 回放）。
- `./gradlew :app:assembleDebug` 绿；release 能产出 arm64-v8a 与 x86_64 两个分包。
- 模拟器手验：内置示例适配器走通 提取→解析→预览→合并；导入 zip 适配器后出现在「用户添加」且可删除；
  从本地 http 起一个 `index.json` 能列出并安装；一键刷新能复用登录态重新提取；
  一张真实课表图片走通 OCR→叠图校对→合并；非白名单域在提取窗口内确实被拦；
  第三方适配器脚本能调 `__ncOcr` 拿到文本框自建课表、`__ncCapabilities.ocr` 为 true、
  `reliable:false` 时适配器被正确阻断、超限额被 reject。
- 规范文档可让没读过本项目代码的人独立写出并提交一个适配器包。

## 决策门与开放问题

**决策门（阻塞 Task 4）**：30–50 张真实青果课表截图的块级精确匹配率与系统性偏移发生率——**需要你提供样本**。

**开放问题（实现前/后需实测）**
1. ~~`shouldInterceptRequest` 对重定向后续 URL、WebSocket、Service Worker 是否回调~~ —— **已核实：不回调**（Android 官方文档 + Chromium 团队），已记入残余风险表。
2. `lw.PPOCR.C` 的 AAR 净增量与 `.so` 16KB 对齐（仅当走备选时需要）。
3. PP-OCRv6 tiny 的 6904 字字典在真实课表上漏多少教师名/楼名。
4. 真实设备上首次识别耗时与内存（官方称测试图约 0.6s，课表整图远大于测试图）。
5. armeabi-v7a 设备真实占比——若极低可只发 arm64 + x86_64。
6. 青果 typeB「网上选课 → 正选结果」是否完整含周次与单双周——若含，OCR 的触发面会大幅缩小。

## 证据（关键来源）

- 生态规模：小爱课程表 385 个适配器（209 GitHub + 176 Gitee）、时光课表 156 个（146 校），全部纯 JS；
  63 个适配器抽样 DOM 抓取 : 同源请求 ≈ 6:4；provider 中位 75 行 / parser 中位 113 行。
- 加密集中在登录环节（正方 RSA+滑块、强智 encodeInp、青果 MD5/AES、超星 DES）；课表查询接口普遍只认会话 Cookie；
  唯一明确反例是金智 ehall 的 HMAC 签名，密钥在页面 JS 里 → 必须复用页面函数（JS 能做，Kotlin 不能）。
- 新版正方 V9 / 金智 ehall / 超星 / 研究生系统课表不在初始 DOM，须 XHR 取 JSON 再渲染。
- 同源 fetch 自动带 Cookie（含 httpOnly）；httpOnly 只挡 `document.cookie` 读取，不挡网络层发送。
- 竞品：WakeUp 5.8MB APK、零 OCR 依赖、覆盖 1200+ 校；小爱教务导入 2025-04 下线后拍照导入实测失败率高。
- 表格结构模型在中文真实表格上 PaddleOCR 自评仅 59.5%（SLANet）/63.7%（SLANet_plus）。
- ORT 1.29.0 体积：本机解包 Gradle 缓存 AAR 实测（arm64 31MB / v7a 22MB / x86 37MB / x86_64 37MB）；
  当前仓库 release 3,118,103 B（2.97 MiB），无 ABI splits。
- `lw.PPOCR.C`：GitHub 仓库与 Release 实存，README 自述 `Android ARM64 Native Preview`、experimental、v0.1.0-preview.2。

## 实机验证（2026-09-08 晚，雷电模拟器 x86_64 / Android 14 / WebView 146）

方法：本机起一个 Python 假教务站点（登录 + 课表 DOM + 课表图片 + 适配器库 `index.json`），
`adb reverse tcp:8777` 给模拟器访问；另起 8778 当「外发收集器」，用来证明网络闸门。
测试适配器覆盖：DOM 抓取、异步接口取数、图片课表、调 OCR 桥、外发探测。

**跑通的链路**：zip 导入（单个 / 多适配器包）、从链接添加库、提取→解析→预览→合并、
一键刷新（复用登录态）、图片课表 OCR→核对→合并、第三方调 `__ncOcr` / `__ncOcrGrid`、
网络闸门（`fetch` / `XHR` / `img` / `sendBeacon` / `WebSocket` 全部拦下，同源请求放行，
收集器零命中）、OCR 调用限额（第 9 次被拒）、内置 key 冲突拒绝、zip 炸弹与缺脚本拒绝。

**发现并修复的 7 个问题**（详见 CHANGELOG `[0.5.0]` 的「修复」）：

1. http 教务页打不开 —— 缺 network security config，平台默认禁明文，与「允许 http loginUrl」的规范冲突；
2. 图片课表周次被教室号顶掉 —— `parseWeeks` 的「周」可选，`教1-101` → 1-10 周（静默错周）；
3. `__ncOcr` 回传 JSON 字符串而非规范承诺的对象；
4. OCR 桥 origin 规则漏端口 —— 非默认端口（8080 等）永不注入；
5. `__ncCapabilities.ocr` 只看引擎不看桥，会撒谎；
6. 一键刷新时 OCR 能力探测（加载 ONNX 模型）未完成，脚本被按「无 OCR」构建；
7. 同 key 重装适配器后 `JwAdapter.equals` 判等 → StateFlow 不发新值 → 界面继续用旧脚本。

**仍未验证**：`__ncOcrGrid` 的 `reliable:false` 阻断路径；内置 `jw-adapters/example-univ`
的完整提取链路（其 `loginUrl` 指向不存在的域名，只能用用户适配器替代跑通同一引擎）；
OCR 决策门（需真实青果课表截图）。
