# 交接文档：TrackMaster UI 重做

> **这份文档写给下一个接手的人。**
> 写于 2026-10-18，分支 `feat/ui-redesign`（33 个提交，从标签 `ui-baseline` 切出）。
>
> 先读 `CLAUDE.md` 了解项目架构与后端不变量，再读这份。
> `CLAUDE.md` 里的架构描述仍然准确；本文档只覆盖 UI 层。

---

## 零、一句话现状

**UI 层重做已完成。** 设计系统统一、全部屏幕迁移、深浅色 + 强调色切换正常、
UI 层零硬编码颜色、lint 零错误、113 个单测全绿、release 包（R8）已验证可跑。

用户能看到的界面工作基本结束。剩下的是内部结构优化，边际收益很低。

---

## 一、先决条件（务必先确认）

### 构建环境

```bash
# JDK 在 D:\javajdk，不是系统默认的 JAVA_HOME
JAVA_HOME=/d/javajdk ./gradlew compileDebugKotlin   # ~45s
JAVA_HOME=/d/javajdk ./gradlew testDebugUnitTest    # 113 个用例
JAVA_HOME=/d/javajdk ./gradlew lintDebug            # 必须 0 错误
JAVA_HOME=/d/javajdk ./gradlew assembleDebug
JAVA_HOME=/d/javajdk ./gradlew assembleRelease      # R8，约 2 分钟
```

- **Android SDK 在 `E:\Androidsdk`**（`ANDROID_HOME` 指向的 `E:\Android` 不存在）
- **不要加 `--offline`**（依赖未全缓存）

### 分支约定

- 当前在 `feat/ui-redesign`，起点标签 `ui-baseline`（可随时 `git reset --hard ui-baseline`）
- **已推送到 GitHub**：<https://github.com/gpfbb/trace/tree/feat/ui-redesign>
- **`main` 也已更新为本分支的内容**（用户要求覆盖，见下一节）
- 远程仓库地址已迁移：`trae_project` → **`trace`**。本地 origin 已更新为
  `https://github.com/gpfbb/trace.git`；旧地址会 302 重定向，但别再用

### ⚠️ main 已被本分支覆盖（重要，先读完再动手）

**`main` 现在和 `feat/ui-redesign` 是同一个提交**（`2aba7d1`）。
是用户明确要求「用我们的直接覆盖」后用 `--force-with-lease` 强推的。

被覆盖掉的 `origin/main` 上有 2 个提交、共 **52 个 bug 修复**：

- `e42e304` 第四轮 — 24 个 P1/P2/P3
- `39a6fb1` 第五轮 — 20+ 个 P0/P1/P2（最终审查）

**这些修复目前不在代码里。** 但它们**没有丢**——覆盖前打了标签：

```bash
git fetch origin
git show backup/pre-overwrite-main          # 原 main 的最后一个提交
git log backup/pre-overwrite-main --oneline # 查看那 52 条修复
git cherry-pick <hash>                      # 需要哪条就挑哪条
```

远程也有这个标签，所以换台机器也能取回。

### 已知两边重叠的地方（挑修复时注意）

覆盖前做过核对：

- **两边独立修了同一个 bug**：`FenceProcessingHolder` 里重复的
  `FenceActiveSession`——我们在 `d680fc8` 统一到 7 字段版本，
  main 在 `39a6fb1`（P0）也做了同样的事。**这条不用再挑**
- **只有 main 有、当前代码没有**：`DataManager` 的 `Locale.getDefault()→Locale.US`
  （我们的同名修复在 `HomeViewModel` 里，不是同一处）。**这条值得挑回来**
- **只有我们有**：`StayDao` 日期查询的 `'localtime'` 修饰符

其他修复还没逐条比对。如果之后发现某个行为不对，先查
`backup/pre-overwrite-main` 上是不是已经有现成的修复，别从零开始查。

### 模拟器（可用）

AEHD hypervisor 驱动已安装，模拟器能跑。**注意 AVD 名字**：

```bash
export ANDROID_SDK_ROOT="E:\\Androidsdk" ANDROID_HOME="E:\\Androidsdk"
E:/Androidsdk/emulator/emulator.exe -avd TrackMaster_Test -no-snapshot-load -no-audio &
```

- `TrackMaster_Test` → android-35，可用
- **`Pixel_8_Pro` 指向不存在的 android-36 镜像，启动即失败**，别用

### 验证纪律

每批改动必须：`compileDebugKotlin` 通过 + `testDebugUnitTest` 不倒退（≥113）
+ `lintDebug` 0 错误。小步提交，每批一个 commit，提交信息用中文。

---

## 二、设计系统怎么用

**地基在 `ui/design/`，这是唯一真源。**

