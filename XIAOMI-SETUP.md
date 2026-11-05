# Xiaomi TV Stick setup

Device: MiTV-AESP0, Android TV 10/API 29, ADB `192.168.100.27:5555`, user 0.

## Build and install

```bash
./setup.sh
launcher/build-and-install.sh --build
adb connect 192.168.100.27:5555
ADB_SERIAL=192.168.100.27:5555 launcher/build-and-install.sh --install
```

Setup installs Homebrew JDK 17 and a project-local SDK in `.local/android-sdk`:
platforms 29 and 34, build-tools 34.0.0. Java compiles against the actual Android 10
framework; resource packaging uses platform 34, minimum SDK is 29, target SDK is 30.
`ANDROID_HOME` and `TVHOME_JAVA_HOME` override the local SDK and JDK respectively.
Android Studio's Java 25 caused a D8 failure, so the build explicitly uses JDK 17.
Native ADB is already installed. The optional Python client is not required.

The build/install script only installs and opens the launcher. Home migration is a
separate, explicitly authorized operation; rebuilding does not repeat package or
accessibility changes. Keep `launcher/debug.keystore` for future updates.
Location permission is not granted; Control Center shows connectivity status without requiring
SSID access. Inputs and TV-tuner shortcuts have been removed from this TV Stick launcher.

## Home routing and remappers

Original resolved Home: `com.google.android.tvlauncher/.MainActivity`.
Monet's actual package is `com.klevico.monet`, version 1.0.97 (131), with service
`com.klevico.monet/com.klevico.monet.LauncherAccessibilityService`. Its accessibility
service redirected other Home launchers to Monet even when Monet was not the resolved Home.

Button Mapper is `flar2.homebutton`, service `flar2.homebutton/a.i`. Its Home Button
configuration was inspected: Customize OFF, Single tap/Double tap/Long press all Default.
Its package, service, and mappings are preserved.

The stock launcher has Home intent priority 2, whereas our launcher has priority 0.
On this firmware `set-home-activity` alone reports Success but still resolves stock Home.
Android 10 checks differing priorities before the saved preference:
[Android package-manager source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/services/core/java/com/android/server/pm/PackageManagerService.java).
The firmware rejects individual component-state changes from shell. Therefore the stock
launcher package is disabled for user 0, with APKs and data preserved for fallback.
It has not been uninstalled. These Home-specific changes are separate from debloating.

Migration commands applied after recording original state:

```bash
adb -s 192.168.100.27:5555 shell settings --user 0 put secure enabled_accessibility_services flar2.homebutton/a.i
adb -s 192.168.100.27:5555 shell am force-stop com.klevico.monet
adb -s 192.168.100.27:5555 shell pm disable-user --user 0 com.google.android.tvlauncher
adb -s 192.168.100.27:5555 shell cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity
```

Do not copy the accessibility command onto another device or after adding new services:
it reflects this device's recorded services. Remove only Monet's component from any newer list.
Do not use the TCL debloat list or legacy `tools/restore.sh` for this Xiaomi.

## Backups and restore

The migration snapshot is recorded in `backup/current-home`; earlier snapshots are
recorded in `backup/current` and `backup/current-layout`. All are local and ignored by Git.
The migration snapshot includes original package states, Home candidates, settings,
accessibility and input state, Button Mapper Home configuration XML, the previous launcher
APK/source, screenshots, and checksums. Monet's `base.apk` and all three split APKs
(armeabi-v7a, English, tvdpi) were pulled before changes. Button Mapper's APK is also saved.

Use the current migration snapshot's restore scripts, not the earlier test-install scripts:

```bash
SNAPSHOT=$(cat backup/current-home)
bash "$SNAPSHOT/restore-stock-home.sh"       # stock fallback only
bash "$SNAPSHOT/restore-home-and-monet.sh"  # reinstall Monet splits and restore old routing/services
bash "$SNAPSHOT/restore-layout.sh"          # optional: previous custom-launcher APK/layout
```

Stock fallback commands are:

