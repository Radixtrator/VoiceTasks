# Voice Tasks

Dictate tasks on your Android phone or Wear OS watch and have them land in your Obsidian vault,
ready for [TaskForge](https://taskforge.md/), the Obsidian Tasks plugin, or TaskNotes.

Press a button, say something like

> "Remind me to email Sam on Monday at 10, it's urgent. Buy milk when I get to the grocery store,
> and take the trash out in an hour."

and about a second later you have three tasks, each with the right due date, time, priority and place.

Everything runs on the device. Speech recognition uses Android's on-device recognizer, and parsing
uses Gemini Nano when the phone has it. Nothing is sent to a server.

## Features

- **One tap to capture:** a Quick Settings tile, a launcher shortcut you can map to Samsung's side
  key, a share target, or the watch app. The capture card works on a foldable's cover screen.
- **Several tasks in one breath:** the on-device model splits a rambling note into separate tasks.
- **Natural dates and times:** "tomorrow", "next Friday", "the 1st", "November 3rd", "at 3 pm",
  "quarter to six", "in 20 minutes", "in an hour and a half". Phrases the built-in rules don't know
  ("first thing tomorrow", "end of day") are worked out by Gemini Nano.
- **Default due time:** tasks with no date are due today, and tasks with no time get a default
  (19:00 unless you change it).
- **Place reminders:** save places like "gym" or "home", then say "…when I get to the gym". You get
  a notification listing that place's open tasks when you arrive. Tasks completed elsewhere are skipped.
- **Your format:**
  - One file (`Inbox.md`, or `Daily/{date}.md`) with one Obsidian Tasks line per task, emoji or
    Dataview style.
  - One note per task, either as a single task line or as a TaskNotes note (status, priority,
    due date and time, tags and contexts in the frontmatter).
- **Galaxy Watch / Wear OS:** a tile and app that send what you say to the phone. Captures made
  away from the phone are queued and delivered when it's back in range.

## How it works

```
mic ─► on-device speech-to-text ─► Gemini Nano (or Gemma, or simple rules) ─► tasks ─► vault
                                     └─ copies out date/time/place words; the app's own code
                                        turns them into exact dates, with Nano's answer as a fallback
```

- **Speech:** Android's `SpeechRecognizer`, on-device when a language pack is installed. It keeps
  listening through short pauses and stops after about 1.5 seconds of silence.
- **Parsing:** [ML Kit's GenAI Prompt API](https://developers.google.com/ml-kit/genai) runs Gemini
  Nano through AICore. On phones without Nano you can import a Gemma model
  (`gemma3-1b-it-int4.task`, MediaPipe LLM Inference). With neither, a rule-based parser handles one
  task per note.
- **Dates:** the model copies out the phrase as spoken; `DateResolver`, `TimeResolver` and
  `RelativeTimeResolver` do the arithmetic, because small models are unreliable at it. When part of
  the phrase is left unexplained, the model's own date and time are used, within sanity limits.
- **Writing:** the vault folder is picked once through Android's folder picker (Storage Access
  Framework). Files are appended to or created; nothing is ever overwritten.
- **Places:** one geofence per saved place through Play services. Geofences are re-registered after
  a reboot or an app update.
- **Watch:** the `wear` module sends the transcript to the phone through the Wear OS Data Layer, and
  the phone's `WatchCaptureService` saves it and replies with the result.

## Building

Requirements: Android Studio (or the Android SDK with JDK 17+). The phone app needs Android 12+,
the watch app Wear OS 3+.

```bash
./gradlew :app:assembleDebug :wear:assembleDebug
adb -s <phone> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <watch> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

Both apps must share the package name and signing key for the Data Layer to connect them; building
both on the same machine does this.

Tests cover the date, time, place and model-reply parsing:

```bash
./gradlew :app:testDebugUnitTest
```

## Setup on the phone

1. Open **Voice Tasks** and choose your vault folder.
2. Under **Where tasks go**, pick one file or a note per task, and the folder or file name.
3. Under **Parser**, check that Gemini Nano says *Ready*; tap *Download* if it offers one.
   Without Nano, import a Gemma `.task` model from
   [litert-community on Hugging Face](https://huggingface.co/litert-community).
4. Tap **Add Quick Settings tile**. On Samsung phones you can also map the side key: Settings ›
   Advanced features › Side button › Double press › Open app › **Quick Task**.
5. For place reminders: allow location (choose *Allow all the time*) and notifications, then add
   places by name, with your current location or coordinates pasted from Google Maps.

The **Test the parser** box at the bottom of the settings screen shows exactly what a sentence
would produce without writing anything.

## Setup on the watch

Install the watch app, then add the **Quick Task** tile, or map a key to it (on a Galaxy Watch:
Settings › Advanced features › Customize keys › Home key double press › **Quick Task**).

## Limitations

- Times are stored only in TaskNotes notes. Obsidian Tasks lines hold a date.
- Repeating tasks ("every Monday") aren't supported yet.
- Spoken English only.
- Geofence arrivals can take a minute or two, and need location services on.
