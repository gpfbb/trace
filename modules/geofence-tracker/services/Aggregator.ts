/**
 * @module GeofenceTracker/Aggregator
 * @description T+1 批处理核心 —— 跨天切割、空间向上累加、分类解耦
 *
 * 核心规则：
 * 1. 仅在 00:00 或下次启动时聚合前一天的 Segment
 * 2. 跨天 Segment 按日界切割，归属到对应日期
 * 3. 父级空间时长 = Σ(子叶子时长) + 父级独立停留时长
 * 4. 同一分钟绝不可重复计算
 * 5. 分类仅绑定叶子围栏，父级绝不继承子级分类
 * 6. leafCategoryMap 仅叶子围栏填充，父级为空对象
 */

import type { StaySegment, DailySummary, Geofence, VizNode } from '../core/types';
import { buildFenceTree, getFenceDepth } from '../utils/GeoMath';
import type { Repository } from '../storage/Repository';

/**
 * 聚合器
 */
export class Aggregator {
  private readonly repo: Repository;
  private readonly fenceMap: Map<string, Geofence>;
  private readonly childrenMap: Map<string, Geofence[]>;
  private readonly leafSet: Set<string>;
  private readonly leavesByDepth: Geofence[];

  constructor(fences: Geofence[], repo: Repository) {
    this.repo = repo;
    const tree = buildFenceTree(fences);
    this.fenceMap = tree.fenceMap;
    this.childrenMap = tree.childrenMap;
    this.leafSet = new Set(tree.leafFences.map(f => f.id));
    this.leavesByDepth = tree.leavesByDepth;
  }

  // ============================================================
  // T+1 聚合主流程
  // ============================================================

  /**
   * 对指定日期执行聚合
   *
   * 流程：
   * 1. 读取该日所有 Segment（含跨天被切割的）
   * 2. 按叶子围栏汇总分钟数（去重：同一分钟只计一次）
   * 3. 向上累加到父级
   * 4. 构建 leafCategoryMap
   * 5. 输出 DailySummary 列表
   *
   * @param dateKey yyyy-MM-dd
   */
  async aggregate(dateKey: string): Promise<DailySummary[]> {
    const segments = await this.repo.getSegmentsByDate(dateKey);

    // 步骤 1：跨天切割 + 按叶子围栏汇总分钟
    const { leafMinutes, leafCategoryMinutes } = this.accumulateLeafDurations(segments, dateKey);

    // 步骤 2：构建完整的汇总列表（含父级向上累加）
    const summaries = this.buildSummaries(leafMinutes, leafCategoryMinutes, dateKey);

    // 步骤 3：持久化
    await this.repo.saveDailySummaries(dateKey, summaries);

    return summaries;
  }

  // ============================================================
  // 跨天切割 + 叶子围栏时长汇总（去重分钟）
  // ============================================================

  /**
   * 将 Segment 列表按叶子围栏汇总为分钟数
   *
   * 关键逻辑：
   * - 跨天 Segment 按日界切割，只计算 dateKey 当天的分钟
   * - 同一分钟内多次进入/退出只计 1 分钟
   * - 同时收集分类时长映射
   *
   * @returns leafMinutes: 每个叶子围栏的总分钟数
   * @returns leafCategoryMinutes: 每个叶子围栏的 { categoryId: minutes } 映射
   */
  private accumulateLeafDurations(
    segments: StaySegment[],
    dateKey: string,
  ): {
    leafMinutes: Map<string, number>;
    leafCategoryMinutes: Map<string, Record<string, number>>;
  } {
    const leafMinutes = new Map<string, number>();
    const leafCategoryMinutes = new Map<string, Record<string, number>>();

    // 当天 00:00 和次日 00:00 的时间戳
    const dayStart = new Date(`${dateKey}T00:00:00`).getTime();
    const dayEnd = dayStart + 86_400_000;

    for (const seg of segments) {
      if (seg.endTs === null) continue; // 跳过未关闭的
      if (!this.leafSet.has(seg.leafGeofenceId)) continue; // 仅处理叶子

      // 跨天切割：取 Segment 与当日的交集
      const effectiveStart = Math.max(seg.startTs, dayStart);
      const effectiveEnd = Math.min(seg.endTs, dayEnd);

      if (effectiveStart >= effectiveEnd) continue; // 不在当日范围内

      // 计算覆盖的分钟集合（去重）
      const startMin = Math.floor(effectiveStart / 60_000);
      const endMin = Math.floor(effectiveEnd / 60_000);

      let addedMinutes = 0;
      for (let m = startMin; m <= endMin; m++) {
        const minuteKey = `${seg.leafGeofenceId}:${m}`;
        // 用全局 Set 去重（跨多个 Segment）
        if (!this._dedupeSet) this._dedupeSet = new Set();
        if (!this._dedupeSet.has(minuteKey)) {
          this._dedupeSet.add(minuteKey);
          addedMinutes++;
        }
      }

      const fenceId = seg.leafGeofenceId;
      leafMinutes.set(fenceId, (leafMinutes.get(fenceId) ?? 0) + addedMinutes);

      // 分类映射
      const fence = this.fenceMap.get(fenceId);
      if (fence?.category) {
        const catMap = leafCategoryMinutes.get(fenceId) ?? {};
        catMap[fence.category] = (catMap[fence.category] ?? 0) + addedMinutes;
        leafCategoryMinutes.set(fenceId, catMap);
      }
    }

    // 清理去重集合
    this._dedupeSet = null;

    return { leafMinutes, leafCategoryMinutes };
  }

