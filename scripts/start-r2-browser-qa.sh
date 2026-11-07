#!/usr/bin/env bash
# Loopback, synthetic login, memory-only catalog/storage. No production component scan.
set -euo pipefail
cd "$(dirname "$0")/.."
exec ./mvnw -B -ntp -Dr2.browser.qa=true -Dtest=R2BrowserQaTest test
# URL: target/r2-browser-qa.json. Stop: create target/r2-browser-qa.stop (15 min timeout).