| 文件 | 内容 |
|---|---|
| `Tokens.kt` | 颜色（`AppPalette` / `AccentColor`）、间距 `Space`、圆角 `Radius`、字号 `AppType`、分类配色 `categoricalColors` |
| `Theme.kt` | `LocalPalette` + `ThemeController` + Material 映射 |
| `Components.kt` | `GroupedSection` / `SettingsRow` / `Divider` / `PrimaryButton` / `StatusBadge` / `IconTile` … |
| `Surfaces.kt` | `SurfaceCard` / `AppDialog` |
| `AppIcons.kt` | 图标语义表 |
| `Typography.kt` | `AppType` → Material `Typography` |
| `ThemePicker.kt` | 主题设置弹窗 |

### 硬性规则

1. **颜色一律 `currentPalette.xxx`。UI 层不允许出现 `Color(0x…)`。**
   唯一例外是 `Tokens.kt` 本身。写完用这条命令自检：
   ```bash
   grep -rn "Color(0x" --include=*.kt app/src/main/java/com/trackmaster/app/ui \
     | grep -v "ui/design/Tokens.kt"
   ```
   输出必须是 0 行。

2. **间距用 `Space.xxx`、圆角用 `Radius.xxx`、字号用 `AppType.xxx`。**
   不要写裸的 `16.dp` / `14.sp`。

3. **分组列表结构**：
   ```kotlin
   GroupedSection(title = "本月常去") {
       InfoRow("公司", "48h 20m")
       Divider()
       InfoRow("家", "120h 05m")
   }
   ```

4. **主题控制器是单例**，由 `TrackMasterDesignTheme` 创建并通过
   `LocalThemeController` 下发。**不要在自己的页面里 `rememberThemeController()`**——
   会创建第二份状态，改它不触发重组（这个 bug 已经踩过一次，见第六节）。

5. **图标用 `AppIcons.xxx`**，不要直接写 `Icons.Filled.X`。风格规则集中在那个文件里
   （导航实心/线框、列表行线框、动作按钮实心）。

---

## 三、代码结构

```
ui/design/          设计系统（1654 行）
ui/map/             地图页拆分产物（1056 行）
  ├─ MapEditorState.kt    绘制/编辑三态状态机 + Saver
  ├─ MapToolsPopup.kt     批量工具
  └─ FenceDialogs.kt      新建/列表/编辑弹窗
ui/settings/        设置页拆分产物（486 行）
  ├─ AdvancedSettingsSection.kt
  ├─ RecalculateRelationshipsDialog.kt
  └─ SettingsComponents.kt
ui/statistics/      统计页拆分产物（865 行）
  ├─ DatePickers.kt           年/月/日选择
  ├─ DurationDonutChart.kt    占比环形图
  ├─ FenceTreeSection.kt      围栏统计树
  └─ TimeDimensionSelector.kt
settings/           业务逻辑
  └─ FenceRelationshipRecalculator.kt   父子关系重算算法
core/FenceTreeFlattener.kt              父子树扁平化
MapRegionDetector.kt                    地图服务商地区判断
data/StayRecordNameResolver.kt          围栏名解析（见第六节）
```

屏幕文件仍在原位置（`com.trackmaster.app` 包根目录），没有移动——
移动会牵动大量 import，收益不抵风险。

---

## 四、用户拍板的方向

- **视觉**：仿 iOS 分组列表 + 大圆角，无渐变、无发光、无玻璃拟态
- **主题**：深浅色跟随系统 + 8 个强调色（`CORAL` / `BLUE` / `RED` / `EMERALD` /
  `PURPLE` / `ORANGE` / `TEAL` / `ROSE`），3 种模式（`SYSTEM` / `LIGHT` / `DARK`）
  - 原来的 13 个暗色主题已废弃并删除
- **适配**：锁竖屏，不做横屏/平板
- **图标**：统一 Material 语义，**不引入 SVG**（理由见第七节）
- **图表**：自己用 Compose Canvas 画，**不引入图表库**（理由见第七节）

---

## 五、这次做完的事

33 个提交，净变化 +11204 / -8445 行。按主题分组：

### 设计系统

- 建立 `ui/design/`：token、主题、组件库、图标语义表
- 主题从「13 个暗色主题 + 全局单例」改为「深浅色 + 强调色 + CompositionLocal」
- **11 个对比度测试**（`DesignSystemTest`），当场抓出并修掉 2 个真实缺陷：
  5 个强调色配白字不达标（2.5:1）、浅色模式状态色只有 2.2:1

### 屏幕重做

首页、历史、引导、底部标签栏、分类管理、设置、统计、数据管理、地图顶栏。
所有屏幕零硬编码颜色。

### 拆分（降低单文件复杂度）

| 文件 | 拆分前 | 拆分后 |
|---|---:|---:|
| `MapScreen.kt` | 1334 | 887 |
| `SettingsScreen.kt` | 1154 | 546 |
| `StatisticsScreen.kt` | 1051 | 360 |
| 新增 `ui/map/` `ui/settings/` `ui/statistics/` | — | 2407 |

