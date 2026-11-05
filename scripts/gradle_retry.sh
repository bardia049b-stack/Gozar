#!/usr/bin/env bash
# Run Gradle, retrying only when the failure is a download/network problem.
#
# Robolectric fetches its android-all runtime from Maven during the test run.
# When that fetch fails the test reports an AssertionError inside
# MavenArtifactFetcher, which looks like a product failure but is not one.
# A genuine assertion failure is never retried, so this cannot hide a bug.
set -uo pipefail

log=$(mktemp)
trap 'rm -f "$log"' EXIT

for attempt in 1 2 3; do
  if bash ./gradlew "$@" 2>&1 | tee "$log"; then
    exit 0
  fi
  if grep -qE 'MavenArtifactFetcher|Could not (download|resolve|GET)|Connection reset|Read timed out|Temporary failure in name resolution|Connection timed out|502 Bad Gateway|503 Service Unavailable' "$log"; then
    echo "::warning::Gradle attempt $attempt hit a dependency download failure; retrying"
    sleep 20
    continue
  fi
  echo "Gradle failed on a real error; not retrying" >&2
  exit 1
done

echo "Gradle kept failing on dependency downloads after three attempts" >&2
exit 1
