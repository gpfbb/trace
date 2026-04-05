# 轨迹大师 - 架构设计文档

## 1. 架构概述

采用**分层架构**设计，遵循**依赖倒置原则**，确保模块间低耦合、高内聚。

```
┌────────────────────────────────────────────────────────┐
│                    Presentation层                      │
│  (UI组件、Activity、Fragment、Compose组件)             │
├────────────────────────────────────────────────────────┤
│                     Domain层                         │
│  (Use Cases、业务逻辑、核心引擎)                     │
├────────────────────────────────────────────────────────┤
│                      Data层                          │
│  (Repositories、Data Sources、数据访问)             │
├────────────────────────────────────────────────────────┤
│                    Infrastructure层                   │
│  (数据库、网络、第三方服务、系统服务)                │
└────────────────────────────────────────────────────────┘
```

## 2. 模块结构

### 2.1 核心包结构

```
com.trackmaster.app.architecture/
├── domain/                   # 领域层
│   ├── models/              # 领域模型
│   └── usecases/            # 业务用例
├── data/                    # 数据层
│   ├── repositories/        # 仓库
│   └── datasources/         # 数据源
├── presentation/            # 表现层
│   ├── viewmodels/          # 视图模型
│   └── ui/                  # UI组件
└── common/                  # 通用组件
    ├── utils/               # 工具类
    └── constants/           # 常量定义
```

### 2.2 模块职责

| 模块 | 职责 | 主要组件 |
|------|------|----------|
| **Domain Models** | 定义核心数据结构，无业务逻辑 | Geofence、LocationFix、StaySegment 等 |
| **Use Cases** | 封装业务逻辑，处理核心功能 | FenceTrackingEngine、GeofenceManagementUseCase 等 |
| **Repositories** | 数据访问抽象，组合数据源 | GeofenceRepository、EventRepository 等 |
| **Data Sources** | 具体数据访问实现 | SQLiteGeofenceDataSource、SQLiteEventDataSource 等 |
| **Presentation** | 界面展示和用户交互 | ViewModels、Compose UI 组件 |
| **Common Utils** | 通用工具和辅助功能 | GeoMath、SmartScheduler 等 |

## 3. 核心功能实现

### 3.1 围栏追踪引擎

**FenceTrackingEngine** 是系统的核心组件，负责：

- 接收位置更新
- 识别用户所在的围栏（支持嵌套围栏）
- 基于面积大小优先识别小围栏
- 生成停留片段（StaySegment）
- 智能调度定位频率

### 3.2 位置服务

**LocationService** 负责：

- 与高德地图SDK交互获取位置
- 实现快速首次定位
- 处理定位权限和服务状态
- 将位置更新传递给引擎

### 3.3 数据管理

**StayDurationUseCase** 负责：

- 管理围栏停留时长
- 处理跨天停留
- 生成日统计数据
- 保存停留记录

### 3.4 统计分析

**StatisticsUseCase** 负责：

- 生成日/月/年统计数据
- 计算围栏停留时长
- 提供数据可视化支持

## 4. 使用方法

### 4.1 初始化应用

```kotlin
// 在 Application 类中初始化
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // TrackMasterApp 会自动初始化所有组件
    }
}
```

### 4.2 启动定位服务

```kotlin
val app = application as TrackMasterApp
app.startLocationService()
```

### 4.3 使用围栏管理功能

```kotlin
val useCase = app.getGeofenceManagementUseCase()

// 获取所有围栏
val fences = useCase.getAllFences()

// 创建新围栏
val newFence = Geofence(
    id = UUID.randomUUID().toString(),
    name = "我的家",
    type = GeoFenceType.CIRCLE,
    center = GeoPoint(39.9042, 116.4074),
    radius = 100.0
)
val fenceId = useCase.createFence(newFence)
```

### 4.4 获取统计数据

```kotlin
val useCase = app.getStatisticsUseCase()

// 获取今日统计
val today = SimpleDateFormat("yyyy-MM-dd").format(Date())
val dailyStats = useCase.getDailyStatistics(today)

// 获取月度统计
val calendar = Calendar.getInstance()
val monthlyStats = useCase.getMonthlyStatistics(
    calendar.get(Calendar.YEAR),
    calendar.get(Calendar.MONTH) + 1
)
```

## 5. 性能优化

1. **智能定位调度**：基于移动状态和精度动态调整定位频率
2. **空间索引**：使用 BBox 快速排除，减少点-in-多边形计算
3. **缓存策略**：合理使用内存缓存，减少数据库访问
4. **协程并发**：使用 Kotlin 协程处理异步任务，避免主线程阻塞
5. **批量操作**：支持批量插入事件和记录，提高数据库性能

## 6. 扩展性

- **数据源扩展**：可轻松添加网络数据源、缓存数据源等
- **引擎扩展**：可添加更多围栏类型和匹配算法
- **UI扩展**：支持多种UI框架（Compose、View体系）
- **服务扩展**：可集成更多位置服务提供商

## 7. 测试策略

- **单元测试**：测试核心算法和业务逻辑
- **集成测试**：测试模块间协作
- **端到端测试**：测试完整用户流程
- **性能测试**：测试定位和围栏检测性能

## 8. 技术栈

- **语言**：Kotlin
- **架构**：Clean Architecture + MVVM
- **数据库**：SQLite
- **位置服务**：高德地图SDK
- **异步处理**：Kotlin Coroutines
- **UI框架**：Jetpack Compose
- **依赖注入**：手动依赖管理（可扩展为Dagger/Hilt）

## 9. 版本控制

- **架构版本**：v1.0
- **API版本**：向后兼容
- **数据版本**：支持数据迁移

## 10. 总结

重构后的架构具有以下优势：

- **模块化**：清晰的职责分离，易于维护和扩展
- **可测试性**：依赖注入和接口抽象，便于单元测试
- **性能优化**：智能调度和空间索引，提高运行效率
- **可扩展性**：良好的分层设计，支持未来功能扩展
- **代码质量**：遵循最佳实践，代码清晰易读

通过此架构，轨迹大师应用将具备更好的性能、可维护性和扩展性，为用户提供更优质的位置追踪和围栏管理体验。