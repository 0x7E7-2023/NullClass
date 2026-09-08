# 贡献指南 / Contributing

欢迎 issue 和 PR！

## 开发环境

- JDK 17+
- Android Studio（Ladybug 或更新版本）
- Android SDK Platform 36

## 提交规范

使用 Conventional Commits：

```
feat: 新功能
fix: 修复缺陷
docs: 文档
refactor: 重构
test: 测试
chore: 构建/工具链
```

## PR 流程

1. fork 并从 `main` 拉分支：`feat/xxx` 或 `fix/xxx`
2. 保证 `./gradlew assembleDebug test` 通过
3. 描述清楚改动动机与影响
4. 等待 CI 通过后 review

## 贡献教务适配器

适配器不需要写 Kotlin：往 `jw-adapters/` 加一个目录（`manifest.json` + `extract.js` + `parse.js` + fixtures），
在 `index.json` 里加一条，然后提 PR。完整规范见 [`docs/jw-adapter-spec.md`](docs/jw-adapter-spec.md)。

- CI 会用 Rhino **真实执行**你的 `parse.js` 并与 fixtures 比对，所以本地先跑 `./gradlew :importer:test`
- 合并前维护者会**逐个人工审计**脚本全文与请求目标（内置适配器的信任来源）
- fixtures 必须脱敏（删掉姓名、学号、身份证号）

### OCR 引擎 / 模型升级清单

改动 `:ocr` 的模型或 ONNX Runtime 版本时，必须重跑：

1. `./gradlew :ocr:test`（JVM 上用桌面版 ORT 跑同一份模型：字典规模、检测、识别）
2. 模拟器/真机人工回归一张真实课表截图（`__ncCapabilities.ocr`、识别结果、耗时）
3. 复测 release APK 体积（`./gradlew :app:assembleRelease`，按 ABI 拆包后的单包大小）
4. 确认 `.so` 仍满足 16KB 页对齐（`targetSdk 36` 要求）

## 行为准则

保持友善。技术讨论对事不对人。
