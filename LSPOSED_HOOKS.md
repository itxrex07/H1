# Hollow — LSPosed / Vector (`Vector-master`) Hook Architecture & Reference Guide

This document is the authoritative technical reference for developing, auditing, and extending Xposed/LSPosed hooks in **Hollow** (`com.hollow.app`). Every hook pattern in this file has been verified directly against the **Vector (`Vector-master`)** framework source code included in this repository.

---

## 1. Vector (`Vector-master`) Framework Internals

### 1.1 Module Detection & `AndroidManifest.xml` Contract
 Verified against:
- `Vector-master/daemon/src/main/kotlin/org/matrix/vector/daemon/util/ModuleDetection.kt`
- `Vector-master/legacy/src/main/java/org/matrix/vector/legacy/LegacyDelegateImpl.java`
- `Vector-master/legacy/src/main/java/de/robv/android/xposed/XSharedPreferences.java`

When Vector scans installed packages or loads a legacy Xposed module (`assets/xposed_init`), it inspects `<application>` `<meta-data>` tags:

| Meta-Data Name | Required Value | Purpose in `Vector-master` |
| :--- | :--- | :--- |
| `xposedmodule` | `"true"` | Marks the APK as a legacy Xposed module when `assets/xposed_init` is present. |
| `xposedminversion` | `"93"` | **Critical**: Both `LegacyDelegateImpl.java` (`minVersion > 92`) and `XSharedPreferences.java` (`xposedminversion > 92`) check this value to enable the `/data/misc/<uuid>/prefs/<pkg>/` remote preference bridge. |
| `xposedsharedprefs` | `"true"` | Explicit flag checked alongside `xposedminversion > 92` to hook `ContextImpl.getSharedPreferencesPath` inside the module app. |
| `xposeddescription` | String | Displayed in the Vector / LSPosed Manager module list. |
| `xposedscope` | `@array/xposed_scope` | Default target packages requested when enabling the module in Vector / LSPosed. |

```xml
<meta-data android:name="xposedmodule" android:value="true" />
<meta-data android:name="xposeddescription" android:value="@string/xposed_description" />
<meta-data android:name="xposedminversion" android:value="93" />
<meta-data android:name="xposedsharedprefs" android:value="true" />
<meta-data android:name="xposedscope" android:resource="@array/xposed_scope" />
```

---

### 1.2 The `"android"` vs `"system"` Package Name Swap (`ModuleDetection.kt` & `LoadedApkHookers.kt`)
Verified against:
- `Vector-master/daemon/src/main/kotlin/org/matrix/vector/daemon/util/ModuleDetection.kt` (`swapLegacyFrameworkNames`)
- `Vector-master/xposed/src/main/kotlin/org/matrix/vector/impl/hookers/LoadedApkHookers.kt`
- `Vector-master/xposed/src/main/kotlin/org/matrix/vector/impl/hookers/SystemServerHookers.kt`

Vector supports both the modern `io.github.libxposed.api` and the legacy `de.robv.android.xposed` API, which use **opposite** names for `system_server` and `android:ui`:
1. **Modern API (`libxposed`)**:
   - `"system"` = `system_server`
   - `"android"` = `android:ui` (system dialogs, Chooser/Share sheet, ResolverActivity)
2. **Legacy API (`de.robv.android.xposed`)**:
   - `"android"` = `system_server` (dispatched via `LegacyDelegateImpl.onSystemServerLoaded(classLoader)`)
   - `"system"` = `android:ui` (rewritten in `LoadedApkHookers.kt`: `val legacyPackageName = if (packageName == "android") "system" else packageName`)

