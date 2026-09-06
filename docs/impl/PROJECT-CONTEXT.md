# NullClass 项目上下文（新会话必读）

> 本文件为跨会话交接文档。开始实现前先读完本文件与对应里程碑文档。
> 最后更新：2026-09-06，基线 commit `b1bfebd`（M2 完成，CI 绿）。

## 项目概况

- **应用**：空课（NullClass）——开源 Android 大学课表应用，中文名「空课」
- **仓库**：https://github.com/0x7E7-2023/NullClass（本地 `D:\NullClass`，main 分支，直接推送）
- **License**：GPL-3.0（防闭源换皮）
- **定位**：本地优先（无账号、无云服务、无埋点），同步走用户自建 WebDAV
- **技术栈**：Jetpack Compose + Material3 + Glance 小组件 + Room + Hilt(KSP) + WorkManager
- **版本约定**：M2 对应 0.1.0（当前 versionName），M3 发 0.2.0，M4 发 0.3.0

## 本机环境（Windows 11）

| 项 | 值 |
|---|---|
| JDK | 25（Gradle 9.5 要求 ≥9.1，OK） |
| Android SDK | `D:\Android\android-sdk`（platform 至 37，build-tools 37）→ 已写入 `local.properties`（不入库） |
| 全局 Gradle | **未安装**，一律用 `.\gradlew.bat` |
| 本地代理 | `http://127.0.0.1:7890`（Clash）。git 已配仓库级代理；PowerShell 下载加 `-Proxy http://127.0.0.1:7890`；Gradle 下载依赖慢时传 `-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890` |
| 测试模拟器 | 雷电 9（`D:\leidian\LDPlayer14`，Android 14 x86_64，1440×2560 density 640）。adb 连接不稳时：`ldconsole quitall` 后 `launch --index 0` 等待，或让用户手动重启模拟器 |
| 联网查资料 | 见用户全局 CLAUDE.md：国内中文→豆包 skill；国际→`tavily_search`(basic)；抓原文→`tavily_extract`。**WebSearch/WebFetch 已被禁用** |
| 查最新依赖版本 | 拉 Maven metadata XML（Google Maven `dl.google.com/android/maven2/...`、Central `repo1.maven.org`），过滤 alpha/beta/rc 取 stable。走代理 |

## 构建与验证命令

```powershell
.\gradlew.bat :app:assembleDebug          # 编译
.\gradlew.bat test                        # 全部单测
.\gradlew.bat :sync:testDebugUnitTest     # 单模块测试
# 装到模拟器
$adb = "D:\Android\android-sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -n com.nullclass.app/.MainActivity
# 截屏（注意：模拟器偶有系统弹窗遮挡，截图异常时先 dumpsys window | grep mCurrentFocus 确认焦点）
& $adb exec-out screencap -p > .test\screen.png
```

## AGP 9 / 新版本踩坑记录（勿重蹈）

1. **AGP 9.3 内置 Kotlin ≠ 内置 Compose 编译器**：Compose 模块必须再 apply `org.jetbrains.kotlin.plugin.compose`（version catalog 里 `kotlin-compose`）
2. `androidx.core 1.19+` 强制 `compileSdk ≥ 37`；当前全部模块 `compileSdk=37, targetSdk=36, minSdk=26`
3. 纯 JVM 模块（core:model、importer）要同时设 Kotlin `jvmTarget=17` 和 `java targetCompatibility=17`
4. Glance 1.2：`SizeMode.Responsive` 收 `Set<DpSize>`；day/night `ColorProvider` 在 `androidx.glance.color` 包（参数是 Compose `Color`）；`material-icons-core` **不随 material3 传递**，用到 `Icons.Default.*` 的模块要显式依赖
5. `kotlin-test` 单独不给 `kotlin.test.Test`，用 `kotlin-test-junit`
6. Android 库模块的测试依赖 Room/Kotlin 库时，`implementation` 不传递——如 `:sync` 直接用了 `room-runtime`/`room-ktx` 才能调 `withTransaction`
7. **Windows 提交的 `gradlew` 会丢可执行位**：`git update-index --chmod=+x gradlew`（CI exit 126 就是这个）
8. Room DAO 默认参数值不被支持（`markCurrent(termId, now = ...)` 会编译失败），参数一律显式传

## 数据架构关键决策（M2 落地，勿推翻）

- **schema v2**：所有业务表 UUID 主键（客户端生成）+ 审计三列 `createdAt/updatedAt/deletedAt`
- **软删除墓碑**：查询一律 `deletedAt IS NULL`；`@Relation` 不带条件过滤，墓碑过滤统一在 Mappers.kt 的 `toModel()`
- **schedule_blocks.termId 冗余列**：周视图免 join
- **period_times 随所属 term 整体取新**（无逐行墓碑）——同步合并按学期两组整体二选一；本地 apply 时必须 `deleteAll()` 再 upsert
- **isCurrent 归一化**：merge 后多个 current 保留 updatedAt 最新；被清除者 bump updatedAt，保证收敛
- **SyncManager 重抛 CancellationException**（不能吞协程取消）
- **大节 = 2 连续小节**，由 `SectionMath` 派生，不落库；`PeriodTime.session`(0上午/1下午/2晚上) 驱动分组线
- 周视图网格严格 56dp/节（`PeriodCellHeight`），课块 `offset(y = 56dp × (startPeriod-1))` 绝对定位；会话分隔线是整行覆盖层（不能进左列流式布局，会错位）
- UI 状态流：`ScheduleViewModel.uiState` 是 `combine(term, selectedWeek).flatMapLatest` 结构

## 流程约定

- 用户使用 `/stage` skill（完整模式）：先写 `docs/impl/YYYY-MM-DD-<name>.md` → 用户点头 → 记 BASE → 实现 → reviewer 子代理审查 → adversary 子代理反驳 → 只修 CONFIRMED → 提交
- Conventional Commits 中文描述
- 每个 milestone 结束：本地 `assembleDebug + test` 绿 → 模拟器手动路径验证 → push → 等 CI 绿
- Room schema 变更：v2 起必须显式 Migration + `MigrationTestHelper` 测试（CI 守护），`schemas/` 目录入库

## 文档索引

- [M3 实现文档](2026-09-06-m3.md)——小组件接数据、课前提醒、自动同步（下一个里程碑）
- [M4 实现文档](2026-09-06-m4.md)——导入导出、二维码分享、WakeUp 适配、首发 Release
- [M5 实现文档](2026-09-06-m5.md)——教务系统 WebView 导入（远期，概要级）
- [M2 实现文档](2026-09-06-m2.md)——已完成，含数据架构决策细节
