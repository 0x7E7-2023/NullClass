# 空课 NullClass

[![CI](https://github.com/0x7E7-2023/NullClass/actions/workflows/ci.yml/badge.svg)](../../actions/workflows/ci.yml)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)

开源的 Android 大学课表应用。**本地优先**：所有数据仅存储在设备上，无账号、无云端、无追踪。

An open-source class schedule app for Android universities. Local-first: no accounts, no cloud, no tracking.

## 功能 / Features

- 📅 周视图课表（滑动切周、当前周高亮、单双周/连堂支持）
- 🗂️ 底部三 Tab（今日 / 课表 / 我的）：今日页按上午/下午/晚上分组，进行中高亮、已结束置灰
- 🧩 Jetpack Glance 桌面小组件（今日课程 / 下节课，自动随课表刷新）
- 🔔 课前提醒（提前 5/15/30 分钟可配，WorkManager 低功耗调度）
- 📤 课表导入导出（`.nullclass` 文件 + 二维码扫码分享）
- 📥 WakeUp 课表一键迁移（`.wakeup_schedule`，连堂/单双周/节次时间/颜色全保留）
- 🔄 WebDAV 同步（坚果云/NextCloud 自建，端到端属于你；支持定时自动同步）
- 🎨 Material 3 + 动态取色（Material You）
- 💚 纯本地存储（Room），隐私干净：无账号、无云端、无埋点

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

## 下载 / Download

从 [GitHub Releases](../../releases) 获取签名 APK。欢迎第三方 F-Droid 打包（reproducible build 意愿欢迎交流，暂不承诺）。

## 数据与迁移 / Data & Migration

- 课表可随时导出为 `.nullclass` 文件或二维码，发给同学即可导入（合并语义，不覆盖更新的数据）
- 从 WakeUp 课表迁移：WakeUp 内备份出 `.wakeup_schedule` 文件 → 空课「导入/导出 → 从 WakeUp 迁移」
- 多设备同步：设置里填 WebDAV（推荐坚果云等支持 HTTPS 的服务）

### 求你的学校适配 / Request your school

应用内支持「教务系统导入」：你在网页里自己登录教务（空课**不碰**你的账号密码），
打开课表页后一键提取。目前覆盖学校有限，欢迎[提交适配请求](../../issues/new?template=jw-adapter-request.md)——
附上课表页脱敏 HTML 或脚本输出，或直接 PR 一个适配器（`importer/jw/adapters/` 加一个文件即可接入）。

## 模块结构 / Modules

```
:app               壳工程：导航、DI 组装
:core:model        纯 Kotlin 领域模型
:core:data         Room 数据库 + Repository + DataStore
:core:ui           主题与通用 Compose 组件
:feature:schedule  课表主界面（周视图 + 今日页）
:feature:edit      课程/学期编辑
:feature:settings  设置与「我的」页、导入导出界面、教务导入 WebView 宿主
:widget            Glance 桌面小组件
:importer          课表导入导出引擎（分享格式、外部格式适配）
:sync              WebDAV 同步引擎（快照编解码、LWW 合并、定时调度）
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
- [x] M2 周视图课表 + 课程/学期编辑
- [x] M3 Glance 小组件 + 课前提醒
- [x] M4 导入导出 + 二维码分享，发布首个 Release
- [x] M5 教务系统导入框架（WebView + 逐校适配，示例适配器已就绪，真实学校逐步接入）
- [ ] M6 首个真实学校教务适配器 + 交互持续打磨（进行中）

## 许可证 / License

[GPL-3.0](LICENSE)

基于本项目修改、分发的软件必须同样以 GPL-3.0 开源。
