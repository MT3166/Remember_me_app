# Task 3 Report

## Status

DONE

## Scope delivered

- Added `DailyStoryWorkScheduler` with stable per-day draft/final/cover work names, connected-network constraints, exponential retry, revision/fingerprint inputs, draft debounce via `REPLACE`, and finalization of previous local days that are not `READY`.
- Added `DailyStoryWorker` and an injectable processor for draft, final, and cover work. Story writes use `DailyStoryRepository.replaceIfCurrent`, so stale revision/fingerprint snapshots cannot overwrite newer source material.
- Updated `RecordingWorker` so a successfully transcribed segment is appended to its persisted `dayKey` exactly once, then schedules the debounced draft. Replayed or concurrently completed transcription remains idempotent.
- Final story generation preserves the previous playable story on failure. Successful final writes enqueue cover work through the existing `ModelClient.cover` contract; failed or stale cover work leaves `coverFile` unchanged so the default illustration remains available.
- Added focused coverage for draft scheduling, append idempotency, stale protection, previous-day finalization, story failure preservation, final-to-cover scheduling, and cover failure fallback.

## TDD and verification

- RED confirmed before implementation: `DailyStoryWorkerTest` failed to compile because the Task 3 worker, scheduler, and recording integration interfaces did not exist.
- Focused tests:

  `JAVA_HOME=/opt/homebrew/opt/openjdk@17 bash ./gradlew :app:testDebugUnitTest --tests com.littlediary.app.DailyStoryWorkerTest`

  → 6 tests, 0 failures, 0 errors.

- Focused regression tests:

  `JAVA_HOME=/opt/homebrew/opt/openjdk@17 bash ./gradlew :app:testDebugUnitTest --tests com.littlediary.app.DailyStoryWorkerTest --tests com.littlediary.app.RecordingWorkerTest`

  → build successful; DailyStoryWorkerTest 6/6 and RecordingWorkerTest 8/8 passed.

- Full unit tests:

  `JAVA_HOME=/opt/homebrew/opt/openjdk@17 bash ./gradlew :app:testDebugUnitTest`

  → 83 tests, 0 failures, 0 errors.

- `git diff --cached --check` passed before the implementation commit.

## Commit

- `e281040 feat: aggregate recordings into daily child stories`

## Notes

- `gradlew` is not executable in this worktree, so the requested wrapper commands were run equivalently with `bash ./gradlew`; no file permissions were changed.
- Task 4 playback/UI work was intentionally not included.
- Existing Task 1/2 and recording-queue changes, plus user-owned memory/docs changes, were preserved.

## Review fix round: CHANGES REQUESTED

### Changes addressed

- `DiaryViewModel.load()` now schedules previous-day final work during startup recovery. `MainActivity` watches `ON_START` and calls the same scheduler only after the local day key changes, so rollover finalization does not depend on a new `RecordingWorker`.
- Appending a new source revision clears `coverFile`. A failed cover attempt therefore leaves the default illustration state instead of exposing an older revision's cover.
- After a successful story append, `RecordingWorker` advances the recording to `ATTACHED_TO_DAY` before scheduling draft work. Existing attached-but-not-transitioned recordings are repaired idempotently, enabling retention cleanup.
- Added regression coverage for old-cover invalidation plus cover failure, transcription-to-attached state transition, and retention deletion after the configured window. Existing previous-day scheduler coverage verifies READY stories are skipped.

### Verification

- RED confirmed before the review fixes: the new regression assertions failed for stale cover retention and missing `ATTACHED_TO_DAY` transition.
- Focused test:

  `JAVA_HOME=/opt/homebrew/opt/openjdk@17 bash ./gradlew :app:testDebugUnitTest --tests com.littlediary.app.DailyStoryWorkerTest`

  → 7 tests, 0 failures, 0 errors.

- Full unit tests:

  `JAVA_HOME=/opt/homebrew/opt/openjdk@17 bash ./gradlew :app:testDebugUnitTest`

  → 84 tests, 0 failures, 0 errors.

- `git diff --check` passed before the review-fix commit.

## Concurrency review fix

- `replaceIfCurrent` now compares the captured source revision/fingerprint together with the expected story state, preventing same-source late draft/final results from replacing a newer result.
- Draft work only accepts `COLLECTING`; final work skips already `READY` stories and both paths re-check the captured state atomically before persisting.
- Cover work carries and validates the final-content fingerprint and prior cover filename. A late cover result is discarded when the saved final or cover state has changed.
- Added regression tests for an old final after a new final, a late draft after `READY`, and a late cover for a previous final.

Verification:

`JAVA_HOME=/opt/homebrew/opt/openjdk@17 bash ./gradlew :app:testDebugUnitTest --tests com.littlediary.app.DailyStoryWorkerTest`

→ 10 tests, 0 failures, 0 errors.

`JAVA_HOME=/opt/homebrew/opt/openjdk@17 bash ./gradlew :app:testDebugUnitTest :app:assembleDebug`

→ build successful; 97 tests, 0 failures, 0 errors; `assembleDebug` passed.