```bash
adb -s 192.168.100.27:5555 shell pm default-state --user 0 com.google.android.tvlauncher
adb -s 192.168.100.27:5555 shell cmd package set-home-activity --user 0 com.google.android.tvlauncher/.MainActivity
adb -s 192.168.100.27:5555 shell am start -n com.google.android.tvlauncher/.MainActivity
```

The full restore reinstalls all Monet splits with `adb install-multiple -r`, restores stock
Home selection, opens Monet to clear its stopped state, and restores the original accessibility
list. This intentionally brings back Monet's old interception. If accessibility services have
changed since migration, merge those newer registrations before restoring the saved list.
Monet removal used `adb shell pm uninstall -k com.klevico.monet` only after Home verification;
`-k` retains its private data for rollback. Private data has not been independently exported,
so APK backups alone do not guarantee preferences survive later data cleanup or a factory reset.
Our custom launcher remains installed when restoring stock Home. Restore Home before ever
uninstalling our launcher, since the stock fallback is disabled during normal operation.

The original four disabled packages remain disabled: `com.google.android.music`,
`com.google.android.youtube.tvmusic`, `com.google.android.videos`, `com.google.android.play.games`.
No unrelated apps or debloat lists are changed.

## Dock layout

Five favorite banners fill the dock's available inner width. The current tvOS-inspired
tile treatment uses 14dp rounded corners and 16dp horizontal gaps (about 19px and 21px
on this 1280×720 device). All Apps uses the same tile corners and horizontal gaps, with
20dp between rows. Banners remain 16:9. Focus enlargement stays at 4% without overshoot. With fewer
favorites, the same five slots are retained and populated from the left, leaving empty
space on the right. An empty dock displays All Apps instead. This full-width layout
supersedes the earlier 151×84px compact sizing at the user's request.

After this styling update, `shots/xiaomi-tvos27-rounded-dock.png` shows the installed
five-app dock and `shots/xiaomi-tvos27-rounded-grid.png` shows the All Apps grid.

App selection no longer applies an artwork-colored glow or color-changing background
gradient. Wallpapers retain only neutral readability scrims; the grid may dim neutrally
for legibility. The large clock, Control Center, clear D-pad targets and selected-app title
remain. The main dock is limited to five favorites; additional apps stay in All Apps.

## Verification

Physical Home before reboot: PASS. YouTube was foreground, a real Xiaomi RC
`KEY_HOMEPAGE DOWN` event was recorded, and `com.mihir.tvhome/.MainActivity` resumed.
The user also confirmed the expected launcher appeared. Hardware events are recorded
separately from ADB-injected key events; injected Home alone is not physical verification.

Physical Home after the first reboot: PASS, again from YouTube with hardware event and user
confirmation. The boot ID changed and Home resolution persisted. Only after both physical tests
passed were the Monet APK backup hashes verified against the device and Monet uninstalled.
An injected Home check after removal also resumed our launcher. A second reboot after Monet
removal changed the boot ID and preserved custom Home. The final physical Home test from
YouTube also passed, with a recorded Xiaomi RC hardware event, resumed activity and user
confirmation. Monet was absent from installed packages at the end.

Dock geometry is verified from UI bounds: 151×84px, 16px gaps, centered within one pixel
of integer rounding. Screenshots: `shots/xiaomi-compact-dock.png`,
`shots/xiaomi-compact-dock-middle.png`, `shots/xiaomi-compact-dock-right.png`, and
`shots/xiaomi-compact-app-grid.png`. Hardware test screenshots and results use the
`physical-home-*` prefix. See those result JSON files, input logs, boot IDs and final
verification notes in the migration snapshot.

The migration snapshot for this run is `backup/home-migration-20261021-142154`.
`verification-final.txt` records the package/settings audit. Button Mapper remains enabled
and retains its original Home configuration. The stock launcher is retained but disabled-user;
use `restore-stock-home.sh` to activate it. Monet is uninstalled with data retained for rollback.
The final screenshot is `shots/xiaomi-compact-dock.png`.

