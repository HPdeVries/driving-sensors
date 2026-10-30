# ODC integration for `driving-sensors`

Artifacts in this folder:

| File | Purpose |
|---|---|
| `extensibility-configuration.json` | Paste into the mobile library's Extensibility Configurations (library schema, MABS 12+) |
| `buildAction.json` | Upload as a library **resource**, Deploy Action = *Deploy to Target Directory* |
| `buildAction.android-fallback.json` | Only if the plugin's Android manifest does not carry the service (see below). Service class name is a placeholder: match it to the Android implementation |
| `client-actions/*.js` | One JS node body per client action |
| `structures.md` | Structures, Local Storage entity, sync flow |

Requires **MABS 12+ with the Capacitor framework**. MABS 12 uses Capacitor 8.0.0 according to an OutSystems forum answer (community, see Sources), which matches this plugin (Capacitor 8).

## Step by step

1. **Tag and publish the plugin**: commit `dist/` (see finding 4), tag `v0.1.2`, push to https://github.com/HPdeVries/driving-sensors. The repo must be public (MABS has to fetch it; private repos are **unverified**).
2. In ODC Studio create a **Mobile library** `DrivingSensors`.
3. **Extensibility Configurations** tab of the library: paste `extensibility-configuration.json`. Library configs use the *library (plugin)* schema; app-level values override library values for permissions.
4. **Resources**: add `buildAction.json`, set *Deploy Action* = **Deploy to Target Directory**. The name must match `$resources.buildAction.json` in the config.
5. Define the three **Extensibility Settings** referenced by the config (names must match exactly): `LocationWhenInUseUsageDescription`, `LocationAlwaysUsageDescription`, `MotionUsageDescription` (Text, with default descriptions the end user will read). The exact UI for declaring settings on a library is **unverified**; the docs only show `$extensibilitySettings.<Name>` references and that app developers change them in the ODC Portal.
6. Create the **structures** from `structures.md`.
7. Create the **8 client actions** (CheckPlugin, CheckPermissions, RequestPermissions, StartTracking, StopTracking, GetStatus, GetBufferedEvents, AckEvents). For each: add the input/output parameters listed in the header comment of the matching `.js` file (with the ODC types given), add a **JavaScript** node, paste the file body, and make sure the JS node's inputs/outputs are exposed (`$parameters.X`). All JS nodes here are async and call `$resolve()`.
8. Mark the actions public, **Publish** the library.
9. In the consuming **mobile app**: add a dependency on the library (Manage Dependencies), build the screens/timers/sync (see `structures.md`).
10. App **Mobile distribution / extensibility settings** (Portal): set the three usage-description settings per app if the defaults are not suitable. Changing them requires a new mobile package.
11. In the app's **Mobile distribution** settings select the **Capacitor** framework (MABS 12) and generate the Android/iOS package. Install on a device and run the field test (see `../example-app/README.md`).
12. At runtime: call `RequestPermissions("location")`, then `RequestPermissions("backgroundLocation")` (Android 11+ requires separate steps, iOS shows the "Always" upgrade prompt later), then `RequestPermissions("motion,notifications")`, then `StartTracking`.

## Research findings (Q1-Q4)

### 1. Library extensibility configuration schema

Confirmed in the library schema page: top-level `pluginConfigurations` and `buildConfigurations` (both optional). Only mobile libraries can hold the library config; the schema "may change with every new MABS version".

- `buildConfigurations.capacitor.source.npm` (string, required when `capacitor` is present): "an npm package spec". Official example uses `"@capacitor/barcode-scanner@2.0.3"`.
- `buildConfigurations.buildAction.config`: `$resources.<file>` (uploaded with Deploy Action *Deploy to Target Directory*) or an extensibility setting holding a file path; binary settings are not supported. Optional `parameters`. Capacitor apps only. Plugin-level actions run before app-level, in JSON order.
- `buildConfigurations.resources`: arrays `android`/`ios` of `{source, target}` (copied before `cap sync`).
- `pluginConfigurations.permissions.ios`: object keyed by Info.plist usage key with `{ "description": "<text or $extensibilitySettings.X>" }`. An entry without description needs a value from the app or another plugin, otherwise the build fails. `permissions.android`: array of permission strings. App-level values take precedence.
- `pluginConfigurations.capacitor.configurations`: per-plugin-class Capacitor plugin config (not needed here).
- **GitHub `url#tag` as `npm` value: NOT documented.** The official pages show only npm specs. A community article (MABS 12 migration) shows a Git URL with a `#tag` suffix in the legacy Cordova `plugin.url` and says `npm` is the preferred key "even when the value is still a Git URL". Treat `https://github.com/HPdeVries/driving-sensors#v0.1.2` in `capacitor.source.npm` as **unverified**; the fallback is publishing to npm and using `"driving-sensors@0.1.2"`.
- **Capacitor-only library: allowed.** The schema marks `cordova` as required only "if `capacitor` is missing" and vice versa, so either alone is valid; no `cordova` section is needed. **What a Cordova-framework build does with a Capacitor-only library is not documented** (the MABS support page only says to ensure the app and its plugins are compatible with the chosen framework to avoid package-creation failures). Expect the plugin to be absent or the build to fail; the JS actions return `PLUGIN_NOT_AVAILABLE` if the plugin is simply missing. **Unverified.**
- MABS 12 requires the universal schema; the legacy Cordova-based schema is not supported (error for Capacitor apps).

### 2. Build actions

