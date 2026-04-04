/**
 * @module GeofenceTracker/Engine
 * @description 核心状态机 — 叶子围栏追踪 + SmartScheduler 集成
 *
 * 核心规则：
 * 1. 叶子优先：仅追踪深度最大的命中围栏，父级不参与实时计时
 * 2. 漂移过滤：accuracy > threshold 的定位点被丢弃
 * 3. 智能降频：通过 SmartScheduler 自适应定位频率
 * 4. 所有计算使用事件 timestamp，不用 Date.now()
 */

import type {
  Geofence, LocationFix, StaySegment, ModuleConfig,
  AccelerometerData, ActivityResult, PowerMode,
} from './types';
import { DEFAULT_CONFIG } from './types';
import { pointInPolygon, buildFenceTree, pointInBBox } from '../utils/GeoMath';
import { SmartScheduler } from '../throttle/SmartScheduler';
import type { Repository } from '../storage/Repository';

/**
 * 围栏追踪引擎
 *
 * ```ts
 * const engine = new Engine(fences, repo, config);
 * await engine.init();
 * const seg = engine.onLocationUpdate(fix); // 每次定位调用
 * await engine.dispose();
 * ```
 */
export class Engine {
  private readonly config: ModuleConfig;
  private readonly repo: Repository;
  private readonly scheduler: SmartScheduler;
  private readonly fenceMap: Map<string, Geofence>;
  private readonly leafFences: Geofence[];

  /** 当前命中的叶子围栏 ID */
  private currentLeafId: string | null = null;
  /** 当前活跃 Segment 的进入时间戳（事件时间） */
  private currentEnterTs = 0;
  /** Segment ID 自增计数器 */
  private segCounter = 0;

  /** 定位回调（供外部轮询或 UI 绑定） */
  private onSegmentCallback: ((seg: StaySegment) => void) | null = null;

  constructor(
    fences: Geofence[],
    repo: Repository,
    config: Partial<ModuleConfig> = {},
  ) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.repo = repo;

    const tree = buildFenceTree(fences);
    this.fenceMap = tree.fenceMap;
    this.leafFences = tree.leavesByDepth;

    this.scheduler = new SmartScheduler(fences, this.config.throttle);
  }

  // ============================================================
  // 生命周期
  // ============================================================

  /** 初始化：恢复未关闭的 Segment */
  async init(): Promise<void> {
    const open = await this.repo.getOpenSegments();
    if (open.length > 0) {
      const latest = open[open.length - 1];
      this.currentLeafId = latest.leafGeofenceId;
      this.currentEnterTs = latest.startTs;
    }
  }

  // ============================================================
  // 定位处理（核心入口）
  // ============================================================

  /**
   * 接收位置更新
   *
   * 流程：
   * 1. SmartScheduler 评估是否应处理
   * 2. 漂移过滤（双重保险）
   * 3. BBox 快速排除 → 精确命中叶子围栏
   * 4. 状态转移 → 生成 StaySegment
   *
   * @returns 生成的 StaySegment（如有），或 null
   */
  onLocationUpdate(
    fix: LocationFix,
    accel?: AccelerometerData,
  ): StaySegment | null {
    // 1. SmartScheduler 评估
    const decision = this.scheduler.evaluate(fix, accel);
    if (!decision.shouldProcess) return null;

    // 2. 漂移过滤（Scheduler 内已过滤，此处双重保险）
    if (fix.accuracy > this.config.throttle.driftThresholdM) return null;

    const point: [number, number] = [fix.lng, fix.lat];

    // 3. 查找最深命中的叶子围栏
    const matched = this.findDeepestMatch(point);

    // 4. 状态转移
    if (matched?.id !== this.currentLeafId) {
      return this.transition(matched?.id ?? null, fix.timestamp);
    }

    return null;
  }

  /**
   * 活动识别回调（转发给 Scheduler）
   */
  onActivityDetected(activity: ActivityResult): void {
    this.scheduler.onActivityDetected(activity);
  }

  /**
   * 屏幕状态变更（转发给 Scheduler）
   */
  onScreenStateChanged(isOn: boolean): void {
    this.scheduler.onScreenStateChanged(isOn);
  }

  /**
   * SLC 系统回调（转发给 Scheduler）
   */
  onSignificantLocationChange(distanceM: number): void {
    this.scheduler.onSignificantLocationChange(distanceM);
  }

  /**
   * 设置电源模式
   */
  setPowerMode(mode: PowerMode): void {
    this.scheduler.setMode(mode);
  }

  /**
   * 是否应注册 SLC 兜底
   */
  shouldRegisterSLC(): boolean {
    return this.scheduler.shouldRegisterSLC();
  }

  /**
   * 是否应注销 SLC
   */
  shouldUnregisterSLC(): boolean {
    return this.scheduler.shouldUnregisterSLC();
  }

  /**
   * 应用终止
   */
  async onAppTerminate(): Promise<void> {
    if (this.currentLeafId) {
      this.transition(null, Date.now());
    }
    await this.repo.flush();
  }

  /**
   * 资源释放
   */
  async dispose(): Promise<void> {
    await this.onAppTerminate();
    this.scheduler.dispose();
    await this.repo.dispose();
  }

  // ============================================================
  // 查询
  // ============================================================

  getCurrentLeafId(): string | null { return this.currentLeafId; }
  getCurrentDurationSec(): number {
    if (!this.currentLeafId || this.currentEnterTs === 0) return 0;
    return Math.floor((Date.now() - this.currentEnterTs) / 1000);
  }
  getScheduler(): SmartScheduler { return this.scheduler; }

  /** 注册 Segment 生成回调 */
  onSegmentCreated(cb: (seg: StaySegment) => void): void {
    this.onSegmentCallback = cb;
  }

  // ============================================================
  // 内部
  // ============================================================

  /**
   * BBox 快速排除 → 精确 point-in-polygon，返回最深叶子
   * 优化：先 bbox 剪枝减少精确计算量
   */
  private findDeepestMatch(point: [number, number]): Geofence | null {
    let deepest: Geofence | null = null;
    let maxDepth = -1;

    for (const leaf of this.leafFences) {
      if (!pointInBBox(point[0], point[1], leaf.bbox)) continue;
      if (pointInPolygon(point, leaf.polygon)) {
        if (leaf.depth > maxDepth) {
          maxDepth = leaf.depth;
          deepest = leaf;
        }
      }
    }
    return deepest;
  }

  /**
   * 状态转移：关闭旧 Segment → 打开新 Segment
   */
  private transition(newLeafId: string | null, ts: number): StaySegment | null {
    let closedSeg: StaySegment | null = null;

    // 关闭旧的
    if (this.currentLeafId && this.currentEnterTs > 0) {
      const dateKey = this.formatDateKey(this.currentEnterTs);
      const exitDateKey = this.formatDateKey(ts);
      const crossedDay = dateKey !== exitDateKey;

      closedSeg = {
        id: `seg_${++this.segCounter}_${this.currentEnterTs}`,
        leafGeofenceId: this.currentLeafId,
        startTs: this.currentEnterTs,
        endTs: ts,
        status: crossedDay ? 'crossed_day' : 'completed',
      };

      // 异步写入（不阻塞主线程）
      this.repo.enqueueSegment(closedSeg).catch(err => {
        console.error('[Engine] segment write error:', err);
      });

      this.onSegmentCallback?.(closedSeg);
    }

    // 打开新的
    this.currentLeafId = newLeafId;
    this.currentEnterTs = newLeafId ? ts : 0;

    return closedSeg;
  }

  private formatDateKey(ts: number): string {
    const d = new Date(ts);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  }
}
