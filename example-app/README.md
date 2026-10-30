# driving-sensors field-test app

Bare Capacitor 8 app (Vite, no framework) that drives the plugin from the parent folder (`"driving-sensors": "file:.."`). Use it to test on real devices before wrapping the plugin in ODC.

Features: permission buttons, editable thresholds, start/stop, status auto-refresh every 5 s while in foreground, event fetch on app resume, last-50 event table with per-type counts, harsh events highlighted with GPS speed, **Ack fetched**, and **Export** (JSON via share sheet). Fetched events are kept in `localStorage` so they survive restarts.

## Build and install

```bash
# once, in the repo root (builds the plugin's dist/, which the app imports)
npm install && npm run build

cd example-app
npm install
npm run build          # vite -> dist/
npx cap sync
```

After changing plugin JS/TS: rebuild the root (`npm run build`) and run `npm run build && npx cap sync` here. Native plugin changes are picked up by the native build directly.

### Android

```bash
cd android
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home   # Gradle cannot run on JDK 26
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or `npx cap open android` and Run on the device. The plugin's own manifest must declare the permissions and the foreground service.

### iOS

`npx cap open ios`, select the `App` target, set your **Team** under Signing & Capabilities (bundle id `com.ceeshr.driveme`), connect the iPhone and Run. Simulator build check: `xcodebuild -project ios/App/App.xcodeproj -scheme App -destination 'generic/platform=iOS Simulator' build`. The project uses SPM (Capacitor 8), no CocoaPods.

`ios/App/App/Info.plist` already contains `UIBackgroundModes = [location]`, `NSLocationWhenInUseUsageDescription`, `NSLocationAlwaysAndWhenInUseUsageDescription` and `NSMotionUsageDescription`. The simulator cannot deliver real motion data: use a physical device.

## Permission order

1. Request location (foreground)
2. Request background location (Android: separate step; iOS: "Always" prompt, may appear later or require Settings > Location > Always)
3. Request motion + notifications (Android 13+ needs notifications for the foreground-service notification)
4. Start tracking

## Field-test checklist

Before the drive
- [ ] All four permissions `granted` (Check permissions). iOS location set to **Always** and Precise Location on.
- [ ] Battery 100 % or note the starting percentage, note the time. Phone model, OS version.
- [ ] Android: disable battery optimisation for the app (Settings > Apps > Battery > Unrestricted). **Samsung**: Device care > Battery > Background usage limits, remove the app from "Sleeping/Deep sleeping apps". **Xiaomi/MIUI/HyperOS**: Autostart on, Battery saver "No restrictions", lock the app in Recents. Repeat the drive once with the defaults (optimisation on) and note if the service is killed.
- [ ] Start tracking, check Status shows `active: true`, then Fetch events once to confirm the pipeline works.

During the drive (30+ minutes)
- [ ] Put the app in the background and turn the **screen off**. Do not reopen it for at least 15 minutes (also covers Android WebView throttling after ~5 min in background).
- [ ] Mount variants over the drive or on separate drives: dashboard mount flat, vertical windscreen mount, cupholder, passenger seat, trouser pocket. Rotate orientation (portrait/landscape) at least once.
- [ ] At safe moments (empty road, good visibility) do 3-5 deliberate hard brakes (~3-4 m/s2) and 3-5 hard accelerations, note the clock time of each.
- [ ] Also drive normally and over bumps/potholes/speed bumps: these must **not** produce HARSH events (false-positive check).
- [ ] A stop of more than 3 minutes should end the trip (TRIP_END), driving off should start a new one.

After the drive
- [ ] Open the app: events are fetched on resume. Check counts: TRIP_START/TRIP_END pairs, LOCATION count roughly drive duration / `locationIntervalMs`.
- [ ] Compare each HARSH event: speed in the event (`speedMps`) and the GPS speed deltas around that time from the LOCATION rows. A HARSH_BRAKE should show speed falling, HARSH_ACCEL speed rising, and the peak must be plausible for the speed change (e.g. 30 to 10 km/h in 2 s is about 2.8 m/s2). Note missed events and false positives; adjust thresholds and repeat.
- [ ] Check LOCATION gaps (time between consecutive events) for background throttling, and `accuracyM` values.
- [ ] Battery drain: percentage used per hour with the screen off; compare with a drive without the app.
- [ ] Tap **Ack fetched**, then Get status: `buffered` must drop to 0 (or only newer events).
- [ ] **Export** and archive the JSON together with your notes (clock times of deliberate events).
- [ ] Kill the app from Recents while tracking: Android should keep recording (foreground service), iOS should resume on relaunch/significant movement per the spec. Note what happens.
- [ ] Reboot test (optional): tracking is not expected to restart before the user opens the app again.

iOS specific: CoreMotion in the background
- [ ] With screen off and app backgrounded, deliberate hard brakes must still produce HARSH events. If LOCATION events keep arriving but no HARSH events ever appear, CoreMotion is being suspended despite the location background mode (handoff open item 2). Note the iOS version, and whether the blue location indicator was visible.
- [ ] Verify the "Always" permission was granted (Check permissions, `backgroundLocation`), not only "While using".

Android specific
- [ ] Persistent notification visible while tracking; on Android 13+ confirm POST_NOTIFICATIONS granted.
- [ ] Android 14+: foreground service of type location starts from the foreground (Start tracking while the app is open).
- [ ] `adb logcat | grep -i drivingsensors` shows discarded candidates with reasons (debug log) for the GPS cross-check.
