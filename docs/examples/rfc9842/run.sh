#!/usr/bin/env bash
# Runs one of this directory's demo programs with the right classpath and
# JVM flags, so contributors don't have to recompute either by hand.
#
# Usage (from anywhere, after the one-time `./mvnw -q compile` from the repo
# root — see README.md):
#   docs/examples/rfc9842/run.sh Server [responseSizeBytes] [--quiet]
#   docs/examples/rfc9842/run.sh NaiveClient
#   docs/examples/rfc9842/run.sh Rfc9842Client
#   docs/examples/rfc9842/run.sh PerfTest [responseSizeBytes]
set -euo pipefail

if [[ $# -lt 1 ]]; then
    echo "usage: $0 <Server|NaiveClient|Rfc9842Client|PerfTest> [args...]" >&2
    exit 1
fi
class="$1"
shift

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$repo_root"

classpath="$(find . -path '*/target/classes' | tr '\n' ':')"
if [[ -z "$classpath" ]]; then
    echo "error: no target/classes found — run './mvnw -q compile' from the repo root first" >&2
    exit 1
fi

flags=(--class-path "$classpath")
case "$class" in
    Server)
        flags=(--enable-native-access=ALL-UNNAMED --add-modules jdk.httpserver "${flags[@]}")
        ;;
    Rfc9842Client|PerfTest)
        flags=(--enable-native-access=ALL-UNNAMED "${flags[@]}")
        ;;
    NaiveClient)
        ;;
    *)
        echo "usage: $0 <Server|NaiveClient|Rfc9842Client|PerfTest> [args...]" >&2
        exit 1
        ;;
esac

exec java "${flags[@]}" "docs/examples/rfc9842/$class.java" "$@"
