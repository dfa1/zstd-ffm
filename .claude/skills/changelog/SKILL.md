---
name: changelog
description: Write CHANGELOG.md entries under `## [Unreleased]` for merged changes that don't have one yet — one or two lines per user-visible change, in caller API terms, linked to the issue or PR. Triggers on "prepare the changelog", "update CHANGELOG", "write changelog entries", or `/changelog`. Standalone version of the `release` skill's CHANGELOG check — use it before cutting a release.
---

## Overview

Entries follow AGENTS.md "Changelog": **what changed for the caller, in API terms**, then
the issue or PR link. Root cause, native function names, internals and test notes belong
in the PR description and commit message, not here.

## Steps

1. Find the commits since the last release: `git log --oneline v<PREV>..HEAD`
   (`PREV` = latest `v*` tag). Squash-merged subjects end in `(#PR)`; the issue is usually
   in the subject too (`fix: … (#185) (#190)`).
2. Drop the ones already covered: an issue/PR number already linked under `## [Unreleased]`.
3. For each remaining **user-visible** change, write one bullet:
   - One or two lines: what changed for the caller, then the link —
     `([#185](https://github.com/dfa1/zstd-ffm/issues/185))` for an issue, `…/pull/157`
     for a PR with no issue. Prefer the issue.
   - Fixes describe the symptom the caller saw and what happens now
     ("crashed the JVM when …; it now throws `IllegalStateException`").
   - Breaking changes start with `**Breaking:**` and name the replacement; a short
     migration list under the bullet is fine.
4. File it under `### Added` / `### Changed` / `### Fixed` / `### Removed` (create the
   heading if missing, in that order).
5. Skip internal-only commits: CI, build, Sonar, dependency bumps of test/benchmark tools,
   refactors, test-only and docs-only changes. A native zstd upgrade **is** user-visible.
6. Commit: `docs: CHANGELOG entries for <short description>`.

## Example

Good:
```
- `ZstdOutputStream.close()` crashed the JVM when the wrapped stream closed it again from its
  `write` or `flush`; the nested close now returns at once. ([#183](https://github.com/dfa1/zstd-ffm/issues/183))
```

Bad (internals, too long):
```
- `close()` set `closed = true` only in its `finally`, so a re-entrant close still saw the
  stream open, drained, called `ZSTD_freeCCtx` and `arena.close()`, then the outer finally
  called `ZSTD_freeCCtx` again (malloc double-free) … ([#183](…))
```

## Rules

- Never invent entries; every bullet links a real issue or PR.
- Never more than two lines per bullet — split it, or move the detail to the PR.
- Editing a **released** section changes its GitHub release notes: re-sync with the
  `release` skill's "Re-sync release notes" step (confirm first).

## When NOT to use

- Dating a version header or fixing the compare link — that's the `release` skill.
- Work that isn't merged yet: write the entry in the PR itself.
