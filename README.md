# WoW Forever for Android

Play **World of Warcraft: Forever** on Snapdragon Android handhelds as a normal Android app. You tap the icon, press Play, and the game runs.

This uses Blizzard's own **Windows ARM64** WoW client, so the game itself runs natively on the phone's CPU. Wine translates the Windows calls, and DXVK plus a patched Turnip Vulkan driver render the game on the Adreno GPU. Nothing is emulated as x86.

> Unofficial community project. Not affiliated with or endorsed by Blizzard Entertainment. You need your own Battle.net account with access to WoW Forever (included with a WoW subscription or Game Time). This repo and its releases contain **no** Blizzard game files.

---

## Supported devices

| Device | Status |
| :--- | :--- |
| AYN Thor (Snapdragon 8 Gen 2 / Adreno 740) | Tested, reaches the game world with controller support |
| Retroid Pocket 6 (Snapdragon 8 Gen 2 / Adreno 740) | Tested by the original community setup |
| AYN Odin 2 / Mini (Snapdragon 8 Gen 2) | Expected to work |
| AYN Odin Portal (Snapdragon 8 Elite / Adreno 830) | Supported via bundled Turnip v32 driver |
| RedMagic 11 Pro (Snapdragon 8 Elite / Adreno 840) | Tested in-world by arusiasotto |
| Samsung Galaxy S23/S24/S25 / Z Fold series | Supported (with automatic Samsung UBWC optimization) |

Requirements:

- Snapdragon 8 series (Adreno 7xx or Adreno 8xx). The app bundles both the Adreno 740 driver and the Turnip v32 driver for Snapdragon 8 Elite / Adreno 8xx, automatically detecting the GPU and applying the optimal driver and environment flags.
- Android 10 or newer, 64-bit.
- About **80 GB** free for the game data (internal storage or SD card), plus about 4 GB of internal storage for the app and its Windows environment.
- A Mac or PC with WoW Forever installed through Battle.net, to copy the game data from.

---

## Installation

### 1. Install the app

