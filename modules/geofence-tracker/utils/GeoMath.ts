/**
 * @module GeofenceTracker/GeoMath
 * @description 地理计算：Haversine 距离、BBox 快速过滤、射线法 point-in-polygon、围栏链解析
 */

import type { GeoPoint, Geofence } from '../core/types';

// ============================================================
// 常量
// ============================================================
const EARTH_R = 6_371_000;
const DEG = Math.PI / 180;

// ============================================================
// 距离
// ============================================================

/**
 * Haversine 公式计算两点间距离（米）
 * @param a [lng, lat]
 * @param b [lng, lat]
 */
export function haversine(a: GeoPoint, b: GeoPoint): number {
  const dLat = (b[1] - a[1]) * DEG;
  const dLng = (b[0] - a[0]) * DEG;
  const r1 = a[1] * DEG;
  const r2 = b[1] * DEG;
  const s = Math.sin(dLat / 2) ** 2 + Math.cos(r1) * Math.cos(r2) * Math.sin(dLng / 2) ** 2;
  return 2 * EARTH_R * Math.asin(Math.sqrt(s));
}

// ============================================================
// BBox
// ============================================================

/**
 * 计算围栏 bbox，支持向外扩展 expandM 米
 * 返回 [minLng, minLat, maxLng, maxLat]
 */
export function computeBBox(polygon: GeoPoint[], expandM = 0): [number, number, number, number] {
  let minLng = Infinity, minLat = Infinity, maxLng = -Infinity, maxLat = -Infinity;
  for (const [lng, lat] of polygon) {
    if (lng < minLng) minLng = lng;
    if (lng > maxLng) maxLng = lng;
    if (lat < minLat) minLat = lat;
    if (lat > maxLat) maxLat = lat;
  }
  if (expandM > 0) {
    const dLat = expandM / 111_320;
    const midLat = (minLat + maxLat) / 2;
    const dLng = expandM / (111_320 * Math.cos(midLat * DEG));
    return [minLng - dLng, minLat - dLat, maxLng + dLng, maxLat + dLat];
  }
  return [minLng, minLat, maxLng, maxLat];
}

/** 检查点是否在 bbox 内 */
export function pointInBBox(lng: number, lat: number, bbox: [number, number, number, number]): boolean {
  return lng >= bbox[0] && lng <= bbox[2] && lat >= bbox[1] && lat <= bbox[3];
}

// ============================================================
// Point-in-Polygon（射线法）
// ============================================================

/**
 * 射线法判断点是否在多边形内部
 * @param point [lng, lat]
 * @param polygon [[lng, lat], ...]
 */
export function pointInPolygon(point: GeoPoint, polygon: GeoPoint[]): boolean {
  let inside = false;
  const n = polygon.length;
  const px = point[0], py = point[1];
  for (let i = 0, j = n - 1; i < n; j = i++) {
    const yi = polygon[i][1], xi = polygon[i][0];
    const yj = polygon[j][1], xj = polygon[j][0];
    if ((yi > py) !== (yj > py) && px < ((xj - xi) * (py - yi)) / (yj - yi) + xi) {
      inside = !inside;
    }
  }
  return inside;
}

// ============================================================
// 围栏树构建
// ============================================================

/**
 * 构建围栏父子映射
 * @returns fenceMap、childrenMap、leafFences、leavesByDepth（按深度降序）
 */
export function buildFenceTree(fences: Geofence[]): {
  fenceMap: Map<string, Geofence>;
  childrenMap: Map<string, Geofence[]>;
  leafFences: Geofence[];
  leavesByDepth: Geofence[];
} {
  const fenceMap = new Map<string, Geofence>();
  const childrenMap = new Map<string, Geofence[]>();
  const childIds = new Set<string>();

  for (const f of fences) {
    fenceMap.set(f.id, f);
    if (f.parentId) {
      const arr = childrenMap.get(f.parentId) ?? [];
      arr.push(f);
      childrenMap.set(f.parentId, arr);
      childIds.add(f.id);
    }
  }

  // 叶子 = 没有子级的围栏
  const leafFences = fences.filter(f => !childrenMap.has(f.id));
  // 按深度降序（最深优先）
  const leavesByDepth = [...leafFences].sort((a, b) => b.depth - a.depth);

  return { fenceMap, childrenMap, leafFences, leavesByDepth };
}

/**
 * 获取围栏深度（0 = 顶级）
 */
export function getFenceDepth(fenceId: string, fenceMap: Map<string, Geofence>): number {
  let d = 0;
  let cur = fenceMap.get(fenceId);
  while (cur?.parentId) {
    d++;
    cur = fenceMap.get(cur.parentId);
  }
  return d;
}

/**
 * 为围栏列表填充 depth 字段
 */
export function populateDepth(fences: Geofence[]): Geofence[] {
  const map = new Map(fences.map(f => [f.id, f]));
  return fences.map(f => ({ ...f, depth: getFenceDepth(f.id, map) }));
}
