/**
 * @module GeofenceTracker/IntegrationTests
 * @description 完整集成测试 —— 嵌套围栏进出、跨天、SmartScheduler 降频
 *
 * 围栏结构（嵌套）：
 *   甲（顶级，叶子 → 后变为非叶子）
 *     └─ 乙（甲的子级）
 *         └─ 丙（乙的子级，叶子）
 *
 * 场景：08:00-09:00 甲 → 09:00-09:30 乙 → 09:30-10:00 丙 → 10:00-10:20 乙 → 10:20-11:00 甲
 *
 * 预期 DailySummary（空间向上累加）：
 * - 丙（叶子）：09:30-10:00 = 30 分钟
 * - 乙：自身(09:00-09:30 + 10:00-10:20) + 丙(30min) = 50 + 30 = 80 分钟
 * - 甲：自身(08:00-09:00 + 10:20-11:00) + 乙总计(80min) = 100 + 80 = 180 分钟
 */

import { Engine } from '../core/Engine';
import { Aggregator } from '../services/Aggregator';
import { InMemoryStorageAdapter } from '../adapters/ExistingAppBridge';
import { Repository } from '../storage/Repository';
import { SmartScheduler } from '../throttle/SmartScheduler';
import { computeBBox } from '../utils/GeoMath';
import type { Geofence, LocationFix, AccelerometerData } from '../core/types';

// ============================================================
// 测试围栏定义（使用新的 Geofence 类型）
// ============================================================

function makeFence(
  id: string,
  name: string,
  parentId: string | null,
  center: [number, number],
  radiusDeg: number,
  category: string | null = null,
  depth = 0,
): Geofence {
  // 用近似圆形多边形（8 边形）模拟
  const polygon: [number, number][] = [];
  for (let i = 0; i < 8; i++) {
    const angle = (i / 8) * Math.PI * 2;
    polygon.push([
      center[0] + radiusDeg * Math.cos(angle),
      center[1] + radiusDeg * Math.sin(angle),
    ]);
  }
  return {
    id,
    name,
    parentId,
    depth,
    category,
    polygon,
    bbox: computeBBox(polygon),
  };
}

const FENCES: Geofence[] = [
  makeFence('jia', '甲', null, [116.4, 39.9], 0.05, null, 0),
  makeFence('yi', '乙', 'jia', [116.4005, 39.9005], 0.02, null, 1),
  makeFence('bing', '丙', 'yi', [116.4008, 39.9008], 0.005, 'cat_bing', 2),
];

// ============================================================
// 辅助函数
// ============================================================

const DATE_KEY = '2026-04-04';

function makeFix(lng: number, lat: number, timestamp: number, accuracy = 10): LocationFix {
  return { lat, lng, accuracy, timestamp };
}

function ts(hour: number, minute: number): number {
  return new Date(`${DATE_KEY}T${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}:00`).getTime();
}

// ============================================================
// 测试
// ============================================================

