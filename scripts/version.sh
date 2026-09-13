#!/usr/bin/env bash
#
# The one place that answers "what version is this build?".
# ============================================================================
#
# There is no version number committed anywhere in this repository, and `main` never
# carries a -SNAPSHOT. Both are the same decision: a version is a statement about what
# changed, and the only record of what changed is the commit log. Deriving the number
# from the log means it cannot disagree with the code, and there is no "bump the
# version" commit to forget, to conflict, or to get wrong.
#
# Three commit types, three levels. Nothing else moves the number:
#
#   breaking  -> MAJOR   a consumer must change something to keep working
#   feature   -> MINOR   something new, everything that worked still works
#   patch     -> PATCH   a fix, with no visible API change
#
# Written as Conventional Commits (https://conventionalcommits.org), with the three
# words above accepted as aliases for the standard spellings, so both read naturally:
#
#   feat: add partial release to reservations        -> MINOR
#   feature(api): add partial release                -> MINOR   (alias of feat)
#   fix: reject a hold whose currency does not match -> PATCH
#   patch: reject a mismatched currency              -> PATCH   (alias of fix)
#   feat!: money is a string, never a JSON number    -> MAJOR   ('!' marks breaking)
#   breaking: drop the v1 reservation endpoint       -> MAJOR   (alias of '!')
#
#   refactor: extract the allocation loop            -> PATCH
#   chore/docs/test/ci/style: ...                    -> no release
#
# A `BREAKING CHANGE:` footer in the body also forces MAJOR, which is how you describe
# a break that the subject line has no room for.
#
# `ci lint` rejects a commit whose subject matches none of these, so an unrecognised
# message fails a pull request instead of silently contributing nothing to the number.
#
# ---------------------------------------------------------------------------
# What comes out
#
#   on main/master   X.Y.Z                     a release; this is what gets tagged
#   anywhere else    X.Y.Z-<branch>-<n>        a pre-release of the version main is
#                                              heading for, never published as a release
#
# The branch form uses the NEXT version, not the last released one. `0.2.0-holds-7`
# sorts after 0.1.0 and before 0.2.0 under both semver and Maven's own ordering, which
# is exactly what a branch build is: after the last release, ahead of the next one.
# Using the last released version instead would produce `0.1.0-holds-7`, which sorts
# BEFORE the 0.1.0 it was branched from — a build that claims to be older than its own
# parent. To change that anyway, drop the `min_bump patch` call in cmd_version.
#
# A branch with only chore/docs commits still needs a number, so it is floored at a
# patch bump; a release with only chore/docs commits is correctly no release at all.
#
# ---------------------------------------------------------------------------
# Usage
#
#   scripts/version.sh              the version this build should carry
#   scripts/version.sh bump         major | minor | patch | none
#   scripts/version.sh tag          the git tag for a release build (vX.Y.Z)
#   scripts/version.sh lint [range] fail on a commit that does not follow the convention
#   scripts/version.sh lint-subject "feat: ..."   the same check on one subject line
#   scripts/version.sh explain      all of the above, for a CI log or a puzzled human
#
#   ./mvnw -Drevision="$(scripts/version.sh)" verify      <- how every build calls it
#
# Environment overrides, all optional:
#   VERSION_BRANCH   branch name, when git cannot tell (CI checks out a detached HEAD)
#   BUILD_NUMBER     the incrementing part of a branch version; CI passes its run number
# ============================================================================
set -euo pipefail

TAG_PREFIX="v"

# The version of the very first release, used only when no tag exists yet. After that
# the last tag is the base and this is never read again.
INITIAL_VERSION="0.1.0"

# Branches whose builds are releases. Everything else is a pre-release.
RELEASE_BRANCHES='^(main|master)$'

# Commit types, by the level each one moves. `!` after any type, or a BREAKING CHANGE
# footer, overrides all of this and forces major.
TYPES_MAJOR='breaking'
TYPES_MINOR='feat|feature'
TYPES_PATCH='fix|patch|perf|refactor|revert|build|deps|security'
TYPES_NONE='chore|docs|test|ci|style'

die() { printf '%s\n' "$*" >&2; exit 1; }

# --- git facts --------------------------------------------------------------

last_tag() {
  git describe --tags --abbrev=0 --match "${TAG_PREFIX}[0-9]*.[0-9]*.[0-9]*" 2>/dev/null || true
}

