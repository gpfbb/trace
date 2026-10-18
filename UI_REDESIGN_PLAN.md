# UI 重做交接文档

> **给接手 UI 重做工作的工程师**：本文档写于 2026-10-18，是 TrackMaster 后端修复工作
> （分支 `fix/critical-path-repair`，19 个提交）收尾时留下的交接说明。
> 请先读 `CLAUDE.md` 了解项目架构与已知陷阱，再读本文档。

---

## 一、先决条件（务必先确认）

### 1. 构建环境
- **Android SDK 在 `E:\Androidsdk`**（不是 `ANDROID_HOME` 指向的 `E:\Android`，那个路径不存在）
- 编译：`JAVA_HOME=/d/javajdk ./gradlew compileDebugKotlin`（约 40-50 秒）
- 测试：`JAVA_HOME=/d/javajdk ./gradlew testDebugUnitTest`（当前 **42 个用例全绿**）
- 打包：`JAVA_HOME=/d/javajdk ./gradlew assembleDebug`
- **不要加 `--offline`**（依赖未全缓存）

### 2. 分支状态
- 当前在 `fix/critical-path-repair`，领先 `main` 19 个提交
- **用户明确要求：不要自动合并到 main，也不要推送。** 每次要动分支前问用户。
- UI 重做建议**从当前分支再开一个新分支**（例如 `feat/ui-redesign`），
  这样后端修复与 UI 重做的回滚互不影响。
- **`modules/` 目录（2030 行 TypeScript）保留**：用户说后面重做 UI 时可能当参考，
  暂时不要删。

### 3. 验证纪律
- 每批改动必须：`compileDebugKotlin` 通过 + `testDebugUnitTest` 不倒退（≥42 全绿）
- 小步提交，每批一个 commit，可单独回退
- 提交信息用中文，结尾加 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`

---

## 二、当前 UI 层实况（已核实的数据）

总计 **9354 行**（含 theme/components/navigation/screens）。

| 文件 | 行数 | 状态 |
|---|---:|---|
| `ui/screens/MapScreen.kt` | 1334 | **最大，混了 7 种职责，必须拆分** |
| `SettingsScreen.kt` | 1154 | 单函数 569 行 |
| `StatisticsScreen.kt` | 1051 | 单函数 252 行 |
| `ui/theme/GlassComponents.kt` | 852 | 组件库，13 个 Composable |
| `DataManagementScreen.kt` | 699 | 单函数 257 行 |
| `ui/components/GoogleMapWrapper.kt` | 491 | 地图封装 |
| `ui/screens/OnboardingScreen.kt` | 412 | 5 页 Pager |
| `ui/components/AMapViewWrapper.kt` | 405 | 地图封装 |
| `CategoryManagerScreen.kt` | 392 | |
| `ui/CategorizedFenceListScreen.kt` | 379 | |
| `StatsExportImportScreen.kt` | 343 | |
| `ui/screens/HistoryScreen.kt` | 296 | |
| `ui/components/ThemePicker.kt` | 270 | |
| `ui/theme/ThemeManager.kt` | 209 | |
| `ui/navigation/TrackMasterNavHost.kt` | 209 | |
| `ui/screens/HomeScreen.kt` | 191 | |
| 其余（Color/Type/Theme/nav/Screen/VacuumPeriodInfo） | ~500 | |

### 导航结构
底部导航 4 个 tab：**首页 / 地图 / 历史 / 设置**。
另有 3 个非 tab 页面：引导页、统计详情、分类管理、数据导入导出。
- `Screen.Splash` 与 `Screen.FenceDetail` **是死路由**（无 composable 注册、无 navigate 调用），
  UI 重做时可顺手删掉，或补实现。
- 统计页目前以**浮层 Dialog** 形式从首页打开，同时 `Screen.Statistics` 路由也存在但从不 navigate
  （两条路径并存，重做时应二选一）。

### ViewModel 现状（重要）
**只有两个**：`HomeViewModel`（191 行）、`MapViewModel`（35 行，只做写代理）。
以下 5 个大屏**完全没有 ViewModel**，全部 `remember { mutableStateOf(...) }` + 直接调
`TrackMasterApplication`：

- SettingsScreen
- StatisticsScreen
- DataManagementScreen
- CategoryManagerScreen
- StatsExportImportScreen

而且 **全工程 `rememberSaveable` 使用量为 0** —— 转屏、进程被杀，所有界面状态全丢。

---

## 三、重做前必须先解决的三件事

这三件不是"可以顺便做"，而是**不解决就会把债原样搬到新 UI 里**。

### 问题 1：MapScreen 的 1334 行混了 7 种职责

单个顶层 Composable（L77-519，442 行）持有 **21 个 `mutableStateOf`**：

```
localFences, localMatchedIds, localLocation, isDrawingMode, selectedFenceType,
drawingPoints, selectedColor, selectedAlpha, drawingPrecisionMode,
editingPrecisionMode, mapType, showFenceList, selectedFence, showCreateDialog,
showEditDialog, showDeleteConfirm, showRecalculateHint, isEditingFence,
editingPoints, editingFence, showToolsPopup
```

它同时负责：
1. 地图渲染与 provider 切换（AMap / Google 两套 Wrapper，21 个参数重复写两遍）
2. 围栏绘制（`DrawingToolbar` L723-882）
3. 围栏编辑（`EditingToolbar` L885-949）
4. 围栏 CRUD 弹窗（`CreateFenceDialog` / `EditFenceDialog` / `FenceListDialog`）
5. 父子树扁平化（`buildVisibleFenceList` L1036-1072，含局部递归）
6. 面积计算（`calculateFenceArea` L536-556，鞋带公式写死在 UI 里）
7. 批量工具（`ToolsPopup` L630-720，**直接调 `app.dbHelper` 绕过 Repository**）

**建议拆法**：
```
MapScreen.kt              (编排 + 状态提升，目标 < 200 行)
  └─ MapViewModel         (接管全部 21 个 state)