Because `ModuleDetection.swapLegacyFrameworkNames()` translates `"android"` <-> `"system"` when reading `@array/xposed_scope` for legacy modules:
- **Rule**: Always include **both** `<item>android</item>` and `<item>system</item>` in `res/values/arrays.xml` (`@array/xposed_scope`), plus `<item>com.android.systemui</item>`.
- **Rule**: In `StorageIsolationHook.handleLoadPackage`, always exempt **both** `pkg == "android"` and `pkg == "system"` (as well as `com.android.systemui`) from file/MediaStore isolation hooks while allowing `SystemStealthHook` to run in all of them.

---

### 1.3 Vector Process Bootstrap & ART Deoptimization
Verified against:
- `Vector-master/xposed/src/main/kotlin/org/matrix/vector/impl/core/VectorStartup.kt`
- `Vector-master/xposed/src/main/kotlin/org/matrix/vector/impl/core/VectorInlinedCallers.kt`
- `Vector-master/xposed/src/main/kotlin/org/matrix/vector/impl/hookers/SystemServerHookers.kt`
- `Vector-master/xposed/src/main/kotlin/org/matrix/vector/impl/hookers/AppAttachHooker.kt`

When a process starts, Vector executes the following sequence:
1. **`VectorStartup.init()`**:
   - Initializes `VectorServiceClient` (`IFrameworkService` Binder channel to Vector daemon).
   - Calls `VectorDeopter.deoptBootMethods()` to deoptimize heavily inlined boot-image callers (`Instrumentation.newApplication`, `LoadedApk.makeApplicationInner`, `LoadedApk.makeApplication`, and `ContextImpl.getSharedPreferencesPath`).
2. **`VectorStartup.bootstrap()`**:
   - **In `system_server` (`isSystem == true`)**:
     - Hooks `com.android.internal.os.ZygoteInit.handleSystemServerProcess` (`HandleSystemServerProcessHooker`), which hooks `com.android.server.SystemServer.startBootstrapServices` (`StartBootstrapServicesHooker`).
     - When `startBootstrapServices` runs (or immediately during late injection if `systemServerStarted == true`), `LegacyDelegateImpl.onSystemServerLoaded(classLoader)` calls `handleLoadPackage` with:
       - `lpparam.packageName = "android"`
       - `lpparam.processName = "android"` (`system_server`)
       - `lpparam.classLoader = systemServerCL` (the `PathClassLoader` containing `services.jar`)
   - **In App & SystemUI Processes (`isSystem == false`)**:
     - Hooks `LoadedApk` constructors (`LoadedApkCtorHooker`), `LoadedApk.createAppFactory` (`LoadedApkCreateAppFactoryHooker`), `LoadedApk.createOrUpdateClassLoaderLocked` (`LoadedApkCreateCLHooker`), and `ActivityThread.attach` (`AppAttachHooker`).
     - `AppAttachHooker` calls `LegacyDelegateImpl.loadModules(activityThread)` to instantiate `StorageIsolationHook` and invoke `initZygote()`.
     - `LoadedApkCreateCLHooker` invokes `handleLoadPackage(lpparam)` once the app's real `ClassLoader` is ready.

---

### 1.4 Cross-Process Configuration & `XSharedPreferences` Architecture
Verified against:
- `Vector-master/legacy/src/main/java/org/matrix/vector/legacy/LegacyDelegateImpl.java`
- `Vector-master/legacy/src/main/java/de/robv/android/xposed/XSharedPreferences.java`
- `Vector-master/legacy/src/main/java/de/robv/android/xposed/services/DirectAccessService.java`
- `Vector-master/daemon/src/main/kotlin/org/matrix/vector/daemon/cache/ConfigCache.kt`

