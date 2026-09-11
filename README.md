# cross-app-agent

A circle that floats over every app. Tap it, say what you want, and it reads whatever app is on
screen through the accessibility tree, decides what to tap, and keeps going until the job is done.

> First experiment after crash-landing on Planet Android. I wanted to know whether the thing Siri
> keeps promising — "just operate my apps for me" — is buildable by one person with public APIs.
> On iOS, no. Here, apparently yes. So naturally, I decided to rationalize the decision through engineering.

**Series:** Android × AI · Things Apple Would Never Let Me Do
**Status:** builds, JVM tests pass. Cross-app tree reading and the floating bubble are verified on an Android 16 emulator. The model loop itself has not been run end to end yet.

## Why I built this

The core capability an assistant needs is boring to state and impossible to get on iOS as a third
party: *read another app's UI and act on it*. Android hands this to any app the user explicitly
trusts, through `AccessibilityService`. Everything else in this repo — the model loop, the tools, the
confirmation gates — exists to find out what that capability is actually worth.

## The iOS brain

Coming from UIKit, my instinct was: this is `XCUITest` territory. You can drive any app from a test
runner, read `XCUIElement` trees, take `XCUIScreen` screenshots. But that runner is signed with a
development profile and installed by Xcode; it is not something you can ship. The App Store version
of "control other apps" is `App Intents` — where *you* expose actions for Siri to call, the exact
opposite direction. ReplayKit can see the screen, but nothing can touch it.

So the iOS brain says: an agent that operates other apps is a platform feature, not an app.

## What Android exposes

| Need | Android API | Notes |
|---|---|---|
| Read another app's UI tree | `AccessibilityService` + `AccessibilityNodeInfo` | Binder IPC into the target app's `ViewRootImpl`; text, contentDescription, resource ids, bounds, clickable/editable/scrollable flags |
| Act on a node without coordinates | `AccessibilityNodeInfo.performAction` | `ACTION_CLICK`, `ACTION_SET_TEXT`, `ACTION_SCROLL_*`, `ACTION_IME_ENTER` |
| Inject touches | `AccessibilityService.dispatchGesture` | Universal fallback: taps, swipes, long-press |
| See pixels | `AccessibilityService.takeScreenshot` (API 30) | Rate-limited; blocked by `FLAG_SECURE` |
| Back / Home | `performGlobalAction` | |
| Launch apps from the background | `startActivity` from a system-bound service | Apps with an enabled accessibility service are exempt from Android 10+ background-start restrictions |
| Know which apps exist | `PackageManager.queryIntentActivities` + `<queries>` | No `QUERY_ALL_PACKAGES` |
| Float UI over every app | `TYPE_APPLICATION_OVERLAY` + `SYSTEM_ALERT_WINDOW` | The chat-head capability; user grants it in Settings |
| Voice input | `SpeechRecognizer` | The recogniser itself is a replaceable system component |

The user must enable the service by hand in *Settings › Accessibility*. There is no API to enable it,
and on Android 13+ sideloaded apps have to pass an extra "restricted settings" step. That friction is
the product: the OS is making the user, not the developer, decide.

## Experiment

**The surface.** A draggable circle sits on top of whatever you are using. Tap it, speak, and it
turns red while listening, blue while driving the phone, amber when it needs an answer from you, and
green when it is done. Drag it anywhere and it snaps to the nearest edge; long-press dismisses it.
A small panel under the bubble streams what the agent is doing. The app's own screen is now just
setup and a debug console.

**The loop.** Text or speech task → Claude tool-use loop → accessibility tree in, actions out.

1. The accessibility service flattens the active window's node tree into a compact text listing with `[ref]`
   handles — the Android analogue of a DOM read with `ref_N` ids:
   ```
   app: com.google.android.apps.messaging
   [1] Input id=search_box "Search conversations" {editable} @540,180
   [2] List id=conversation_list {scrollable} @540,1200
     [3] View "Mom · Are you coming for dinner?" {clickable} @540,420
   [4] Button "Start chat" {clickable} @900,2100
   ```
2. Claude (`claude-opus-5`, adaptive thinking, effort `medium`) gets the task, the list of launchable
   apps, and that screen. It responds with tool calls: `launch_app`, `tap(ref)`, `type_text(ref, text,
   submit)`, `scroll`, `press_back`, `screenshot`, `ask_user`, `done`.