ui/map/DrawingToolbars.kt (绘制 + 编辑工具栏)
ui/map/FenceDialogs.kt    (创建/编辑/删除/列表弹窗)
ui/map/FenceTreeList.kt   (父子树扁平化 + 渲染)
ui/map/MapToolsPopup.kt   (批量工具)
```
`ToolsPopup` 里直接调 `app.dbHelper` 的地方必须改走 `MapViewModel` / Repository。

### 问题 2：五个大屏没有 ViewModel

重做 UI 时**每屏都要建 ViewModel**，否则：
- 没有 `viewModelScope`，协程不受生命周期管理
- 转屏状态全丢（现在就是）
- 业务逻辑写在按钮回调里（例：`SettingsScreen` L438-453 的 O(n²) 父子关系计算
  直接写在 AlertDialog 的确认按钮里，且直接操作 `app.fenceTreeManager`）

**依赖注入**：项目用 Koin。注册模板见 `di/AppModule.kt`：
```kotlin
viewModel { HomeViewModel(get()) }
```
现有两个可参考的仓储接口：`data/repository/FenceRepository.kt`、`SessionRepository.kt`。
**不要**在 ViewModel 里直接拿 `TrackMasterApplication` 单例（现有代码有这个坏习惯，
Phase 6 重构曾试图消除但只做了一半）。

所有需要跨配置变更保留的 UI 状态改用 `rememberSaveable`。

### 问题 3：主题系统有三份调色板 + 大量硬编码颜色

**三份重复的调色板**：
1. `ui/theme/ThemeManager.kt` —— **实际生效的这一份**（91 行 `Color(0x…)`，13 个主题的 `when` 分支）
2. `ui/theme/ColorScheme.kt` —— 只剩 `AppTheme` 枚举（13 个主题名与描述）。
   原来的 13 个 `*Scheme` object 已在修复中删除（原本就是死代码）
3. `ui/components/ThemePicker.kt` —— 14 处 tertiary 色字面量，第三份

**13 个主题**（全是暗色系，name/description 硬编码中文，不随语言切换）：
DARK_NIGHT / SOFT_EYE / CLASSIC_RED / MINIMAL_GRAPHITE / AURORA_GREEN /
DREAM_PURPLE / SUNSET_ORANGE / GLACIER_BLUE / CYBER_TEAL / MINT_FRESH /
ROSE_GOLD / SPACE_GRAY / LAVENDER

**硬编码颜色分布**（重做时需全部替换为主题 token）：
```
SettingsScreen.kt      55 处
StatisticsScreen.kt    37 处
DataManagementScreen.kt 23 处
ThemePicker.kt         14 处
StatsExportImportScreen.kt 9 处
OnboardingScreen.kt     6 处
```
典型问题：`Color(0x4D2A221C)`（DARK_NIGHT 的 surface 色）被写死在各屏卡片里，
切换主题时这些卡片底色不变。

**两套主题系统并存**：
- `TrackMasterTheme`（MaterialTheme，`MainActivity` 里包裹）—— 用的是 `md_theme_*`，
  且 `dynamicColor = true`，但 UI 层几乎不用 `MaterialTheme.colorScheme`
- `ThemeManager`（object 单例 + 全局 `mutableIntStateOf` 版本号触发重组）—— 实际在用

重做时必须**收敛成一套**，否则每加一个组件都要决定用哪套。

### 组件库说明
`ui/theme/GlassComponents.kt`（852 行，13 个 Composable）：
`GlassCard` / `GlassTopCard` / `GlassBottomSheetCard` / `GlassButton` /
`GlassFloatingActionButton` / `ScreenEnterAnimation` / `GlassDialog` / `GlassBadge` /
`GlassTextField` / `ResponsiveText` / `ResponsiveTitleText` / `ResponsiveSubtitleText` /
`ResponsiveLabelText`

**注意：这不是真玻璃拟态。** 全文件 0 次 `Modifier.blur`，实际配方是
半透明渐变 + 双层边框 + 彩色 shadow。
已知问题：
- `GlassCard` 同时跑两个无限动画（8000ms + 3000ms），每帧重建 Brush，无可见性 gating
- `GlassButton` / `GlassFloatingActionButton` 的按压态在同一帧置位复位，按压缩放动画永不显示
- `ResponsiveText` 每个实例都挂一个 300ms `animateColorAsState`，静态文本也有动画器

**重做时可以整库替换，不必保留。**

---

## 四、建议的实施顺序

### 阶段 0：定方向（需用户拍板，不要自行决定）
必须先问清楚：
1. **风格方向**：继续玻璃拟态？Material 3 原生？还是别的（仿 iOS / 极简 / 卡片式）？
2. **是否保留 13 个主题**？还是收敛到 2-3 个（浅色 + 深色）？
3. **是否需要浅色模式**？（当前 13 个主题全是暗色）
4. **是否要做平板/横屏适配**？（`MainActivity` 现在锁死 `portrait`）

### 阶段 1：设计系统（新 UI 的地基）
产出 `ui/design/` 包：
- `Tokens.kt`：颜色、字号、间距、圆角、阴影（**唯一真源**）
- `Theme.kt`：把 `ThemeManager` 的 13 个 `when` 分支改为读 Token
- 基础组件：Button / Card / TextField / Dialog / List / Switch / Badge
- 删掉 `GlassComponents.kt`（或按新风格重写）
- 提供一个演示页（可用 Compose Preview）跑通全部 token 和组件

**验收**：现有任意一屏能不改业务逻辑就套上新主题。

### 阶段 2：逐屏替换（每屏独立 commit）
推荐顺序（从依赖少的开始，积累经验后再动难的）：

1. **OnboardingScreen**（412 行）—— 独立，无数据依赖，适合验证设计系统
2. **HomeScreen**（191 行）—— 最小，已有 HomeViewModel
3. **HistoryScreen**（296 行）
4. **SettingsScreen**（1154 行）—— 先拆成多个 section composable，再重做
5. **StatisticsScreen**（1051 行）—— 图表目前是手搓的进度条，可考虑引入图表库
6. **DataManagementScreen**（699）+ **StatsExportImportScreen**（343）
7. **CategoryManagerScreen**（392）+ **CategorizedFenceListScreen**（379）
8. **MapScreen**（1334 行）—— 最后做，先拆职责再重做

每屏完成后：编译 + 测试 + 手动验证。

### 阶段 3：收尾
- 统一日期/时长格式化（现在散落各处，`StatisticsManager.formatDuration`、
  `HomeViewModel.formatDuration`、`CategoryManagerScreen` 里各有一份）
- 检查所有硬编码字符串是否走了 `strings.xml`（当前 3 种语言：
  `values/` 英文、`values-zh/` 中文、`values-ms/` 马来语）
- 删除死路由 `Screen.Splash` / `Screen.FenceDetail`（或补实现）

---

## 五、绝对不能破坏的东西

UI 重做是**表现层**工作。以下后端不变量改动会引发数据丢失：

1. **`SmartScheduler.evaluate()` 每个 fix 只能调一次**
   - `Engine.evaluateLocation()` 是唯一入口，`processApprovedLocation()` 不碰 scheduler
   - 重复调用会让时间闸门误判"已处理过"，`stays` 表重新变空

2. **`Engine.transition()` 先写库再更新 `currentLeafId`**，失败时两者一起回滚

3. **`Repository.flush()` 事务成功后才清 buffer**，失败有指数退避重试

4. **`appScope` 带 SupervisorJob** —— 不要改回裸 `CoroutineScope(Dispatchers.IO)`

5. **`refreshEngineFences()` 会迁移当前停留状态并清缓存** —— 不要在 UI 层另写一套刷新逻辑

6. **`dateKey` 一律用 `Locale.US` 格式化**（写入方 `StayDurationManager` 的口径）

7. **`AppPreferences.isTrackingEnabled` 是追踪总开关** —— 新增 UI 不要绕过它直接启停服务

---

## 六、已知但本次未处理的问题（可顺手修）

| 问题 | 位置 | 说明 |
|---|---|---|
| 旋转/进程死亡丢状态 | 全 UI | `rememberSaveable` 为 0 处 |
| 主题名不随语言切换 | `ColorScheme.kt` 的 `AppTheme` | `displayName`/`description` 硬编码中文 |
| 硬编码中文字符串 | `VacuumPeriodInfo.kt` L53-73、`CategorizedFenceListScreen.kt` 多处 | 英/马界面会显示中文 |
| 图表是手搓的 | `StatisticsScreen.kt` | 只有进度条，无真实图表 |
| `HistoryScreen` 一次性快照 | `HistoryScreen.kt` L54 附近 | `LaunchedEffect(Unit)` 加载后不刷新 |
| 权限状态不实时 | `OnboardingScreen.kt` L174-176 | 组合期直接 `checkSelfPermission` |
| `MapScreen` 绕过 Repository | `MapScreen.kt` L673-677 | 直接调 `app.dbHelper` |
| `MainActivity` 锁竖屏 | `AndroidManifest.xml` | `screenOrientation="portrait"` |

---

## 七、参考资料

- `CLAUDE.md` —— 架构总览、模块地图、全部关键陷阱
- `README.md` —— 功能清单、技术栈、构建方式
- `STAY_DURATION_DESIGN.md` —— 停留计时设计（注意：其中"所有命中围栏并行计时"
  与当前代码的"叶子优先"实现不一致，以代码为准）
- `modules/geofence-tracker/` —— TypeScript 设计原型，保留作参考

## 八、用户偏好（重要）

- 用户不熟悉这份代码的内部实现（项目大部分由 AI 生成），**沟通要说人话**，
  避免术语堆砌，讲清楚"会有什么后果"而不是"用了什么模式"
- 用户重视**可验证的结果**：改完要能编译、测试要过、最好能说清怎么确认真的好了
- 用户要求**小步提交**，每批可回退
- 涉及范围变更、删除文件、合并分支等，**先问再做**