# The commits this build is accountable for: everything since the last release, or the
# whole history if there has never been one.
commit_range() {
  local tag; tag="$(last_tag)"
  if [ -n "$tag" ]; then printf '%s..HEAD' "$tag"; else printf 'HEAD'; fi
}

base_version() {
  local tag; tag="$(last_tag)"
  if [ -n "$tag" ]; then printf '%s' "${tag#"$TAG_PREFIX"}"; else printf '0.0.0'; fi
}

current_branch() {
  # A CI checkout is usually a detached HEAD, where git knows the SHA and nothing else.
  # GITHUB_HEAD_REF is the source branch of a pull request and empty otherwise, so a
  # push to main falls through to GITHUB_REF_NAME.
  local b="${VERSION_BRANCH:-${GITHUB_HEAD_REF:-${GITHUB_REF_NAME:-}}}"
  [ -n "$b" ] || b="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo HEAD)"
  printf '%s' "$b"
}

# --- the three levels -------------------------------------------------------

rank() {
  case "$1" in none) echo 0;; patch) echo 1;; minor) echo 2;; major) echo 3;; *) echo 0;; esac
}
unrank() {
  case "$1" in 0) echo none;; 1) echo patch;; 2) echo minor;; 3) echo major;; esac
}

# The level of a single commit message, read from the subject plus the body footer.
level_of() {
  local msg="$1" subject
  subject="$(printf '%s\n' "$msg" | head -n1)"

  # A break can be declared two ways, and either wins over the type.
  if printf '%s\n' "$msg" | grep -qE '^BREAKING[ -]CHANGE:' \
  || printf '%s\n' "$subject" | grep -qE "^($TYPES_MAJOR|$TYPES_MINOR|$TYPES_PATCH|$TYPES_NONE)(\([^)]*\))?!:"; then
    echo major; return
  fi
  if printf '%s\n' "$subject" | grep -qE "^($TYPES_MAJOR)(\([^)]*\))?:"; then echo major; return; fi
  if printf '%s\n' "$subject" | grep -qE "^($TYPES_MINOR)(\([^)]*\))?:"; then echo minor; return; fi
  if printf '%s\n' "$subject" | grep -qE "^($TYPES_PATCH)(\([^)]*\))?:"; then echo patch; return; fi
  echo none
}

# The highest level among the commits since the last release. Merge commits are skipped:
# "Merge pull request #12" describes the merge, not the change, and the commits it brings
# in are counted individually.
detect_bump() {
  local range highest=0 sha msg lvl r
  range="$(commit_range)"
  while IFS= read -r sha; do
    [ -n "$sha" ] || continue
    msg="$(git log -1 --format=%B "$sha")"
    lvl="$(level_of "$msg")"
    r="$(rank "$lvl")"
    if [ "$r" -gt "$highest" ]; then highest="$r"; fi
    [ "$highest" -lt 3 ] || break
  done < <(git rev-list --no-merges "$range")
  unrank "$highest"
}

apply_bump() {
  local base="$1" bump="$2" major minor patch
  IFS=. read -r major minor patch <<< "$base"
  case "$bump" in
    major) major=$((major + 1)); minor=0; patch=0 ;;
    minor) minor=$((minor + 1)); patch=0 ;;
    patch) patch=$((patch + 1)) ;;
    none)  ;;
  esac
  printf '%s.%s.%s' "$major" "$minor" "$patch"
}

next_version() {
  local bump="$1" base
  base="$(base_version)"
  # No tag yet: the first release is INITIAL_VERSION whatever the log says, because
  # there is no previous API for a "breaking" change to have broken.
  if [ -z "$(last_tag)" ]; then printf '%s' "$INITIAL_VERSION"; return; fi
  apply_bump "$base" "$bump"
}

# A semver pre-release identifier is alphanumerics and hyphens. `feature/JIRA-4_holds`
# has to become `feature-jira-4-holds` before it can go in a version.
branch_slug() {
  local slug
  slug="$(current_branch \
    | tr '[:upper:]' '[:lower:]' \
    | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//' \
    | cut -c1-30 \
    | sed -E 's/-+$//')"
  [ -n "$slug" ] || slug="detached"
  printf '%s' "$slug"
}

