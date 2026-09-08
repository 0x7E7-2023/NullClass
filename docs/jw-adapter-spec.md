# 空课教务适配器规范 v1

> 本文件是**权威规范**。适配器作者只需要读这一份就能写出、测试、提交一个适配器。

适配器 = **一个目录 + 两段 JS**，没有任何 Kotlin / 原生代码。它既是随 APK 发布的内置适配器，
也是任何人都能 fork 的第三方库，还是用户能在应用里直接导入的 zip。

---

## 1. 包结构

```
<school-key>/
  manifest.json          必须
  extract.js             必须
  parse.js               可选（省略时 extract 的输出就是课表载荷）
  fixtures/              可选，但**合并进主线必须带**
    <case>.extracted.json  extract.js 的真实输出样例（脱敏）
    <case>.expected.json   对应的课表载荷期望值
```

一个 zip 里可以放**多个**学校目录（即整个库的压缩包），应用会逐个校验并列出让用户确认。

限制（应用强制）：单文件 ≤256KB、整包解压后 ≤2MB、条目 ≤64、包内文件最多 2 层目录、拒绝绝对路径 / `..` / 符号链接。

## 2. `manifest.json`

```json
{
  "specVersion": 1,
  "key": "example-univ",
  "name": "示例大学",
  "version": "1.0.0",
  "author": "你的名字",
  "homepage": "https://github.com/you/repo",
  "loginUrl": "https://jw.example.edu.cn/",
  "scheduleUrlHint": "https://jw.example.edu.cn/xkcx/kb",
  "minAppVersionCode": 11,
  "extract": "extract.js",
  "parse": "parse.js",
  "allowHosts": ["cas.example.edu.cn"],
  "fixtures": [
    { "name": "基本表格", "extracted": "fixtures/basic.extracted.json", "expected": "fixtures/basic.expected.json" }
  ]
}
```

| 字段 | 必须 | 说明 |
|---|---|---|
| `specVersion` | ✔ | 必须是 `1`。高于应用支持的版本会明确提示「请升级空课」 |
| `key` | ✔ | `^[a-z0-9][a-z0-9-]{1,39}$`。**不能与已合并的适配器重复** |
| `name` | ✔ | 展示名，≤60 字 |
| `version` | ✔ | `x.y.z` |
| `loginUrl` | ✔ | 登录页或课表页；允许 http（会显示「不安全连接」标记） |
| `scheduleUrlHint` | | 一键刷新时优先打开的课表页 |
| `minAppVersionCode` | | 高于应用 versionCode 时明确报错而不是行为诡异 |
| `extract` | ✔ | 包内相对路径，`.js` |
| `parse` | | 同上；省略时 extract 输出即载荷 |
| `allowHosts` | | 需要额外请求的域名（只写主机名）。**安装前会展示给用户** |
| `fixtures` | | 回归用例；进主线必须带 |

未知字段会被忽略（向前兼容）。

## 3. 两段脚本的执行契约

### 3.1 运行环境

- `extract.js` 在**用户已手工登录的教务页面**里执行（同一页面主世界）。
- `parse.js` 在**同一页面**执行，输入是 `__ncInput`（extract 的原始输出字符串）。
- 脚本内容是**一个表达式**。用 `new Function` 编译执行，所以**语法错误会被捕获并回报**。
- 允许异步：返回值可以是 Promise，也可以自己调用 `__ncDone(json)`。
- 用 ES5 语法写（`Promise` 用原生即可，minSdk 26 的 WebView 都支持）。不要用箭头函数 / `let` / `const` / 模板串——旧设备 WebView 会挂。

### 3.2 宿主注入的全局

| 全局 | 说明 |
|---|---|
| `__ncInput` | `parse.js` 的输入：`extract.js` 的原始输出字符串 |
| `__ncDone(json)` | 提交结果并结束脚本 |
| `__ncError(msg)` | 报错并结束脚本 |
| `__ncCapabilities` | `{ specVersion, ocr, ocrMaxPixels, ocrMaxCalls }`，先查再用 |
| `__ncOcr(input, options)` | 调用应用内 OCR，返回 Promise（见 §5） |
| `__ncOcrGrid(input, options)` | OCR + 应用的表格结构层，返回对齐后的网格（见 §5） |

三种结束方式任选：

```js
// ① 同步返回字符串
(function () { return JSON.stringify(payload); })()

// ② 返回 Promise
(function () { return fetch('/api/kb').then(function (r) { return r.text(); }).then(transform); })()

// ③ 多步异步：自己调 __ncDone
(function () {
  fetch('/api/term').then(function (r) { return r.json(); }).then(function (term) {
    return fetch('/api/kb?term=' + term.id);
  }).then(function (r) { return r.json(); }).then(function (data) {
    __ncDone(JSON.stringify(toPayload(data)));
  }).catch(function (e) { __ncError(e.message); });
})()
```

超时 30 秒；结果超过 16M 字符会报错（不会静默截断）。

### 3.3 网络

- **允许**：与 `loginUrl` 同源，以及 `allowHosts` 里声明的域。
- **拦截**：其余域名在提取期间会被拦掉（子资源、导航、JS 层 `fetch`/`XHR`/`WebSocket`/`sendBeacon` 都有包装）。
- 提取结束后 JS 层白名单继续生效（同源照常），但不再拦子资源/导航——否则会打断教务页面自身。
- 沙箱是**纵深防御，不是安全边界**：同源读取（成绩、学籍、localStorage 令牌）挡不住，这是适配器能力的本质；
  重定向后续 URL、WebSocket、Service Worker 也不经过宿主的请求拦截——**不要用它们外发数据**，
  被发现会拒绝合并（内置）或由用户自负后果（自装）。

