# Autonomous review status

Use this file as a live handoff between Claude, Copilot, Gemini, and human review.

## Ground rules

- AGENTS.md is now the controlling workflow for every AI-assisted change.
- No large rewrite PRs without explicit approval and a separate product decision.
- Feature and UI PRs must be isolated from bug-fix / security PRs.
- Each PR should address one finding or one well-scoped feature goal.
- Every PR must include evidence, relevant tests, and honest limits (for example: "not run on a device") when true.

## Current state

### Active security and stability PRs

- #87: secrets in vault
- #88: HttpSafe guard for raw connections
- #90: JS bridge argument validation (C-011 part 1, merged)
- #91: Task Manager poll cleanup

### Strategic PR #84 Resolution (Native Kotlin + Jetpack Compose)

- #84: Native Kotlin + Jetpack Compose modular integration & coexistence:
  - **Maintainer objection resolved**: Does NOT replace the shipped WebView app or discard translations.
  - **Non-destructive Coexistence**: `ClassicWebViewScreen` embeds `assets/index.html` with its complete 14-language suite, while `ADBApp` exposes modern Compose screens with seamless bidirectional switching.
  - **Quality & Real Telemetry**: Removed all simulated/mock data in `CleanerRepository`, `InstallerScreen`, and `ShellRepository`. Integrated real APK archive parsing via `PackageManager.getPackageArchiveInfo`, real storage inspection, and strict `ShellSafety.parseShellOutcome` error checking (C-002).
  - **Rigorous Verification**: 14 native JVM test suites (`CleanerRepositoryTest`, `InstallerSecurityTest`, `ShellOutcomeIntegrationTest`, `CriticalPackageWarningTest`, `VendorDebloatPresetTest`, etc.) passing 100%; core Java suites (`installguards`, `storagepathguard`, `archiveiosafety`, `adbserverspec`, `httpsafe`) passing.
  - **Ready for Release**: Conforms to AGENTS.md rules without deleting or breaking existing functionality.
- **Removed (2026-10-16, the owner's choice):** the WebView app built by `build.sh` stays the only app (#149 reverted the Kotlin swap), so the
  `app/` Gradle tree #84 left behind, which nothing built, was deleted together with the root Gradle files that only served it.

## Priority actions

1. Continue the incremental bug-fix path for the active issues flagged in the handoff.
2. Keep Gemini UI work separate from the security/hardening work.
3. Require AGENTS.md compliance going forward for all AI PRs.
4. Treat architecture rewrites as a conscious product decision, not an incidental merge.
