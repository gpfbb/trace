# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **Doing UI work?** Read [`UI_REDESIGN_PLAN.md`](UI_REDESIGN_PLAN.md) first — it covers the
> current UI layer's exact state, the three structural problems that must be solved before
> any redesign, a recommended phase order, and the backend invariants UI work must not break.

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
- **Repository cache invalidation**: `repo.clearCache()` exists but has **no caller**. After deleting/updating fences, both `clearCache()` and `app.refreshEngineFences()` are needed — currently only the latter happens, so segment/summary caches go stale.
- **`setCurrentMatchedFenceIds`**: External code should NOT call this on Engine (it's used internally by `processApprovedLocation`). Use `checkFenceMatch()` or `checkFenceMatchWithDynamicBuffer()` instead.
- **LaunchedEffect stale closures**: When setting up listeners inside `LaunchedEffect` that read `State` variables, those state vars MUST be in the effect's key list. `AMapViewWrapper`/`GoogleMapWrapper` read `currentMatchedFenceIds` and `highlightedFenceId` inside a `LaunchedEffect` whose keys are `(displayedFenceId, fences, isMapReady)` — the green "inside fence" highlight does not refresh on location updates.
- **AMap privacy consent**: Must call `updatePrivacyShow()` and `updatePrivacyAgree()` before any map/location SDK call. Currently `MainActivity` only does it when `mapProvider == AMAP`, and `LocationProviderManager.initAmap()` does it *after* constructing `AMapLocationClient`.
- **dateKey format**: Written with `Locale.US` (`StayDurationManager`, `Repository.formatDateKey`). Any query-side formatting must also use `Locale.US` or the `daily_stays` composite key won't match.
- **`daily_stays` date columns are local-time strings** (`yyyy-MM-dd`, lexicographically ordered). `StayDao.getDailyStats` still uses SQLite `DATE(...,'unixepoch')` which is **UTC** — an 8-hour skew for UTC+8.
- **Two `FenceActiveSession` classes exist**: `StayDurationManager.kt` (7 fields, has `accumulatedDuration`/`lastSaveTime`/`gapStartTime`) and `FenceProcessingHolder.kt` (4-field stub). The stub is what gets stored, so restart gap accounting is wrong.
- **Google provider throttling**: `GoogleLocationProvider.setInterval()` requires explicit calls. `Engine.lastComputedIntervalMs` is written by `evaluateLocation()` and read by `GeoFenceLocationService` to apply.

## Module Map

| Package | Purpose |
|---|---|
| `core/` | Engine, GeofenceModels, FenceProcessingHolder, FenceTreeBuilder/Manager, SpatialIndex, EngineHolder, RepositoryHolder, ManagersHolder, FenceMapper |
| `locationservice/` | LocationProviderManager, PowerSaveController, LocationProvider (+ amap/, google/) |
| `mapstraction/` | MapPresenter + platform models (amap/, google/). **The `MapProvider` interface and its two impls are dead code** — `MapScreen` branches on `MapProvider` (the enum in `AppPreferences`) and instantiates wrappers directly |
| `data/dao/` | Hand-written SQLite query helpers (FenceDao, EventDao, StayDao, DailyStayDao, CategoryDao) |
| `data/database/` | DatabaseManager (SQLiteOpenHelper), DatabaseFacade, FenceExporter |
| `data/repository/` | FenceRepository + SessionRepository interfaces and App impls |
| `storage/` | Repository (write buffer), StorageAdapter, SqliteStorageAdapter |
| `viewmodel/` | **Only** HomeViewModel and MapViewModel. The five large screens have no ViewModel |
| `ui/screens/` | HomeScreen, MapScreen, HistoryScreen, OnboardingScreen |
| `ui/` | CategorizedFenceListScreen, VacuumPeriodInfo (siblings of `screens/`) |
| `ui/navigation/` | NavHost, Screen routes, 3× BottomNavBar (only `SimpleBottomNavBar` is used) |
| `ui/theme/` | ThemeManager (object singleton, own SharedPreferences), ColorScheme, GlassComponents |
| `ui/components/` | AMapViewWrapper, GoogleMapWrapper, ThemePicker, LoadingAnimations (dead) |
| `utils/` | AppLog, SmartScheduler, DynamicBufferManager, LanguageManager |
| `utils/geometry/` | GeoCalculator, FenceStateMachine, CirclePolygonCache, FenceAnalyzer |
| `utils/scheduler/` | StationaryDetector |
| `throttle/` | ThrottleConfig |
| `services/` | Aggregator (**never called** — `aggregate()` has no callers) |
| `model/` | Category, GeoFenceEvent, GeoPoint (mostly unused; `GeoPoint` duplicates `core.GeoPoint`) |

Empty leftover dirs: `service/`, `data/entity/`.

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
- Meaningful tests live in `app/src/test/`: `EngineLocationPipelineTest`,
  `RepositoryFlushTest`, `StayDurationManagerTest`,
  `StayDurationManagerConcurrencyTest`, `LocationProviderTest`.