## 4. 课表载荷（脚本的输出）

```json
{
  "specVersion": 1,
  "kind": "schedule",
  "ocrAssisted": false,
  "terms": [
    {
      "name": "2026-2027 学年第一学期",
      "firstDay": "2026-09-07",
      "totalWeeks": 20,
      "periodTimes": [ { "periodIndex": 1, "start": "08:00", "end": "08:45" } ],
      "courses": [
        {
          "name": "高等数学A(一)",
          "teacher": "张三",
          "note": null,
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

- 不需要写 UUID / 时间戳 / 颜色 / 节次表——应用补齐（`periodTimes` 缺省用默认节次表）。
- 校验规则：`totalWeeks∈1..30`、`dayOfWeek∈1..7`、`startPeriod≤endPeriod`、
  `weekType∈{ALL,ODD,EVEN}`、`startWeek/endWeek∈1..totalWeeks`、`name` 非空。
  违规会给出**带定位**的错误（「第 2 个学期的第 3 门课程缺少 name」）。
- 多学期：`terms` 是数组，一次给多个学期也可以。
- 用 OCR 生成的载荷请标 `"ocrAssisted": true`，应用会在导入预览里加核对提示。

### 4.1 图片课表（`kind: "image"`）

课表本身就是图片时（青果部分学校），把图片交给应用识别：

```json
{
  "specVersion": 1,
  "kind": "image",
  "images": [ { "url": "https://jw.example.edu.cn/kb.png", "hint": "教学安排表" } ]
}
```

`url` 由宿主带 WebView 的 Cookie 下载（受 `allowHosts` 约束）；小图可直接给 `data`（base64 data URL）。
应用会 OCR → 表格结构还原 → **叠回原图逐格校对** → 用户确认后才入库。

## 5. 给第三方适配器的 OCR 接口

OCR 是应用内的原生能力，通过桥暴露给脚本：

```js
// 先查能力（未打包 OCR 的 ABI 上 ocr 可能为 false）
if (__ncCapabilities.ocr) {
  __ncOcr("https://jw.example.edu.cn/kb.png").then(function (r) {
    // r.boxes: [{ text, x, y, w, h, confidence }]
  });
  __ncOcrGrid(imageUrl).then(function (g) {
    // g.cells[row][col]、g.reliable、g.warnings、g.rowAnchors、g.colAnchors
  });
}
```

约束：

- 单次提取最多 **8 次**调用、单图 ≤4096×4096、总像素 ≤16M、单次 ≤15s。
- 桥只接受图片、**只回传文本与坐标**，不返回图片字节，也不能当通用 fetch 用。
- `__ncOcrGrid` 返回 `reliable: false` 时**不得据此生成课表**——要么改返回 `kind:"image"` 交给应用的校对流程，要么 `__ncError`。
- 图片可以是 URL（宿主带 Cookie 下载）或 `data:image/...;base64,...`（你自己从 canvas / `<img>` 取的）。

## 6. 库（第三方适配器仓库）

一个库 = 一个目录，结构就是本仓库根目录的 `jw-adapters/`：

```
index.json         库索引
<school-key>/      各适配器目录（结构见 §1）
README.md          可选
```

```json
{
  "specVersion": 1,
  "name": "某某的适配器库",
  "adapters": [ { "key": "example-univ", "name": "示例大学", "version": "1.0.0", "path": "example-univ" } ]
}
```

用户可以在应用里粘 GitHub 仓库地址（自动归一化到 `raw.githubusercontent.com/<owner>/<repo>/HEAD/index.json`）
或任意 `index.json` 链接，应用会列出适配器并逐个下载安装。**只允许 https**（debug 构建例外）。

## 7. 测试与提交

### 本地验证

```bash
./gradlew :importer:test
```

CI 会用 Rhino 在 JVM 上**真实执行**你的 `parse.js`，与 `fixtures/*.expected.json` 逐字段比对，
并对两段脚本做 ES5 检查。所以：

- `fixtures/` 里必须放**真实抓取**的 extract 输出（脱敏：删掉姓名、学号、身份证号）。
- `expected.json` 必须与你 `parse.js` 的实际输出一致（键顺序无关，数组顺序有关）。

### 合并进主线

1. 在 `jw-adapters/` 下加你的目录，并在 `index.json` 里加一条；
2. `./gradlew :importer:test` 通过；
3. 提 PR，在 PR 描述里写清：学校、教务系统类型、取数方式（DOM / 接口）、请求了哪些域名；
4. **维护者会逐个人工审计**你的脚本全文与请求目标——这是内置适配器的信任来源。

### 用户自己导入

用户也可以把目录打成 zip 在应用里导入，或直接引用你的库。**这类适配器不经过审计**，
应用会在安装前明确提示「会读取你已登录的教务页面内容」并提供脚本全文查看。

## 8. 安全与隐私红线

- **不要碰凭证**：登录全程由用户在 WebView 手工完成，适配器不得读取、存储、上报账号密码。
- **不要外发数据**：除课表接口外不得向任何域发送数据；`allowHosts` 只写你真的需要的域名。
- **不要埋点**：任何统计、上报、遥测都会被拒绝合并。
- 适配器在用户已登录的页面里运行，能力等同于该教务账号：**请只做取课表这一件事**。
