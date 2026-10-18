# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **Doing UI work?** Read [`UI_REDESIGN_PLAN.md`](UI_REDESIGN_PLAN.md) first. The UI 重做
> 已完成（33 个提交）：里面写了设计系统怎么用、新代码结构、验证方式（含模拟器与取色技巧）、
> 七个踩过的坑与排查方法，以及还剩什么。本文档描述的后端架构与不变量仍然准确。

## Build / Test / Lint

```bash
JAVA_HOME=/d/javajdk ./gradlew compileDebugKotlin   # Fast type-check (~40s)
JAVA_HOME=/d/javajdk ./gradlew testDebugUnitTest    # JVM unit tests
./gradlew assembleDebug          # Build debug APK
./gradlew connectedAndroidTest   # Instrumented tests (requires device)
./gradlew lint                   # Android lint
```

- **Android SDK lives at `E:\Androidsdk`** — `local.properties` points there. The
  `ANDROID_HOME` env var points at a non-existent `E:\Android`; don't trust it.
- Gradle 9, AGP 8.12.3, Kotlin 1.9.24, JDK 17 target, `minSdk 26`, `targetSdk 34`
- Dependencies are not fully cached — do **not** pass `--offline`.
- Gradle 9: `--tests` only works with a concrete task (`testDebugUnitTest --tests "..."`).

## Architecture Overview

```
┌─────────────────────────────────────────┐
│  MainActivity (Compose host)            │
│  TrackMasterNavHost → screens/          │
│  ViewModels consume Repositories        │
└──────────────┬──────────────────────────┘
               │ Koin DI (AppModule.kt)
┌──────────────▼──────────────────────────┐
│  TrackMasterApplication (singleton)      │
│  ├─ ManagersHolder  (stayDuration, etc.) │
│  ├─ RepositoryHolder (shared state)      │
│  ├─ EngineHolder     (fence engine)      │
│  ├─ FenceProcessingHolder (core logic)   │
│  └─ FenceTreeManager (parent/child)      │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│  GeoFenceLocationService (foreground)    │
│  ├─ LocationProviderManager              │
│  │   └─ AMapLocationClient (inline)      │
│  │   └─ GoogleLocationProvider           │
│  ├─ PowerSaveController                  │
│  └─ calls app.checkGeoFences()           │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│  Engine (core state machine)            │
│  ├─ SmartScheduler (adaptive interval)  │
│  ├─ SpatialIndex (BBox pre-filter)      │
│  ├─ DynamicBufferManager                │
│  └─ Repository (write buffer + flush)   │
└─────────────────────────────────────────┘
```

### Data Flow

1. **GPS → Engine**: `GeoFenceLocationService.onLocationChanged()` calls `app.checkGeoFences(location, accel)`
2. **FenceProcessingHolder.checkGeoFences**: `engine.evaluateLocation(fix, accel)` — the **only** SmartScheduler entry point per fix
3. **If approved** → `appScope.launch` → `engine.processApprovedLocation(fix)`: spatial query → fence matching → state transition → `StaySegment` generation
4. **FenceProcessingHolder**: handles enter/exit events, stay timing via `StayDurationManager`, persistent state save
5. **UI observes**: `RepositoryHolder` state (currentMatchedFences, fenceStateVersion) via `collectAsState()`

### Key Design Decisions

- **Leaf-first timing**: Only the deepest matched fence triggers `StaySegment` tracking. Parents are tracked for containment context but don't independently generate segments.
- **Scheduler is called exactly once per fix**: `SmartScheduler.evaluate()` has side effects (writes `lastProcessedTs`, location history, stationary accumulation). `evaluateLocation()` is the only caller; `processApprovedLocation()` must never touch the scheduler. Calling it twice for the same fix makes the second call fail the time gate (`ts - lastProcessedTs == 0 < interval`) and silently drop every segment. This was a real P0 bug.
- **Repository as write buffer**: `Repository.enqueueSegment()` buffers in memory, flushes to SQLite in batches of 5, with exponential-backoff retry on failure (250ms doubling, max 5 attempts).
- **DI pattern**: Repository interfaces (`FenceRepository`, `SessionRepository`) wrap `TrackMasterApplication` — they are thin delegates, not independent data stores.
- **Engine is replaceable**: `EngineHolder._engine` is swapped on fence import/delete via `refresh()`; exposed as a read-only `val` getter.
- **Database is raw SQLite**, not Room: `SQLiteOpenHelper` in `DatabaseManager.kt`, POJO data classes, DAOs are hand-written query helpers with no annotations.
- **Event data is queried on demand**, not preloaded at startup. `Application.onCreate` only loads fences and categories (what Engine init needs). `geoFenceEvents` in `RepositoryHolder` is an in-process cache of newly generated events only.