The audit observed reboot/runtime bookkeeping changes: `boot_count` increased by two,
`key_prop_speedupload_started_times` reset from 6 to 1, and the existing Google notification
listener gained a duplicate abbreviated component spelling (the normalized listener set is
unchanged). Those runtime-generated values were left alone; no writes to those settings
were issued. The intentional secure-setting change removes only Monet's accessibility service.

## Control Center update

The top-right capsule combines the current local time and a white vector Control Center
icon. Its idle background and border use the same alpha values as the dock (`0x1FFFFFFF`
and `0x14FFFFFF`). It opens a rounded, dark translucent panel anchored below the capsule,
with white vector icons for Wi-Fi, Bluetooth and system Settings. Wi-Fi reports Connected,
Offline or the SSID when Android makes it available; no location grant was added.

The panel uses a static translucent scrim and a short 140ms entrance animation. It does
not capture or blur the wallpaper, request overlay privileges, or run a background service.
Inputs and the old Menu-to-Inputs handler are removed. The large clock is unchanged.

Navigation:

- Up from any dock tile focuses the capsule; OK opens the panel with Wi-Fi focused.
- Up/Down selects the three rows, with subtle focus highlighting and no wrapping.
- OK opens the selected system screen. Bluetooth first tries the standard Android action;
  this firmware requires `com.google.android.intent.action.CONNECT_INPUT`, which opens its
  native Bluetooth accessory-pairing screen. No pairing or connectivity change is automated.
- Back closes the panel and restores the capsule's focus. Down returns to the previously
  selected dock app. Home closes the panel and returns to the first dock app as before.

Remote shortcut investigation used the active Xiaomi RC device and its vendor key layout:
`/vendor/usr/keylayout/Vendor_2717_Product_32b9.kl`. It maps Home, Back, voice, Settings,
Menu and branded app keys, but no dedicated Control Center button was verified on this
remote. No additional shortcut, long-Home override, global key remapping or accessibility
service was introduced. Up + OK provides predictable access without changing system controls.

The UI update snapshot is recorded in `backup/current-control-center`. It contains the
previous APK, source/config, Home/accessibility state, UI test evidence and `restore-layout.sh`.
To roll back only this UI update while retaining current Home routing:

```bash
SNAPSHOT=$(cat backup/current-control-center)
bash "$SNAPSHOT/restore-layout.sh"
```

The older Home/Monet restore scripts remain in the snapshot referenced by `backup/current-home`.
This UI update does not repeat or alter that Home migration. Open/closed screenshots are
`shots/xiaomi-control-center-open.png` and `shots/xiaomi-control-center-closed.png`.

## Favorites and conservative debloat session

Hold OK/Enter on an app tile in the dock or All Apps to open an anchored context menu.
Short OK launches the app. The long press and its release are consumed, so releasing the
opening press cannot launch the app or activate a menu row. Back dismisses and restores
focus to that app. Favorites offer Remove from Favorites and, when applicable, Move;
other apps offer Add to Favorites. The dock remains limited to five 16:9 tiles, using the full-width/left-aligned slot layout above.
If full, Add reports that a favorite must be removed first; it never silently replaces one.

Move previews Left/Right swaps. OK commits the order; Back restores the original order.
Leaving the launcher or pressing Home also cancels an unfinished move. Favorites and order
are stored in private SharedPreferences (`favorites`, JSON `order`), synchronously committed
for immediate reboot safety. Empty lists are explicit and do not reseed defaults. Duplicate
package names are removed on load, missing packages are pruned, and replacement broadcasts
from app updates do not erase a favorite mid-update. Disabled/temporarily hidden packages
remain remembered but are omitted when not launchable. An empty dock displays a focusable
All Apps button and guidance for adding favorites. No new services or permissions were added.

The session snapshot is recorded in `backup/current-favorites-debloat`. It includes package
states, complete settings, running services/processes, memory, storage, candidate APK manifests,
Home/accessibility state, timing samples and exact-state restore commands.

Candidate decisions, based on the installed Xiaomi APKs:

| Package | Decision and purpose |
| --- | --- |
| `com.miui.tv.analytics` | Disabled. Labeled Analytics; exposes AnalyticsService, EventService, WakeupService and DataCollectorService. A foreground WakeupService/process was running in the baseline. Optional usage/event telemetry, separate app UID. |
| `com.google.android.feedback` | Disabled. Market Feedback Agent; FeedbackActivity, SendService and BugReportService provide optional feedback/bug-report submission. That reporting feature is unavailable until restored. Play services, Play Store and updates remain enabled. |
| `com.xiaomi.statistic` | Kept. Statistics/upload/bug-report services share the system UID and declare vendor authentication permissions; boundaries are uncertain. |
| `com.mitv.tvhome.michannel` | Kept. Mi Channel synchronizes channels/programs, recommendations and notifications; possible promotional content, but content dependencies/use are uncertain. |
| `com.mitv.tvhome.atv`, `com.mitv.tvhome.mitvplus` | Kept. PatchWall and Xiaomi TV+ are launchable content apps; household use and playback are preserved. |
| `android.autoinstalls.config.xiaomi.mibox3` | Kept. PlayAutoInstalls partner configuration; provisioning interactions uncertain and no running-service saving established. |

All other packages are left as they were. This includes Xiaomi update/download/system
services, casting, DRM/codecs/media, Google Play, Settings, Wi-Fi/Bluetooth, remote input,
voice search, every household app and the installed stock fallback. The stock launcher was
already disabled by the earlier authorized Home migration; this session does not change it.
The TCL list was not used. No system app was uninstalled and no firmware/root changes made.

Only the two confirmed optional packages were disabled, one at a time. Their original user-0
states were default (0), not explicitly enabled (1). The restore script contains only those
two successful changes and uses `pm default-state`, respecting all pre-existing disabled apps:

```bash
SNAPSHOT=$(cat backup/current-favorites-debloat)
bash "$SNAPSHOT/restore-debloat.sh"   # restore only this session's two package changes
bash "$SNAPSHOT/restore-launcher.sh"  # optional: restore previous Control Center APK
```

The launcher restore does not reset Home or clear private app data. Existing Home/Monet
restore commands remain in the earlier Home-migration snapshot. Do not use the TCL-oriented
legacy `tools/restore.sh` for this session.

Paired measurements used the same pre-favorites launcher APK on both sides of debloating,
three cold Home starts from Settings, followed by memory/storage snapshots. This separates
the package-state comparison from the new favorites UI:

| Measurement | Before | After two optional packages disabled |
| --- | --- | --- |
| Cold Home start trials | 811, 696, 645 ms | 797, 551, 501 ms |
| Median cold Home start | 696 ms | 551 ms |
| Kernel MemAvailable | 207,280 KiB (202.4 MiB) | 225,648 KiB (220.4 MiB) |
| `/data` available | 2,732,348 KiB | 2,732,340 KiB |

These are three-sample observations, not guaranteed gains or proof of causality; caches and
other background work vary. Disabling packages retains APKs/data and does not reclaim their
storage. Baseline and after measurements, process lists and per-package checkpoints are
saved in the session snapshot.

The user separately removed Projectivy Launcher and confirmed that removal. It was already
absent from the fresh favorites/debloat session package snapshot. No command from this session targeted it, and the session restore does not reinstall
or re-enable it. The paired debloat measurements used the same package set apart from the two optional disables.
A temporary no-permission app (`com.mihir.tvhome.testfixture`) was installed only to test
favorite APK replacement and uninstall behavior, then removed; no household or system app
was uninstalled by these tests.

## Final favorites validation

The saved order at completion is Netflix, YouTube, Prime Video, Nuvio, TV Bro. Integration
checks exercised short OK launching, long OK without launch-on-release, dock and grid menus,
add/remove, the five-favorite cap, move save/cancel, an explicitly empty dock and All Apps,
launcher restart, signed launcher APK replacement, favorite-app APK replacement, and pruning
an uninstalled temporary favorite. The final full-width layout and four-card left alignment
were verified from UI bounds. The colored wallpaper glow has been removed.

