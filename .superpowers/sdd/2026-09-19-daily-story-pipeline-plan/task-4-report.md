# Task 4 Report

## Status

DONE

## Scope delivered

- Added `StoryPlayback` and a testable playback controller.
- Story playback prefers `finalBody`, falls back to `draftBody`, and when both are blank can replay the day's original recording segments.
- Added Android Chinese TTS playback with chunking for long story text.
- Added sequential AAC playback through `MediaPlayer`; segments are ordered by `startedAt`/startMs, and switching sources stops and releases the active source first.
- Added pause and stop forwarding for the active source.
- Replaced the existing `MainActivity` TTS wiring with `AndroidStoryPlayback`; no visual UI redesign was included.
- Added focused tests for final/draft preference, original-audio fallback, start-time ordering, source switching, pause, and stop.

## TDD and verification

- RED confirmed before implementation: `StoryPlaybackTest` failed to compile because the Task 4 playback interfaces and controller did not exist.
- Focused tests:

  `JAVA_HOME=/opt/homebrew/opt/openjdk@17 bash ./gradlew :app:testDebugUnitTest --tests com.littlediary.app.StoryPlaybackTest`

  → 6 tests, 0 failures, 0 errors.

- Full unit tests and Debug build:

  `JAVA_HOME=/opt/homebrew/opt/openjdk@17 bash ./gradlew :app:testDebugUnitTest :app:assembleDebug`

  → 90 tests, 0 failures, 0 errors; `assembleDebug` succeeded.

- `git diff --check` and `git diff --cached --check` passed before the implementation commit.

## Commit

- `50d3074 feat: play daily stories and original recordings`

## Notes

- `gradlew` is not executable in this worktree, so the requested wrapper commands were run equivalently with `bash ./gradlew`; no file permissions were changed.
- The implementation commit includes only `StoryPlayback.kt`, `MainActivity.kt`, and `StoryPlaybackTest.kt`. Existing MEMORY/docs and Task 1–3/recording-queue changes were preserved.