  /** 跨 Segment 去重集合（每次 aggregate 调用重置） */
  private _dedupeSet: Set<string> | null = null;

  // ============================================================
  // 向上累加（空间聚合）
  // ============================================================

  /**
   * 从叶子向上累加到所有祖先节点
   *
   * 父级时长 = Σ(所有直接子节点时长) + 自身独立停留时长
   * 注意：父级不继承子级的 category（分类彻底解耦）
   */
  private buildSummaries(
    leafMinutes: Map<string, number>,
    leafCategoryMinutes: Map<string, Record<string, number>>,
    dateKey: string,
  ): DailySummary[] {
    const allMinutes = new Map<string, number>();
    const allCategoryMap = new Map<string, Record<string, number>>();

    // 按深度降序排列，确保先处理子级
    const sortedFences = [...this.leavesByDepth];
    // 加入非叶子围栏
    for (const f of this.fenceMap.values()) {
      if (!this.leafSet.has(f.id) && !sortedFences.find(sf => sf.id === f.id)) {
        sortedFences.push(f);
      }
    }
    sortedFences.sort((a, b) => b.depth - a.depth);

    for (const fence of sortedFences) {
      let totalMin = 0;

      if (this.leafSet.has(fence.id)) {
        // 叶子：取直接汇总值
        totalMin = leafMinutes.get(fence.id) ?? 0;
        allCategoryMap.set(fence.id, leafCategoryMinutes.get(fence.id) ?? {});
      } else {
        // 父级：累加所有子级
        const children = this.childrenMap.get(fence.id) ?? [];
        for (const child of children) {
          totalMin += allMinutes.get(child.id) ?? 0;
        }
        allCategoryMap.set(fence.id, {}); // 父级无分类
      }

      allMinutes.set(fence.id, totalMin);
    }

    // 构建输出
    const summaries: DailySummary[] = [];
    for (const fence of this.fenceMap.values()) {
      const durationMin = allMinutes.get(fence.id) ?? 0;
      if (durationMin > 0) {
        summaries.push({
          date: dateKey,
          geofenceId: fence.id,
          name: fence.name,
          spatialDurationMin: durationMin,
          leafCategoryMap: allCategoryMap.get(fence.id) ?? {},
          isLeaf: this.leafSet.has(fence.id),
          depth: fence.depth,
        });
      }
    }

    return summaries;
  }

  // ============================================================
  // 可视化转换
  // ============================================================

  /**
   * 将 DailySummary 列表转换为 Sunburst / TreeMap 标准 JSON
   *
   * 输出结构：
   * ```json
   * {
   *   "name": "root",
   *   "children": [
   *     { "name": "甲", "children": [
   *       { "name": "乙", "children": [
   *         { "name": "丙", "value": 30 }
   *       ]}
   *     ]}
   *   ]
   * }
   * ```
   */
  toVizTree(summaries: DailySummary[]): VizNode {
    const durationMap = new Map<string, number>();
    for (const s of summaries) {
      durationMap.set(s.geofenceId, s.spatialDurationMin);
    }

    // 找到顶级围栏（parentId === null 且有数据）
    const roots = Array.from(this.fenceMap.values())
      .filter(f => f.parentId === null && durationMap.has(f.id));

    if (roots.length === 0) {
      return { name: 'root', children: [] };
    }

    const children = roots.map(r => this.buildVizNode(r, durationMap));
    return { name: 'root', children };
  }

  /**
   * 递归构建可视化节点
   * 叶子节点附带 value（分钟），父级节点递归包含 children
   */
  private buildVizNode(
    fence: Geofence,
    durationMap: Map<string, number>,
  ): VizNode {
    const children = this.childrenMap.get(fence.id);
    const duration = durationMap.get(fence.id) ?? 0;

    if (!children || children.length === 0) {
      // 叶子
      return { name: fence.name, value: duration };
    }

    // 父级
    const childNodes = children
      .filter(c => durationMap.has(c.id))
      .map(c => this.buildVizNode(c, durationMap));

    return {
      name: fence.name,
      children: childNodes.length > 0 ? childNodes : undefined,
    };
  }
}
