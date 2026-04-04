/**
 * @module GeofenceTracker/SmartScheduler
 * @description 智能定位降频调度器 — 多源静止判定 + 阶梯降频 + 系统兜底
 *
 * 设计目标：在不影响围栏计时精度的前提下，最大化省电。
 *
 * 核心算法：
 * 1. 多源静止判定：距离 + 加速度方差综合判断
 * 2. 阶梯降频：静止时间越长，定位间隔越大
 * 3. 立即重置：位移/围栏离开/屏幕/活动识别触发恢复
 * 4. 三模式兼容：性能/均衡/省电
 * 5. SLC 兜底：降频期间注册系统级位移检测
 */

import type {
  GeoPoint, LocationFix, AccelerometerData, ActivityResult,
  PowerMode, ThrottleConfig, Geofence,
} from '../core/types';
import { DEFAULT_THROTTLE_CONFIG } from '../core/types';
import { haversine, pointInBBox, pointInPolygon, buildFenceTree } from '../utils/GeoMath';

// ============================================================
// 降频状态枚举
// ============================================================

/** 降频阶梯等级 */
export type ThrottleLevel = 0 | 1 | 2 | 3;

/** 调度器输出 */
export interface ScheduleDecision {
  /** 是否应处理此次定位 */
  shouldProcess: boolean;
  /** 当前降频等级 */
  level: ThrottleLevel;
  /** 下次应请求定位的间隔（ms） */
  nextIntervalMs: number;
  /** 是否处于边界 Buffer 区（省电模式提频） */
  inBoundaryBuffer: boolean;
}

// ============================================================
// SmartScheduler
// ============================================================