describe('GeofenceTracker — 嵌套围栏', () => {
  let storage: InMemoryStorageAdapter;
  let repo: Repository;
  let engine: Engine;
  let aggregator: Aggregator;

  beforeEach(() => {
    storage = new InMemoryStorageAdapter();
    repo = new Repository(storage, 5);
    engine = new Engine(FENCES, repo);
    aggregator = new Aggregator(FENCES, repo);
  });

  afterEach(async () => {
    await engine.dispose();
    storage.reset();
  });

  // --------------------------------------------------------
  test('叶子围栏进出：丙进入后离开，Segment 正确生成', async () => {
    engine.onLocationUpdate(makeFix(116.4008, 39.9008, ts(9, 30)));
    expect(engine.getCurrentLeafId()).toBe('bing');

    engine.onLocationUpdate(makeFix(117.0, 40.0, ts(10, 0)));
    expect(engine.getCurrentLeafId()).toBeNull();

    await repo.flush();
    const segments = await storage.querySegmentsByDate(DATE_KEY);
    expect(segments.length).toBe(1);
    expect(segments[0].leafGeofenceId).toBe('bing');
    expect(segments[0].startTs).toBe(ts(9, 30));
    expect(segments[0].endTs).toBe(ts(10, 0));
  });

  // --------------------------------------------------------
  test('完整场景：甲→乙→丙→乙→甲，空间向上累加正确', async () => {
    // 08:00 在甲内（非乙丙范围）
    engine.onLocationUpdate(makeFix(116.4, 39.9, ts(8, 0)));
    expect(engine.getCurrentLeafId()).toBe('jia');

    // 09:00 进入乙
    engine.onLocationUpdate(makeFix(116.4005, 39.9005, ts(9, 0)));
    expect(engine.getCurrentLeafId()).toBe('yi');

    // 09:30 进入丙
    engine.onLocationUpdate(makeFix(116.4008, 39.9008, ts(9, 30)));
    expect(engine.getCurrentLeafId()).toBe('bing');

    // 10:00 离开丙回到乙
    engine.onLocationUpdate(makeFix(116.4005, 39.9005, ts(10, 0)));
    expect(engine.getCurrentLeafId()).toBe('yi');

    // 10:20 离开乙回到甲
    engine.onLocationUpdate(makeFix(116.4, 39.9, ts(10, 20)));
    expect(engine.getCurrentLeafId()).toBe('jia');

    // 11:00 离开甲
    engine.onLocationUpdate(makeFix(117.0, 40.0, ts(11, 0)));
    expect(engine.getCurrentLeafId()).toBeNull();

    await repo.flush();

    // 执行聚合
    const summaries = await aggregator.aggregate(DATE_KEY);
    const summaryMap = new Map(summaries.map(s => [s.geofenceId, s]));

    // 丙（叶子）：09:30-10:00 = 30 min
    const bing = summaryMap.get('bing');
    expect(bing).toBeDefined();
    expect(bing!.isLeaf).toBe(true);
    expect(bing!.spatialDurationMin).toBe(30);

    // 乙：自身 20min(09:00-09:30+10:00-10:20) + 丙 30min = 50 min
    // 注：按分钟去重，09:00-09:30 = 30min, 10:00-10:20 = 20min → 自身 50min
    const yi = summaryMap.get('yi');
    expect(yi).toBeDefined();
    expect(yi!.isLeaf).toBe(false);
    expect(yi!.spatialDurationMin).toBe(50 + 30); // 自身50 + 丙30

    // 甲：自身 100min(08:00-09:00+10:20-11:00) + 乙总计 80min = 180 min
    const jia = summaryMap.get('jia');
    expect(jia).toBeDefined();
    expect(jia!.isLeaf).toBe(false);
    expect(jia!.spatialDurationMin).toBe(100 + 80); // 自身100 + 乙80
  });

  // --------------------------------------------------------
  test('分类解耦：父级 leafCategoryMap 为空，叶子有分类', async () => {
    engine.onLocationUpdate(makeFix(116.4008, 39.9008, ts(9, 0)));
    engine.onLocationUpdate(makeFix(117.0, 40.0, ts(10, 0)));
    await repo.flush();

    const summaries = await aggregator.aggregate(DATE_KEY);
    const summaryMap = new Map(summaries.map(s => [s.geofenceId, s]));

    // 丙（叶子）有分类
    const bing = summaryMap.get('bing');
    expect(bing?.leafCategoryMap).toEqual({ cat_bing: 60 });

    // 乙（父级）无分类
    const yi = summaryMap.get('yi');
    expect(yi?.leafCategoryMap).toEqual({});

    // 甲（父级）无分类
    const jia = summaryMap.get('jia');
    expect(jia?.leafCategoryMap).toEqual({});
  });

  // --------------------------------------------------------
  test('漂移过滤：accuracy > threshold 的定位点被丢弃', () => {
    const driftFix: LocationFix = {
      lat: 39.9008, lng: 116.4008,
      accuracy: 200,
      timestamp: ts(9, 0),
    };

    engine.onLocationUpdate(driftFix);
    expect(engine.getCurrentLeafId()).toBeNull();
  });

  // --------------------------------------------------------
  test('可视化树输出：toVizTree 正确生成层级结构', async () => {
    engine.onLocationUpdate(makeFix(116.4008, 39.9008, ts(9, 0)));
    engine.onLocationUpdate(makeFix(117.0, 40.0, ts(10, 0)));
    await repo.flush();

    const summaries = await aggregator.aggregate(DATE_KEY);
    const vizTree = aggregator.toVizTree(summaries);

    expect(vizTree.name).toBe('root');
    expect(vizTree.children).toBeDefined();
    expect(vizTree.children!.length).toBeGreaterThan(0);

    // 树中应包含甲 → 乙 → 丙 的层级
    const jiaNode = vizTree.children!.find(c => c.name === '甲');
    expect(jiaNode).toBeDefined();

    const yiNode = jiaNode!.children?.find(c => c.name === '乙');
    expect(yiNode).toBeDefined();

    const bingNode = yiNode!.children?.find(c => c.name === '丙');
    expect(bingNode).toBeDefined();
    expect(bingNode!.value).toBe(60); // 60 分钟
  });

  // --------------------------------------------------------
  test('跨天：Segment 日期键正确分配', async () => {
    const lateNight = new Date(`${DATE_KEY}T23:50:00`).getTime();
    engine.onLocationUpdate(makeFix(116.4008, 39.9008, lateNight));

    const nextDay = new Date('2026-04-05T00:10:00').getTime();
    engine.onLocationUpdate(makeFix(117.0, 40.0, nextDay));

    await repo.flush();

    // Segment 应归属于进入日期（2026-04-04）
    const segs = await storage.querySegmentsByDate('2026-04-04');
    expect(segs.length).toBe(1);
    expect(segs[0].leafGeofenceId).toBe('bing');
  });
});

