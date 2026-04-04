/**
 * @module GeofenceTracker/ExistingAppBridge
 * @description 对接现有 App 的桥接适配器示例
 *
 * 提供 LocationAdapter 和 StorageAdapter 的示例实现，
 * 通过依赖注入对接现有定位流与本地数据库。
 * 零侵入 —— 不修改宿主 App 任何核心代码。
 */

import type {
  LocationFix, StaySegment, DailySummary, Geofence,
  ModuleConfig, AccelerometerData, ActivityResult, PowerMode, VizNode,
} from '../core/types';
import type { StorageAdapter } from '../storage/Repository';
import { Engine } from '../core/Engine';
import { Repository } from '../storage/Repository';
import { Aggregator } from '../services/Aggregator';

// ============================================================
// LocationAdapter：定位流适配器
// ============================================================

/**
 * 定位适配器接口
 * 宿主 App 将自身定位流转换为标准 LocationFix 并推送给引擎
 */
export interface LocationAdapter {
  /** 注册位置更新回调 */
  onLocation(callback: (fix: LocationFix) => void): void;
  /** 移除回调 */
  offLocation(callback: (fix: LocationFix) => void): void;
  /** 开始定位 */
  start(): void;
  /** 停止定位 */
  stop(): void;
}

// ============================================================
// 示例：内存 StorageAdapter（用于开发 / 测试）
// ============================================================

/**
 * 内存存储适配器 —— 适用于单元测试和原型验证
 */
export class InMemoryStorageAdapter implements StorageAdapter {
  private segments: StaySegment[] = [];
  private summaries: DailySummary[] = [];

  async insertSegments(segs: StaySegment[]): Promise<void> {
    this.segments.push(...segs);
  }

  async querySegmentsByDate(dateKey: string): Promise<StaySegment[]> {
    return this.segments.filter(s => {
      // 按进入日期匹配
      const d = new Date(s.startTs);
      const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
      return key === dateKey;
    });
  }

  async queryOpenSegments(): Promise<StaySegment[]> {
    return this.segments.filter(s => s.endTs === null);
  }

  async updateSegments(segs: StaySegment[]): Promise<void> {
    for (const update of segs) {
      const idx = this.segments.findIndex(s => s.id === update.id);
      if (idx >= 0) {
        this.segments[idx] = { ...this.segments[idx], ...update };
      }
    }
  }

  async saveDailySummaries(summs: DailySummary[]): Promise<void> {
    const dateKey = summs[0]?.date;
    this.summaries = this.summaries.filter(s => s.date !== dateKey);
    this.summaries.push(...summs);
  }

  async queryDailySummary(dateKey: string): Promise<DailySummary[]> {
    return this.summaries.filter(s => s.date === dateKey);
  }

  async beginTransaction(): Promise<void> { /* no-op */ }
  async commitTransaction(): Promise<void> { /* no-op */ }
  async rollbackTransaction(): Promise<void> { /* no-op */ }

  // 测试辅助
  getAllSegments(): StaySegment[] { return [...this.segments]; }
  getAllSummaries(): DailySummary[] { return [...this.summaries]; }
  reset(): void { this.segments = []; this.summaries = []; }
}

// ============================================================
// 主入口：GeofenceTrackerModule
// ============================================================

/**
 * 嵌套地理围栏追踪模块（含智能省电）
 *
 * 3 行启用示例：
 * ```ts
 * const tracker = new GeofenceTrackerModule(fences, locationAdapter, storageAdapter);
 * await tracker.init();
 * // App 退出时:
 * await tracker.dispose();
 * ```
 */
export class GeofenceTrackerModule {
  private readonly engine: Engine;
  private readonly repo: Repository;
  private readonly aggregator: Aggregator;
  private readonly locationAdapter: LocationAdapter;

  private readonly locationHandler: (fix: LocationFix) => void;
  private screenCallbackRegistered = false;