How `XSharedPreferences(MODULE_PKG, PREFS_NAME)` works under Vector:
1. **Writer Side (Inside Hollow Manager App)**:
   - Because Hollow declares `xposedminversion="93"` and `xposedsharedprefs="true"`, `LegacyDelegateImpl.loadModules` hooks `android.app.ContextImpl.getSharedPreferencesPath(String)` inside Hollow.
   - When Hollow opens `SharedPreferences("isovault_rules", Context.MODE_WORLD_READABLE)` (with `Context.MODE_PRIVATE` fallback if the hook is not active yet), Vector redirects the directory path via `VectorServiceClient.getPrefsPath(MODULE_PKG)` to `/data/misc/<uuid>/prefs/com.hollow.app/isovault_rules.xml` and calls `DirectAccessService.ensureModulePrefsPermission(dir)` (`chmod 0755`).
   - Additionally, `IsolationRuleManager.getReadableSharedPrefs(context)` explicitly calls `setReadable(true, false)` and `setExecutable(true, false)` on the preference file and its parent directories after every `.commit()`.
2. **Reader Side (Inside `system_server`, `com.android.systemui`, and Target Apps)**:
   - `XSharedPreferences(MODULE_PKG, "isovault_rules")` resolves the file via `SELinuxHelper.getAppDataFileService().getFilePath(...)`, which calls `VectorServiceClient.getPrefsPath(MODULE_PKG)` to read `/data/misc/<uuid>/prefs/com.hollow.app/isovault_rules.xml` (falling back to `/data/data/com.hollow.app/shared_prefs/isovault_rules.xml`).
   - Before reading, call `if (prefs.hasFileChanged()) prefs.reload()` inside a TTL cache (`2500ms` with a `hasFetchedOnce = true` guard so empty maps do not trigger disk stats on every frame).
3. **Never Call `ContentResolver.call()` Inside `system_server`**:
   - In `system_server` (`currentPkg == "android"` or `Process.myUid() == Process.SYSTEM_UID`), synchronous `ContentResolver.call(Uri.parse(PROVIDER_URI), ...)` can deadlock or fail while `ActivityManagerService` / `PackageManagerService` locks are held.
   - **Rule**: `system_server` must **only** read from `XSharedPreferences` (cached in memory). Regular app processes may use `ContentResolver.call()` as a secondary fallback.

---

## 2. Hollow Feature Hook Implementations

### Feature 1: Module Activation Self-Check
- **File**: `app/src/main/java/com/example/isolation/hook/StorageIsolationHook.kt`
- **Target Package**: `com.hollow.app` (`MODULE_PKG`)
- **Hooked Classes & Methods**:
  - `com.hollow.app.MainActivity$Companion#isLsposedModuleActiveReal()`
  - `com.hollow.app.MainActivity#isLsposedModuleActiveReal()`
- **Behavior**:
  - Sets `param.result = true` in `beforeHookedMethod` and immediately returns from `handleLoadPackage` so Hollow never hooks its own storage or hides apps from its own UI.

---

### Feature 2: System-Wide App Hider & Custom Name/Icon Disguise
- **File**: `app/src/main/java/com/example/isolation/hook/SystemStealthHook.kt`
- **Data Source**: `IsolationRuleManager.KEY_STEALTH_TABLE` in `isovault_rules.xml` (`Map<String, StealthRule>`), completely independent from per-app storage isolation (`isIsolated`).

#### Layer 2A: `system_server` `LauncherAppsService` Hook (Universal Launcher Coverage)
- **Target Process**: `system_server` (`lpparam.packageName == "android"`)
- **Target Class**: `com.android.server.pm.LauncherAppsService$LauncherAppsImpl`
- **Target Method**: `getLauncherActivities(String callingPackage, ...)`
- **Why This Hook Is Essential**:
  - In Vector (`ConfigCache.kt`), modules are only injected into processes listed in their module scope. If a user installs a brand-new third-party launcher (e.g., Nova, Lawnchair, Niagara) without adding that new launcher to Vector's scope, client-side hooks won't run inside that launcher.
  - However, **every** Android launcher fetches its app drawer list from `system_server` via `LauncherAppsService$LauncherAppsImpl.getLauncherActivities` over Binder!
