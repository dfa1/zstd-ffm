---
name: release
description: Cut a zstd-ffm release. Runs preflight checks, bumps the docs' dependency snippets, dates the CHANGELOG section, sets the release version, tags `vX`, bumps to the next SNAPSHOT, and — after explicit OK — pushes; the tag triggers `publish.yml` (Maven Central + GitHub release with notes from CHANGELOG.md) and then `release-smoke.yml`. Triggers when the user says "release", "cut a release", "tag a release", "release 0.N", or invokes `/release`.
---

## Overview

Cuts a release end-to-end, following the manual flow documented in the root
`pom.xml` (the comment above the `release` profile). Everything up to the push is
local and reversible; **pushing the tag publishes to Maven Central, which cannot be
undone** — stop and get an explicit OK first.

Versions are two-part: `0.17`, then `0.18-SNAPSHOT`.

## Inputs

- `<version>` — the release (e.g. `0.17`). Default: the current `pom.xml` version minus `-SNAPSHOT`.
- `<next>` — the next snapshot. Default: minor + 1 with `-SNAPSHOT` (`0.18-SNAPSHOT`).

## Steps

### 1. Preflight — stop on any failure

- On `main`, working tree clean (`git status --short` empty).
- Not behind origin: `git fetch origin && git rev-list --count main..origin/main` is 0.
  Being ahead is fine only for release-prep commits; list them at the push gate.
- `git tag -l "v<version>"` is empty, locally and on origin (`git ls-remote --tags origin "v<version>"`).
- `## [Unreleased]` in `CHANGELOG.md` has at least one bullet. If empty, stop: run the
  `changelog` skill first. Never invent release notes.
- `./mvnw verify` passes (Maven exit 0 — capture it, don't trust a grep pipeline).
  **Never `install`/`deploy`** (AGENTS.md).
- `gh auth status` succeeds.

Warn, and ask whether to continue (not hard stops):

- **Upstream zstd is newer than vendored**: compare
  `gh api repos/facebook/zstd/releases/latest -q .tag_name` with
  `git -C third_party/zstd describe --tags`. A newer upstream may be a security release.
- **Last release smoke test did not pass**: `gh run list --workflow release-smoke.yml --limit 3`.
  Check per-leg results (`gh run view <id> --json jobs`); a leg cancelled within seconds
  with no runner usually means a retired runner image.

### 2. Docs dependency snippets

Replace the previous release in user-facing snippets (README.md, docs/*.md):
`<version>PREV</version>`, `:PREV"` (Gradle), and prose like ``pin `PREV` ``. Leave
unrelated numbers alone (e.g. the Zig version `0.16.0` in `docs/contributing.md`).
`git grep -n "PREV" -- README.md docs` must show nothing release-related afterwards.

Commit: `docs: bump dependency-snippet versions to <version>`.

### 3. CHANGELOG

- Insert `## [<version>] - YYYY-MM-DD` (today) right after `## [Unreleased]`, leaving
  Unreleased empty above it.
- Point the bottom link at the new tag: `[Unreleased]: https://github.com/dfa1/zstd-ffm/compare/v<version>...HEAD`.
- The section body becomes the GitHub release notes verbatim (`publish.yml` awk-extracts
  it), so check it reads well on its own: **Breaking** entries first in their category,
  every bullet linked to its issue/PR (AGENTS.md "Changelog").

### 4. Release commit and tag

Set the version in every reactor pom (`FROM`/`TO` = `0.17-SNAPSHOT`/`0.17` here, then
`0.17`/`0.18-SNAPSHOT` in step 5) and check nothing was missed:

```bash
FROM=0.17-SNAPSHOT TO=0.17
# inline $(...) on purpose: zsh does not word-split an unquoted $VAR
for f in $(git ls-files '*pom.xml' ':!.github/smoke/pom.xml'); do sed -i '' "s|<version>$FROM</version>|<version>$TO</version>|" "$f"; done
git ls-files '*pom.xml' ':!.github/smoke/pom.xml' | wc -l                                    # these two
git grep -l -F "<version>$TO</version>" -- '*pom.xml' ':!.github/smoke/pom.xml' | wc -l      # must match
git diff --stat | tail -1                                                                    # one line per pom
```

`.github/smoke/pom.xml` is standalone (version `0`, takes the release as `-Dzstd.version`):
leave it alone.

Re-run `./mvnw verify` on the release version, then:

```bash
git commit -am "release: <version>"
git tag v<version>        # lightweight, like every earlier v* tag
```

### 5. Next snapshot

Same loop with `FROM=<version> TO=<next>`; commit
`build: bump to <next> for the next development iteration`. Without it main carries the
released version and is indistinguishable from the release (this bit main after 0.13).

### 6. Push — explicit OK only

Show what will go out:

```bash
git log --oneline origin/main..main
git show --stat --format='%h %s' v<version> | tail -1
```

On OK, push the commits, then the tag:

```bash
git push origin main
git push origin v<version>
```

### 7. Monitor — verify the logs, not just the exit code

- `gh run list --workflow publish.yml --limit 1`, then `gh run watch <id>`.
- A green run is not proof: `gh run view <id> --log` must show the Central upload
  actually ran (the `central-publishing` deployment uploaded and published) and the
  "Create GitHub release" step created `v<version>`.
- `gh release view v<version>` — notes match the CHANGELOG section.
- Maven Central (can take 10–30 min, not a hard gate):
  `curl -fsSI https://repo1.maven.org/maven2/io/github/dfa1/zstd/zstd/<version>/zstd-<version>.pom | head -1`.
- `release-smoke.yml` starts after a successful publish and pulls the release from
  Central on every classifier: watch it and report each leg.

If publishing fails, **do not delete or move the tag** — report it. Re-publishing a
version needs manual work on Central; a broken release gets a new version instead.

## Re-sync release notes (after editing a released CHANGELOG section)

Outward-facing: confirm first and name the versions that will change.

```bash
v=0.16
awk -v h="## [$v]" 'index($0,h)==1{p=1;next} /^## \[/{p=0} /^\[[A-Za-z0-9.]+\]: /{p=0} p' CHANGELOG.md > notes.md
grep -q '^- ' notes.md && gh release edit "v$v" --notes-file notes.md
```

Always `gh release edit`, never delete and recreate.

## Rules

- Never force-push, delete or move a pushed tag.
- Never `./mvnw install`/`deploy` locally; publishing happens only in `publish.yml`.
- Never skip hooks or signing (`--no-verify`, `--no-gpg-sign`).
- Never invent release notes; the CHANGELOG section is the contract with users.
- Confirm before every push; stop on the first failure in steps 1–6.
- Stage explicit paths (`git add <paths>`), not `git add -A`: crashed test JVMs leave
  `hs_err_pid*.log` files around.

## When NOT to use

- Patching an already-released version — cut a new one.
- Releasing from a branch other than `main`, or with an empty Unreleased section.
