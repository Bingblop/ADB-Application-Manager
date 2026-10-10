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

### Separate strategic PR

- #84: Native Kotlin + Jetpack Compose rewrite
  - This is a major architecture rewrite and should be treated as a separate product initiative.
  - It should not be treated as a normal bug-fix or hardening PR.
  - It should be reviewed under a different approval path and must be clearly scoped as an exploratory or strategic rewrite.

## Priority actions

1. Continue the incremental bug-fix path for the active issues flagged in the handoff.
2. Keep Gemini UI work separate from the security/hardening work.
3. Require AGENTS.md compliance going forward for all AI PRs.
4. Treat architecture rewrites as a conscious product decision, not an incidental merge.