- **Implementation Details**:
  1. Checks `callingPkg == MODULE_PKG` first; never filters results requested by Hollow itself.
  2. Unwraps `android.content.pm.ParceledListSlice` via `XposedHelpers.callMethod(rawResult, "getList")`.
  3. Extracts `ActivityInfo` from each element (`ResolveInfo`, `ActivityInfo`, or `LauncherActivityInfoInternal.mActivityInfo`).
  4. **If `rule.visibilityMode == HIDDEN`**: Removes the item from the list before re-wrapping in `ParceledListSlice`.
  5. **If `rule.visibilityMode == DISGUISED` and `rule.customLabel.isNotBlank()`**:
     - Clones `ActivityInfo` and its nested `ApplicationInfo` (never mutate the shared `PackageManager` cache instance in-place!).
     - Sets `nonLocalizedLabel = rule.customLabel` and `labelRes = 0` on both `ActivityInfo` and `ApplicationInfo`.
     - Because `PackageItemInfo.loadLabel(pm)` checks `if (nonLocalizedLabel != null) return nonLocalizedLabel` before `labelRes`, **every launcher on the device** displays the custom user-typed name automatically across Binder!

#### Layer 2B: `system_server` `PackageManagerService` / `ComputerEngine` Hook
- **Target Process**: `system_server` (`lpparam.packageName == "android"`)
- **Target Classes**:
  - `com.android.server.pm.ComputerEngine` (Android 12L / 13 / 14 / 15 / 16)
  - `com.android.server.pm.PackageManagerService` (Android 12 and fallback)
- **Target Method**: `queryIntentActivitiesInternal(...)`
- **Implementation Details**:
  1. Inspects `Intent` for `Intent.CATEGORY_LAUNCHER` or `Intent.CATEGORY_INFO`.
  2. Checks `val callingAppId = Binder.getCallingUid() % 100_000`.
  3. **Caller Exemptions**:
     - Exempts Hollow (`callingAppId == cachedHollowAppId`) so Hollow can always query and launch hidden apps.
     - Exempts explicit single-package launch lookups (`intent.package != null || intent.component != null`) from `SYSTEM_UID` or Hollow so `getLaunchIntentForPackage` succeeds when launching hidden apps from Hollow or SystemUI.
     - Never hides an app from itself (`targetAppId == callingAppId`).
  4. Filters `HIDDEN` packages and clones `ResolveInfo` to set `nonLocalizedLabel = rule.customLabel` (`labelRes = 0`) for `DISGUISED` packages.

#### Layer 2C: `system_server` `ActivityTaskSupervisor` Recents & Label Hook
- **Target Process**: `system_server` (`lpparam.packageName == "android"`)
- **Reference in `Vector-master`**: Modeled after `ParasiticManagerSystemHooker.kt` (`ActivityTaskSupervisor.resolveActivity`).
- **Target Classes**:
  - `com.android.server.wm.ActivityTaskSupervisor`
  - `com.android.server.wm.ActivityStackSupervisor`
- **Target Method**: `resolveActivity(...)`
- **Implementation Details**:
  - When an activity is resolved for launch:
    - If `rule.visibilityMode == HIDDEN && rule.hideInRecents`: Clones the returned `ActivityInfo` and ORs `ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS` into `flags` so the hidden app leaves no card in the SystemUI Recents screen.
    - If `rule.visibilityMode == DISGUISED && rule.customLabel.isNotBlank()`: Clones `ActivityInfo` and sets `nonLocalizedLabel = rule.customLabel` (`labelRes = 0`) so the task description uses the disguised title.

