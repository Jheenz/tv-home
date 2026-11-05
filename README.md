# tv-home

**Make a slow, ad-filled Android TV fast and clean again.** Debloat it over Wi-Fi and give it a
tiny Apple TV-style home screen with rotating nature wallpapers. You can hand the whole thing to
Claude Code with one prompt, or do it by hand with the scripts here.

![Before: Google TV home with a sponsored hero. After: clean dock over a Lake Louise wallpaper](before-after.png)

On a 2 GB TCL Google TV this disabled 25 bloat apps and cut the home screen's memory use from
192 MB to 83 MB. Everything is reversible: packages are disabled, never uninstalled.

## Do it with Claude Code (easiest)

1. On the TV: Settings → System → About → press OK on **Build** 7 times → back → Developer options
   → turn on **USB debugging**. Keep the TV on, on the same Wi-Fi as your computer.
2. Clone this repo, open Claude Code inside it (so it can reuse the launcher and scripts), and paste:

   ```text
   My Android TV is slow and full of ads. Find it on my Wi-Fi, connect over ADB, and debloat it:
   disable the manufacturer's ads, telemetry and junk (reversibly, never uninstall), asking me first
   about apps and features I might use. Then build and install a fast, minimal Apple TV-style home
   screen with a dock of my favourite apps and rotating nature wallpapers. Back up before changing
   anything, check every step with screenshots from the TV, and give me a restore script.

   Heads-up from people who've done this: use the adb-shell Python package if macOS blocks adb; on
   Google TV also disable com.google.android.tungsten.setupwraith or it hijacks the Home button; and
   test the Home button from inside an app.
   ```

3. Accept "Allow USB debugging?" on the TV when asked, then answer a few questions: which apps you
   use, which go in the dock, whether you want wallpapers.

The prompt is short on purpose; Claude works out the details. The gotchas below are there in case it gets stuck.

## Do it by hand (macOS)

For the Xiaomi MiTV-AESP0 on Android 10, use the staged procedure in
[XIAOMI-SETUP.md](XIAOMI-SETUP.md). The installer now only installs and opens the app for testing.
It does not change the default Home, grant location, disable packages, or apply debloat lists.

```bash
./setup.sh                              # local Android SDK; reuses the Mac's JDK and ADB
launcher/build-and-install.sh --build    # build only, Android 10 minimum
adb connect 192.168.100.27:5555           # use your device's authorized address
ADB_SERIAL=192.168.100.27:5555 launcher/build-and-install.sh --install
```

Record device state and prepare restore commands before installing. To undo a fresh test install,
uninstall `com.mihir.tvhome`. The TCL debloat example is specific to that TCL model; do not apply
it to Xiaomi. The legacy `tools/restore.sh` assumes Google TV and is not a snapshot-based restore.
Install the optional Python ADB client with `./setup.sh --no-launcher` if needed.

## What's here

| Path | What it is |
|---|---|
| [PROMPT.md](PROMPT.md) | The prompt for Claude Code / any coding agent |
| [launcher/](launcher/) | The home screen. About 4.7 MB including wallpapers; plain Android, no network, optional screen-lock-only standby accessibility service |
| [launcher/res/values/config.xml](launcher/res/values/config.xml) | Dock apps, hidden apps, Live TV app, grid columns |
| [tools/tv.py](tools/tv.py) | Pure-Python ADB client: shell, batch, screenshots, chunked APK push |
| [tools/find-tv.sh](tools/find-tv.sh) | Finds Android TVs on your Wi-Fi |
| [tools/type.sh](tools/type.sh) | Type into the TV's on-screen keyboard from your laptop (`tools/type.sh` alone prompts hidden, for passwords) |
| [tools/disable.sh](tools/disable.sh), [tools/restore.sh](tools/restore.sh) | Apply / undo a debloat list |
| [examples/](examples/) | Debloat lists per TV model |

## The launcher

- Clock, greeting and date top-left. An icon-only **Control Center** button opens compact
  frosted-glass tiles for Standby, Wi-Fi, Bluetooth, Inputs and Settings, plus Assistant,
  AirPlay, launcher light/dark appearance and display settings. D-pad Up reaches it.
  Standby uses the optional Home standby accessibility service, which requests no
  screen content or events. AirPlay opens an available receiver app; it does not add
  AirPlay support to devices without a receiver. Unsupported settings shortcuts fall
  back to system Settings.
  The open panel softly blurs the Home screen; its tiles and the dock use cached,
  blurred backdrops with translucent shading and fine glass borders.
- A compact dock of up to five favorites, with 16:9 banners and a focused-app title. Hold OK
  on dock or grid apps to add/remove favorites or reorder them. Short OK launches normally.
  Favorites persist across restarts and updates. Press Down for the All Apps grid.
- 12 nature photos crossfade every 10 minutes, with a scrim so text stays readable. Swap them in
  `launcher/assets/wallpapers/` (1920×1080 JPEGs, credits in `launcher/WALLPAPER-CREDITS.md`).
- Deterministic D-pad focus, remembers the last tile, picks up newly installed apps on return.

Organize the dock on the TV by holding OK on an app. `config.xml` supplies first-run defaults;
existing saved favorites take precedence. See [XIAOMI-SETUP.md](XIAOMI-SETUP.md) for the device
setup, conservative debloat decisions, measurements and exact session restore scripts.

## Gotchas we hit

- **macOS blocks the `adb` binary** on the local network ("No route to host"). `tools/tv.py` uses
  the `adb-shell` Python package instead.
- **Google TV hijacks the Home button.** With the stock launcher disabled, the setup app
  (`com.google.android.tungsten.setupwraith`) has a higher HOME priority than any third-party
  launcher. This is a Google TV observation, not a reason to disable it on other models.
  The installer leaves it alone.
- **Home opened the launcher behind the current app**, because of a stale home task from switching
  launchers. Force-stopping the launcher and `com.android.tv.settings` and pressing Home fixes it.
  The installer does not alter Home or force-stop system settings.
- **Chained `pm` commands** (`pm disable a; pm disable b`) can silently stop after the first. The
  scripts run one per shell call.
- **Apps installed from the Play Store didn't appear**, because the package broadcast was missed
  while another app was in front. The launcher re-checks with `getChangedPackages` on every return.
- **Unsplash blocks scripted downloads** with a bot check. The wallpapers come from Wikimedia
  Commons Featured Pictures instead.

## Supported TVs

Android TV and Google TV: TCL, Xiaomi/Redmi, Hisense, Sony, OnePlus, Realme, Nvidia Shield,
Chromecast with Google TV and most others. Not Samsung (Tizen), LG (webOS) or Fire TV.
Tested on: TCL Google TV (Android 11). Add yours to `examples/`.

## Credits

Inspired by [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack). Wallpaper photos are
from Wikimedia Commons under their own licences, see
[launcher/WALLPAPER-CREDITS.md](launcher/WALLPAPER-CREDITS.md). Code is MIT, see [LICENSE](LICENSE).
