# TrackMaster — 地理围栏轨迹记录

TrackMaster 是一个 Android 地理围栏定位与停留时长统计应用。用户在地图上手绘围栏（圆形或多边形），应用在后台持续追踪用户位置，自动记录进入/离开围栏的时间，并按日/月/年统计在每个围栏内的停留时长。

## 核心概念

### 围栏（GeoFence）
地图上用户绘制的区域，支持**圆形**和**多边形**两种类型。围栏支持嵌套——一个围栏可以包含另一个，形成父子关系。例如"小区→办公楼→办公室"三层嵌套。

### 停留计时（Stay Duration）
- **进入即计时**：检测到用户位置进入围栏时立即开始计时
- **离开即结算**：离开围栏时停止计时，将时长写入数据库
- **跨天拆分**：跨越午夜的停留自动分配时长到对应日期
- **嵌套并行**：多个嵌套围栏各自独立计时，父围栏累加子围栏时长
- **应用重启恢复**：重启后从数据库恢复未关闭的停留会话，补算真空期

### 叶子优先原则
当用户同时处于多层嵌套围栏中（如"小区→办公楼→办公室"），只有最深层的叶子围栏生成停留段（StaySegment），父围栏的计时通过子围栏时长转移累加。

## 截图

（待补充）

## 功能特性

### 地图与围栏
- 高德地图 / Google 地图双引擎（可在设置中切换）
- 手绘圆形和多边形围栏，支持编辑顶点
- 围栏颜色和透明度自定义
- 一键随机分配围栏颜色（避免全红重叠看不清）
- 一键批量调整所有围栏透明度
- 围栏父子关系自动几何计算（嵌套检测）
- 灵动岛：地图顶部实时显示当前命中的最小围栏

### 定位与后台
- 高精度 GPS + 网络混合定位
- 前台服务保活，通知栏显示定位状态
- 自适应定位频率：围栏内高频、围栏外低频
- 三种省电模式：高性能 / 均衡 / 极致省电
- 加速度传感器辅助静止检测
- 空间索引 + 动态缓冲区快速围栏命中检测

### 数据统计
- 日/月/年三维度围栏停留时长统计
- 围栏时长排行榜（Top 10）
- 父子围栏层级树形统计视图
- 月均 Top 3 围栏展示（首页）
- 今日进出次数实时统计

### 数据管理
- 完整备份：围栏 + 事件 + 停留记录一键导出 JSON
- 围栏模板分享：仅导出围栏定义（不含历史数据），他人可导入复用
- 停留记录专项导出/导入（合并策略）
- 自动清理旧事件日志
- 导出文件保存至公共 Downloads 目录（卸载不丢失）

## 技术架构

```
┌──────────────────────────────────────────┐
│  UI Layer (Jetpack Compose + Material3)  │
│  MapScreen / HomeScreen / HistoryScreen  │
│  StatisticsScreen / SettingsScreen       │
└─────────────┬────────────────────────────┘
              │ StateFlow / collectAsState
┌─────────────▼────────────────────────────┐
│  ViewModel Layer                         │
│  MapViewModel / HomeViewModel            │
│  ← Koin DI (AppModule.kt)               │
└─────────────┬────────────────────────────┘
              │ Repository Interfaces
┌─────────────▼────────────────────────────┐
│  TrackMasterApplication (singleton)      │
│  ├─ Engine (核心状态机)                   │
│  ├─ FenceProcessingHolder (围栏处理)      │
│  ├─ StayDurationManager (计时管理)        │
│  ├─ StatisticsManager (统计计算)          │
│  ├─ FenceTreeManager (父子关系)           │
│  └─ RepositoryHolder (共享状态)           │
└─────────────┬────────────────────────────┘
              │
┌─────────────▼────────────────────────────┐
│  GeoFenceLocationService (前台服务)       │
│  ├─ LocationProviderManager              │
│  │   ├─ AMapLocationProvider (默认)      │
│  │   └─ GoogleLocationProvider           │
│  └─ PowerSaveController (省电状态机)      │
└─────────────┬────────────────────────────┘
              │
┌─────────────▼────────────────────────────┐
│  Data Layer                              │
│  ├─ Repository (写缓冲 + 批量事务)        │
│  ├─ DatabaseFacade → 5 DAOs             │
│  ├─ SqliteStorageAdapter (SQLite)        │
│  └─ FenceExporter (JSON 导入导出)         │
└──────────────────────────────────────────┘
```

### 关键设计决策