- iOS `plist` action schema (documented): `Array<{ file?: string; replace?: boolean; entries: Array<any> }>`, default file Info.plist; values merge by default, `replace: true` overwrites the target. Format used in `buildAction.json` follows the documented example exactly (`platforms.ios.plist[].entries[]`).
- **Whether `UIBackgroundModes` (an array) merges item-wise, appends or replaces is not documented**. Since the Capacitor Info.plist normally has no `UIBackgroundModes`, `["location"]` is expected to simply be set. If a future app also needs e.g. `audio`, another library/app build action could clobber or duplicate it: **verify by inspecting the built Info.plist**.
- Android `manifest` action (documented): entries with `file`, `target` (XML path) and one of `attrs`, `merge`, `inject` (single well-formed XML element), `deleteAttributes`, or `delete` (XPath). The docs have no service example; `buildAction.android-fallback.json` is an adaptation using `inject` under `manifest/application`. Normally not needed: the plugin's own `AndroidManifest.xml` is merged by Gradle (standard Android library behaviour; MABS merging of it is **unverified**).
- Deployment: add the JSON as a resource of the library, **Deploy Action = Deploy to Target Directory**, and reference as `"buildAction": {"config": "$resources.buildAction.json"}`. If you need both iOS and Android actions, put both under `platforms` in the same file (merge the fallback file into `buildAction.json`); only one `config` is referenced.

### 3. Accessing the plugin from JS nodes

The official "Integrate Capacitor plugin into a mobile app" page uses `window.CapacitorPlugins.<PluginName>` and guards with `typeof(window.CapacitorPlugins) !== "undefined"`; it states `window.CapacitorPlugins` is populated at build time with the plugins referenced in library configurations. That is the primary path. `window.Capacitor.Plugins.X` is the Capacitor-core proxy and `Capacitor.registerPlugin` is the generic API; whether they exist/work in MABS 12 builds is **unverified**, so all client actions try, in order: `window.CapacitorPlugins.DrivingSensors`, `window.Capacitor.Plugins.DrivingSensors`, then (native only) `window.Capacitor.registerPlugin("DrivingSensors")`; otherwise they return `Success=false, ErrorCode="PLUGIN_NOT_AVAILABLE"`. The name `DrivingSensors` is the `registerPlugin` name in `src/index.ts`. Verified offline with a mock harness (present/absent plugin), not on a real MABS build.

### 4. GitHub `url#tag` sources: commit `dist/` or `prepare`?

Not documented by OutSystems (no page found describing how MABS fetches the package or whether install scripts run). From general npm behaviour: installing a git dependency runs `npm pack`-style preparation including the `prepare` script, but only if devDependencies can be installed and scripts are not disabled (`--ignore-scripts`). Whether MABS does this is **unverified**. **Recommendation: commit `dist/` on the release tag** (works regardless of MABS install flags and the plugin's `main`/`module` point into `dist/`; the native `android/`, `ios/Sources`, `Package.swift` are what MABS actually builds). The root `.gitignore` ignores `dist/`, so release with `git add -f dist` on a release commit that is tagged (or remove `dist/` from `.gitignore`). Optionally also add `"prepare": "npm run build"` as a safety net, but note `build` runs docgen and rollup that need devDependencies. If MABS cannot fetch the Git source at all, publish to npm and change `npm` to `driving-sensors@0.1.2`.

## Sources

- Integrate Capacitor plugin into a mobile app: https://success.outsystems.com/documentation/outsystems_developer_cloud/building_apps/mobile_apps/use_mobile_plugins/integrate_capacitor_plugin_into_a_mobile_app/
- Library (plugin) extensibility configuration JSON schema: https://success.outsystems.com/documentation/outsystems_developer_cloud/building_apps/mobile_apps/configure_mobile_apps/universal_extensibility_configurations_json_schema/library_plugin_extensibility_configuration_json_schema/
- Universal extensibility configurations JSON schema (MABS 12 requires it): https://success.outsystems.com/documentation/outsystems_developer_cloud/building_apps/mobile_apps/configure_mobile_apps/universal_extensibility_configurations_json_schema/
- Build actions: https://success.outsystems.com/documentation/outsystems_developer_cloud/building_apps/mobile_apps/configure_mobile_apps/build_actions/
- iOS build actions: https://success.outsystems.com/documentation/outsystems_developer_cloud/building_apps/mobile_apps/configure_mobile_apps/build_actions/ios_build_actions/
- Android build actions: https://success.outsystems.com/documentation/outsystems_developer_cloud/building_apps/mobile_apps/configure_mobile_apps/build_actions/android_build_actions/
- Capacitor and Cordova support in MABS: https://success.outsystems.com/documentation/outsystems_developer_cloud/building_apps/mobile_apps/create_mobile_app_package/capacitor_and_cordova_support_in_mabs/
- Implement offline data synchronization: https://success.outsystems.com/documentation/outsystems_developer_cloud/building_apps/data_management/offline_data_synchronization_in_mobile_apps/implement_offline_data_synchronization/
- Community: Bridging the Gap, updating plugins for MABS 12 (Git URL with `#tag` in legacy config): https://medium.com/@fabiofantato/bridging-the-gap-updating-your-mobile-plugins-to-work-with-mabs-12-e6c61a284cfe (page itself returned 403 when fetched; content known only from a search summary)
- Community: Capacitor 8.0.0 in MABS 12: https://www.outsystems.com/forums/discussion/105198/mabs-12-general-availability/ (from a search summary, not fetched)

## Unverified summary

GitHub `url#tag` in `capacitor.source.npm`; `dist/` vs `prepare`; private-repo fetch; Cordova-build behaviour with a Capacitor-only library; `UIBackgroundModes` array merge; plugin AndroidManifest merging in MABS; `window.Capacitor.Plugins` / `registerPlugin` availability; how library-level extensibility settings are declared in the ODC Studio UI; JSON Deserialize handling of omitted attributes; ODC offline sync action names beyond `OnSync`.