  constructor(
    fences: Geofence[],
    locationAdapter: LocationAdapter,
    storageAdapter: StorageAdapter,
    config?: Partial<ModuleConfig>,
  ) {
    this.locationAdapter = locationAdapter;
    this.repo = new Repository(storageAdapter, config?.flushBatchSize);
    this.engine = new Engine(fences, this.repo, config);
    this.aggregator = new Aggregator(fences, this.repo);

    // 绑定定位回调
    this.locationHandler = (fix: LocationFix) => {
      const seg = this.engine.onLocationUpdate(fix);
      // seg 由 Engine 内部写入 repo，此处仅用于外部回调
      void seg;
    };
  }

  /**
   * 初始化模块
   * 1. 恢复未关闭的 Segment
   * 2. 绑定定位流
   * 3. 检查是否需要执行 T+1 聚合
   */
  async init(): Promise<void> {
    await this.engine.init();
    this.locationAdapter.onLocation(this.locationHandler);

    // 检查是否有昨天的未聚合数据
    const yesterday = this.getYesterdayKey();
    const existing = await this.repo.getDailySummaries(yesterday);
    if (existing.length === 0) {
      try {
        await this.aggregator.aggregate(yesterday);
      } catch (e) {
        console.warn('[GeofenceTracker] T+1 aggregation failed:', e);
      }
    }
  }

  /**
   * 带加速度数据的定位更新
   * 比纯定位更新更精准的静止判定
   */
  onLocationUpdateWithAccel(fix: LocationFix, accel: AccelerometerData): void {
    this.engine.onLocationUpdate(fix, accel);
  }

  /**
   * 活动识别回调（转发给 SmartScheduler）
   * WALKING/DRIVING/CYCLING 时立即重置降频
   */
  onActivityDetected(activity: ActivityResult): void {
    this.engine.onActivityDetected(activity);
  }

  /**
   * 屏幕状态变更（转发给 SmartScheduler）
   * 屏幕点亮时立即重置降频
   */
  onScreenStateChanged(isOn: boolean): void {
    this.engine.onScreenStateChanged(isOn);
  }

  /**
   * SLC 系统回调（转发给 SmartScheduler）
   */
  onSignificantLocationChange(distanceM: number): void {
    this.engine.onSignificantLocationChange(distanceM);
  }

  /**
   * 设置电源模式
   * - performance：关闭降频，持续高精度
   * - balanced：标准阶梯降频（默认）
   * - powersave：阶梯降频 + 边界 Buffer 提频 + 围栏外降精度
   */
  setPowerMode(mode: PowerMode): void {
    this.engine.setPowerMode(mode);
  }

  /**
   * 是否应注册 SLC 兜底（降频等级 ≥ 2 时）
   */
  shouldRegisterSLC(): boolean {
    return this.engine.shouldRegisterSLC();
  }

  /**
   * 是否应注销 SLC
   */
  shouldUnregisterSLC(): boolean {
    return this.engine.shouldUnregisterSLC();
  }

  /**
   * App 进入后台
   */
  async onAppBackground(): Promise<void> {
    await this.repo.flush();
  }

  /**
   * App 退出 / 销毁
   */
  async dispose(): Promise<void> {
    this.locationAdapter.offLocation(this.locationHandler);
    await this.engine.dispose();
  }

  /**
   * 手动触发指定日期的聚合
   */
  async aggregateDate(dateKey: string): Promise<DailySummary[]> {
    return this.aggregator.aggregate(dateKey);
  }

  /**
   * 获取可视化树（Sunburst / TreeMap JSON）
   */
  async getVizTree(dateKey: string): Promise<VizNode> {
    const summaries = await this.repo.getDailySummaries(dateKey);
    return this.aggregator.toVizTree(summaries);
  }

  /**
   * 获取当前命中的叶子围栏 ID
   */
  getCurrentLeafId(): string | null {
    return this.engine.getCurrentLeafId();
  }

  /**
   * 获取当前已停留秒数
   */
  getCurrentDurationSec(): number {
    return this.engine.getCurrentDurationSec();
  }

  /**
   * 获取 SmartScheduler 引用（用于外部监控）
   */
  getScheduler() {
    return this.engine.getScheduler();
  }

  private getYesterdayKey(): string {
    const d = new Date(Date.now() - 86_400_000);
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
  }
}
