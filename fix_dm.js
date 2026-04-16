const fs = require('fs');
const file = 'D:\\trace\\app\\src\\main\\java\\com\\trackmaster\\app\\DataManager.kt';
let c = fs.readFileSync(file, 'utf8');

// Fix events import: replace lines 317-325 block
const oldEventsBlock = `                val time = dateTimeFormat.parse(eventJson.getString("time")) ?: Date()
                val event = GeoFenceEvent(
                    id = eventJson.optString("id", UUID.randomUUID().toString()),
                    fenceId = eventJson.getString("fenceId"),
                    fenceName = eventJson.getString("fenceName"),
                    action = GeoFenceAction.valueOf(eventJson.getString("action")),
                    time = time,
                    latitude = eventJson.getDouble("latitude"),
                    longitude = eventJson.getDouble("longitude"),
                    accuracy = eventJson.getDouble("accuracy").toFloat(),
                    durationSeconds = eventJson.optLong("durationSeconds").takeIf { it > 0 }
                )`;

const newEventsBlock = `                // 【MEDIUM修复】校验必填字段，跳过无效事件，避免JSON解析崩溃
                val fenceId = eventJson.optString("fenceId", "").takeIf { it.isNotBlank() } ?: continue
                val fenceName = eventJson.optString("fenceName", "").takeIf { it.isNotBlank() } ?: continue
                val actionStr = eventJson.optString("action", "")
                val action = try { GeoFenceAction.valueOf(actionStr) } catch (e: Exception) { continue }
                val time = dateTimeFormat.parse(eventJson.optString("time", "").takeIf { t -> t.isNotBlank() }) ?: Date()
                val event = GeoFenceEvent(
                    id = eventJson.optString("id", UUID.randomUUID().toString()),
                    fenceId = fenceId,
                    fenceName = fenceName,
                    action = action,
                    time = time,
                    latitude = eventJson.optDouble("latitude", 0.0),
                    longitude = eventJson.optDouble("longitude", 0.0),
                    accuracy = eventJson.optDouble("accuracy", 0.0).toFloat(),
                    durationSeconds = eventJson.optLong("durationSeconds").takeIf { it > 0 }
                )`;

if (c.includes(oldEventsBlock)) {
    c = c.split(oldEventsBlock).join(newEventsBlock);
    console.log('Events block replaced');
} else {
    console.log('Events block NOT FOUND');
}

// Fix importFencesFromJson: lines 522-557 block
const oldFencesBlock = `                val type = GeoFenceType.valueOf(obj.getString("type"))
                // 优先保留原始 ID，若与已有围栏冲突则生成新 ID
                val originalId = obj.optString("id", null)
                val existingFence = originalId?.let { dbHelper.getFenceById(it) }
                val fenceId = if (existingFence != null) UUID.randomUUID().toString() else originalId ?: UUID.randomUUID().toString()
                val fence = if (type == GeoFenceType.CIRCLE) {
                    GeoFence(
                        id = fenceId,
                        name = obj.getString("name"),
                        type = type,
                        center = GeoFencePoint(obj.getDouble("centerLat"), obj.getDouble("centerLng")),
                        radius = obj.getDouble("radius").toFloat(),
                        categoryId = obj.optString("categoryId", Category.UNASSIGNED_ID),
                        color = obj.optString("color", "#FF0000"),
                        alpha = obj.optDouble("alpha", 0.3).toFloat(),
                    )
                } else {
                    val pts = mutableListOf<GeoFencePoint>()
                    val arr = obj.getJSONArray("points")
                    for (j in 0 until arr.length()) {
                        val pj = arr.getJSONObject(j)
                        pts.add(GeoFencePoint(pj.getDouble("lat"), pj.getDouble("lng")))
                    }
                    GeoFence(
                        id = fenceId,
                        name = obj.getString("name"),
                        type = type,
                        points = pts,
                        categoryId = obj.optString("categoryId", Category.UNASSIGNED_ID),
                        color = obj.optString("color", "#FF0000"),
                        alpha = obj.optDouble("alpha", 0.3).toFloat(),
                    )
                }
                dbHelper.insertFence(fence)
                count++`;

const newFencesBlock = `                // 【MEDIUM修复】校验必填字段，跳过无效记录，避免导入崩溃
                val typeStr = obj.optString("type", "")
                val name = obj.optString("name", "").takeIf { it.isNotBlank() }
                if (typeStr.isBlank() || name == null) continue
                val type = try { GeoFenceType.valueOf(typeStr) } catch (e: Exception) { continue }
                // 优先保留原始 ID，若与已有围栏冲突则生成新 ID
                val originalId = obj.optString("id", null)
                val existingFence = originalId?.let { dbHelper.getFenceById(it) }
                val fenceId = if (existingFence != null) UUID.randomUUID().toString() else originalId ?: UUID.randomUUID().toString()
                val fence = if (type == GeoFenceType.CIRCLE) {
                    val centerLat = obj.optDouble("centerLat", Double.NaN)
                    val centerLng = obj.optDouble("centerLng", Double.NaN)
                    val radius = obj.optDouble("radius", Double.NaN)
                    if (centerLat.isNaN() || centerLng.isNaN() || radius.isNaN()) continue
                    GeoFence(
                        id = fenceId,
                        name = name,
                        type = type,
                        center = GeoFencePoint(centerLat, centerLng),
                        radius = radius.toFloat(),
                        categoryId = obj.optString("categoryId", Category.UNASSIGNED_ID),
                        color = obj.optString("color", "#FF0000"),
                        alpha = obj.optDouble("alpha", 0.3).toFloat(),
                    )
                } else {
                    val pts = mutableListOf<GeoFencePoint>()
                    val arr = obj.optJSONArray("points") ?: continue
                    for (j in 0 until arr.length()) {
                        val pj = arr.optJSONObject(j) ?: continue
                        pts.add(GeoFencePoint(pj.optDouble("lat", Double.NaN), pj.optDouble("lng", Double.NaN)))
                    }
                    if (pts.size < 3) continue
                    GeoFence(
                        id = fenceId,
                        name = name,
                        type = type,
                        points = pts,
                        categoryId = obj.optString("categoryId", Category.UNASSIGNED_ID),
                        color = obj.optString("color", "#FF0000"),
                        alpha = obj.optDouble("alpha", 0.3).toFloat(),
                    )
                }
                dbHelper.insertFence(fence)
                count++`;

if (c.includes(oldFencesBlock)) {
    c = c.split(oldFencesBlock).join(newFencesBlock);
    console.log('Fences block replaced');
} else {
    console.log('Fences block NOT FOUND');
}

fs.writeFileSync(file, c);
console.log('Done');