3. Every action returns the *new* screen, so most steps are a single round trip.
4. Two safety layers: the system prompt requires `ask_user` before irreversible actions, and the
   loop independently intercepts taps on nodes whose label looks like send / pay / delete and asks
   the user first.
5. Password fields are redacted in the serializer before anything is sent anywhere.

Later phases, in order: spoken replies (TTS) → `ROLE_ASSISTANT` + `VoiceInteractionService` so the
power button and "hey" wake word reach this agent instead of the stock one → Set-of-Marks
screenshots → macro cache that replays known trajectories without the model → on-device intent
router for the cases that never need the cloud.

## Architecture

```
┌──────────────┐  task   ┌──────────────────┐  tools   ┌──────────────────────┐
│ MainActivity │────────▶│   ClaudeAgent    │─────────▶│  DeviceController    │
│  (Compose)   │◀────────│ observe→act loop │◀─────────│ (a11y implementation)│
└──────────────┘ events  └────────┬─────────┘  screen  └──────────┬───────────┘
                                  │ Anthropic Java SDK            │ performAction / dispatchGesture
                                  ▼                               ▼
                           Claude Opus 5                 AgentAccessibilityService
                                                          ▲ bound by system_server
                                                          │ Binder
                                                   target app's ViewRootImpl
```

```
app/src/main/java/com/ioscastaway/crossappagent/
├── platform/      AgentAccessibilityService, tree reader, serializer, DeviceController, VoiceRecognizer
├── agent/         ClaudeAgent (loop), AgentTools (schemas), AgentEvent, ApiKeyStore
├── bubble/        BubbleService — the floating overlay, voice entry point, progress panel
└── ui/            MainActivity (setup + debug console, Compose), AgentViewModel
```

## Setup

Nothing here needs root, ADB tricks, or a special device.

```bash
brew install --cask android-studio      # once; brings the JDK, SDK, emulator
./scripts/bootstrap.sh                  # Gradle wrapper + local.properties skeleton
```

Then either put `ANTHROPIC_API_KEY=sk-ant-...` in `local.properties` before building, or paste the
key into the app's status card at runtime (stored in the app's private SharedPreferences on that
device; fine for an experiment, not a pattern to ship). Open the folder in Android Studio, run on a
device, and:

1. Open the app → *Open accessibility settings* → enable **Cross-App Agent**.
2. Back in the app the status card should read *connected*.
3. Tap *Dump in 5s*, switch to any other app, come back: the *Screen* tab shows that app's tree.
4. Type a task such as `Open Messages and search for "dinner"` → *Run*.

On an emulator (or any device over adb) the accessibility toggle can be flipped without touching
Settings. Sideloaded apps on Android 13+ also need the restricted-settings gate opened first:

```bash
adb shell appops set com.ioscastaway.crossappagent ACCESS_RESTRICTED_SETTINGS allow
adb shell settings put secure enabled_accessibility_services com.ioscastaway.crossappagent/com.ioscastaway.crossappagent.platform.AgentAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

## What I learned

First findings, from the emulator (Pixel 8 profile, Android 16 / API 36). Agent-loop findings will
follow once it runs against real tasks.

- **The tree is rich where it matters.** The Settings app came back with resource ids on almost
  every node (`search_action_bar`, `recycler_view`, `title`, `summary`), clean hierarchy, and
  `{clickable}` on the row containers. That is a better grounding signal than pixels for the model
  and a stable anchor for future macro replay.
- **Compose puts the click on the parent.** In my own Compose UI, a Material `Button` shows up as
  `View {clickable}` with a child `Text "Run"`. The text node the model will naturally pick is not
  the clickable one. The controller therefore walks up to the nearest clickable ancestor before
  falling back to a coordinate tap. Expect the same in RecyclerView rows.
- **Enabling is the hard part, by design.** `settings put` alone did nothing for a sideloaded APK on
  API 36; the service stayed unlisted until `appops ... ACCESS_RESTRICTED_SETTINGS allow`. This is
  Android 13's restricted-settings gate working as intended. On a real device the user does this
  through the Settings UI; there is no programmatic path from the app itself.
- **The service survives reinstall, the process does not.** After `installDebug` the process was
  killed and system_server re-bound the service within a second (`onServiceConnected` fired again
  with a new pid). No user action needed. This is the "system keeps you alive" property that has no
  iOS equivalent.
- **`uiautomator dump` fights with your service.** Running it while the agent service is enabled
  causes AccessibilityManagerService to disconnect and rebind third-party services. Use
  `adb exec-out screencap` and fixed coordinates for scripted testing instead.
- **An overlay is allowed, but the system can still veto it.** The bubble renders over the launcher
  and ordinary apps, yet over Settings it vanished. The window was alive and correctly placed; the
  dump showed `mForceHideNonSystemOverlayWindow=true` and therefore `isVisible=false`. Screens that
  handle sensitive input can ask the window manager to hide every non-system overlay, which kills
  tapjacking as a class. So the honest framing is not "Android lets apps draw anywhere" but "Android
  lets apps draw anywhere the screen underneath has not objected".
- **Foreground service types are load-bearing.** A service that opens the mic must declare
  `foregroundServiceType="microphone"` and hold `FOREGROUND_SERVICE_MICROPHONE`, and `adb` cannot
  start a non-exported one for you (`Requires permission not exported from uid ...`) — the tap has
  to come from the app.
- **Toolchain notes.** Android Studio 2026.1 bundles JBR 25; Gradle 8.14 wants a 17–24 JDK for the
  daemon, so the wrapper is pointed at Homebrew's `openjdk@17` through `~/.gradle/gradle.properties`.
  The wrapper *launcher* still needs `java` on `PATH` or `JAVA_HOME` in non-interactive shells.

## iOS comparison

Be precise, so:

- **Reading another app's UI:** iOS has no public API for third-party apps. The capability exists
  (VoiceOver, Switch Control, XCUITest) but is system- or developer-tooling-only. **API-level gap.**
- **Injecting input:** no public API at all on iOS. **API-level gap.**
- **Seeing the screen:** possible on iOS via a ReplayKit broadcast extension, but user-started each
  time and read-only. **Narrower, UX-level gap.**
- **Being the assistant:** no equivalent of `ROLE_ASSISTANT`; Siri is not replaceable. **Policy +
  architecture.**
- **Floating UI over other apps:** iOS has no third-party equivalent. Picture-in-Picture is the only
  thing an app may leave on screen, it carries video only, and it belongs to the app that started it.
  **API-level gap.**
- **Speech to text:** both platforms expose it (`SFSpeechRecognizer` vs `SpeechRecognizer`). The
  difference is structural rather than capability: on Android the recogniser is a swappable system
  role, so the same call can be served by Google's engine, the OEM's, or yours.
- **Being operated by an assistant:** iOS is arguably ahead here — App Intents give Siri and Shortcuts
  a typed, first-class way to call into apps. Android's App Actions are Google Assistant-only.
- **Prototyping the same loop on iOS:** entirely feasible on a personal device with WebDriverAgent
  (XCUITest under the hood). Not shippable, but a fair way to compare the two accessibility trees.

## Limitations

- **Security / privacy.** This service can read everything on screen and act on the user's behalf.
  Permissions used: `BIND_ACCESSIBILITY_SERVICE` (system-granted when the user enables the service),
  `INTERNET`. Data processed: the accessibility tree of the foreground app and, on request,
  a downscaled screenshot; both are sent to the Anthropic API only while a task is running. Password
  fields are redacted client-side. There is no persistence of screen content. Do not use it on
  banking or medical apps; many of them detect enabled accessibility services and refuse to run
  anyway.
- **Policy.** Google Play restricts accessibility APIs to accessibility purposes unless the use is
  prominently disclosed and consented to. This repo is sideload-only and does **not** declare
  `isAccessibilityTool`, so it correctly does not see `accessibilityDataSensitive` views on Android 14+.
- **Coverage.** Apps with poor Compose semantics, Flutter, canvas games and some WebViews produce a
  thin or empty tree; the screenshot fallback covers some of that at higher cost and latency.
- **Latency.** Each step is a cloud round trip. Fast paths (deep links, cached macros) are future work.
- **OS versions.** `minSdk 30`. Accessibility-as-IME text input (API 33) and
  `accessibilityDataSensitive` (API 34) are not yet used.

## Verdict

Half earned. The platform half — read another app's UI, act on it, float above it, stay alive — works
exactly as the docs promise and took an evening. The agent half is where the real experiment starts:
steps per task, failure modes per app, how often the screenshot fallback is needed. This section gets
rewritten after those runs.

---

**Reason #05 I don't regret switching to Android:**
An AI agent gets much more interesting when it can see beyond its own app.