#### Layer 2D: Client-Side Launcher, Settings (`com.android.settings`), Share Sheet (`system` / `android:ui`), and SystemUI Recents Hooks
- **Target Processes**: `com.android.systemui`, `com.android.settings`, `system` (`android:ui`), and any launcher in scope.
- **Hooked Classes & Methods**:
  - `android.content.pm.LauncherApps#getActivityList` -> filters `HIDDEN` apps (and `hideFromPlayStore` apps in `com.android.vending`).
  - `android.app.ApplicationPackageManager#queryIntentActivities` & `queryIntentActivitiesAsUser` -> filters `HIDDEN` apps and applies `customLabel`.
  - `android.content.pm.LauncherActivityInfo#getLabel` -> returns `rule.customLabel`.
  - `android.content.pm.PackageItemInfo#loadLabel` & `ApplicationPackageManager#getApplicationLabel` -> returns `rule.customLabel` (respecting `rule.hideInRecents` in `com.android.systemui` and `rule.spoofInSettings` in `com.android.settings`).
  - `android.content.pm.LauncherActivityInfo#getIcon`, `getBadgedIcon`, `android.content.pm.PackageItemInfo#loadIcon`, `loadUnbadgedIcon`, `ApplicationPackageManager#getApplicationIcon`, `getActivityIcon`, `loadItemIcon`, `loadUnbadgedItemIcon`, `getDrawable`, and `com.android.launcher3.icons.IconProvider#getIcon` -> returns `resolveDisguiseDrawable(rule)` wrapped in an `AdaptiveIconDrawable` (supporting Custom Gallery Images, Cloned Installed App Icons, and Built-in Presets).

#### Layer 2E: `system_server` & Client-Side Google Play Store Hider + System Settings App Management Spoofer
- **Target Processes**: `system_server` (`android`), `com.android.vending` (Play Store), and `com.android.settings`.
- **Hooked Classes & Methods**:
  - `com.android.server.pm.ComputerEngine` / `PackageManagerService`: `getInstalledApplications`, `getInstalledPackages`, `getApplicationInfo`, `getPackageInfo` (guarded by `ThreadLocal` `isInPmInfoHook`).
  - When `callingAppId == cachedPlayStoreAppId` and `rule.hideFromPlayStore == true`: filters the package out of `getInstalledPackages` / `getInstalledApplications` and returns `null` from `getPackageInfo` / `getApplicationInfo`.
  - When `rule.spoofInSettings == true && rule.visibilityMode == DISGUISED`: clones `ApplicationInfo` / `PackageInfo` with `nonLocalizedLabel = rule.customLabel` (`labelRes = 0`) and bumps `packageInfo.lastUpdateTime` deterministically so `Launcher3`'s SQLite `app_icons.db` automatically invalidates stale cached icons.
  - Additionally, `LauncherAppsService.getLauncherActivities` and `queryIntentActivitiesInternal` swap `icon`, `logo`, `sourceDir`, and `publicSourceDir` on cloned `ActivityInfo`/`ApplicationInfo` (for non-system callers) to point at `rule.donorSourceDir` and `rule.donorIconRes`.

---

### Feature 3: Direct SystemUI Stealth Trigger & Secret Entry Points
When Hollow itself is set to **Hidden** (`LauncherVisibilityMode.HIDDEN`), the user needs a reliable way to open Hollow without relying on any home launcher. Hollow provides **three independent system entry points**:

#### Entry Point 3A: Direct `com.android.systemui` Header / Clock Long-Press Hook
- **File**: `SystemStealthHook.kt` (`attachSystemUiDirectHooks`)
- **Target Process**: `com.android.systemui`
- **Hooked Views**:
  - `com.android.systemui.statusbar.policy.Clock`
  - `com.android.systemui.statusbar.policy.DateView`
  - `com.android.systemui.qs.QuickStatusBarHeader`
- **Why We Hook `onAttachedToWindow` + `performLongClick` Instead of `setOnLongClickListener`**:
  - In Android 12–16 SystemUI, `ShadeHeaderController` / `CollapsedStatusBarFragment` attaches its own click/touch listeners after view inflation, which would overwrite a `setOnLongClickListener` set in the constructor.
  - Instead, we:
    1. Set `view.isLongClickable = true` in both `<init>` (constructor) and `onAttachedToWindow`.
    2. Hook `performLongClick` in `beforeHookedMethod`: when `cachedSystemUiTrigger` is enabled, we collapse the notification shade via `context.getSystemService("statusbar")` -> `collapsePanels()` and start Hollow with `Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP` and category `com.hollow.app.LAUNCH_HOLLOW`, setting `param.result = true`.