## Critical Gotchas

- **Never call `SmartScheduler.evaluate()` twice for the same fix** — see "Scheduler is called exactly once per fix" above.
- **`Engine.transition()` writes to repo BEFORE updating `currentLeafId`** — if repo write fails, both `currentLeafId` and `currentEnterTs` are rolled back. Don't invert this order, and don't roll back only one of them.
- **Repository cache invalidation**: 删改围栏后需要同时调 `repository.clearCache()` 和
  `app.refreshEngineFences()`。`TrackMasterApplication.refreshEngineFences()` 内部已经
  调了 `clearCache()`，**不要在它之外另写一套刷新逻辑**。
- **`setCurrentMatchedFenceIds`**: External code should NOT call this on Engine (it's used internally by `processApprovedLocation`). Use `checkFenceMatch()` or `checkFenceMatchWithDynamicBuffer()` instead.
- **LaunchedEffect stale closures**: When setting up listeners inside `LaunchedEffect` that read `State` variables, those state vars MUST be in the effect's key list. `AMapViewWrapper`/`GoogleMapWrapper` read `currentMatchedFenceIds` and `highlightedFenceId` inside a `LaunchedEffect` whose keys are `(displayedFenceId, fences, isMapReady)` — the green "inside fence" highlight does not refresh on location updates.
- **AMap privacy consent**: Must call `updatePrivacyShow()` and `updatePrivacyAgree()` before any map/location SDK call. Currently `MainActivity` only does it when `mapProvider == AMAP`, and `LocationProviderManager.initAmap()` does it *after* constructing `AMapLocationClient`.
- **dateKey format**: Written with `Locale.US` (`StayDurationManager`, `Repository.formatDateKey`). Any query-side formatting must also use `Locale.US` or the `daily_stays` composite key won't match.
- **`daily_stays` date columns are local-time strings** (`yyyy-MM-dd`, lexicographically ordered)。
  `StayDao` 里的日期查询已经加了 `'localtime'` 修饰符（只写 `'unixepoch'` 返回 UTC，
  UTC+8 会差 8 小时）。新增查询照此办理。
- **`FenceActiveSession` 只有一个定义**（`StayDurationManager.kt`，7 字段，含
  `accumulatedDuration`/`lastSaveTime`/`gapStartTime`）。`FenceProcessingHolder`
  直接 import 这个版本——历史上曾有一份 4 字段的副本，导致重启后的间隙计算错误，已删除。
- **Google provider throttling**: `GoogleLocationProvider.setInterval()` requires explicit calls. `Engine.lastComputedIntervalMs` is written by `evaluateLocation()` and read by `GeoFenceLocationService` to apply.

## Module Map

| Package | Purpose |
|---|---|
| `core/` | Engine, GeofenceModels, FenceProcessingHolder, FenceTreeBuilder/Manager, SpatialIndex, EngineHolder, RepositoryHolder, ManagersHolder, FenceMapper |
| `locationservice/` | LocationProviderManager, PowerSaveController, LocationProvider (+ google/) |
| `mapstraction/` | MapPresenter + platform models (amap/, google/). `MapScreen` branches on `MapProvider` (the enum in `AppPreferences`) and instantiates wrappers directly |
| `data/dao/` | Hand-written SQLite query helpers (FenceDao, EventDao, StayDao, DailyStayDao, CategoryDao) |
| `data/database/` | DatabaseManager (SQLiteOpenHelper), DatabaseFacade, FenceExporter |
| `data/repository/` | FenceRepository / SessionRepository / CategoryRepository 接口与 App 实现（都是薄委托） |
| `storage/` | Repository (write buffer), StorageAdapter, SqliteStorageAdapter |
| `viewmodel/` | **Only** HomeViewModel and MapViewModel. The remaining screens have no ViewModel |
| `ui/design/` | **设计系统唯一真源**：Tokens（颜色/间距/圆角/字号）、Theme、Components、Surfaces、AppIcons、Typography、ThemePicker |
| `ui/screens/` | HomeScreen, MapScreen, HistoryScreen, OnboardingScreen |
| `ui/map/` | 地图页拆分产物：MapEditorState（绘制/编辑三态状态机）、MapToolsPopup、FenceDialogs |
| `ui/settings/` | 设置页拆分产物：AdvancedSettingsSection、RecalculateRelationshipsDialog、SettingsComponents |
| `ui/statistics/` | 统计页拆分产物：DatePickers、DurationDonutChart、FenceTreeSection、TimeDimensionSelector |
| `ui/navigation/` | NavHost, Screen routes, SimpleBottomNavBar |
| `ui/components/` | AMapViewWrapper, GoogleMapWrapper |
| `settings/` | FenceRelationshipRecalculator（父子关系重算算法） |
| `utils/` | AppLog, SmartScheduler, DynamicBufferManager, LanguageManager |
| `utils/geometry/` | GeoCalculator, FenceStateMachine, CirclePolygonCache, FenceAnalyzer |
| `utils/scheduler/` | StationaryDetector |
| `throttle/` | ThrottleConfig |
| `model/` | Category, GeoFenceEvent, GeoPoint (mostly unused; `GeoPoint` duplicates `core.GeoPoint`) |

### 已删除的包（别再去这些路径找）

`ui/theme/`（ThemeManager / ColorScheme / GlassComponents）、`services/`（Aggregator）、
`service/`、`data/entity/`、`locationservice/amap/`。详见 `UI_REDESIGN_PLAN.md`。

## Map & Location Providers

The app supports both AMap and Google Maps, but the abstraction is thin:

- **Map rendering**: `MapPresenter` interface → `AMapPresenter` / `GoogleMapPresenter`. `MapScreen` picks a wrapper with a plain `if (currentMapProvider == GOOGLE)` branch.
- **Location**: `LocationProviderManager` holds an `AMapLocationClient` **inline** for AMap. The standalone `locationservice/amap/AMapLocationProvider.kt` is dead code.
- Default is AMap; switchable via `appPreferences.mapProvider`.
- **The two map paths are not equivalent**: `GoogleMapPresenter`'s drawing mode is stubbed out (`isDrawingMode() = false`, `getDrawingPoints() = emptyList()`); only `AMapPresenter` has editing markers. AMap uses `delay(100)` to fake map readiness while Google uses an `isMapReady` StateFlow.

## Signing

- Debug keystore: `trackmaster_debug.jks` (password: `trackmaster2024`)
- Release keystore: `trackmaster.jks` (password: `trackmaster2024`)
- AMap API key is a `buildConfigField` in `app/build.gradle.kts`; the Google Maps API key is hardcoded in `AndroidManifest.xml`.

## Testing Notes

- `build.gradle.kts` includes `kotlinx-coroutines-test` and sets
  `testOptions.unitTests.isReturnDefaultValues = true` (required — `AppLog` calls
  `android.util.Log`, which throws in JVM tests otherwise).
- 测试共 **113 个**，全部是 JVM 单测（`app/src/test/`）。当前 `lintDebug` 为 0 错误，
  改动后保持这一点。
- 后端相关：`EngineLocationPipelineTest`、`RepositoryFlushTest`、`StayDurationManagerTest`、
  `StayDurationManagerConcurrencyTest`、`LocationProviderTest`、`EngineRefreshStateTest`、
  `AppScopeSupervisionTest`、`TrackingPreferenceTest`、`FenceTreeFlattenerTest`、
  `FenceAreaTest`、`FenceRelationshipGeometryTest`、`StayRecordNameResolverTest`、
  `MapRegionDetectorTest`
- UI 相关：`DesignSystemTest`（对比度）、`MapEditorStateTest`、`MapEditorStateSaverTest`、
  `DurationDonutChartTest`