build_number() {
  local n="${BUILD_NUMBER:-${GITHUB_RUN_NUMBER:-}}"
  if [ -z "$n" ]; then n="$(git rev-list --count "$(commit_range)")"; fi
  printf '%s' "$n"
}

is_release_branch() {
  printf '%s\n' "$(current_branch)" | grep -qE "$RELEASE_BRANCHES"
}

# --- commands ---------------------------------------------------------------

cmd_version() {
  local bump; bump="$(detect_bump)"
  if is_release_branch; then
    if [ "$bump" = "none" ]; then
      local since; since="$(last_tag)"; [ -n "$since" ] || since="the start of history"
      die "Nothing to release: no breaking/feature/patch commit since $since."
    fi
    next_version "$bump"
  else
    # Floor at patch: a branch of nothing but docs commits still needs a number, and it
    # must sort after the release it left.
    [ "$bump" != "none" ] && : || bump="patch"
    printf '%s-%s-%s' "$(next_version "$bump")" "$(branch_slug)" "$(build_number)"
  fi
}

# The regex that decides whether a subject is readable at all. `cmd_lint` applies it to a
# range of commits; `cmd_lint_subject` applies it to one string, which is what a squash-merge
# needs — the pull request title becomes the commit subject, so it is the title that has to
# parse, not the branch commits it replaces.
subject_is_valid() {
  printf '%s\n' "$1" \
    | grep -qE "^($TYPES_MAJOR|$TYPES_MINOR|$TYPES_PATCH|$TYPES_NONE)(\([^)]*\))?!?: .+"
}

cmd_lint_subject() {
  local subject="${1:-}"
  [ -n "$subject" ] || die "usage: $0 lint-subject '<commit subject>'"
  if ! subject_is_valid "$subject"; then
    printf '  %s\n' "$subject" >&2
    convention_help
    exit 1
  fi
  printf 'Subject follows the convention: %s\n' "$subject"
}

cmd_lint() {
  local range="${1:-$(commit_range)}" sha subject bad=0
  while IFS= read -r sha; do
    [ -n "$sha" ] || continue
    subject="$(git log -1 --format=%s "$sha")"
    if ! subject_is_valid "$subject"; then
      printf '  %s  %s\n' "$(git log -1 --format=%h "$sha")" "$subject" >&2
      bad=1
    fi
  done < <(git rev-list --no-merges "$range")

  if [ "$bad" -ne 0 ]; then
    convention_help
    exit 1
  fi
  echo "All commits follow the convention."
}

convention_help() {
  cat >&2 <<'MSG'

The commit subjects above do not follow the convention, so the release level cannot
be read from them. Expected  <type>[(scope)][!]: <description>  where <type> is one of:

  breaking          a consumer must change something         -> MAJOR
  feat | feature    something new, nothing broken            -> MINOR
  fix | patch       a fix, no visible API change             -> PATCH
  perf refactor revert build deps security                   -> PATCH
  chore docs test ci style                                   -> no release

Add `!` after the type, or a `BREAKING CHANGE:` footer, to force MAJOR.
Fix with `git rebase -i` (or reword the pull request's squash subject).
MSG
}

cmd_explain() {
  local bump; bump="$(detect_bump)"
  printf 'branch        %s%s\n' "$(current_branch)" "$(is_release_branch && echo '  (release branch)' || echo '  (pre-release)')"
  printf 'last release  %s\n' "$(last_tag || true)"
  printf 'commits       %s (%s non-merge)\n' "$(commit_range)" "$(git rev-list --count --no-merges "$(commit_range)")"
  printf 'level         %s\n' "$bump"
  if is_release_branch && [ "$bump" = "none" ]; then
    printf 'version       -  nothing releasable\n'
  else
    printf 'version       %s\n' "$(cmd_version)"
  fi
}

case "${1:-version}" in
  version) cmd_version ;;
  bump)    detect_bump ;;
  tag)     is_release_branch || die "tag is only meaningful on a release branch"; printf '%s%s' "$TAG_PREFIX" "$(cmd_version)" ;;
  lint)    shift; cmd_lint "${1:-}" ;;
  lint-subject) shift; cmd_lint_subject "${1:-}" ;;
  explain) cmd_explain ;;
  *)       die "usage: $0 [version|bump|tag|lint [range]|explain]" ;;
esac
