# 空课 NullClass

[![CI](https://github.com/0x7E7-2023/NullClass/actions/workflows/ci.yml/badge.svg)](../../actions/workflows/ci.yml)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)

开源的 Android 大学课表应用。**本地优先**：所有数据仅存储在设备上，无账号、无云端、无追踪。

An open-source class schedule app for Android universities. Local-first: no accounts, no cloud, no tracking.

## 功能 / Features

- 📅 周视图课表（滑动切周、当前周高亮、单双周/连堂支持）
- 🎓 上课中
  - 🗂️ 今日课表：底部三 Tab（今日 / 课表 / 我的），按上午/下午/晚上分组；正在上的课置顶实时卡片（进度条 + 还剩 X 分钟倒计时）
  - 🧩 桌面小组件：Jetpack Glance（今日课程 / 下节课），上课中状态带分钟级倒计时，随课节边界自动刷新
- 🔔 课前提醒（提前 5/15/30 分钟可配，WorkManager 低功耗调度）
- 📤 课表导入导出（`.nullclass` 文件 + 二维码扫码分享）
- 📥 WakeUp 课表一键迁移（`.wakeup_schedule`，连堂/单双周/节次时间/颜色全保留）
- 🏫 教务系统导入（WebView 手工登录 + 社区适配器包；课表是图片的学校也能识别）
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
打开课表页后一键提取；保留登录态，学校改了课表可以「一键刷新」。

适配器**不需要写 Kotlin**——一个目录 + 两段 JS 就是一个适配器：

```
jw-adapters/<school-key>/
  manifest.json     学校名、登录页、脚本名、白名单域名
  extract.js        在已登录的教务页面里抓数据
  parse.js          把抓到的数据转成课表
  fixtures/         回归用例（CI 会实跑 parse.js 比对）
```

三种用法：

- **合并进主线**：往本仓库 `jw-adapters/` 加一个目录 + 在 `index.json` 加一条，提 PR。
  维护者会逐个人工审计脚本；合并后随版本内置。
- **自己维护一个库**：fork 这个目录结构托管到你的仓库，别人在应用里粘你的 `index.json` 链接即可安装。
- **自己导入**：把目录打成 zip，在应用里「导入适配器包」——这类适配器**不经过审计**，
  安装界面会明确提示它会读取你已登录的教务页面内容，并支持查看脚本全文。

规范见 [`docs/jw-adapter-spec.md`](docs/jw-adapter-spec.md)，现成例子见 [`jw-adapters/ustc`](jw-adapters/ustc)
与 [`jw-adapters/dlutci`](jw-adapters/dlutci)；没有适配器的学校用内置的 **通用适配器**
（自己填教务地址，应用读页面文字自己还原表格，课表是图片的走离线 OCR）。
[提交适配请求](../../issues/new?template=jw-adapter-request.md)，附上课表页脱敏 HTML 或脚本输出。

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
:importer          课表导入导出引擎（分享格式、外部格式适配、教务适配器运行时）
:sync              WebDAV 同步引擎（快照编解码、LWW 合并、定时调度）
:ocr               离线 OCR 引擎（ONNX Runtime + PP-OCRv6 tiny，图片课表识别）
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
- [x] M6 适配器平台化（包规范 + 社区库 + 用户导入 + 图片课表 OCR）
- [x] M7 首个真实学校适配器 + 交互持续打磨（大连工程学院已内置；上课中实时状态落地）

## 许可证 / License

[GPL-3.0](LICENSE)

基于本项目修改、分发的软件必须同样以 GPL-3.0 开源。
