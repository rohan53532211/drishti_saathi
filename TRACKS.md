# Parallel build: track ownership and rules

`PROJECT_BRIEF.md` is the spec, `PLAN.md` the build order. This file says who may edit what while tracks run in parallel git worktrees, so merges stay clean.

## Build and test (every track, after every change)
```bash
cd <worktree>/app
export JAVA_HOME="$(cygpath -w ~/.jdks/jdk-21.0.13+9)"
./gradlew -q :engine:test :app:testDebugUnitTest :app:assembleDebug
```
- First time in a new worktree, create `app/local.properties` containing `sdk.dir=C\:\\Users\\HP\\AppData\\Local\\Android\\Sdk` (it is gitignored).
- If Gradle fails with "Unable to delete directory ... test-results", it is a Windows file lock: run `./gradlew -q --stop`, delete `engine/build/test-results` and `app/build/test-results`, rerun.
- Memory is tight (16 GB shared by several builds): never run more than one Gradle build at a time in your worktree; prefer `:engine:test` alone when only the engine changed.
- All existing tests must keep passing. If a test must change, say why in the commit message.
- Commit on your branch after each working step. End commit messages with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Shared contracts (do not change their shape; additions are fine if backward compatible)
| Contract | File |
|---|---|
| Landmarks / face frame | `engine/.../engine/face/FaceModel.kt` |
| Distance provider + fake | `app/.../vision/DistanceProvider.kt` |
| Face tracker | `app/.../camera/FaceTracker.kt` |
| Calibration | `app/.../calibration/Calibration.kt` |
| String registry `tx(en, hi)` | `app/.../i18n/TrackStrings.kt` |

## Ownership
| Track | Owns (create/edit freely) | rules.json section | Strings object |
|---|---|---|---|
| A engine | `engine/**` new files; `engine/.../config/EngineTrackConfigs.kt`; may edit `Optics.kt`, `Anthro.kt`, `RulesEngine.kt`, `Model.kt` (backward compatible) | `distance`, `bayes`, `faceGeometry` | none (engine has no text) |
| B camera | `app/.../camera/**`, `app/.../calibration/**`, `engine/.../config/CameraConfig.kt`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/assets/face_landmarker.task` | `camera` | `SCamera.kt` |
| C vision tests | `app/.../vision/**` (not the DistanceProvider contract), `engine/.../config/VisionTestConfig.kt` | `visionTests` | `SVision.kt` |
| D voice | `app/.../voice/**`, `app/.../ui/InterviewScreen.kt`, `app/.../interview/Questions.kt` (additive only) | none | `SVoice.kt` |
| E try-on | `tools/frames_cutout.py`, `app/src/main/assets/frames/**`, `app/src/main/assets/frames.json`, `Frame` class in `engine/.../Frames.kt` (add optional fields only), `engine/.../tryon/**`, `app/.../tryon/**`, `engine/.../config/TryOnConfig.kt` | `tryOn` | `STryOn.kt` |
| Integration (lead) | `MainActivity.kt`, `SessionViewModel.kt`, `ui/ResultScreen.kt`, `i18n/Strings.kt`, `PLAN.md`, `TRACKS.md` | | `SFlow.kt` |

Nobody but the lead edits `MainActivity.kt`, `SessionViewModel.kt`, `Strings.kt` or `Config.kt`. Each track exposes screens/composables and classes; the lead wires them into navigation.

## Wording (Section 6, enforced by `StringsWordingTest`)
Say "estimated band", "starting trial lens". Never "prescription", "diagnosis", "power is" (Hindi: पर्चा, निदान, पावर है) outside the disclaimer. Every string has English and Hindi; Hindi needs a native-speaker proofread before demo.