### 删除

`GlassComponents.kt`(852)、`Color.kt`(149)、旧 `Theme.kt`/`Type.kt`/`TrackMasterTheme.kt`、
`CategorizedFenceListScreen.kt`(379)、`VacuumPeriodInfo.kt`(82)、
死路由 `Screen.Splash`/`Screen.FenceDetail`、`BottomNavItem`。
`ui/theme/` 整个包已不存在。

### 修掉的真实缺陷（不止 UI）

| 缺陷 | 说明 |
|---|---|
| 缺定位权限时 app 崩溃 | Android 14 起前台定位服务缺权限会抛 SecurityException；且 `startForegroundService()` 拉起的服务必须 5 秒内调一次 `startForeground()`。用户点「稍后授权」就会撞上 |
| 运行时切换主题完全不生效 | `MainActivity` 和 `SettingsScreen` 各自 `remember` 了一个 `ThemeController`，是两个独立实例 |
| 多边形围栏面积恒为 0 | `Σ ΔλΔφ` 不是鞋带公式，轴对齐矩形恒等于 0 |
| 父子树成环会栈溢出 | `FenceTreeFlattener` 用队列做可达性遍历 |
| 父围栏删除后子围栏从列表消失 | 孤儿节点提升为顶级节点 |
| 围栏名被写成 UUID | 见第六节，**这类 bug 出现了两次** |
| 统计查询在主线程跑数据库 | `getStatistics` 改成 suspend + IO |
| 分类功能从未接通 | 页面存在但无入口、围栏无法归分类。已接通 |
| 弹窗底色被染成灰色 | 见第六节 |
| 语言切换后界面文字不刷新 | Compose 缓存了 `LocalConfiguration` |
| 停留时长不实时刷新 | 首页/统计页原来 60s / 30s 才刷一次，显示却精确到秒 |
| 高德地图在境外不可用 | 见第六节 |

---

## 六、踩过的坑（重点，别再踩）

### 1. 「ID 被写进名称列」——出现了两次

`stays.fence_name` / `daily_stays.fence_name` / `events.fence_name` 里存的是
围栏 UUID，界面上直接显示 `bf64623f-7995-4af7-9f27-b4b12feeffed`。

两次来源：
1. `SqliteStorageAdapter` 写 `stays` 时用了 `leafGeofenceId`（897c193 修）
2. `FenceProcessingHolder.handleFenceExit` 拿不到围栏名，把 `fenceId` 传给
   `recordFenceEvent` / `onFenceExit` / `saveLastExitState`（012ab9f 修）

**排查方法**：任何把 `fenceId` 传给名字参数的地方都值得怀疑。搜：
```bash
grep -rn "onFenceExit(fenceId, fenceId\|recordFenceEvent(fenceId, fenceId" --include=*.kt app/src/main/java
```

**已落库的脏数据**由 `data/StayRecordNameResolver.kt` 在读取时兜底：
只有当存的「名字」恰好等于 `fenceId` 时才替换成围栏表里的当前名字
（历史改名记录不受影响）。应用在四处：首页本月常去、统计页、历史页、导出 JSON。

**没有写数据库迁移**——改用户历史记录不可逆，风险高于收益。

### 2. Material 的 tonalElevation 会把表面染成灰/粉

`surfaceColorAtElevation` 会把 `surfaceTint` 的 **alpha 强制覆盖**成 0.1076
再压到 surface 上。

- 把 `surfaceTint` 设成 `Color.Transparent` 是**错的**——Transparent 是 RGB 全黑，
  结果是「10.76% 黑压在白色卡片上」= `#E4E4E4`
- 必须设成**卡片色本身**才是真正的无操作（见 `ui/design/Theme.kt`）

判断方法：`adb exec-out screencap > raw.bin` 拿原始 RGBA（12 字节头：w, h, format），
按 `12 + (y*W + x)*4` 用 `od` 读像素。别靠肉眼猜。

### 3. 高德地图的境外限制

高德**国内版** Key 在境外 IP 下返回 `INSUFFICIENT_ABROAD_PRIVILEGES`（错误码 7），
地图全灰；Google Maps 在国内不可用。

已用 `MapRegionDetector` 按设备 Locale 国家代码自动选
（CN/HK/MO/TW → 高德，其余 → Google），**只在用户没显式选过时生效**。
这是启发式：中国用户出国时 Locale 仍是 zh-CN，会选中高德然后失败，需手动切。

### 4. Compose 的 LocalConfiguration 缓存

`LanguageManager.applyLocale` 只改 Resources，但 `stringResource()` 依赖
`LocalConfiguration` 做缓存——Resources 变了而它不是新对象，已有文本节点不会更新。

