# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build / Test / Lint

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew test                   # Run JUnit unit tests (app/src/test/)
./gradlew connectedAndroidTest   # Run instrumented tests (requires device)
./gradlew compileDebugKotlin     # Fast type-check without full build
./gradlew lint                   # Android lint
```

- Gradle 9.0, AGP 8.12.3, Kotlin 1.9.24, JDK 17 target
- `minSdk 26`, `targetSdk 34`, Compose compiler extension 1.5.14
- Koin 3.5.3 for DI (the README says Hilt — that is wrong)

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
│  ├─ ManagersHolder  (stayDuration, etc) │
│  ├─ RepositoryHolder (shared state)     │
│  ├─ EngineHolder     (fence engine)     │
│  ├─ FenceProcessingHolder (core logic)  │
│  └─ FenceTreeManager (parent/child)     │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│  GeoFenceLocationService (foreground)    │
│  ├─ LocationProviderManager             │
│  │   └─ AMapLocationProvider (default)  │
│  │   └─ GoogleLocationProvider          │
│  ├─ PowerSaveController                 │
│  └─ calls app.checkGeoFences()          │
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
2. **Engine.evaluateLocation()**: SmartScheduler decides `shouldProcess` (drift filter + adaptive interval)
3. **Engine.onLocationUpdate()** (async via `appScope.launch`): spatial query → fence matching → state transition → `StaySegment` generation
4. **FenceProcessingHolder**: handles enter/exit events, stay timing via `StayDurationManager`, persistent state save
5. **UI observes**: `RepositoryHolder` state (currentMatchedFences, fenceStateVersion) via `collectAsState()`

### Key Design Decisions

- **Leaf-first timing**: Only the deepest matched fence triggers `StaySegment` tracking. Parents are tracked for containment context but don't independently generate segments.
- **Repository as write buffer**: `Repository.enqueueSegment()` buffers in memory, flushes to SQLite in batches of 5. Cache is cleared AFTER successful transaction (P0 fix to prevent crash data loss).
- **DI pattern**: Repository interfaces (`FenceRepository`, `SessionRepository`, etc.) wrap `TrackMasterApplication` — they are thin delegates, not independent data stores.
- **Engine is replaceable**: `EngineHolder.engine` is a `var` that gets replaced on fence import/delete via `refresh()`.

## Critical Gotchas

- **LaunchedEffect stale closures**: When setting up listeners inside `LaunchedEffect` that read `State` variables, those state vars MUST be in the effect's key list — otherwise the closure captures stale values on recomposition.
- **AMap privacy consent**: Must call `AMapLocationClient.updatePrivacyShow()` and `updatePrivacyAgree()` BEFORE any map/location SDK calls. Done in `MainActivity.onCreate()`.
- **TOCTOU races**: `appScope.launch` in `checkGeoFences` is async. Operations after the launch may see stale state. The codebase captures snapshots (`matchedFenceIdsSnapshot`) before launching to mitigate this.
- **Engine state writes**: `Engine.transition()` writes to repo BEFORE updating `currentLeafId` — if repo write fails, state is preserved. Don't invert this order.
- **Repository cache invalidation**: After deleting/updating fences, call `repo.clearCache()` and `app.refreshEngineFences()`. Failing to do both will return stale segments.
- **`setCurrentMatchedFenceIds`**: External code should NOT call this on Engine (it's used internally by `onLocationUpdate`). External sync calls create TOCTOU races with async updates. Use `checkFenceMatch()` or `checkFenceMatchWithDynamicBuffer()` instead.
- **Google provider throttling**: `GoogleLocationProvider.setInterval()` exists but requires explicit calls. Engine saves `lastComputedIntervalMs` for `GeoFenceLocationService` to read and apply.

## Module Map

| Package | Purpose |
|---|---|
| `core/` | Engine, GeofenceModels, FenceProcessingHolder, FenceTreeBuilder/Manager, SpatialIndex |
| `locationservice/` | Location provider abstraction (AMap, Google) |
| `mapstraction/` | Map UI abstraction (AMap, Google maps) |
| `data/dao/` | Room DAOs (FenceDao, EventDao, StayDao, DailyStayDao, CategoryDao) |
| `data/database/` | DatabaseManager, DatabaseFacade, FenceExporter |
| `data/repository/` | Repository interfaces + App implementations (delegates to Application) |
| `storage/` | Repository (write buffer), StorageAdapter, SqliteStorageAdapter |
| `viewmodel/` | HomeVM, MapVM, SettingsVM, StatisticsVM, MainVM |
| `ui/screens/` | HomeScreen, MapScreen, HistoryScreen, OnboardingScreen |
| `ui/navigation/` | NavHost, bottom bar variants, Screen routes |
| `ui/theme/` | ThemeManager, ColorScheme, GlassComponents |
| `ui/components/` | AMapViewWrapper, GoogleMapWrapper, ThemePicker |
| `utils/geometry/` | GeoCalculator, FenceStateMachine, CirclePolygonCache, FenceAnalyzer |
| `utils/scheduler/` | StationaryDetector |
| `throttle/` | ThrottleConfig |
| `services/` | Aggregator |
| `model/` | Category, GeoFenceEvent, GeoPoint |

## Map & Location Providers

The app supports both AMap and Google Maps via abstraction layers:
- **Map rendering**: `MapProvider` interface → `AMapProvider` / `GoogleMapProvider` → respective `MapPresenter`
- **Location**: `LocationProvider` interface → `AMapLocationProvider` / `GoogleLocationProvider`
- Default is AMap; switchable via `appPreferences.mapProvider`

## Signing

- Debug keystore: `trackmaster_debug.jks` (password: `trackmaster2024`)
- Release keystore: `trackmaster.jks` (password: `trackmaster2024`)
- AMap API key is in `app/build.gradle.kts` as `buildConfigField`

## DI Registration

When adding new DAOs, repositories, or managers, register them in `AppModule.kt` (Koin). The pattern is:
- DAOs → `single { XxxDao(get()) }` (DatabaseManager is the parameter)
- Repositories → `single<Interface> { AppImpl(get()) }` (Application is the parameter)
- ViewModels → `viewModel { XxxViewModel(get()) }`
