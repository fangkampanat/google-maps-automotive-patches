# Google Maps Automotive for MicroG-RE on BYD cars

Google Maps Automotive on a BYD DiLink 3 head unit (Android 10) with [MicroG-RE 7.2.2](https://github.com/MorpheApp/MicroG-RE/releases/tag/7.2.2).

[DL Test APK](https://tryy.cc/byd), PIN: 344993

![Maps Car in split screen on a BYD Dolphin: route preview to Nakhon Ratchasima showing "Arrive with 29%", next to MicroG-RE 7.2.2](docs/gmaps-cars-byd.webp)

The patched app, **Maps Car** (`app.morphe.android.apps.maps.automotive`), installs alongside phone Google Maps. This repository publishes `.mpp` bundles only; bring your own original APK.

## Features

- **UI density** in Settings, so the car layout fits split screen.
- **BYD battery (EV)** in Settings: Maps shows the battery on arrival from the car's own reading. Read-only.
- **Send to car** from phone Google Maps on the same Google account.

## Patches list

<!-- PATCHES_START -->
[v0.1.0](https://github.com/fangkampanat/google-maps-automotive-patches/releases/tag/v0.1.0) · 1 patch

Eligible versions: Any version.

| Patch | Description |
| --- | --- |
| Google Maps Automotive for MicroG-RE | Runs Google Maps for Android Automotive on Android 10 as "Maps Car" with MicroG-RE 7.2.2. Adds UI density and BYD battery (EV) settings and Send to car. |

<!-- PATCHES_END -->

## Tested

| Device | Result |
| --- | --- |
| BYD Dolphin, DiLink 3.0 / Android 10, MicroG-RE 7.2.2 | Sign-in, search, navigation, Thai and English voice, split screen, vehicle off/on, battery on arrival, Send to car |

Other BYD models, DiLink 5 and Android 11 to 15 are untested. On other BYD models, BYD battery starts off; you can turn it on, but the estimate may be less accurate. Plug-in hybrids are not supported.

## Requirements

- [Morphe Desktop](https://github.com/MorpheApp/morphe-desktop) 1.19.1 or newer, with Java 21
- The original **Google Maps (Android Automotive) 26.10.120003.E** APK (version code `1068833413`), for example from APKMirror. Check its SHA-256:

  ```text
  03ff2f0fffe542a210ac919b48f7ffd52bd6024c69f9f550c5b9bfc652c577df
  ```

  The patch also tries other Automotive versions and stops if it cannot find its hooks, but only 26.10.120003.E is tested.

- An ARM64 BYD head unit with Android 10 or newer, 4 KiB memory pages and ADB access

## Install

1. Install `microg-7.2.2-icon-arm64-v8a.apk` from [MicroG-RE 7.2.2](https://github.com/MorpheApp/MicroG-RE/releases/tag/7.2.2) on the head unit and whitelist it:

   ```bash
   adb shell dumpsys deviceidle whitelist +app.revanced.android.gms
   ```

   Turn OFF **MicroG RE** in BYD's **Disable Autostart**, then open it, grant its permissions and sign in. For Send to car, turn on **Cloud messaging**.
2. In Morphe Desktop, add this repository as a **Remote** source, make it active and turn off other sources for Google Maps:

   ```text
   https://github.com/fangkampanat/google-maps-automotive-patches
   ```

3. Drop the original APK onto **Drop APK here**, click **PATCH WITH DEFAULTS** and install the result on the head unit.
4. Start **Maps Car**.

## Reporting problems

In Maps Car, open **Settings › Report a problem**, check the report and tap **Send**. Nothing leaves the car before that. Your location, e-mail and VIN are removed first, and the report is deleted after 14 days.

Then open an [issue](https://github.com/fangkampanat/google-maps-automotive-patches/issues) with the report ID. If Maps Car will not start, describe the car model, DiLink version and bundle version instead.

## Credits and license

Built on [Morphe](https://github.com/MorpheApp) and [MicroG-RE](https://github.com/MorpheApp/MicroG-RE). The project is unofficial and not endorsed by Google, BYD or Morphe. Source code is licensed under [GPL-3.0](LICENSE); preserve [NOTICE](NOTICE). Build instructions: [docs/build-setup.md](docs/build-setup.md).