A real device reboot changed the boot ID and preserved both favorite order and custom Home.
The physical Bluetooth remote then opened Home from YouTube and held OK for about 0.89 seconds;
the menu opened without Netflix launching, and Back closed it, confirmed by the user and input
logs. Control Center and the neutral wallpaper scrim remain. Post-reboot package audit shows
only Analytics and Market Feedback Agent newly disabled, with no permanent app additions or
removals relative to the fresh snapshot. The temporary test app is absent. Button Mapper,
stock fallback state, Cast, DRM/media and system packages are preserved; crash buffer is empty.

Wi-Fi was observed connected and validated, and actual Bluetooth remote events were recorded.
Netflix/Prime Video playback cannot be manually verified because the user has no subscriptions;
this does not establish DRM playback correctness. The user confirmed regular YouTube picture and sound over Wi-Fi, and confirmed phone-to-TV
YouTube casting after the changes. These free-video tests do not verify subscription DRM playback. Enabled packages/services alone are not treated
as proof of playback or casting.

Screenshots: `shots/xiaomi-app-context-menu.png`, `shots/xiaomi-grid-context-menu.png`,
`shots/xiaomi-reordered-dock.png`, `shots/xiaomi-reorder-mode.png`,
`shots/xiaomi-four-favorites-left-aligned.png` and `shots/xiaomi-empty-favorites.png`.
All session evidence and restore scripts are in the directory referenced by
`backup/current-favorites-debloat`.

Manual confirmations are saved in `manual-validation.json` in the session snapshot. The
user confirmed YouTube playback/audio and casting, as well as physical long-press menu
behavior and Back dismissal. Wi-Fi was connected/validated and Bluetooth remote key events
were recorded after reboot. Netflix/Prime Video playback remains unverified without a
subscription; their packages and DRM/media components were left unchanged.

Final package/settings audit: installed package set matches this session's fresh baseline;
only the two optional package disabled states changed. Secure settings, including accessibility
registration, are unchanged. Reboot bookkeeping changed `boot_count` and the vendor
`key_prop_speedupload_started_times` runtime counter; those values were left alone.

## Switch to tvOS Launcher 2.0.7 — November 4, 2026

The active Home launcher is now `com.sid.tvos/.MainActivity`, installed from
`tvOS_Launcher_2.0.7-full.apk` (version 2.0.7, version code 28). This supersedes
the custom-launcher Home configuration above. At migration time, accessibility
services were `flar2.homebutton/a.i:com.sid.tvos/com.sid.tvos.RemapService`;
this migration did not change that setting.

After selecting tvOS Launcher, an ADB-injected Home press from system Settings
opened it successfully. The old `com.mihir.tvhome` launcher was then removed with
`pm uninstall -k`, retaining its data for rollback. The package was confirmed absent
from the installed list, and tvOS Launcher remained both the resolved Home and
foreground activity. Physical remote behavior and reboot persistence were not tested
in this migration.

The old APK and final screenshot are saved in `backup/tvos-migration-20261104/`.
To reinstall the old launcher and select it as Home:

```bash
adb -s 192.168.100.27:5555 install -r backup/tvos-migration-20261104/old-tvhome.apk
adb -s 192.168.100.27:5555 shell cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity
adb -s 192.168.100.27:5555 shell am start -n com.mihir.tvhome/.MainActivity
```

tvOS Launcher's enabled RemapService may also intercept Home when rolling back;
review its Home-remapping configuration if the old launcher does not stay active.

## Custom launcher restored — November 5, 2026

The November 4 switch above has been reverted at the user's request. The saved
`backup/tvos-migration-20261104/old-tvhome.apk` was reinstalled with `install -r`,
and `com.mihir.tvhome/.MainActivity` is again the default Home. The retained
favorites are visible in the restored launcher screenshot.

Only `com.sid.tvos/com.sid.tvos.RemapService` was removed from the observed
accessibility list, preserving `flar2.homebutton/a.i`. An ADB-injected Home press
from Settings opened the custom launcher before `com.sid.tvos` was uninstalled.
Uninstall returned Success, its installed package listing is empty, and Home
still resolves to the custom launcher. Physical remote and reboot tests were not
performed during this restoration. Evidence is in
`backup/xiaomi-restore-custom-20261105/`, including `restored-home.png`.

