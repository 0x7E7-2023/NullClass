# 空课 NullClass

[![CI](https://github.com/0x7E7-2023/NullClass/actions/workflows/ci.yml/badge.svg)](../../actions/workflows/ci.yml)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)

开源的 Android 大学课表应用。**本地优先**：所有数据仅存储在设备上，无账号、无云端、无追踪。

An open-source class schedule app for Android universities. Local-first: no accounts, no cloud, no tracking.

## 功能 / Features

**开发中 / WIP**

- 📅 周视图课表（滑动切周、当前周高亮）
- 🧩 Jetpack Glance 桌面小组件（今日课程 / 下节课）
- 🔔 课前提醒
- 📤 课表导入导出（自有 JSON 格式 + 二维码分享）
- 🎨 Material 3 + 动态取色（Material You）
- 💚 纯本地存储（Room），隐私干净

## 技术栈 / Tech Stack

| | |
|---|---|
| UI | Jetpack Compose + Material 3 |
| 桌面小组件 | Jetpack Glance |
| 架构 | 单 Activity + Compose Navigation，MVVM，多模块 |
| 依赖注入 | Hilt (KSP) |
| 存储 | Room + DataStore |
| 后台任务 | WorkManager |
| 最低版本 | Android 8.0 (API 26) |

## 模块结构 / Modules

```
:app               壳工程：导航、DI 组装
:core:model        纯 Kotlin 领域模型
:core:data         Room 数据库 + Repository + DataStore
:core:ui           主题与通用 Compose 组件
:feature:schedule  课表主界面（周视图）
:feature:edit      课程/学期编辑
:widget            Glance 桌面小组件
:importer          课表导入导出引擎（分享格式、外部格式适配）
```

## 构建 / Build

需要 JDK 17+ 与 Android SDK（compileSdk 36）。

```bash
./gradlew :app:assembleDebug
```

运行测试：

```bash
./gradlew test
```

## 路线图 / Roadmap

- [x] M1 项目骨架
- [ ] M2 周视图课表 + 课程/学期编辑
- [ ] M3 Glance 小组件 + 课前提醒
- [ ] M4 导入导出 + 二维码分享，发布首个 Release
- [ ] M5 教务系统导入（WebView + 逐校适配）

## 许可证 / License

[GPL-3.0](LICENSE)

基于本项目修改、分发的软件必须同样以 GPL-3.0 开源。