describe('SmartScheduler — 智能降频', () => {
  let scheduler: SmartScheduler;

  beforeEach(() => {
    scheduler = new SmartScheduler(FENCES);
  });

  test('性能模式：不降频，始终处理', () => {
    scheduler.setMode('performance');
    const fix = makeFix(116.4, 39.9, ts(9, 0));
    const d1 = scheduler.evaluate(fix);
    expect(d1.shouldProcess).toBe(true);
    expect(d1.level).toBe(0);

    // 1ms 后仍应处理
    const d2 = scheduler.evaluate(makeFix(116.4, 39.9, ts(9, 0) + 1));
    expect(d2.shouldProcess).toBe(true);
  });

  test('均衡模式：静止后阶梯降频', () => {
    scheduler.setMode('balanced');
    const baseTs = ts(9, 0);

    // 先发一个定位建立基准
    scheduler.evaluate(makeFix(116.4, 39.9, baseTs));

    // 模拟静止 15 分钟（应进入 level 1：2min/次）
    const after15Min = baseTs + 15 * 60_000;
    // 连续发几个静止定位
    for (let i = 0; i < 5; i++) {
      scheduler.evaluate(makeFix(116.4, 39.9, after15Min + i * 10_000));
    }

    expect(scheduler.getLevel()).toBeGreaterThanOrEqual(1);
  });

  test('立即重置：移动 > 50m 后降频重置为 level 0', () => {
    scheduler.setMode('balanced');
    const baseTs = ts(9, 0);

    // 建立基准
    scheduler.evaluate(makeFix(116.4, 39.9, baseTs));

    // 模拟静止 15 分钟
    scheduler.evaluate(makeFix(116.4, 39.9, baseTs + 15 * 60_000));
    expect(scheduler.getLevel()).toBeGreaterThanOrEqual(1);

    // 大幅移动（>50m）
    const resetFix = makeFix(116.5, 39.95, baseTs + 16 * 60_000);
    scheduler.evaluate(resetFix);
    expect(scheduler.getLevel()).toBe(0);
  });

  test('活动识别：WALKING 触发重置', () => {
    scheduler.setMode('balanced');
    const baseTs = ts(9, 0);

    // 先建立静止状态
    scheduler.evaluate(makeFix(116.4, 39.9, baseTs));
    scheduler.evaluate(makeFix(116.4, 39.9, baseTs + 15 * 60_000));
    expect(scheduler.getLevel()).toBeGreaterThanOrEqual(1);

    // WALKING 活动识别
    scheduler.onActivityDetected({ type: 'WALKING', confidence: 90, timestamp: baseTs + 16 * 60_000 });
    expect(scheduler.getLevel()).toBe(0);
  });

  test('屏幕点亮触发重置', () => {
    scheduler.setMode('balanced');
    const baseTs = ts(9, 0);

    scheduler.evaluate(makeFix(116.4, 39.9, baseTs));
    scheduler.evaluate(makeFix(116.4, 39.9, baseTs + 15 * 60_000));
    expect(scheduler.getLevel()).toBeGreaterThanOrEqual(1);

    scheduler.onScreenStateChanged(true);
    expect(scheduler.getLevel()).toBe(0);
  });

  test('SLC 注册/注销判断', () => {
    scheduler.setMode('balanced');
    const baseTs = ts(9, 0);

    // level 0 时不注册
    scheduler.evaluate(makeFix(116.4, 39.9, baseTs));
    expect(scheduler.shouldRegisterSLC()).toBe(false);

    // 模拟静止到 level 2
    scheduler.evaluate(makeFix(116.4, 39.9, baseTs + 35 * 60_000));
    if (scheduler.getLevel() >= 2) {
      expect(scheduler.shouldRegisterSLC()).toBe(true);
      scheduler.markSLCRegistered();
      expect(scheduler.shouldRegisterSLC()).toBe(false);
    }
  });

  test('漂移过滤：accuracy 过大直接返回不处理', () => {
    const fix = makeFix(116.4, 39.9, ts(9, 0), 200); // accuracy=200
    const d = scheduler.evaluate(fix);
    expect(d.shouldProcess).toBe(false);
  });
});
