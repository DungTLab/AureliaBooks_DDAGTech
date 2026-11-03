#!/usr/bin/env bash
set -euo pipefail

repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$repo"
if [[ $# -gt 1 ]]; then
  echo "Usage: bash scripts/prepare-r2-smoke-offline.sh [new-output-directory]" >&2
  exit 2
fi
if [[ -n "$(git status --porcelain)" ]]; then
  echo "Commit/review source changes first; the draft must bind to a clean revision." >&2
  exit 2
fi
revision=$(git rev-parse HEAD)
destination=${1:-"$repo/target/r2-smoke-draft"}
destination=$(realpath -m -- "$destination")
if [[ -e "$destination" || -L "$destination" ]]; then
  echo "Output already exists; retain it for review. Do not overwrite/reuse a smoke run." >&2
  exit 2
fi

# Only this opt-in JUnit writer runs. No Spring/MySQL/server/client/secrets lookup.
# Offline Maven and headless ImageIO also work without a desktop display.
./mvnw -o -B -ntp -Djava.awt.headless=true \
  -Dtest=R2SmokeDraftPreparationTest \
  -Dr2.smoke.prepare=true \
  "-Dr2.smoke.prepare-dir=$destination" \
  "-Dr2.smoke.source-revision=$revision" test
echo "Offline draft only; live execution is NOT approved:"
echo "$destination/approval-draft.json"
echo "$destination/normalized.png"
