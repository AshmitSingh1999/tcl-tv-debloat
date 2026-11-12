# TCL Android TV debloat

A TCL / iFFALCON Android TV cleaned up over Wi-Fi: ad rows, telemetry and preloaded bloat disabled, the stock home replaced with a quiet Apple TV-style launcher, and ad-blocking DNS switched on. No root, nothing flashed, nothing uninstalled from the system, and one script puts everything back.

| Before: stock home with sponsored rows | After: the new home |
|---|---|
| ![Stock Android TV home with a sponsored row](docs/before-stock-home.jpg) | ![New launcher with a four-app dock](docs/after-home.jpg) |
| ![App grid below the dock](docs/after-grid.jpg) | ![Inputs menu listing HDMI ports, AV and Live TV](docs/after-inputs.jpg) |

Built on [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv): the launcher in `launcher/` and the scripts in `tools/` are theirs (MIT), configured here for one TV. The batch lists, `rollback.sh` and this write-up are specific to this project.

## The TV

| | |
|---|---|
| Model | TCL `BeyondTV` (Realtek RTD2851) |
| Android | Android TV 9 (API 28), build `PPR1.180610.011`, incremental `AP08` |
| Client type | `TCL-AP-RT2851-S1` (India) |
| Stock home | `com.google.android.tvlauncher` |

Other firmware needs its own list. Check `getprop ro.build.version.incremental` before reusing these batches.

## What was changed

**19 packages disabled** with `pm disable-user --user 0`, in four batches with a screenshot after each:

| Batch | Packages | Why |
|---|---|---|
| [`01-telemetry`](batches/01-telemetry.txt) | 5 | TCL analytics, Google feedback and bug-report uploaders, partner attribution |
| [`02-ads-promos`](batches/02-ads-promos.txt) | 7 | TCL home promotions, TCL Channel feed, message box, T-key promo page, Google recommendation rows |
| [`03-preloads`](batches/03-preloads.txt) | 4 | Agreement screens, TCL's unused WebView, Stan and Rakuten TV (neither offered in India) |
| [`04-after-launcher`](batches/04-after-launcher.txt) | 3 | Stock home, TCL cleaner, TCL app store. Applied only once the new launcher was Home |

**Launcher:** the cleanyourtv reference launcher (`com.gathin.tvhome`) with a four-app dock (Netflix, Prime Video, YouTube, JioHotstar) and `com.tcl.tv` as the Live TV entry in the Inputs menu. The only source change is in [`launcher/app/src/main/res/values/config.xml`](launcher/app/src/main/res/values/config.xml).

The install script grants the launcher the location permission, which Android requires before an app may read the Wi-Fi name shown in the top bar. The launcher has no internet permission.

**DNS:** Android Private DNS set to `dns.adguard-dns.com`. It blocks ad and tracker domains for every app. It does not block YouTube ads.

**Left alone on purpose:** Netflix, Prime Video, JioHotstar, YouTube, ZEE5, Chromecast, Miracast and T-Cast, the Google voice assistant, the firmware updater, Live TV, HDMI inputs, the remote and Bluetooth services, the keyboard, Play Store and Play Services.

## Verified on the TV

- After every batch: Home still opens, Wi-Fi stays up, no new crash in `logcat -b crash`.
- YouTube and Netflix launch with Private DNS on.
- The launcher's dock, app grid and Inputs menu (HDMI 1 to 3, AV, Live TV) respond to the D-pad.
- After a reboot: the new launcher is Home, all 19 packages are still disabled, Private DNS is still on.

Not tested: HDMI picture with a source connected, tuner reception, standby over several days, a firmware update, and a real run of `rollback.sh` (only its dry run).

## Do it on the same TV model

On the TV: Settings → Device Preferences → About → press OK on **Build** seven times, then Developer options → **USB debugging** on. The TV and the computer must be on the same network.

```bash
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
```

```bash
export TV_HOST=<tv ip>
```

Accept the "Allow USB debugging?" prompt on the TV, then apply the first three batches one at a time and look at the TV between them:

```bash
tools/disable.sh batches/01-telemetry.txt
```

```bash
tools/disable.sh batches/02-ads-promos.txt
```

```bash
tools/disable.sh batches/03-preloads.txt
```

Build and install the launcher (JDK 17 or 21, Android SDK platform 34 and build-tools 34.0.0). The script starts the launcher, then disables the stock home and hands Home over, and puts the stock home back if that fails:

```bash
launcher/build-and-install.sh
```

```bash
tools/disable.sh batches/04-after-launcher.txt
```

Optional ad-blocking DNS:

```bash
tools/tv.py 'settings put global private_dns_specifier dns.adguard-dns.com'
```

```bash
tools/tv.py 'settings put global private_dns_mode hostname'
```

When you are done, switch USB debugging off again on the TV and check that the port is closed:

```bash
tools/adb-closed.py <tv ip>
```

## Rolling back

Everything, in one command (needs USB debugging on):

```bash
TV_HOST=<tv ip> ./rollback.sh
```

It re-enables every package from the batches, hands Home back to the stock launcher, removes the custom launcher only after that worked, restores Private DNS and the accessibility settings, reboots, and prints PASS or FAIL for each check. Packages that were already disabled before this project ([`original-disabled.txt`](original-disabled.txt)) stay disabled.

| Command | What it does |
|---|---|
| `./rollback.sh --dry-run` | Prints every command, sends nothing to the TV |
| `./rollback.sh --batch 02` | Re-enables only the packages of batch 02 |
| `./rollback.sh --no-reboot` | Full rollback without the final reboot |

Smaller undos:

- **One app:** `tools/tv.py 'pm enable --user 0 <package>'`, or on the TV: Settings → Apps → See all apps → Show system apps → the app → Enable.
- **DNS only:** `tools/tv.py 'settings put global private_dns_mode opportunistic'`.
- **T button on the remote:** its page lives in `com.tcl.partnercustomizer` (batch 02). Re-enable that package to get it back.
- **No computer at hand:** a factory reset from the TV's settings undoes everything, because nothing was uninstalled or flashed.

## Changing the dock or the look

Edit `dock_apps` (order and apps) and `columns` (4 to 7) in [`config.xml`](launcher/app/src/main/res/values/config.xml), then run `launcher/build-and-install.sh` again. The same file holds the wallpaper, greeting and clock options; each key is documented in place.

## Privacy

`backup/` (the TV's package lists, properties and settings) and raw screenshots stay out of the repository. The published screenshots have the Wi-Fi name and the account avatar masked.

## Licence

MIT. The launcher and `tools/` are © Gathin and the cleanyourtv contributors; see [LICENSE](LICENSE). The launcher's font and icons carry their own licences in [`launcher/`](launcher/).