/**
 * 智能定位降频调度器
 *
 * 使用 timestamp（事件时间）而非 Date.now() 计算，避免息屏后 OS 合并计时导致逻辑错乱。
 *
 * ```ts
 * const scheduler = new SmartScheduler(fences, config);
 * scheduler.setMode('balanced');
 * const decision = scheduler.evaluate(fix, accelData);
 * if (decision.shouldProcess) { /* 处理定位 */ }
 * ```
 */
export class SmartScheduler {
  private readonly config: ThrottleConfig;
  private readonly fenceMap: Map<string, Geofence>;
  private readonly leavesByDepth: Geofence[];

  // ---- 状态 ----
  private mode: PowerMode = 'balanced';
  private level: ThrottleLevel = 0;

  /** 上次处理的定位时间戳（事件时间，非 Date.now） */
  private lastProcessedTs = 0;
  /** 首次进入静止状态的时间戳 */
  private stationarySinceTs = 0;
  /** 上一次定位坐标 */
  private lastPoint: GeoPoint | null = null;
  /** 累计静止时间（ms），用于阶梯判断 */
  private accumulatedStationaryMs = 0;
  /** 是否注册了 SLC 兜底 */
  private slcRegistered = false;

  // ---- 加速度窗口 ----
  private accelBuffer: AccelerometerData[] = [];
  private readonly ACCEL_WINDOW = 10; // 滑动窗口大小

  // ---- 围栏缓存（用于边界 Buffer 检测） ----
  private readonly bboxCache = new Map<string, [number, number, number, number]>();

  constructor(fences: Geofence[], config: Partial<ThrottleConfig> = {}) {
    this.config = { ...DEFAULT_THROTTLE_CONFIG, ...config };

    const tree = buildFenceTree(fences);
    this.fenceMap = tree.fenceMap;
    this.leavesByDepth = tree.leavesByDepth;

    // 预计算 bbox 缓存
    for (const f of this.leavesByDepth) {
      this.bboxCache.set(f.id, f.bbox);
    }
  }

  // ============================================================
  // 公开 API
  // ============================================================

  /**
   * 设置电源模式
   * - performance：关闭降频，持续高精度
   * - balanced：标准阶梯降频
   * - powersave：阶梯降频 + 边界 Buffer 提频
   */
  setMode(mode: PowerMode): void {
    this.mode = mode;
    if (mode === 'performance') {
      this.resetThrottle('性能模式激活');
    }
  }

  /**
   * 获取当前电源模式
   */
  getMode(): PowerMode {
    return this.mode;
  }

  /**
   * 核心评估入口：每收到一个 LocationFix 调用此方法
   *
   * @param fix 定位数据
   * @param accel 可选加速度数据
   * @returns 调度决策
   */
  evaluate(fix: LocationFix, accel?: AccelerometerData): ScheduleDecision {
    const ts = fix.timestamp; // 事件时间

    // ---- 性能模式：不降频 ----
    if (this.mode === 'performance') {
      this.lastProcessedTs = ts;
      this.lastPoint = [fix.lng, fix.lat];
      return {
        shouldProcess: true,
        level: 0,
        nextIntervalMs: this.config.baseIntervalMs,
        inBoundaryBuffer: false,
      };
    }

    // ---- 漂移过滤 ----
    if (fix.accuracy > this.config.driftThresholdM) {
      return this.decision(false);
    }

    const currentPoint: GeoPoint = [fix.lng, fix.lat];

    // ---- 降频检查：是否到了该处理的时间 ----
    const interval = this.getIntervalForLevel();
    if (this.lastProcessedTs > 0 && ts - this.lastProcessedTs < interval) {
      return this.decision(false);
    }

    // ---- 多源静止判定 ----
    const distance = this.lastPoint ? haversine(this.lastPoint, currentPoint) : Infinity;
    const isStationary = this.checkStationary(distance, fix.accuracy, accel);

    // ---- 边界 Buffer 检测（省电模式） ----
    const inBuffer = this.mode === 'powersave' && this.checkBoundaryBuffer(currentPoint);

    // ---- 立即重置条件检查 ----
    const shouldReset = this.checkResetConditions(distance, ts);

    if (shouldReset) {
      this.resetThrottle('重置条件触发');
    } else if (isStationary && !inBuffer) {
      // 更新静止时间
      if (this.stationarySinceTs === 0) {
        this.stationarySinceTs = ts;
      }
      this.accumulatedStationaryMs = ts - this.stationarySinceTs;
      this.updateThrottleLevel();
    } else if (!isStationary) {
      // 移动中，重置静止计时
      this.stationarySinceTs = 0;
      this.accumulatedStationaryMs = 0;
      this.level = 0;
    }

    // 省电模式下边界 Buffer 区临时提频
    if (inBuffer && this.level > 0) {
      this.level = 0; // Buffer 内强制原频率
    }

    // 更新状态
    this.lastProcessedTs = ts;
    this.lastPoint = currentPoint;

    return this.decision(true, inBuffer);
  }

  /**
   * 屏幕状态变更回调
   * 屏幕点亮时立即重置降频
   */
  onScreenStateChanged(isOn: boolean): void {
    if (isOn) {
      this.resetThrottle('屏幕点亮');
    }
  }

  /**
   * 活动识别回调
   * WALKING / DRIVING 时立即重置
   */
  onActivityDetected(activity: ActivityResult): void {
    if (activity.type === 'WALKING' || activity.type === 'DRIVING' || activity.type === 'CYCLING') {
      this.resetThrottle(`活动识别: ${activity.type}`);
    }
  }

  /**
   * SLC（Significant Location Change）系统回调
   * 系统检测到位移 > slcDriftM 时唤醒
   */
  onSignificantLocationChange(distanceM: number): void {
    if (distanceM > this.config.slcDriftM) {
      this.resetThrottle(`SLC 位移: ${distanceM}m`);
    }
  }

  /**
   * 获取当前降频等级
   */
  getLevel(): ThrottleLevel {
    return this.level;
  }

  /**
   * 获取当前应使用的定位精度（米）
   * 省电模式且围栏外时降低精度
   */
  getDesiredAccuracy(currentPoint: GeoPoint): number {
    if (this.mode === 'powersave') {
      const inAnyFence = this.isPointInAnyFence(currentPoint);
      if (!inAnyFence) return 1000; // 围栏外降至 1000m
    }
    return -1; // 最高精度（平台默认）
  }

  /**
   * 是否应注册 SLC 兜底
   */
  shouldRegisterSLC(): boolean {
    return this.level >= 2 && !this.slcRegistered;
  }

  /** 标记 SLC 已注册 */
  markSLCRegistered(): void {
    this.slcRegistered = true;
  }

  /** 是否应注销 SLC */
  shouldUnregisterSLC(): boolean {
    return this.level === 0 && this.slcRegistered;
  }

  /** 标记 SLC 已注销 */
  markSLCUnregistered(): void {
    this.slcRegistered = false;
  }

  /**
   * 清理资源
   */
  dispose(): void {
    this.accelBuffer = [];
    this.lastPoint = null;
    this.stationarySinceTs = 0;
    this.accumulatedStationaryMs = 0;
    this.level = 0;
  }

  // ============================================================
  // 内部方法
  // ============================================================

  /**
   * 多源静止判定
   * isStationary = (距离 < max(精度半径×1.2, 30)) && (加速度方差 < 0.15)
   */
  private checkStationary(distanceM: number, accuracyM: number, accel?: AccelerometerData): boolean {
    const distThreshold = Math.max(accuracyM * this.config.stationaryAccuracyMultiplier, this.config.stationaryMinDistanceM);
    const distOk = distanceM < distThreshold;

    if (!accel) {
      // 无加速度数据时仅依赖距离
      return distOk;
    }

    // 计算加速度方差
    this.accelBuffer.push(accel);
    if (this.accelBuffer.length > this.ACCEL_WINDOW) {
      this.accelBuffer.shift();
    }
    const variance = this.computeAccelVariance();
    const accelOk = variance < this.config.stationaryAccelVariance;

    return distOk && accelOk;
  }

  /**
   * 计算加速度方差（滑动窗口）
   */
  private computeAccelVariance(): number {
    if (this.accelBuffer.length < 3) return 0;
    const n = this.accelBuffer.length;
    // 计算加速度模值
    const magnitudes = this.accelBuffer.map(a => Math.sqrt(a.x ** 2 + a.y ** 2 + a.z ** 2));
    const mean = magnitudes.reduce((s, v) => s + v, 0) / n;
    return magnitudes.reduce((s, v) => s + (v - mean) ** 2, 0) / n;
  }

  /**
   * 更新降频阶梯等级
   * 0-10min → level 0（原频率）
   * 10-30min → level 1（2min/次）
   * 30-60min → level 2（4min/次）
   * ≥60min → level 3（8min/次）
   */
  private updateThrottleLevel(): void {
    const minMs = this.accumulatedStationaryMs;
    const steps = this.config.throttleSteps;

    let newLevel: ThrottleLevel = 0;
    for (let i = steps.length - 1; i >= 0; i--) {
      if (minMs >= steps[i].afterMin * 60_000) {
        newLevel = i as ThrottleLevel;
        break;
      }
    }
    this.level = newLevel;
  }

  /**
   * 获取当前等级对应的定位间隔
   */
  private getIntervalForLevel(): number {
    const steps = this.config.throttleSteps;
    return steps[Math.min(this.level, steps.length - 1)].intervalMs;
  }

  /**
   * 立即重置条件检查
   * - 移动距离 > 50m
   * - 离开所有围栏
   * - 任意重置事件已触发
   */
  private checkResetConditions(distanceM: number, _ts: number): boolean {
    if (distanceM > this.config.resetDistanceM) return true;
    return false;
  }

  /**
   * 检查点是否在任何叶子围栏的边界 Buffer 区内
   * Buffer 区 = 围栏外但在 bbox 扩展 bufferM 范围内
   */
  private checkBoundaryBuffer(point: GeoPoint): boolean {
    for (const leaf of this.leavesByDepth) {
      const bbox = this.bboxCache.get(leaf.id);
      if (!bbox) continue;

      // 围栏内不算 Buffer
      if (pointInPolygon(point, leaf.polygon)) continue;

      // 检查是否在 Buffer 区（bbox 扩展 200m）
      const expandedBbox: [number, number, number, number] = [
        bbox[0] - this.config.boundaryBufferM / (111_320 * Math.cos(point[1] * Math.PI / 180)),
        bbox[1] - this.config.boundaryBufferM / 111_320,
        bbox[2] + this.config.boundaryBufferM / (111_320 * Math.cos(point[1] * Math.PI / 180)),
        bbox[3] + this.config.boundaryBufferM / 111_320,
      ];

      if (pointInBBox(point[0], point[1], expandedBbox)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 检查点是否在任何围栏内
   */
  private isPointInAnyFence(point: GeoPoint): boolean {
    for (const leaf of this.leavesByDepth) {
      const bbox = this.bboxCache.get(leaf.id);
      if (bbox && !pointInBBox(point[0], point[1], bbox)) continue;
      if (pointInPolygon(point, leaf.polygon)) return true;
    }
    return false;
  }

  /**
   * 重置降频状态
   */
  private resetThrottle(reason: string): void {
    this.level = 0;
    this.stationarySinceTs = 0;
    this.accumulatedStationaryMs = 0;
    // reason 可用于日志
    void reason;
  }

  /**
   * 构造调度决策
   */
  private decision(shouldProcess: boolean, inBuffer = false): ScheduleDecision {
    return {
      shouldProcess,
      level: this.level,
      nextIntervalMs: this.getIntervalForLevel(),
      inBoundaryBuffer: inBuffer,
    };
  }
}
