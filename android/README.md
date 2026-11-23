# Project Menu for Android

A native wrapper around `../index.html`. The page runs in a WebView and talks to
an on-device replacement for `server.py`, so attachments, recordings and saving
work the same as on the desktop.

`index.html` at the repo root is the only copy of the page: the build copies it
into the APK (task `copyWebAssets`), so rebuild after editing it.

## Build

Requires the Android SDK (platform 35) and JDK 17+ (Android Studio's bundled JBR works).

```
# PowerShell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug

# bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

`local.properties` must point at the SDK (`sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk`);
Android Studio creates it automatically when the folder is opened.

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Install

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Where data lives

Everything is in the app's private storage (`/data/data/com.projectmenu.app/files/`),
with the same layout as the desktop server:

| Path | Contents |
| --- | --- |
| `projects.json` | all projects, tasks and tags |
| `backups/projects-YYYY-MM-DD.json` | first version of each day, kept before overwriting |
| `media/` | attached files and audio recordings |
| `media/.trash/` | attachments removed in the app |

Uninstalling the app deletes this data. Use Export in the app to save a copy. To pull
files from a debug build: `adb exec-out run-as com.projectmenu.app cat files/projects.json > projects.json`.
