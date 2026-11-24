# Polaris

Polaris is an offline personal planner for Android. It combines a daily to-do list, a calendar, and notes. Your data stays on your device, and no account is required.

## Features

- **Today** — add tasks quickly, mark them complete, or move them to tomorrow. Typing `Call Alex at 18:30` creates a task with a time. Unfinished tasks roll forward automatically, and tasks can repeat daily, weekly, or monthly.
- **Calendar** — browse tasks and events by date or see both in a single daily timeline.
- **Reminders** — timed events default to a reminder two hours before they start. You can change the interval or turn the reminder off. Enable Android notifications to receive reminders while the app is closed.
- **Notes** — choose plain text, a numbered list, a checklist, or a note with sections. Search by title or content and pin important notes. Cards start collapsed and expand on tap.
- **Home screen widget** — scroll through today's tasks, mark them complete, and add a new task without opening the full app.

## Installation

Polaris requires **Android 8.0 or later**. Open the APK on your phone and follow the Android installer. To keep your records when upgrading, install the new APK over the existing app.

To add the widget, touch and hold an empty spot on your home screen, open **Widgets**, and select Polaris.

## Project structure

- `src/` — Java source for the app, widget, local storage, and reminders.
- `res/` — Android layouts, styles, icons, and artwork.
- `AndroidManifest.xml` — app configuration and permissions.
- `NativePlannerCheck.java` — checks for task scheduling, notes, reminders, and widget logic.
- `build.ps1` — Windows APK build and signing script.

## Build from source

Use PowerShell 7 on Windows with JDK 21, Android SDK Platform 35, and Android SDK Build Tools 35.0.0. Use a project path with ASCII characters because Android build tools on Windows may fail on paths with non-ASCII characters.

The build script expects this tools directory layout:

```text
android-tools/
├── jdk/<jdk-installation>/
├── platform/android-35/android.jar
└── buildtools/android-15/
```

From the project directory, run:

```powershell
.\build.ps1 -ToolsDirectory "C:\path\to\android-tools"
```

The signed APK is written to `build/Polaris.apk`. To build an update for an existing installation, use `-SigningDirectory` to point to the original signing files. The source archive does not include the release signing key or password.