## Control Center adjustment — November 5, 2026

Removed the top-right time so Control Center is an icon-only button; the large
left clock remains. Built, signed, and installed the update on the Xiaomi.
Bluetooth now tries the standard Bluetooth settings action, falling back to system
Settings rather than `CONNECT_INPUT` (which starts accessory pairing).
This firmware has no handler for the standard Bluetooth settings or connected-device
actions. Settings resumes its existing page: during verification it reopened
Remotes & Accessories with Xiaomi RC and Jhenz Boombox 4 listed. If Settings opens
at its root, select Remotes & Accessories. A direct shortcut to that fragment is
not exposed by this firmware. The updated UI and Bluetooth click were checked on
device; screenshots and the previous APK are in `backup/xiaomi-controls-20261105/`.

## Tile Control Center — November 5, 2026

Control Center now follows the supplied reference: a time header with one close
control (no avatar), a large Standby tile next to stacked Wi-Fi/Bluetooth tiles,
Inputs and Settings beneath, then circular Assistant, AirPlay, appearance and
display controls. The closed Home button remains icon-only. Tile surfaces use
the dock's fill and border values; focus uses a white surface with dark text/icons.
The light/dark toggle changes launcher surfaces, saves `light_mode` locally, and
does not change the system theme. The default dark surface preserves the original
dock styling. Explicit D-pad routes keep focus within the open panel; Back closes it.

Assistant uses the available voice-assist/assist action. On this Xiaomi it opens
Google Assistant's overlay. AirPlay searches installed receiver apps, preferring
system apps; on this device it opens `io.github.jqssun.airplay/.MainActivity`.
No receiver is installed automatically. Display opens the native
`com.droidlogic.tv.settings.display.DisplayActivity`. Wi-Fi and Bluetooth retain
their prior settings destinations. Inputs uses the TV input settings action,
with a general Settings fallback when the firmware has no handler, as on this Stick.

Standby uses `com.mihir.tvhome/.StandbyService` and Android's
`GLOBAL_ACTION_LOCK_SCREEN`. This service requests no events or screen-content
capabilities (device dump: capabilities=0, eventTypes empty). It was added to the
existing accessibility service list while preserving Button Mapper. If disabled,
Standby explains how to enable it and opens Accessibility settings. It is distinct
from the firmware's `com.google.android.pano.action.SLEEP`, which was inspected
and found to start the screensaver rather than put the device in standby.

Device validation passed: all main D-pad routes, Back dismissal, Assistant overlay,
AirPlay receiver launch, native display settings, both appearance modes, and
standby/wake (`mWakefulness=Asleep`, then `Awake`). Home still resolves to our
launcher and the crash buffer was empty. These checks use ADB input; physical
remote behavior, actual AirPlay streaming and Assistant speech recognition were
not separately tested. The dark appearance was restored after verification.

The previous APK, original accessibility settings, screenshots, validation results,
and power-state evidence are in `backup/xiaomi-control-tiles-20261105/`.
`restore.sh` in that directory removes only the Home standby accessibility service,
reinstalls the previous APK and opens it. Use `ADB_SERIAL` to override the address
when using a temporary local ADB bridge on macOS.

## Compact frosted Control Center — November 5, 2026

Reduced Control Center dimensions by 20% to match the full-screen reference.
On the 1280×720 Xiaomi, the panel spans approximately 337 pixels (26% of screen
width), with a 162×162 Standby tile and 68-pixel circular shortcuts. Its top and
right insets remain aligned with the original header area. Labels and icons were
scaled for the smaller containers; explicit D-pad routes and actions are retained.

Opening Control Center captures only the launcher's own view at quarter resolution
and applies two separable box-blur passes before adding the sharp controls. Closing
it removes the snapshot, restoring the live, sharp Home screen. This works on
Android 10 without screen-capture permissions or continuous framebuffer filtering.
The dock and tiles now use aligned blurred backdrops, translucent vertical tints,
and fine glass borders; focused tiles retain a white fill with dark labels/icons.
The wallpaper blur cache refreshes after each wallpaper crossfade. Appearance
switching updates the glass shading for both light and dark modes.

