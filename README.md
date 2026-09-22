# DayTrace

An Android voice recorder that turns what you say into short, sorted notes.

You talk; DayTrace records, transcribes with Gemini, and splits the result into
separate notes — a task, an idea, a thought — each with a title and tags. Notes
with a deadline become reminders, and any note can be sent to Google Calendar,
Google Tasks or Google Docs after you review it.

---

## How a recording becomes notes

```
RecordFragment ──► RecordingService          foreground service, AAC to internal storage
                        │
                        ▼
                   Mp3Converter               FFmpeg, MP3 at the chosen quality
                        │
                        ▼
                   MediaStore                 Music/App Records/
                        │
                        ▼
                   SummaryWorker              WorkManager, retries and network rules
                        │
                        ▼
                   GeminiSummarizer           transcript + notes
                        │
                        ▼
                   NoteStore                  one JSON file per recording
                        │
                        ▼
                   audio deleted               only now, and only on success
```

The audio is deleted **after** the notes are safely written, never before. If
processing fails the audio is kept, the failure is recorded, and `RecoveryWorker`
picks it up on the next launch without reprocessing anything twice.

---

## What it does

**Recording**
- Foreground service, so recording survives leaving the app
- Live waveform and timer; the screen re-syncs with the service when reopened
- Three quality profiles that reach the encoder, not just the label:

  | Quality | Sample rate | Bit rate |
  |---------|-------------|----------|
  | High    | 44.1 kHz    | 128 kbps |
  | Medium  | 44.1 kHz    | 64 kbps  |
  | Low     | 22.05 kHz   | 32 kbps  |

**Notes**
- Gemini writes a full transcript, then separates it into notes
- Four categories: `Thoughts`, `Idea`, `Remember`, `Random Gossip`
- Each note gets a short title and up to three tags
- Deadlines are only set when the recording actually said one

**Organising**
- History with a month calendar and category filters
- Per-day view, note detail, and a stats screen
- 30-day recycle bin for deleted and completed notes
- Import existing audio files, export as text or a `.daytrace` backup

**Reminders**
- `Remember` notes with a deadline schedule alarms via `AlarmManager`
- Early reminder plus one at the deadline; day-only deadlines remind in the morning
- Survive reboot, app update and time-zone changes

**Google integrations** — each connected separately, nothing sent without confirming

| | For | Duplicate protection |
|---|---|---|
| **Calendar** | something you attend at a set time | event id derived from the note, so Google itself rejects a second copy |
| **Tasks** | something to do or finish | a mark in the task's notes; the list is searched before anything is created |
| **Docs** | ideas and longer thoughts | a mark in the document; the document's own text decides what is already in it |

A failed Google request never changes a note. Completing a note in DayTrace
never touches the task in your Google account.

---

## Project layout

```
app/src/main/java/com/example/voicerecorder/
├── service/      RecordingService — foreground recording
├── encoder/      Mp3Converter — FFmpeg wrapper
├── summary/      Gemini, NoteStore, workers, import/backup
├── google/       shared OAuth + REST client
│   ├── calendar/ event detection and adding
│   ├── tasks/    task drafts and adding
│   └── docs/     document creation and appending
├── reminders/    alarms and notifications
├── settings/     stored preferences
└── ui/           fragments, dialogs, custom views
```

Views and fragments, no Compose. Notes are stored as one JSON file per recording
under the app's private storage, written to a temp file and renamed so a crash
can never leave half a file.

---

## Building

**Requirements**

| | |
|---|---|
| Android Gradle Plugin | 8.9.3 |
| Gradle | 8.11.1 |
| Kotlin | 1.9.24 |
| JDK | 17 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 (Android 8.0) |

```bash
./gradlew assembleDebug      # debug APK
./gradlew bundleRelease      # release AAB
./gradlew testDebugUnitTest  # unit tests
./gradlew lintDebug          # lint
```

### Gemini API key

Required. Put it in `local.properties`, which is gitignored — never in source:

```properties
GEMINI_API_KEY=your_key_here
```

It reaches the app through `BuildConfig.GEMINI_API_KEY`.

### Google integrations (optional)

Only needed for Calendar, Tasks and Docs. In a Google Cloud project:

1. Enable the **Calendar**, **Tasks**, **Docs** and **Drive** APIs.
2. Create an **Android OAuth client** for the app's package name and your
   signing certificate's SHA-1. There is no client secret in the app — it uses
   Play Services `AuthorizationClient`.
3. On the OAuth consent screen add the scopes below. While the app is in
   Testing mode, add your account as a test user.

Scopes are kept as narrow as they can be:

| Service | Scope | Why |
|---|---|---|
| Calendar | `calendar.calendarlist.readonly`, `calendar.events.owned` | list calendars, add events to ones you own |
| Tasks | `tasks` | the narrowest scope that can add a task |
| Docs | `drive.file` | only the documents DayTrace itself created |

Without this the app records, transcribes and reminds as normal; the Google
screens simply stay disconnected.

---

## Native libraries

FFmpeg comes from `ffmpeg-kit-audio` rather than `ffmpeg-kit-full`: DayTrace runs
a single command that decodes audio and encodes MP3 with `libmp3lame`, so the
video codecs were never reachable. All bundled `.so` files are **16 KB page
aligned**, which Android 15 and newer require.

---

## Tests

```bash
./gradlew testDebugUnitTest
```

Unit tests cover the parts where a mistake is expensive: Google duplicate
prevention (repeated taps, lost responses, reinstall, revoked access), deadline
resolution from spoken words, reminder scheduling, import date handling, and the
REST client's auth-retry behaviour.

---

## Status

Personal project, pre-release. `dev` is the working branch; `main` is the stable
line.
