# 安全审计 —— 浙江工业大学（正方新版 jwglxt 平台）

审计对象：`jw-adapters/zjut/` 下的 `extract.js` / `parse.js`，以及它们移植自的上游脚本
`shiguang_warehouse` 的 `ZJUT/zjut_01.js`（快照 commit `e62554a4034386b893bcd6813c7b2b64f8c730a3`，2026-09-12，MIT，上游 maintainer `glxgo`）。

审计方式：逐行读上游脚本 + 逐行读移植件，并跑移植手册 §5 给的静态扫描
（`grep -oE "https?://[A-Za-z0-9.-]+"` 与 `grep -nE "fetch\(|XMLHttpRequest|sendBeacon|new WebSocket|\.src\s*="`）。

## 1. 请求域与请求清单

上游脚本里**没有任何绝对 URL**，两个 `fetch` 的地址都由 `window.location.origin` 拼出来；
移植件沿用同一做法，所以两个脚本请求的主机都只有**用户当前所在的那一个教务主机**。

| 谁 | 方法 | 地址 | 干什么 |
|---|---|---|---|
| extract.js | GET | `<origin>/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N253508&layout=default` | 读课表页上的学年学期字段（`#xnm` / `#xqm`）。**用户已经在课表页时这一步跳过**，直接读页面上的这两个字段 |
| extract.js | POST | `<origin>/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N253508` | 取课表数据（JSON，`kbList` 排课行） |

- `<origin>` 只可能是 `http(s)://www.gdjw.zjut.edu.cn`（`loginUrl` 的主机）——extract 用
  `window.location.origin` 拼地址、不在页面里跳转。
- **`allowHosts` 是空的**：两条请求都是同源，与 `loginUrl` 同一个主机，不需要额外白名单
  （应用会把 `loginUrl` 的主机自动放进白名单）。
- 请求头只带 `Content-Type` / `X-Requested-With` / `Accept`，**不带任何自定义令牌**；
  Cookie 由 WebView 自己按同源规则带上，脚本不读也不写它。
- `parse.js` 不发任何请求（CI 里用 Rhino 实跑，是纯函数）。

## 2. 移植手册 §5 八条逐条

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 不碰凭据 | **过**。脚本没有 `password` / `pwd` / 登录表单读取，也没有 `localStorage` / `sessionStorage` 访问（静态扫描无命中）。唯一的「凭据」是浏览器自己带的会话 Cookie，脚本既不读它也不把它发到别处 |
| 2 | 不外发 | **过**。两个脚本合计只有 `extract.js` 里的 `fetch` 调用，地址全部由 `window.location.origin + '/jwglxt'` 拼成；没有 `sendBeacon` / `WebSocket` / `EventSource` / `new Image().src` / 隐藏表单，也没有任何第三方域 |
| 3 | 请求域可控 | **过**。请求主机只有一个：`loginUrl` 的主机 `www.gdjw.zjut.edu.cn`（http 与 https 都可能是它，取决于用户实际落在哪个地址上）。`allowHosts` 留空，不写通配 |
| 4 | 只读课表 | **过**。只请求课表页与课表接口（`/kbcx/*`），不碰成绩（`/cjcx/*`）、学籍、个人信息、缴费等任何其它接口。**注意**：课表接口的响应体里会夹带登录学生自己的信息（正方把它们放在 `xsxx` 之类的字段里）——`extract.js` 只把 `kbList` 排课行交出去，其它字段一律不带出，也不会进 fixture |
| 5 | 不埋点 | **过**。没有任何统计 / 上报 / 遥测，也没有 `img` 打点 |
| 6 | 不 eval 远程代码 | **过**。两个脚本都没有 `eval` / `new Function`；只对教务自己的响应做 `JSON.parse` |
| 7 | 不写页面 | **过**。`extract.js` 只**读**当前页面里 `#xnm` / `#xqm` 两个字段的值（下拉框或隐藏 input，与上游一样读 `value`）；取课表页 HTML 时用的是 `fetch` + `DOMParser`（离线文档，不插入当前页面），既不写 DOM、不改表单、也不触发提交或点击。上游的 `showToast` / `showSingleSelection` / `notifyTaskCompletion` 这类桥调用**全部没有移植**（我们的移植件连提问桥都不用） |
| 8 | 不依赖用户输入之外的秘密 | **过**。没有硬编码密钥、令牌或他人的学号。脚本里的常量只有正方的菜单号 `N253508` 与上下文路径 `/jwglxt`，都是公开的页面参数 |

