# 停留时长统计模块设计文档

## 一、概述
本文档描述了【停留时长统计】核心计时规则功能模块的设计与实现，包括进入即计时、围栏嵌套叠加处理、时间统计规范等功能。

## 二、核心功能

### 1. 进入即计时机制
- 实时监测用户位置，进入围栏立即启动计时
- 离开围栏精确停止计时并累加时长
- 计时精度：秒级

### 2. 多围栏同时计时
- 支持嵌套/叠加围栏（国家→省→市→小区→公司）
- 所有命中围栏并行计时，独立累加
- 不进行最小围栏过滤，确保所有围栏真实统计
- 退出单个围栏时仅停止该围栏，其他围栏继续正常计时

### 3. 时间统计规范
- 按自然日（00:00-23:59）独立统计
- 跨天停留自动分配到对应日期
- 月度/年度统计由每日数据自动汇总
- 单日总时长不超过 24 小时

## 三、数据存储设计

### 1. 表结构

#### daily_stays（按日停留记录表）
| 字段名 | 类型 | 说明 |
|--------|------|------|
| daily_date | TEXT | 日期键（yyyy-MM-dd） |
| daily_fence_id | TEXT | 围栏ID |
| daily_fence_name | TEXT | 围栏名称 |
| daily_duration | INTEGER | 总停留时长（秒） |
| daily_last_updated | INTEGER | 最后更新时间 |
| PRIMARY KEY | (daily_date, daily_fence_id) | 复合主键 |

#### stays（详细停留记录）
| 字段名 | 说明 |
|--------|------|
| stay_id | 停留记录ID |
| fence_id | 围栏ID |
| fence_name | 围栏名称 |
| enter_time | 进入时间 |
| exit_time | 离开时间（可为空表示未离开） |
| duration | 停留时长（秒） |

## 四、核心类说明

### StayDurationManager
核心计时管理器，负责：
- 管理活动围栏会话
- 处理进入/退出事件
- 按日累计停留时长
- 提供日/月/年统计查询
- 验证数据合理性（单日不超过 24 小时）

### GeoFenceDatabaseHelper
数据库帮助类，新增方法：
- `saveDailyStayRecord()` 保存日统计记录
- `getDailyStayRecord()` 获取单条日统计记录
- `getDailyStayRecordsByDate()` 获取指定日期所有记录
- `getOpenStayRecords()` 获取未结束的停留记录
- `getStayRecordsByTimeRange()` 按时间范围获取停留记录

## 五、使用示例

### 基础使用
```kotlin
// 获取管理器实例
val manager = (application as TrackMasterApplication).stayDurationManager

// 进入围栏
manager.onFenceEnter(fenceId, fenceName, Date())

// 离开围栏
manager.onFenceExit(fenceId, fenceName, Date())

// 获取今日某围栏总停留时长
val today = manager.getDateKey(Date())
val record = manager.getDailyRecord(today, fenceId)

// 获取今日所有围栏总停留
val totalToday = manager.getTotalDailyDuration(today)

// 验证数据合理性
val isValid = manager.validateDailyDuration(today)
```

### 统计查询
```kotlin
// 获取月度统计
val monthly = manager.getMonthlyRecords(2024, Calendar.JANUARY)

// 获取年度统计
val yearly = manager.getYearlyRecords(2024)

// 获取当前活动会话
val activeSessions = manager.getActiveSessions()
```

## 六、测试覆盖

已实现的单元测试包括：
1. 单围栏进入退出测试
2. 多嵌套围栏同时计时测试
3. 跨天停留测试
4. 单日多次停留累计测试

## 七、性能优化

- 使用 ConcurrentHashMap 保证线程安全
- 数据库操作采用批量处理
- 实现本地缓存，减少数据库访问
- 使用复合索引加速查询

## 八、日志记录

关键事件都会记录日志：
- 围栏进入/退出
- 日统计更新
- 跨天停留处理
- 数据验证警告
