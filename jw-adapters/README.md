# jw-adapters —— 教务适配器库

这个目录就是「适配器库」本身：**一个学校一个子目录**，加上根目录的 `index.json`。
构建时它会被打进 APK 成为「内置适配器」；任何人 fork 这个目录、托管自己的 `index.json`，
就是一份可被空课直接引用的第三方适配器库。

## 目录结构

```
jw-adapters/
  index.json              库索引（列出本目录下所有适配器）
  <school-key>/
    manifest.json         清单：学校名、登录页、脚本名、白名单域名、fixture 列表
    extract.js            在已登录的教务页面里执行，抓取数据
    parse.js              把 extract 的输出转成课表载荷（可省略，省略时 extract 输出即载荷）
    fixtures/
      *.extracted.json    extract.js 的真实输出样例（脱敏）
      *.expected.json     对应的课表载荷期望值
```

## 提交一个适配器

1. 复制 `example-univ/` 改个 `key`（小写字母/数字/连字符，2-40 字符），改 `manifest.json`；
2. 写 `extract.js`（DOM 抓取或同源请求）与 `parse.js`；
3. 在浏览器里跑通，把**脱敏后的** extract 输出与期望载荷放进 `fixtures/`；
4. 在 `index.json` 里加一条；
5. `./gradlew :importer:test` 本地跑通（CI 会用 Rhino 实跑你的 `parse.js` 对 fixture 比对）；
6. 提 PR。

完整规范（契约、字段、安全规则、审计要求）见 [`docs/jw-adapter-spec.md`](../docs/jw-adapter-spec.md)。

## 注意

- **`key` 不能与已合并的适配器重复**，也不能用内置适配器的 key 覆盖它们。
- 合并进主线的适配器会被**逐个人工审计**（脚本全文 + 请求目标）。
- 用户自己导入的适配器不受审计保护：安装界面会明确提示，安全性由用户自负。
