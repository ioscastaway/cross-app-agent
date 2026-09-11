# cross-app-agent — repo notes for agents

Identity, tone, series rules, and engineering defaults live in the workspace guide one level up:
`../CLAUDE.md`. This file only adds what is specific to this repository.

## What this is

Phase-1 cross-app phone agent: `AccessibilityService` tree → Claude tool-use loop → node actions.
Series: Android × AI (primary), Things Apple Would Never Let Me Do (secondary). Claims Reason #05.

## Build & run

```bash
./scripts/bootstrap.sh            # first time on a machine: Gradle wrapper + local.properties
./gradlew assembleDebug
./gradlew testDebugUnitTest       # JVM tests (serializer)
./gradlew installDebug            # device connected via adb
```

`local.properties` must contain `sdk.dir=...` and `ANTHROPIC_API_KEY=...`. Never commit it.

## Layout

- `platform/` — everything that touches Android system APIs. `DeviceController` is the seam; nothing
  in `agent/` may import `android.accessibilityservice` or `AccessibilityNodeInfo`.
- `agent/` — model loop and tool schemas. Pure Kotlin + Anthropic SDK. Keep `SYSTEM_PROMPT` and the
  tool list order stable; they form the cached prompt prefix.
- `ui/` — debug console (Compose). Not the product.

## Conventions specific to this repo

- Every action tool returns the fresh screen in its `tool_result`; do not add a separate read step.
- Anything that can be irreversible (send, pay, delete, post) must go through `ask_user` — both in the
  prompt and in `ClaudeAgent.confirmIfRisky`. Extend the `RISKY` list rather than bypassing it.
- Do not set `isAccessibilityTool="true"` in the service config. This is not an assistive technology.
- New model/API code: follow the workspace AI defaults (official Java SDK, `claude-opus-5`, adaptive
  thinking, `effort` for latency tuning, never `budget_tokens`).
- README "What I learned" and "Verdict" are filled from real device runs only. Do not invent results.

## Local environment (this Mac, set up 2026-09-11)

- JDK for Gradle: **21** (`/opt/homebrew/opt/openjdk@21/...`, Homebrew formula — the Temurin cask
  needs sudo). Gradle 8.14 accepts JDK 8–24, so Android Studio's bundled JBR 25 cannot run the daemon
  and the IDE refuses to sync with it. One source of truth: `org.gradle.java.home` in
  `~/.gradle/gradle.properties`, which the IDE picks up because `.idea/gradle.xml` sets
  `gradleJvm` to `#GRADLE_LOCAL_JAVA_HOME`. `.idea/` is git-ignored, so a fresh clone has to set the
  Gradle JDK again (Settings → Build Tools → Gradle → Gradle JDK).
- This is the JDK that *runs* Gradle. It is independent of `compileOptions` / `jvmTarget`, which stay
  at **17** — D8/R8 desugaring does not accept Java 21 bytecode for app code.
- In non-interactive shells `JAVA_HOME` may be unset; `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
  (or put its `bin` on `PATH`) before `./gradlew`, or the wrapper launcher fails with
  "Unable to locate a Java Runtime".
- SDK: `~/Library/Android/sdk` (platform 36, build-tools 36.0.0 + 35.0.0, platform-tools, emulator).
  `ANDROID_HOME`, `JAVA_HOME`, and `PATH` are exported in `~/.zshrc`.
- Emulator: AVD `agent_api36` (Pixel 8, Android 16 google_apis arm64).
  `emulator -avd agent_api36` for a window, add `-no-window` for headless.
- Enable the service on the emulator with the three `adb shell` lines in README → Setup.
- Do not use `uiautomator dump` while testing; it unbinds third-party accessibility services.
  Use `adb exec-out screencap -p > shot.png` and read the image.
- Android Studio is installed at `/Applications/Android Studio.app` (Homebrew cask). Opening the
  project there is optional; CLI builds work.