The previous APK and visual evidence are saved in `backup/xiaomi-frosted-20261105/`.
This supersedes the earlier flat-translucent/no-blur rendering descriptions.
Both appearance modes, D-pad navigation, Back dismissal/restoration of sharp Home,
and the display shortcut were checked on-device. Specific settings intents now use
`FLAG_ACTIVITY_CLEAR_TOP` so existing Settings task history does not obscure the
requested page; the general Settings fallback retains its prior behavior.

The glass effect was subsequently made more transparent at the user's request:
backdrop layers use 110/255 alpha, surface tint opacity is reduced in both modes,
and blur uses one horizontal/vertical radius-2 pass at quarter resolution. The
Control Center dimming is lighter as well. The compact sizing and white focus
highlight are retained. Previous APK and screenshots are saved in
`backup/xiaomi-clear-glass-20261105/`.

## Liquid-glass refraction trial — November 5, 2026

Added a cached local-background lens to the dock and Control Center containers.
The lens samples the existing wallpaper/Home backdrop with mild central
magnification and curved displacement near each rounded perimeter, rather than
adding a fixed reflection image. A low-opacity inner highlight and directional
rim gradient suggest reflected light while retaining the transparent center tint.
Lens patches are regenerated when their backdrop, position or dimensions change;
there is no continuous refraction animation. Focused controls remain white with
dark icons/text. The previous clear-glass APK and dark/light screenshots are in
`backup/xiaomi-liquid-glass-20261105/`.

## Dock body and Settings backdrop fix — November 5, 2026

The dock's lens now samples the undimmed wallpaper instead of the dark bottom
readability scrim. Its backdrop opacity is 180/255, with a neutral reflective fill
and stronger inner sheen, so the container has a visible glass body rather than
just an outline over a dark gradient. Control Center's existing transparent tint
and background blur are retained.

Native TV Settings uses a translucent window. Settings shortcuts now start within
the launcher's task, allowing Home to remain visible behind the panel rather than
exposing a separate black wallpaper task. The native Settings process also needed
one force-stop/reopen to clear stale blank rendering. No Settings data was cleared.

A diagnostic system-wallpaper synchronization attempt set the current mountain
photo as the Android system wallpaper (wallpaper id changed from 2 to 3), but the
firmware's ImageWallpaper renderer reported EGL/texture errors. The final app does
not synchronize system wallpaper and does not request SET_WALLPAPER; it keeps Home
beneath Settings instead. The test wallpaper remains assigned at system level.

The prior APK, before/after dock and Settings screenshots, and repeated navigation
checks are saved in `backup/xiaomi-glass-settings-fix-20261105/`.

The dock's brighter fill was subsequently removed to match the user's preferred
Control Center appearance. Both now share 110/255 backdrop opacity, the same
light/dark tint values, inner highlight, refraction and rim styling. The dock still
samples undimmed wallpaper so it does not revert to the old dark-scrim artifact.
The prior APK and comparison screenshots are in `backup/xiaomi-matched-glass-20261105/`.

The dock rim accents were then softened by reducing their opacity by roughly 60%
in both appearance modes. The transparent fill, refraction, and Control Center
styling remain as above. Backup: `backup/xiaomi-soft-dock-rim-20261105/`.

## Control Center edge lens — November 5, 2026

Control Center now captures a second, sharper half-resolution backdrop when opened.
Its rounded edges blend in that sharper sample with stronger curved displacement
(up to 13dp within a 16dp edge band), making the lens bend details from behind the
container. The center keeps its prior 110/255 transparency; only the refractive
perimeter strengthens its sampled layer. Surrounding Home blur, tint, focus
highlight, and dock styling are unchanged. Both snapshots are released when the
panel closes. This remains a snapshot effect rather than continuous live sampling.
Previous APK and dark/light screenshots: `backup/xiaomi-control-lens-20261105/`.
