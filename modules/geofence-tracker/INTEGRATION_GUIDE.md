# 嵌套地理围栏停留追踪模块 — 接入指南

## 3 步启用

### 步骤 1：准备围栏数据

将现有围栏数据转换为 `Geofence[]` 格式：

```ts
import type { Geofence } from './core/types';
import { computeBBox, populateDepth } from './utils/GeoMath';

// 定义多边形顶点 [lng, lat]
let fences: Geofence[] = [
  { id: 'jia', name: '公司', category: null, parentId: null, depth: 0,
    polygon: [[116.35,39.85],[116.45,39.85],[116.45,39.95],[116.35,39.95]],
    bbox: computeBBox([[116.35,39.85],[116.45,39.85],[116.45,39.95],[116.35,39.95]]) },
  { id: 'yi', name: '园区', category: null, parentId: 'jia', depth: 1,
    polygon: [...], bbox: [...] },
  { id: 'bing', name: '办公楼', category: 'cat_building', parentId: 'yi', depth: 2,
    polygon: [...], bbox: [...] },
];

// 或自动填充 depth：
fences = populateDepth(fences);
```

**注意**：
- `category` 仅绑定叶子围栏，父级设为 `null`
- `polygon` 为 `[经度, 纬度]` 顶点数组
- `bbox` 用 `computeBBox()` 预计算

### 步骤 2：实现存储适配器

实现 `StorageAdapter` 接口对接你现有的数据库（SQLite / Room / IndexedDB）：

```ts
import type { StorageAdapter } from './storage/Repository';

class MyDBAdapter implements StorageAdapter {
  async insertSegments(segs) { /* DB 批量插入 */ }
  async querySegmentsByDate(dateKey) { /* 按进入日期查询 */ }
  async queryOpenSegments() { /* endTs === null */ }
  async updateSegments(segs) { /* 批量更新 */ }
  async saveDailySummaries(summs) { /* 保存日汇总 */ }
  async queryDailySummary(dateKey) { /* 查询日汇总 */ }
  async beginTransaction() { /* 开事务 */ }
  async commitTransaction() { /* 提交 */ }
  async rollbackTransaction() { /* 回滚 */ }
}
```

### 步骤 3：3 行代码启用

```ts
import { GeofenceTrackerModule } from './adapters/ExistingAppBridge';

const tracker = new GeofenceTrackerModule(fences, myLocationAdapter, myStorageAdapter);
await tracker.init();
// App 退出时:
await tracker.dispose();
```

## 智能省电

模块内置 SmartScheduler，自动根据静止状态降频定位：

### 三档电源模式

```ts
tracker.setPowerMode('balanced');    // 默认：阶梯降频
tracker.setPowerMode('powersave');   // 省电：降频 + 边界Buffer提频
tracker.setPowerMode('performance'); // 性能：不降频
```

### 降频阶梯

| 静止时间 | 定位间隔 | 级别 |
|---------|---------|------|
| 0-10 min | 5s（原频率） | Level 0 |
| 10-30 min | 2 min | Level 1 |
| 30-60 min | 4 min | Level 2 |
| ≥ 60 min | 8 min | Level 3 |

### 立即重置条件（恢复高频定位）

- 位移 > 50m
- 离开所有围栏
- 屏幕点亮
- 活动识别：WALKING / DRIVING / CYCLING
- SLC 检测到位移 > 300m

### 加速传感器集成

更精准的静止判定：

```ts
// 定位更新时携带加速度数据
tracker.onLocationUpdateWithAccel(fix, { x: 0.1, y: 0.2, z: 9.8, timestamp: Date.now() });
```

### 活动识别集成

```ts
tracker.onActivityDetected({ type: 'WALKING', confidence: 90, timestamp: Date.now() });
```

### SLC 兜底

```ts
if (tracker.shouldRegisterSLC()) {
  // 注册系统 Significant Location Change（>300m 位移唤醒）
  registerSLC();
}
if (tracker.shouldUnregisterSLC()) {
  unregisterSLC();
}
```

## 数据流图

```
┌──────────────┐     LocationFix      ┌───────────────┐
│ GPS / 网络    │ ──────────────────▶  │  SmartScheduler│
│ 定位服务      │                      │  (降频评估)     │
└──────────────┘                      └───────┬───────┘
                                              │ shouldProcess
┌──────────────┐                              ▼
│ 加速度传感器  │ ── accel ───────▶  ┌───────────────┐
└──────────────┘                     │  Engine        │
                                     │  (状态机)       │
┌──────────────┐                     └───────┬───────┘
│ 活动识别     │ ── activity ──▶              │ StaySegment
└──────────────┘                              ▼
                                      ┌───────────────┐
                                      │  Repository    │
                                      │  (缓冲批量写入) │
                                      └───────┬───────┘
                                              │ T+1
                                              ▼
                                      ┌───────────────┐
                                      │  Aggregator    │
                                      │  (跨天切割+累加)│
                                      └───────┬───────┘
                                              │
                                     ┌────────┴────────┐
                                     ▼                  ▼
                               DailySummary[]     VizNode (Sunburst/TreeMap)
```

## UI 数据绑定示例

### 获取当日统计

```ts
const summaries = await tracker.aggregateDate('2026-04-04');
const vizTree = await tracker.getVizTree('2026-04-04');
```

### DailySummary 输出示例

```json
{
  "date": "2026-04-04",
  "geofenceId": "jia",
  "name": "公司",
  "spatialDurationMin": 180,
  "leafCategoryMap": {},
  "isLeaf": false,
  "depth": 0
}
```

### VizNode 输出示例

```json
{
  "name": "root",
  "children": [
    {
      "name": "公司",
      "children": [
        {
          "name": "园区",
          "children": [
            { "name": "办公楼", "value": 30 }
          ]
        }
      ]
    }
  ]
}
```

直接喂给 ECharts Sunburst / D3 Treemap 即可渲染。

### 实时停留状态

```ts
const currentFence = tracker.getCurrentLeafId();   // 当前所在叶子围栏 ID
const duration = tracker.getCurrentDurationSec();   // 已停留秒数
```

## 降级与恢复策略

| 场景 | 行为 |
|------|------|
| 定位权限拒绝 | 引擎静默跳过，不崩溃，不写入 Segment |
| GPS 信号弱（accuracy > 阈值） | 漂移过滤，丢弃该定位点 |
| App 被杀 | 下次启动时自动恢复未关闭的 Segment |
| DB 锁定 | 写入缓冲区自动重试，不丢失数据 |
| 长时间静止 | SmartScheduler 阶梯降频至 8min/次，省电 |
| 快速移动 | 位移 > 50m 立即重置降频，恢复高频 |

## 文件结构

```
modules/geofence-tracker/
├── index.ts                     # 统一导出入口
├── core/
│   ├── types.ts                 # 全部类型定义 + 默认配置
│   └── Engine.ts                # 状态机、叶子追踪、SmartScheduler 集成
├── storage/
│   └── Repository.ts            # 缓冲批量写入、事务保护、缓存
├── utils/
│   └── GeoMath.ts               # bbox/PIP/Haversine/围栏树
├── throttle/
│   └── SmartScheduler.ts        # 智能降频、多源静止判定、三模式
├── services/
│   └── Aggregator.ts            # T+1 聚合、跨天切割、空间累加、VizTree
├── adapters/
│   └── ExistingAppBridge.ts     # 桥接模块、InMemoryAdapter、GeofenceTrackerModule
├── tests/
│   └── integration.test.ts      # 集成测试
└── INTEGRATION_GUIDE.md         # 本文档
```
