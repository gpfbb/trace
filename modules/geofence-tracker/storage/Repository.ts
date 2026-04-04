/**
 * @module GeofenceTracker/Repository
 * @description 独立存储层 — 内存缓存 + 批量事务写入 + T+1 聚合查询
 *
 * 设计要点：
 * 1. Segment 写入先进内存队列，达到 flushBatchSize 后批量 flush
 * 2. 读取时内存缓存优先，减少底层存储访问
 * 3. 所有操作通过 StorageAdapter 委托，零侵入现有 DB
 */

import type { StaySegment, DailySummary } from '../core/types';

// ============================================================
// 存储适配器接口（宿主 App 实现）
// ============================================================

export interface StorageAdapter {
  /** 批量插入 Segment */
  insertSegments(segments: StaySegment[]): Promise<void>;
  /** 查询指定日期的所有 Segment */
  querySegmentsByDate(dateKey: string): Promise<StaySegment[]>;
  /** 查询未关闭的 Segment（endTs === null） */
  queryOpenSegments(): Promise<StaySegment[]>;
  /** 批量更新 Segment */
  updateSegments(segments: StaySegment[]): Promise<void>;
  /** 保存日汇总 */
  saveDailySummaries(summaries: DailySummary[]): Promise<void>;
  /** 查询日汇总 */
  queryDailySummary(dateKey: string): Promise<DailySummary[]>;
  beginTransaction(): Promise<void>;
  commitTransaction(): Promise<void>;
  rollbackTransaction(): Promise<void>;
}

// ============================================================
// Repository
// ============================================================

export class Repository {
  private readonly adapter: StorageAdapter;
  private readonly batchSize: number;

  private writeBuffer: StaySegment[] = [];
  private segmentCache = new Map<string, StaySegment[]>();
  private summaryCache = new Map<string, DailySummary[]>();
  private flushing = false;

  constructor(adapter: StorageAdapter, batchSize = 20) {
    this.adapter = adapter;
    this.batchSize = batchSize;
  }

  /**
   * 追加 Segment 到写入缓冲区
   * 达到 batchSize 自动触发 flush
   */
  async enqueueSegment(seg: StaySegment): Promise<void> {
    this.writeBuffer.push(seg);
    this.segmentCache.delete(seg.startTs ? this.formatDateKey(seg.startTs) : '');
    if (this.writeBuffer.length >= this.batchSize) {
      await this.flush();
    }
  }

  /**
   * 批量 flush 到底层存储（事务保护）
   */
  async flush(): Promise<void> {
    if (this.writeBuffer.length === 0 || this.flushing) return;
    this.flushing = true;
    const batch = this.writeBuffer.splice(0);
    try {
      await this.adapter.beginTransaction();
      await this.adapter.insertSegments(batch);
      await this.adapter.commitTransaction();
    } catch (err) {
      await this.adapter.rollbackTransaction();
      this.writeBuffer.unshift(...batch); // 放回重试
      throw err;
    } finally {
      this.flushing = false;
    }
  }

  /** 按日期获取 Segment（缓存优先） */
  async getSegmentsByDate(dateKey: string): Promise<StaySegment[]> {
    if (this.segmentCache.has(dateKey)) return this.segmentCache.get(dateKey)!;
    const segs = await this.adapter.querySegmentsByDate(dateKey);
    this.segmentCache.set(dateKey, segs);
    return segs;
  }

  /** 获取未关闭的 Segment */
  async getOpenSegments(): Promise<StaySegment[]> {
    return this.adapter.queryOpenSegments();
  }

  /** 保存日汇总（T+1 聚合产物） */
  async saveDailySummaries(dateKey: string, summaries: DailySummary[]): Promise<void> {
    await this.adapter.beginTransaction();
    await this.adapter.saveDailySummaries(summaries);
    await this.adapter.commitTransaction();
    this.summaryCache.set(dateKey, summaries);
  }

  /** 读取日汇总 */
  async getDailySummaries(dateKey: string): Promise<DailySummary[]> {
    if (this.summaryCache.has(dateKey)) return this.summaryCache.get(dateKey)!;
    const s = await this.adapter.queryDailySummary(dateKey);
    this.summaryCache.set(dateKey, s);
    return s;
  }

  /** 清除所有缓存 */
  clearCache(): void {
    this.segmentCache.clear();
    this.summaryCache.clear();
  }

  /** 资源释放 */
  async dispose(): Promise<void> {
    await this.flush();
    this.clearCache();
  }

  private formatDateKey(ts: number): string {
    const d = new Date(ts);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  }
}
