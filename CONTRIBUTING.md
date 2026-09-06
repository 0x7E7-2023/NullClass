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

## 行为准则

保持友善。技术讨论对事不对人。