| 决策 | 说明 |
|------|------|
| 单线程串行化处理 | `appScope = CoroutineScope(Dispatchers.IO)` — 所有围栏状态变更在同一作用域内，通过 `@Volatile` + `AtomicReference` + `synchronized` 保护竞态 |
| 写缓冲批量提交 | `Repository` 内存缓冲 StaySegment，满 5 条或手动 flush 后批量写入 SQLite，事务保护 |
| 内存优先、数据库兜底 | daily_stays 使用 `dailyCache`（ConcurrentHashMap）缓存，定时 5 分钟保存到数据库 |
| 空间索引加速 | `GridSpatialIndex` 将围栏按经纬度网格分组，查询时先 BBox 粗筛再精确 point-in-polygon |
| 动态缓冲区 | `DynamicBufferManager` 根据 GPS 精度和速度自适应调整围栏边界缓冲区 |

### 数据模型

| 表 | 用途 |
|----|------|
| `fences` | 围栏定义（ID、名称、类型、顶点/圆心、颜色、透明度、分类） |
| `categories` | 围栏分类（可自定义分组） |
| `events` | 围栏进入/退出事件日志 |
| `stays` | 围栏停留记录（进入时间、离开时间、时长） |
| `daily_stays` | 按日聚合的停留统计（date + fence_id 复合主键） |

## 构建运行

### 环境要求
- Android Studio Hedgehog (2023.1) 或更新
- JDK 17+
- Gradle 9.0（wrapper 已包含）
- Android SDK 34, minSdk 26

### 命令

```bash
./gradlew assembleDebug          # 编译 Debug APK
./gradlew test                   # 运行单元测试
./gradlew compileDebugKotlin     # 仅 Kotlin 类型检查（快速验证）
./gradlew lint                   # Android Lint 检查
```

### 高德 Key 配置

1. 访问 https://console.amap.com/dev/key/app 申请 Key
2. 替换 `app/build.gradle.kts` 中的 `AMAP_API_KEY`：

```kotlin
debug {
    buildConfigField("String", "AMAP_API_KEY", "\"你的DEBUG_KEY\"")
}
release {
    buildConfigField("String", "AMAP_API_KEY", "\"你的RELEASE_KEY\"")
}
```

### 签名

| 文件 | 密码 | 别名 |
|------|------|------|
| `trackmaster_debug.jks` | trackmaster2024 | trackmaster_debug |
| `trackmaster.jks` | trackmaster2024 | trackmaster |

## 技术栈

- **语言**: Kotlin 1.9.24
- **UI**: Jetpack Compose 1.5.14 + Material3
- **导航**: Navigation Compose 2.7.7
- **DI**: Koin 3.5.3
- **异步**: Coroutines 1.7.3 + Flow
- **数据库**: SQLite（原生 API，未使用 Room）
- **地图**: 高德 3D 地图 SDK 10.0.600 + Google Play Services Maps 19.0.0
- **定位**: 高德定位 SDK + Google Fused Location Provider 21.0.1

## 权限说明

| 权限 | 用途 |
|------|------|
| `ACCESS_FINE_LOCATION` | 精确定位（GPS） |
| `ACCESS_BACKGROUND_LOCATION` | 后台持续定位（Android 10+） |
| `FOREGROUND_SERVICE` | 前台服务保活 |
| `FOREGROUND_SERVICE_LOCATION` | 定位类型前台服务（Android 14+） |
| `POST_NOTIFICATIONS` | 通知栏显示定位状态（Android 13+） |
| `INTERNET` | 地图瓦片加载 |
| `WAKE_LOCK` | 省电模式定时唤醒 |

## 开发注意事项

- 所有 DB 写入后必须调用 `fenceDao.invalidateCache()`，否则后续读取返回旧数据
- 修改围栏后必须调用 `app.refreshEngineFences()` 和 `app.checkAndUpdateFenceState()`
- Engine 的 `transition()` 使用 `AtomicReference.compareAndSet` 防竞态，不要在外部直接写 `currentLeafId`
- 导入围栏后需要 `app.geoFences.clear() + addAll(dbHelper.getAllFences())` 刷新内存列表
- `LaunchedEffect` 中读取的 `State` 变量必须放入 key 列表，否则闭包捕获旧值
- 高德 SDK 必须在任何地图/定位调用之前执行 `updatePrivacyShow` 和 `updatePrivacyAgree`
- SharedPreferences 写入关键状态使用 `commit()` 而非 `apply()`，防止进程崩溃时丢失