**结论：可以进内置库。** 全部请求落在本校教务主机上，只读课表，不碰凭据、不外发、不埋点。

## 3. 移植时对上游做的删改（都不牵涉安全问题，但需要审阅者知道）

1. **ES6 → ES5**：`async/await` 改成 `then` 链，去掉模板串、箭头函数、`let` / `const`。
2. **不问用户**：上游在「不在课表页」时弹窗让用户选学年 / 学期（`showSingleSelection`）。
   移植件改成自动取教务当前学期 —— 用户在课表页时读页面上的 `#xnm` / `#xqm`（他自己切过的学期优先），
   否则取一次课表页 HTML 读这两个下拉框的默认选中项。载荷里用 `warnings` 说明「只导入了当前学期」。
3. **切两段**：上游在同一个脚本里算周次、拼课程、存配置；移植件把取数留在 `extract.js`
   （只交出 `kbList` 排课行），周次解析、课程合并、开学日推算全部落在 `parse.js`，
   这样这段逻辑在 CI 里有真回归。
4. **空教师 / 空教室不再写占位符**：上游写 `'未知'` / `'未排地点'`，在课表里会被当成真名显示；
   移植件留空（`null`）。信息没有丢失——教务本来就没给。
5. **开学日与总周数**：上游给 `semesterStartDate: null`；移植件按学年学期推算开学周的周一，
   并且把「推算了什么、依据是什么」写进载荷的 `warnings`，让用户在导入预览里就能看到。
   总周数取课表里出现的最大周次（与上游同口径），也写进 `warnings`。
6. **脏数据不再静默丢**：上游 `parseCourses` 里 `return` 掉的行不留痕迹；移植件统计跳过行数并写进 `warnings`。

## 4. 没能确定的事（交接给下一位）

- **fixture 是合成的**（`fixtures/basic.extracted.json`）：按上游实际读取的字段名
  （`kcmc` / `xm` / `cdmc` / `cdbh` / `xqj` / `jcs` / `zcd` / `xqh_id`）编出来的形状正确的数据，
  **不是真实抓取**。它只保证「同样的输入永远得到同样的输出」，不保证真实页面上解析正确。
  拿到真实 dump 后请替换 fixture 并重跑门（测试方案 §3）。
- **没验证过的**：正方接口返回的其它字段（例如可能存在的 `sjkList` 作息表）一律没有使用，
  作息表用的是上游脚本里写死的浙江工业大学节次表；`jcs` / `zcd` 的真实写法只覆盖了上游脚本
  能解析的那几种（`"1-2"`、`"1-16周"`、`"1-16周(单)"`、`"1-4,6-8周"`）。
- **开学日**：教务不给，只能推算（见上文第 5 条）。真机上请核对一次 ZJUT 的实际开学日。
- **学期代号**：正方用 `3 / 12 / 16` 表示第一 / 二 / 三学期，移植件的名称与开学日推算先看教务
  自己的下拉框文本（`一/二/三`），文本认不出来才用这套代号。
- 需要真实环境才能验的：登录后的会话是否被两条接口接受、课表页 HTML 里是否真有 `#xnm` / `#xqm` 两个选择框、
  `N253508` 菜单号是否仍然有效。

## 5. 签名

- 上游作者：`glxgo`（shiguang_warehouse，MIT）
- 移植：NullClass（本次移植由 Claude Code 执行）
- 日期：2026-09-12
