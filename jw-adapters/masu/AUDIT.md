# 安全审计 —— 马鞍山学院教务适配器（`masu`）

审计对象：`jw-adapters/masu/extract.js` + `parse.js`
（移植自拾光社区 `MASU/masu.js`，上游作者 Haooz，MIT）

审计人：NullClass（移植者）　日期：2026-09-12

## 结论

**通过。** 本适配器只读当前已登录页面的课表表格，不发起任何网络请求，不碰凭据，
不读写存储，不写页面，没有第三方域。

## 请求了哪些域

**没有任何网络请求。** 两段脚本都没有 `fetch` / `XMLHttpRequest` / `sendBeacon` /
`WebSocket` / `new Image().src` / `navigator.sendBeacon`：

```bash
grep -nE "fetch\(|XMLHttpRequest|sendBeacon|new WebSocket|\.src\s*=" jw-adapters/masu/*.js
grep -oE "https?://[A-Za-z0-9.:-]+" jw-adapters/masu/*.js
```

- 上面那条 grep **没有输出**（`extract.js` 里唯一一处 `.src` 是 `best.currentSrc || best.src` ——
  读 `<img>` 的地址**值**，不赋值、不发起请求，而且只在「页面上找不到课表表格」时才可能被用到）。
- `grep -oE "https?://[A-Za-z0-9.:-]+"` 只命中三处，**全部在注释里**，没有任何一处被请求：
  `extract.js` 的文件头出处链接（上游仓库）、文件头里说明平台用的
  `http://jwxt.masu.edu.cn:8080`（学校教务处公布的登录地址，只用于说明这是强智 eams
  而不是上游标注的青果），以及 `parse.js` 文件头里的同一个出处链接。
  `fixtures/basic.extracted.json` 里的课表页地址是回归用例的输入数据，不是脚本发出的请求。

因此 `manifest.json` 的 `allowHosts` 为 **空数组**：连同源请求都不需要。
课表页本身由 WebView 按 `loginUrl`（`https://jwxt.masu.edu.cn/`）打开，那是用户手工登录的页面。

## 逐条对照手册 §5

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 不碰凭据 | ✅ 全文没有 `password` / `pwd` / 登录表单值 / `localStorage` / `cookie` 的读取（`token` 只作为 `sectionOf(token)` 的形参名出现，与令牌无关）；不注入脚本，不监听输入。 |
| 2 | 不外发 | ✅ 除本校页面外没有任何请求目标；没有 `fetch` / `XHR` / `sendBeacon` / `WebSocket` / `new Image().src`。 |
| 3 | 请求域可控 | ✅ 不发起请求，`allowHosts: []`（没有通配，更没有 `*.edu.cn`）。 |
| 4 | 只读课表 | ✅ 只读 `#manualArrangeCourseTable` 里的单元格文字、页面 `<select>` 的选项文字和 `document.title`。不碰成绩、学籍、缴费、个人信息页。 |
| 5 | 不埋点 | ✅ 没有统计 / 上报 / 遥测；连 `console.log` 都没有。 |
| 6 | 不 eval 远程代码 | ✅ 全文没有 `eval` / `new Function` / `setTimeout("字符串")`；`setTimeout` 只接收函数。 |
| 7 | 不写页面 | ✅ 只读 DOM：`querySelectorAll` / `getElementById` / `textContent` / `getAttribute` / `table.rows` / `options` / `canvas.toDataURL`。不写 DOM、不改表单、不触发提交、不点击。 |
| 8 | 不依赖用户输入之外的秘密 | ✅ 没有硬编码密钥、令牌或他人学号；脚本里没有出现任何真实账号数据。 |

## 读到了什么数据

只有三类，全部来自用户当前打开的那一页：

1. 课表表格 `#manualArrangeCourseTable` 的每个单元格：文字、`title`、`id`、`className`、
   `rowSpan` / `colSpan`，以及它在表格里的行列位置。**不含**学号 / 姓名 / 成绩。
2. 页面上 `<select>` 的选项文字：学期名候选（用于给学期起名）、「第N周」选择器（用于推算开学日）。
3. `document.title`、`location.href`、取数当天的日期，以及页面 JS 里的 `var unitCount = N`。

以上原始数据经 `parse.js` 转成课表载荷后，才交给空课。

## 已知的能力边界（不是安全问题的说明）

- 适配器运行在用户已登录的页面里，能力等同于该账号；本适配器**只用**其中「读课表页」这一项。
- `fixtures/basic.extracted.json` 是**合成**的（维护者手上没有可公开的真实课表页），
  已删掉姓名学号等个人信息；换成真实 dump 才有回归价值。
