/**
 * @module GeofenceTracker/Types
 * @description 嵌套地理围栏停留追踪模块 — 全部类型定义
 */

/** 地理坐标点 [经度, 纬度] */
export type GeoPoint = [number, number];

/** 围栏定义 */
export interface Geofence {
  /** 全局唯一 ID */
  id: string;
  /** 父级 ID，null 表示顶级 */
  parentId: string | null;
  /** 树深度（0 = 顶级），由 buildFenceTree 填充 */
  depth: number;
  /** 分类 ID（仅叶子有效，父级为纯空间容器 = null） */
  category: string | null;
  /** 多边形顶点列表 [[lng,lat], ...] */
  polygon: GeoPoint[];
  /** 预计算的 bbox [minLng, minLat, maxLng, maxLat] */
  bbox: [number, number, number, number];
  /** 显示名称 */
  name: string;
}

/** 停留原始片段 */
export interface StaySegment {
  id: string;
  /** 命中的叶子围栏 ID */
  leafGeofenceId: string;
  /** 进入时间戳（Unix ms） */
  startTs: number;
  /** 离开时间戳（null = 仍在停留） */
  endTs: number | null;
  /** 片段状态 */
  status: 'active' | 'completed' | 'crossed_day';
}

/** 每日汇总 */
export interface DailySummary {
  /** 日期键 yyyy-MM-dd */
  date: string;
  /** 围栏 ID */
  geofenceId: string;
  /** 围栏名称 */
  name: string;
  /** 该围栏总停留分钟数（含子级累加） */
  spatialDurationMin: number;
  /** 叶子分类时长映射 { categoryId: minutes }，仅叶子有效 */
  leafCategoryMap: Record<string, number>;
  /** 是否为叶子围栏 */
  isLeaf: boolean;
  /** 围栏深度 */
  depth: number;
}

/** 可视化节点（Sunburst / TreeMap） */
export interface VizNode {
  name: string;
  value?: number;
  children?: VizNode[];
}

/** 定位更新（由外部适配器提供） */
export interface LocationFix {
  lat: number;
  lng: number;
  /** GPS 精度半径（米） */
  accuracy: number;
  /** 时间戳（Unix ms），**必须使用事件时间而非 Date.now()** */
  timestamp: number;
}

/** 加速度传感器数据 */
export interface AccelerometerData {
  x: number;
  y: number;
  z: number;
  timestamp: number;
}

/** 设备活动识别结果 */
export type ActivityType = 'STILL' | 'WALKING' | 'RUNNING' | 'DRIVING' | 'CYCLING' | 'UNKNOWN';

export interface ActivityResult {
  type: ActivityType;
  confidence: number; // 0-100
  timestamp: number;
}

/** 电源模式 */
export type PowerMode = 'performance' | 'balanced' | 'powersave';

/** 智能降频配置 */
export interface ThrottleConfig {
  /** 漂移过滤阈值（米），accuracy > 此值丢弃 */
  driftThresholdM: number;
  /** 静止判定：距离阈值倍率（精度半径 × 此值） */
  stationaryAccuracyMultiplier: number;
  /** 静止判定：最小距离阈值（米） */
  stationaryMinDistanceM: number;
  /** 静止判定：加速度方差阈值 */
  stationaryAccelVariance: number;
  /** 阶梯降频各阶段（静止分钟 → 间隔ms） */
  throttleSteps: Array<{ afterMin: number; intervalMs: number }>;
  /** 立即重置距离阈值（米） */
  resetDistanceM: number;
  /** 边界 Buffer 区宽度（米），省电模式下临时提频 */
  boundaryBufferM: number;
  /** SLC 兜底位移阈值（米） */
  slcDriftM: number;
  /** 基础定位间隔（ms） */
  baseIntervalMs: number;
}

/** 默认降频配置 */
export const DEFAULT_THROTTLE_CONFIG: ThrottleConfig = {
  driftThresholdM: 50,
  stationaryAccuracyMultiplier: 1.2,
  stationaryMinDistanceM: 30,
  stationaryAccelVariance: 0.15,
  throttleSteps: [
    { afterMin: 0, intervalMs: 5_000 },    // 0-10min: 保持原频率
    { afterMin: 10, intervalMs: 120_000 },  // 10-30min: 2min/次
    { afterMin: 30, intervalMs: 240_000 },  // 30-60min: 4min/次
    { afterMin: 60, intervalMs: 480_000 },  // ≥60min: 8min/次
  ],
  resetDistanceM: 50,
  boundaryBufferM: 200,
  slcDriftM: 300,
  baseIntervalMs: 5_000,
};

/** 模块完整配置 */
export interface ModuleConfig {
  throttle: ThrottleConfig;
  /** Segment 批量写入阈值 */
  flushBatchSize: number;
}

export const DEFAULT_CONFIG: ModuleConfig = {
  throttle: DEFAULT_THROTTLE_CONFIG,
  flushBatchSize: 20,
};
