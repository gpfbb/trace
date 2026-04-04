/**
 * @module GeofenceTracker
 * @description 模块统一导出入口
 */

// 核心引擎
export { Engine } from './core/Engine';
export type {
  Geofence,
  GeoPoint,
  LocationFix,
  StaySegment,
  DailySummary,
  VizNode,
  AccelerometerData,
  ActivityResult,
  ActivityType,
  PowerMode,
  ThrottleConfig,
  ModuleConfig,
} from './core/types';
export { DEFAULT_CONFIG, DEFAULT_THROTTLE_CONFIG } from './core/types';

// 存储层
export { Repository } from './storage/Repository';
export type { StorageAdapter } from './storage/Repository';

// 地理计算
export {
  haversine,
  pointInPolygon,
  buildFenceTree,
  getFenceDepth,
  populateDepth,
  computeBBox,
  pointInBBox,
} from './utils/GeoMath';

// 智能调度器
export { SmartScheduler } from './throttle/SmartScheduler';
export type { ThrottleLevel, ScheduleDecision } from './throttle/SmartScheduler';

// 聚合器
export { Aggregator } from './services/Aggregator';

// 桥接 / 适配器
export { GeofenceTrackerModule, InMemoryStorageAdapter } from './adapters/ExistingAppBridge';
export type { LocationAdapter } from './adapters/ExistingAppBridge';