#### Entry Point 3B: SystemUI Quick Settings Tile (`HollowStealthTileService`)
- **File**: `app/src/main/java/com/example/isolation/service/HollowStealthTileService.kt`
- **Manifest Declaration**: `android.service.quicksettings.action.QS_TILE` with label `"Quick Calc"`.
- **Behavior**: Hosted natively by `com.android.systemui` in the Quick Settings panel; tapping the tile collapses the shade and opens Hollow even when Hollow's launcher icon is hidden.

#### Entry Point 3C: Secret Dialer Code (`*#*#465569#*#*` — "HOLLOW")
- **File**: `app/src/main/java/com/example/isolation/service/HollowSecretCodeReceiver.kt`
- **Reference in `Vector-master`**: Modeled after Vector's dialer secret code (`*#*#832867#*#*` in `VectorService.kt`).
- **Manifest Declaration**: `<receiver>` listening for `android.provider.Telephony.SECRET_CODE` with `<data android:scheme="android_secret_code" android:host="465569" />`.
- **Behavior**: Dialing `*#*#465569#*#*` in the system Phone app broadcasts `SECRET_CODE` and launches Hollow directly.

---

### Feature 4: Per-App Storage Isolation & Shared Hollow Workspace
- **File**: `app/src/main/java/com/example/isolation/hook/StorageIsolationHook.kt`
- **Modes Supported**:
  - `ENCRYPTED_VAULT` / `SANDBOX_REDIRECT`: Per-app isolated storage inside `/data/user/0/<pkg>/files/IsoVault_Sandbox`.
  - `SHARED_WORKSPACE`: Shared multi-app workspace inside `/storage/emulated/0/Hollow_Workspace` (falling back to per-app sandbox if inaccessible).
  - `FAKE_EMPTY`: Returns empty media cursors and empty directory listings.
  - `READ_ONLY`: Blocks writes while permitting reads.

| Layer | Hooked Classes & Methods | Purpose |
| :--- | :--- | :--- |
| **Layer 1: Virtual MediaStore & ContentResolver** | `ContentResolver#query`, `ContentProviderClient#query`, `ContentProviderProxy#query`, `ContentResolver#insert`, `ContentResolver#openInputStream`, `openOutputStream`, `openFileDescriptor`, `openAssetFileDescriptor`, `openTypedAssetFileDescriptor`, `loadThumbnail`, `delete`, `ImageDecoder#createSource`, `MediaMetadataRetriever#setDataSource` | Serves `SafeSandboxMatrixCursor` / `SafeEmptyMatrixCursor` populated exclusively from the app's sandbox (`DCIM`, `Pictures`, `Movies`, `Download`), redirects media saves into the sandbox, and blocks reads of real phone media URIs unless present in `userAllowedUris`. |
| **Layer 2: SQLite Camera Roll Cache Guard** | `android.database.sqlite.SQLiteSession#executeForCursorWindow` | Intercepts `SELECT` queries targeting `camera_roll`, `local_media_reference`, or `/storage/emulated/0/dcim` and clears the `CursorWindow` so apps like Snapchat cannot display cached real gallery thumbnails from their local DB. |
| **Layer 3: System Photo & File Picker Interceptor** | `Instrumentation#execStartActivity`, `Activity#startActivityForResult`, `Activity#dispatchActivityResult` | Respects `PickerPolicy.ALLOW` (binds `EXTRA_INITIAL_URI` to sandbox `Pictures`, adds user-picked URIs to `userAllowedUris`, and atomically copies picked files via `.part` -> final file into sandbox) or `PickerPolicy.BLOCK` (cancels picker and delivers `Activity.RESULT_CANCELED`). Never intercepts internal in-app activities (`componentPkg == pkg`). |
| **Layer 4: `java.io.File` & `BitmapFactory` Redirection** | `File(String)`, `File(String, String)`, `File(File, String)`, `File#list`, `File#listFiles`, `File#exists`, `isDirectory`, `isFile`, `length`, `canRead`, `canWrite`, `BitmapFactory#decodeFile` | Redirects `/storage/emulated/0`, `/sdcard`, `/mnt/sdcard`, and `/storage/self/primary` paths to the app's sandbox (or `/dev/null` when blocking root pollution folders), while preserving whitelisted folders and standing down when a root bind-mount (`MountManager.VOLUME_MARKER`) is active. |
| **Layer 5: Low-Level `libcore.io` & `Environment`** | `libcore.io.IoBridge#open`, `libcore.io.BlockGuardOs#open/stat/lstat/access/opendir`, `android.os.Environment#getExternalStorageDirectory`, `getExternalStoragePublicDirectory` | Rewrites raw POSIX path strings passed to `IoBridge` / `BlockGuardOs` and returns sandbox `File` roots from `Environment` getters. |