修法在 `MainActivity`：一个 `localeVersion` 状态，切语言时 +1，以它作 key
重建 `LocalConfiguration`。通知链路：
`OnboardingScreen` / `SettingsPage` → `TrackMasterNavHost` → `MainActivity`。

### 5. HorizontalPager 只组合当前页

页内的 `LaunchedEffect` / `DisposableEffect` 随页离场销毁。需要父级在翻到该页时
主动刷新状态（引导页的权限状态就是这么修的）。

### 6. 测试不要依赖「当前时刻」

`StayDurationManagerTest` 原来用 `Date()` 当基准再加 1 小时。如果测试恰好在
23:00 之后跑，加完跨过午夜，mock 把时长拆到两天，断言差几十秒。
**跑测试的时刻不应该影响结果**——固定到某天上午 10 点。

### 7. 验证 UI 的实用手段

```bash
adb exec-out screencap -p > shot.png                    # 截图
adb shell "uiautomator dump //sdcard/ui.xml"           # UI 层级（路径必须 // 前缀）
adb exec-out screencap > raw.bin                        # 原始像素，用于取色
```

- **Git Bash 会把 `/sdcard` 转成 `E:/Git/sdcard`**，所以 dump 路径必须写 `//sdcard`
- **点击坐标必须从 `uiautomator` 的 bounds 取，不要从截图换算**——
  换算常差几十像素，会点在按钮之间的缝隙上，误判成「点了没反应」
  （我在这上面浪费过好几轮）

---

## 七、验证过但「不要做」的事

避免下一个人重复劳动：

### 换 SVG 不会让图标更清晰

项目用的 `ImageVector` **已经是矢量**，和 SVG 是同类东西。实测通配符 import 版
与具名 import 版的 release dex **完全一样（4792 KB）**——R8 早就把没用到的图标
剔干净了。图标层面能改善的只有「风格统一」，这个已经做了。

### 不引入图表库

统计页只需要「各围栏时长占比」一种图。Vico / MPAndroidChart 要加几百 KB 依赖，
还要为它们做主题适配（库自带配色、文字样式、动画）。Canvas 画环约 100 行，
颜色直接用围栏自己的颜色，深浅色自动跟随。

### 不写数据库迁移来修 UUID

见第六节第 1 条。

---

## 八、还剩什么

| 项 | 说明 | 价值 |
|---|---|---|
| `DataManagementScreen`(745) / `CategoryManagerScreen`(502) 未按职责拆分 | 单文件偏大 | 纯可维护性，零用户可见变化 |
| `MapScreen`(887) 主函数仍有 11 个 `mutableStateOf` | 绘制/编辑状态已收进 `MapEditorState`，剩下的是弹窗可见性之类的 UI 开关 | 同上 |
| 只有 MapScreen / MapToolsPopup / HistoryScreen / OnboardingScreen 用了 `rememberSaveable` | 其余屏幕进程被杀会丢状态 | 低频场景（已锁竖屏，转屏不是问题） |
| 统计图表 | 环形图已做；没有时间趋势折线图 | 产品决策，非技术债 |

**说实话，这些的边际收益已经很低。** 继续拆文件只是让文件更短，行为和功能不会变。

---

## 九、给下一个人的建议

这两轮最有价值的发现（围栏名变 UUID、面积恒为 0、分类没入口、缺权限崩溃）
**都不是「UI 代码写得不好」，而是用起来才暴露的功能缺陷**。

所以比起继续拆文件，更值得做的可能是：

1. **在真机上实际用一段时间**。模拟器只能验证「能不能跑、看不看得清」，
   真实手感（滑动、动画、地图交互、GPS 漂移下的行为）只有真机能判断
2. **带着「这个功能真的能用吗」的眼光去点每个入口**。分类管理就是这样发现的：
   页面、数据、CRUD 全都在，但没有任何入口，功能等于不存在

---

## 十、用户偏好（重要）

- 用户不熟悉这份代码的内部实现（项目大部分由 AI 生成），**沟通要说人话**，
  避免术语堆砌，讲清楚「会有什么后果」而不是「用了什么模式」
- 用户重视**可验证的结果**：改完要能编译、测试要过、最好能说清怎么确认真的好了
- 用户要求**小步提交**，每批可回退
- 涉及范围变更、删除文件、合并分支等，**先问再做**
- 用户会自己试用并反馈问题（这次几个关键 bug 都是他先用出来的）

---

## 十一、参考资料

- `CLAUDE.md` —— 架构总览、模块地图、后端不变量与关键陷阱
- `README.md` —— 功能清单、技术栈、构建方式
- `STAY_DURATION_DESIGN.md` —— 停留计时设计（注意：其中「所有命中围栏并行计时」
  与当前代码的「叶子优先」实现不一致，以代码为准）
- `modules/geofence-tracker/` —— TypeScript 设计原型，用户要求保留作参考
