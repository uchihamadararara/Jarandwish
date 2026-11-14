# JARVIS 2.0.10 Agentic Task Engine Upgrade

## What changed

- Added `TaskCapabilityRouter`, a deterministic intent classifier that gives the planner a preferred execution path for device controls, app workflows, communication, web research, content creation, file operations, and general UI tasks.
- Generic task progress plans now include capability selection, prerequisite inspection, execution, outcome verification, and recovery/blocker reporting. Existing Instagram bulk-unlike and Spotify workflows retain their dedicated plans.
- Added system-prompt rules for closed-loop execution: fresh observations before UI mutations, one meaningful action at a time, safe recovery, confirmation for consequential actions, and honest blocker reporting.
- Removed the unsafe completion fallback that accepted a task after a second `task_completed` call despite having no local verifier. Unknown task types now fail closed instead of producing false success.
- Added read-back verification for exact brightness and media-volume targets, plus flashlight on/off state, using Android's current system state.
- Added unit tests for capability routing and the generic task plan.

## Important limits

This is an upgrade to planning, routing hints, execution discipline, and verification. It does **not** create new external integrations by itself. The router only chooses among capabilities already present in the app; unavailable APIs, browser sessions, file permissions, credentials, and third-party services remain unavailable until separately integrated.

Complex tasks across arbitrary apps are still not guaranteed. Android permissions, Accessibility support, app UI changes, login/CAPTCHA, network availability, and confirmation requirements can block execution. For unsupported task types, JARVIS should now report that it cannot verify completion rather than claim success.

## Validation

- `TaskCapabilityRouter.kt` compiled independently with the installed Kotlin compiler.
- Source sanity checks passed for the modified prompt and completion-verifier paths.
- Full Gradle unit tests/build could not be run in this environment because the Gradle 8.9 distribution is not cached and outbound network access to `services.gradle.org` is unavailable. Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` in an Android/Termux environment with the configured Android SDK to complete validation.