---

## 3. Mandatory Golden Rules for Future Hook Development

1. **Always Guard Against Hook Recursion (`ThreadLocal<Boolean>`)**:
   - Inside any `File`, `ContentResolver`, `SQLiteSession`, `libcore.io`, or overloaded `ComputerEngine.queryIntentActivitiesInternal` hook, check `if (isHandlingHook.get() == true) return` before executing any logic, and reset it in a `finally` block.
2. **Never Mutate Shared `PackageManager` Objects In-Place in `system_server`**:
   - In `system_server` (`ComputerEngine`, `PackageManagerService`, `LauncherAppsService`, `ActivityTaskSupervisor`), `ActivityInfo`, `ApplicationInfo`, and `ResolveInfo` instances may come from PackageManager's in-memory cache. Always clone (`ActivityInfo(original)`, `ApplicationInfo(original)`, `ResolveInfo(original)`) before setting `nonLocalizedLabel`, `labelRes = 0`, or `flags`.
3. **Strict `processName` + `UID` Filtering & Single-Attach Per Process (Anti-Freeze Rule)**:
   - When `System Framework` (`android`) is enabled in LSPosed/Vector, `handleLoadPackage` also fires for secondary system packages loaded inside `system_server` (e.g., `com.android.inputdevices`, `com.android.location.fused`, `com.android.server.telecom`, `com.android.keychain` where `lpparam.processName == "android"` and `UID == 1000`).
   - Always check `Process.myUid() < Process.FIRST_APPLICATION_UID`, `lpparam.processName == "android"`, `lpparam.processName.startsWith("com.android.systemui")`, and `lpparam.isFirstApplication`, and guard hook attachment with an `AtomicBoolean` (`compareAndSet(false, true)`) so `BootClassLoader` hooks are never attached inside `system_server` or stacked multiple times in one process.
4. **Never Block UI or `system_server` Threads on `XSharedPreferences` or `ContentResolver` IPC**:
   - Never instantiate `XSharedPreferences` inside `initZygote()`.
   - Never call `ContentResolver.call()` or synchronous `XSharedPreferences.reload()` on hooked threads inside `system_server` or `com.android.systemui` (which causes deadlocks during Direct Boot lockscreen and screen wake). Always return the volatile in-memory rule map in `0ms` and refresh `XSharedPreferences` asynchronously on a single daemon background thread.
5. **Preserve Direct Launch & Self-Query Exemptions**:
   - Never hide `MODULE_PKG` or any hidden app when the query originates from `MODULE_PKG` (`callingAppId == cachedHollowAppId`), nor when `targetPkg == currentPkg`.