Download `WoW-Forever.apk` from the [latest release](https://github.com/zteksu/wow-forever-android/releases/latest) and install it on your device. The app's package name is `app.wowforever`, so it can sit alongside GameNative or Winlator.

### 2. Copy your WoW game data to the device (one-time setup)

Copy two things from your Battle.net install (on a Mac that's `/Applications/World of Warcraft/`) into a folder on the device. The default is `/storage/emulated/0/WoW Forever/`. Any other folder works too, including one on an SD card. You only need to do this **once**; all subsequent game updates are handled directly in-app over Wi-Fi.

```
WoW Forever/
├── .build.info                   <- from the install root
└── Data/                         <- the whole Data folder (~67 GB)
```

- `Data/` is the same on every platform, so a Mac, Windows, or ARM install all work.
- `.build.info` is a hidden file. Copy it too. Without it the game fails with *"CAS system was unable to initialize"*.
- You don't need `Wow-ARM64.exe`. Mac and x86 installs don't ship it, so the app downloads the matching Windows ARM64 client from Blizzard's CDN when you press Play, and checks its hashes.
- If you used a different folder, or moved it, the launcher shows **Locate Game Files**. Pick the folder that contains `.build.info` and `Data/`. **Change Location** switches it later.

Transfer the files over to a `WoW Forever/` folder on your device. You can do this by:

- **SD Card:** Insert the microSD card into your computer and copy the files over (make sure your file manager shows hidden files so `.build.info` is included).
- **USB File Transfer:** Connect your device to your computer via USB (in File Transfer / MTP mode) and copy the files directly to internal storage (default: `/storage/emulated/0/WoW Forever/`).
- **ADB Script:** If you have ADB installed and this repo checked out, you can use the sync script to copy initial files easily over USB:

```bash
WOW_SRC="/Applications/World of Warcraft" tools/sync_wow_to_device.sh
```

### 3. Play

Open **WoW Forever**. Once your game files and Battle.net credentials are configured, the app automatically launches straight into the game. If credentials are not yet configured, the launcher stops at the setup screen so you can enter them before playing. The first launch downloads the ARM64 game client and installs the Windows environment, which takes a few minutes and needs an internet connection.

- **Setup screen:** to access folder settings, forget credentials, check environment status, or view updates, hold **Start + Select + L2 + R2** (or tap the back button) during the loading splash to cancel boot and return to the setup screen.
- The app creates a basic `WTF/Config.wtf` on first run and always sets `gxApi "D3D11"`, the renderer that works with DXVK.

### Controls and signing in

- **Controller:** built-in handheld controllers work in-game. WoW's own gamepad mode handles the mapping.
- **In-game menu:** press Back (the button or the back swipe gesture) to open the sidebar. It has **Keyboard**, on-screen controls, performance overlay and **Exit**.
- **Keyboard:** the sidebar's **Keyboard** opens the Android keyboard. On dual-screen devices like the Thor it appears on the bottom screen. Symbols like `@` work, and so does pasting.
- **Battle.net Auto-Login:** configure credentials directly on the launcher setup screen (**Configure Login** / **Update Login**). Credentials are saved encrypted on-device via Android Keystore. On boot, the launcher generates `_forever_/login.txt`, which the WoW client automatically reads on startup to sign in natively without macro simulation or synthetic clicks. Use **Forget Saved Login** on the launcher setup screen to clear credentials and remove the login file.
  - Authenticator codes still have to be entered by hand.

---

## Updating the game

WoW Forever v2.0 features a native in-app game updater connected directly to Blizzard's public edge CDNs. **You no longer need a computer or USB cables to keep your game updated.**

Whenever Blizzard patches the game:
1. **Live Version Check:** The launcher automatically checks the live version against Blizzard's public patch service on startup.
2. **Update Gate:** If an update is detected, auto-launch cancels and displays the update card with the remote build version, plus a secondary *Launch Anyway (Outdated)* override.
3. **One-Tap Update:** Tapping **UPDATE TO {version}** downloads the new ARM64 client binaries (`Wow-ARM64.exe` and companion DLLs), saves remote configs to `Data/config/`, syncs new CASC index archives to `Data/indices/`, and atomically updates `.build.info` on disk over Wi-Fi.
4. **Instant Boot:** Once the update completes, the launcher automatically transitions into booting World of Warcraft at 60 FPS.
5. **In-Game Streaming:** The game engine's internal streaming client seamlessly streams any newly introduced assets from Blizzard's edge CDNs during gameplay.

*(Optional fallback: If you ever want to re-seed or mirror your full PC installation over USB, `tools/sync_wow_to_device.sh` is still available.)*

### Moving from the beta to the release

The released game (4 November 2026) is a separate Battle.net product, `wowf`, installed in `_forever_/` with `Wow-ARM64.exe`; the beta was `wow_classic_beta` in `_classic_beta_/` with `WowB-ARM64.exe`. Both use the same game data, so a beta install on the device carries over without re-copying `Data/`:

1. The launcher adds a `wowf` entry to `.build.info` (keeping the beta one, with a `.build.info.bak` backup).
2. It shows **UPDATE TO 1.60.1.x**. Tapping it, or Play, downloads the release client into `_forever_/`.
3. `_classic_beta_/WTF/Config.wtf` is copied to `_forever_/WTF/` so your settings carry over, and its beta `portal "test"` is switched to your region.

The `_classic_beta_/` folder can be deleted afterwards.

---

## Troubleshooting

| Symptom | Fix |
| :--- | :--- |
| *CAS system was unable to initialize: no active install info entries* | `.build.info` or `_forever_/.flavor.info` is missing on the device. |
| *No realms available* / no servers listed | The device client is out of date. Tap **Update** on the launcher setup screen, or restart the app with Wi-Fi enabled. |
| Keyboard doesn't appear | Force-stop Gboard (Settings → Apps → Gboard → Force stop) and open **Keyboard** again. It can get stuck on the second screen. |
| Returns to the launcher after "Launching Game…" | Check the files under `_forever_/Errors/` on the device. |
| Handheld frontend (e.g. Cocoon) shows the wrong icon | The frontend cached an old icon. Set it with the frontend's "Edit App Artwork", or reinstall the app. |

---

### Add-ons (WoW Forever and Retail)

**Add-ons** on the main screen lists popular add-ons that publish GitHub releases with the BigWigs packager's `release.json` (Questie, RestedXP, Guidelime, DBM, BigWigs, Plater, Bartender4, Dominos, MoveAny and more). Only those with a zip built for your game are shown (flavor `forever`, or `mainline` for Retail). Install, update and remove put them in `_forever_/Interface/AddOns` (or `_retail_`). *Add from GitHub* takes any other `owner/repository` that publishes the same way.

**CurseForge:** paste your own API key from [CurseForge for Studios](https://console.curseforge.com) (API keys) into the Add-ons screen. The list then also shows CurseForge's most popular add-ons for your game, and the search box searches all of them; installs pull in required dependencies. The key stays on the device and is only sent to `api.curseforge.com`. Some authors turn off downloads through apps; those show their CurseForge page instead.

## Building from source

Requirements: JDK 17 and the Android SDK (with build-tools and platform-tools).

```bash
tools/fetch_components.sh           # Wine/Proton, DXVK and Turnip archives (~130 MB, not in git)
./gradlew assembleForeverModernRelease   # WoW Forever: app/build/outputs/apk/foreverModern/release/
./gradlew assembleRetailModernRelease    # WoW Retail:  app/build/outputs/apk/retailModern/release/
./gradlew assembleForeverModernDebug     # unminified debug build, faster to iterate on
```

### WoW Retail build

The same code also builds **WoW Retail**, an APK for standard (retail) World of Warcraft. The `game` product flavor in `app/build.gradle.kts` picks the target:

| | WoW Forever | WoW Retail |
| :--- | :--- | :--- |
| Package | `app.wowforever` | `app.wowretail` (installs alongside Forever) |
| Battle.net product | `wowf` | `wow` |
| Client folder / exe | `_forever_/Wow-ARM64.exe` | `_retail_/Wow-ARM64.exe` |
| Default game folder | `WoW Forever/` | `World of Warcraft/` |
| `portal` CVar | Region from `.build.info` (`US`, `EU`, ...) | Region from `.build.info` |
| Release asset | `WoW-Forever.apk` | `WoW-Retail.apk` |

Retail has its own launcher icon in `app/src/retail/res` (original artwork, regenerate it with `tools/make_flavor_icon.py retail`). Copy `.build.info` and `Data/` from a retail install, or run `WOW_GAME=retail tools/sync_wow_to_device.sh`. Retail data is well over 100 GB, and retail is much heavier on the GPU than Forever, so expect lower settings and frame rates. Each app's self-updater only picks its own APK from a GitHub release, so both APKs can go on the same release.

Release builds are signed with `app/keystores/keystore.properties` when it exists (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Otherwise they fall back to the debug key. Builds signed with different keys can't update each other, so switching between them means uninstalling first.

### OctoWoW build

`./gradlew assembleOctoModernRelease` builds **OctoWoW** (`app.octowow`) for the [OctoWoW](https://octowow.st) vanilla 1.12 private server. Its client is different from Blizzard's current clients, so this build:

- Expects the OctoWoW client folder as-is (`WoW.exe`, `Data/`, `realmlist.wtf`, `WTF/`) in `OctoWoW/` on the device. There is no `.build.info`, Blizzard updater or Battle.net login.
- Runs the 32-bit x86 client through the bundled wowbox64 emulator. It launches `VanillaFixes.exe` when present, so the client mods in `dlls.txt` load, and `WoW.exe` otherwise. Rendering uses the DXVK `d3d9.dll` and `dxvk.conf` shipped in the client folder.
- Installs the [ShaguController](https://github.com/shagu/ShaguController) addon into `Interface/AddOns/` and maps the controller to the keys it expects: left stick moves, right stick moves the mouse, L2/R2 are right/left click, A jumps, Y/X/B and the D-pad are action buttons, L1/R1 cycle targets, Select opens the map, Start is Escape, L3 opens bags and R3 the quest log.
- **Game updates:** checks OctoWoW's client torrent (`dl.octowow.st/download/client.torrent`) on launch, the same manifest its own launcher uses. **Update** downloads only the changed files over HTTPS from the torrent's web mirror and verifies them against the torrent's SHA-1 piece hashes. The last applied torrent is kept in `.octowow-android/` in the client folder. **Verify Game Files** re-checks every file and repairs any that don't match.
- **Add-ons:** browses OctoWoW's addon catalog (`octowow.st/api/addons.json`, the list its launcher offers) with search, and installs, updates or removes addons in `Interface/AddOns/`, along with any catalog addons they require.
- **Controller layout** (in Add-ons): ShaguController, [ConsoleExperience Classic](https://github.com/pepordev/ConsoleExperienceClassic), a ConsolePort-style UI for 1.12 (face buttons, D-pad and bumpers are keys 1-0, LT/RT are held Shift/Ctrl for three more bars, Start is Shift+Escape for its radial menu, Select opens the map), or **Forever Gamepad** (below). The unused ones are parked in `Interface/AddOns-disabled/`.

#### Forever Gamepad (OctoPad)

The bundled OctoPad addon (`app/src/octo/assets/addons/OctoPad`) recreates WoW Forever's Gamepad (Alpha) controls on the 1.12 client. It is original code: Forever's own UI is built on engine gamepad support (`C_GamePad`, Edit Mode) that 1.12 doesn't have, so the controller profile sends each button as its own key (F1-F12, numpad `/ * -`) and the addon works out layers, modifiers and menus. The sticks are handled by the app.

| Input | Action |
| :--- | :--- |
| Left stick | Move: forward/back and strafe, in the direction the camera faces |
| Right stick | Turn the camera and your character with it (a right-button drag); drives the pointer while a menu is open |
| A / X / Y / B | Jump / interact (right-clicks the reticle: talk, loot, attack what's in front) / unit menu / cancel or clear target |
| LT, RT, LT+RT + D-pad or face button | Three layers of 8 actions; D-pad alone is 4 more, shared by every arrangement |
| LB+RB + D-pad left/right | Switch between three bar arrangements |
| RB tap / LB tap | Target the enemy ahead (again: next enemy) / a friend |
| Hold RB + button | Hostile targeting: D-pad left/right cycle enemies, up nearest, down last; A attack, X next marked, Y mark, B clear |
| Hold LB + button | Friendly targeting: A self, X pet, B assist, Y mark; D-pad moves through the group frames (Fixed Party Targeting: one D-pad direction per party member) |
| LB+RB + B / X / Y / up / down | Bags / sheathe / buff viewer / quest log / chat with the on-screen keyboard |
| LB+RB + right stick / L3 / hold R3 | Zoom / recenter the camera / look behind |
| L3 / R3 | Auto-run / ping your position |
| Start | Radial menu: character, spellbook, talents, professions, bags, quests, map, chat, game menu; LB/RB switch to the social and system tabs; D-pad up opens emotes, down sits |
| Select | Pointer mode (like Forever's touchpad): the right stick moves the mouse, A and B click |

With a window open (vendor, quest, bags, spellbook, loot, popups, ...), the D-pad moves a highlight, A clicks, X is the secondary action (use or equip an item, buy or sell, bind a spell), Y opens a context menu (bind, split, destroy, share or abandon a quest), B goes back, LT/RT switch windows and the bumpers switch tabs. Bind spells and items to the bar as in Forever: X on a spell in the spellbook (or Y, *Bind to Action Bar* on an item), then press the button combination. Ground-targeted spells switch to pointer mode with A to place them and B to cancel.

Classes with stances, forms or auras get a flyout on D-pad left, hunters and warlocks pet control and pet spell flyouts on D-pad down and right; open one and pick with the button shown under each choice. `/octopad` (or *Gamepad Options* in the radial menu) has Forever's options (Compact Action Bar, Fixed Party Targeting, Swap Target Modifier Sides, Swap Friendly/Hostile Target Action Sides) plus camera speed, bar size, placing flyouts, clearing a slot and moving the bar.

The addon tells the app when a menu wants the pointer through VanillaHelpers' `WriteFile` (`VanillaHelpersData/OctoPad.txt`); the app adds `VanillaHelpers.dll` to `dlls.txt` if it's missing. Without it, the camera starts in pointer mode for the login screens and Select switches modes by hand. Targeting uses UnitXP_SP3's facing- and distance-aware functions when the client has them. Tests: `python3 tools/octopad_test/test_octopad.py` (needs `pip install lupa`) and `python3 tools/lint_lua50.py app/src/octo/assets/addons/OctoPad` (the client runs Lua 5.0).

### Skyrim SE build

`./gradlew assembleSkyrimModernRelease` builds **Skyrim SE** (`app.skyrimse`) for *The Elder Scrolls V: Skyrim Special Edition* (including the Anniversary content) from **GOG**, which has no copy protection. The Steam release needs the Steam client running and isn't supported.

- Copy the game folder (the one with `SkyrimSE.exe` and `Data/`) to `Skyrim Special Edition/` on the device, or pick it with *Locate Game Files*. There's no updater, login or add-on catalog.
- `SkyrimSE.exe` is 64-bit x86. It runs under the same ARM64EC Proton through FEX (`libarm64ecfex.dll`, the `fexcore-2605` build bundled in `app/src/skyrim/assets/fexcore/`), which the prefix already registers for x86-64 code. DirectX 11 goes through x86-64 DXVK 2.6.1-gplasync, fetched from GameNative's component server on the first launch. It compiles shaders in the background, and frames are capped at 60 because Skyrim's physics break above that (the Odin 3 screen runs at 120 Hz). FEX uses its intermediate preset, which keeps x86 memory ordering for Skyrim's threads.
- Settings live in the prefix's `Documents/My Games/Skyrim Special Edition GOG/SkyrimPrefs.ini`. The first launch writes handheld defaults: screen-sized borderless fullscreen, shadows and ambient occlusion turned down, and gamepad on. Later launches only fix a resolution that no longer fits.
- The controller is passed through as an Xbox pad, using Skyrim's own controller support. The launch log goes to `Download/Skyrim SE-launch.log` and appears in View Logs.
- Audio: Skyrim's music and voices are xWMA. Proton's media converter plays silence for xWMA files it has no pre-converted copy of, so the container installs Microsoft's XAudio2 (the `xaudio` component, from GameNative's server), which decodes xWMA itself.

Each app has its own branch and release series:

| Branch | App | Release tag | Release asset |
| :--- | :--- | :--- | :--- |
| `forever` | WoW Forever | `forever-v<version>` | `WoW-Forever.apk` |
| `retail` | WoW Retail | `retail-v<version>` | `WoW-Retail.apk` |
| `octowow` | OctoWoW | `octowow-v<version>` | `OctoWoW.apk` |
| `skyrim` | Skyrim SE | `skyrim-v<version>` | `Skyrim-SE.apk` |

`.github/workflows/release-apk.yml` publishes them: a push to one of those branches that changes `versionName` builds that app and creates its release. Each app's updater picks the newest release carrying its own APK, so the three release independently. Add the `ANDROID_KEYSTORE_*` repository secrets described in the workflow so every release is signed with the same key; otherwise each run signs with a new debug key and installed apps can't update to it.

`fetch_components.sh` downloads the three runtime archives from this repo's `components-v1` release and checks them against `tools/components.sha256`. If you already have them, pass a folder instead: `tools/fetch_components.sh /path/to/components`.

---

## Credits and how it works

This project packages other people's work into a single-purpose app. None of it would exist without:

- **[GameNative](https://github.com/utkarshdalal/GameNative)** by Utkarsh Dalal and contributors (GPL-3.0). The Android app, the Wine container management and the X server are all GameNative, based on v1.2.1. GameNative in turn builds on **[Pluvia](https://github.com/oxters168/Pluvia)**, **[Winlator](https://github.com/brunodev85/winlator)**, **[Winlator Cmod](https://github.com/coffincolors/winlator)** and the **[Bionic Vulkan wrapper](https://github.com/leegao/bionic-vulkan-wrapper)**.
- **The WoW Forever RP6 community bundle**, which first got the beta running on a Retroid Pocket 6 in GameNative and supplied the three custom runtime components:
  - **Proton 11 ARM64EC** built from [The412Banner/proton-wine](https://github.com/The412Banner/proton-wine/tree/e5fa703ed7f7329e20d7ede481ab185cf8b1a8b2) with an ARM64 copied-syscall fix and an NLS fallback allocation patch.
  - **DXVK 2.4.1 (aarch64)** built from [doitsujin/dxvk](https://github.com/doitsujin/dxvk/tree/0cf05780abd7250c2cd713b7749cf32180157cf5).
    - **Mesa Turnip (Adreno 740)** built from Mesa [`fe067b17d9`](https://github.com/mirror/mesa/tree/fe067b17d9) with a patch limiting barycentric waits to the current block, which fixes a shader scheduler assertion on Adreno 740.
  - **Mesa Turnip (Adreno 8xx / Snapdragon 8 Elite)** built by **[arusiasotto](https://github.com/arusiasotto/wow-forever-a840)** from **[whitebelyash/mesa-unified](https://github.com/whitebelyash/mesa-unified)** (`turnip/gen8` v32) with the barycentric scheduler fix and ICD exports.
- **[Wine](https://www.winehq.org/)**, **[Proton](https://github.com/ValveSoftware/Proton)**, **[DXVK](https://github.com/doitsujin/dxvk)**, **[Mesa](https://mesa3d.org/)**, **[FEX-Emu](https://github.com/FEX-Emu/FEX)**, **[box64](https://github.com/ptitSeb/box64)** and **[PulseAudio](https://www.freedesktop.org/wiki/Software/PulseAudio/)**.
- **Blizzard Entertainment** for World of Warcraft and its Windows ARM64 client. The app icon and splash art come from Blizzard's official [WoW Forever page](https://worldofwarcraft.blizzard.com/en-us/forever). Blizzard owns them and the World of Warcraft marks.

Full third-party license details are in [`THIRD_PARTY_NOTICES`](THIRD_PARTY_NOTICES).

### What this fork changes from GameNative

GameNative is a general game library with Steam, GOG, Epic, Amazon, EA and Rockstar stores, mod management, VR support and per-game container settings. This fork turns it into a launcher for one pre-configured container:

- **Standalone identity:** package `app.wowforever`, WoW name, icons, banners and splash screen, installable next to GameNative.
- **Direct launch & instant boot:** the app opens to a WoW splash screen (`ui/screen/wow/WoWForeverScreen.kt`) and automatically boots straight into the game once configured. Holding **Start + Select + L2 + R2** or pressing Back on the loading screen cancels boot to return to the setup screen.
- **Pre-configured container:** bionic, Proton 11 ARM64EC, Turnip through the Vulkan wrapper, DXVK 2.4.1 aarch64, WINEESYNC off, all 8 cores, 1920x1080, and `G:` mapped to `/storage/emulated/0/WoW Forever`.
- **Native ARM64 launch:** GameNative wraps every Windows program in `winhandler.exe`, an x86-64 helper that needs x86 emulation. ARM64 executables now launch directly, so the game never goes through FEX, and the working directory is set from the mapped drive.
- **Game file setup:** the launcher writes `_forever_/.flavor.info` and a basic `WTF/Config.wtf` if they're missing, forces `gxApi "D3D11"` (so a copied Mac config that says Metal can't break it), and refuses to start without `.build.info`.
- **Package-name fixes:** several paths were hard-coded to `app.gamenative`: the bionic library path rewrite (`WINEMU_HOST_PKG` / `HOST_PKG`), the gamepad shared-memory files, the DXVK state cache and the default drives. The controller path was the reason controllers didn't work.
- **Small robustness fixes:** non-numeric container IDs no longer crash the ID parser, and the bionic redirect library is copied in if it's missing.
- **Keyboard fixes:** GameNative's on-screen keyboard dropped shifted symbols (`@` came through as `2`), because it sent key codes without the character or the Shift key. The fix is in `Keyboard.java` and `IMEInputReceiver.kt`. The keyboard now always goes through the IME receiver, so it appears on the Thor's bottom screen.
- **Battle.net auto-login:** native `login.txt` client authentication managed via Keystore-encrypted credentials (`ui/screen/wow/BattleNetSignIn.kt`). Eliminates coordinate-based typing macros in favor of the game client's built-in startup authentication, with configuration available directly from the launcher setup screen.
- **Native In-App Game Updater (v2.0):** Live version check against Blizzard patch services, direct-from-CDN ARM64 binary and manifest downloads, CASC index synchronization, and atomic `.build.info` updates on-device over Wi-Fi without any PC dependency.
- **Lean runtime & startup:** eliminated continuous background accelerometer polling during gameplay in favor of native OS window management, removed cold-boot bitmap allocations, guarded background performance metric collection loops, and switched DNS to native platform resolution.
- **Removed:** every store backend (Steam and JavaSteam, GOG, Epic, Amazon, EA, Rockstar), library, login, settings, custom game scanner, and downloads screens, ExoPlayer and browser dependencies, `DownloadService`, `ContainerMigrator`, Nexus mod management, the Meta Quest/XR build, PostHog analytics, Play Integrity, the self-updater, the Room database, the notification prompt, extra bundled Box64/FEX/DXVK versions and the legacy (Android 9) build. That's roughly 135k lines of code and about half the APK size.
- **Tooling:** `tools/download_wow_arm64.py` fetches the ARM64 client from Blizzard's CDN, `tools/sync_wow_to_device.sh` handles updates, and `tools/fetch_components.sh` downloads the runtime archives.

## License

GPL-3.0, the same as GameNative. See [`LICENSE`](LICENSE). The bundled runtime components carry their own licenses (LGPL, MIT and others). See `THIRD_PARTY_NOTICES` and the notices inside each component archive.
