# Ventoid
<div align="center">
<img src="app/src/main/res/drawable-nodpi/ventoid_icon.png" alt="Ventoid logo" width="128"/>

Ventoid turns an Android phone into a practical Ventoy-style USB writer. Plug in a drive over OTG, pick the target device, and prepare bootable media without needing to go back to a PC for the write step.

- OTG-first workflow for rescue kits and field installs
- Direct USB mass-storage writing from Android
- Selectable MBR or GPT Ventoy-style disk layout with data and EFI partitions
- Clear stage-based install flow for `MBR`, `CORE`, `DATA`, and `EFI`
- Bundled Secure Boot marker verification for the EFI image before install
- No ads, no analytics, no network dependency

## Build from source

Ventoid should be verifiable by anyone. You can open the project in Android Studio or build it directly with Gradle from the repository root.

### Android Studio

1. Clone the repository.
2. Open `Ventoid-publish` in Android Studio.
3. Let Gradle sync and install any missing Android SDK components it asks for.
4. Build a debug APK from **Build > Build Bundle(s) / APK(s) > Build APK(s)**, or run the app on a device with USB debugging enabled.

### Command line verification

On Windows, run these commands from the repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

If you also want a release build:

```powershell
.\gradlew.bat :app:assembleRelease
```

Release signing is optional. If no `VENTOID_RELEASE_*` environment variables or Gradle properties are set, the release artifact will be generated as an unsigned APK.

### Notes

- The bundled boot assets are already included in the repository, so no extra download step is required to verify the build.
- The project uses Gradle dependency locking and verification metadata to keep dependency resolution auditable.

## Release preflight

Before pushing a new F-Droid metadata update, run the local preflight once from the repository root:

```powershell
pwsh -File .\scripts\Test-FdroidPreflight.ps1 -UpdateMetadata
```

That command keeps the bundled `fdroiddata/metadata/com.ventoid.app.yml` copy aligned with the current app version and commit, then runs the checks that have caused F-Droid review churn in the past:

- `:app:lintRelease`
- `:app:testDebugUnitTest`
- `:app:assembleRelease`
- `:app:bundleRelease`

GitHub Actions also runs the same Android verification on pushes and pull requests, plus an F-Droid preflight job on `v*` tags and manual dispatches.

## info

Ventoid is an Android app for creating Ventoy-style USB drives directly from a phone. It is built for OTG workflows, repair kits, and those moments when your phone is the only working device you have nearby.

### Why people use it

- Prepare a bootable USB without a PC
- Rebuild install media in the field
- Keep a cleaner, phone-first workflow for rescue drives

### Core features

- Detect attached USB mass-storage devices
- Request Android USB permission only when needed
- Choose between MBR and GPT Ventoy-compatible disk layouts
- Write `core.img` and the EFI image
- Verify bundled Secure Boot markers in the EFI image before install
- Format the data partition as exFAT
- Show stage-based progress and a local write log

