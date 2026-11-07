#!/usr/bin/env bash
# Only the named local QA container; never inherit the application's datasource.
set -euo pipefail
set +x
cd "$(dirname "$0")/.."
container=aureliabooks-r2-mysql-qa
binding=$(docker port "$container" 3306/tcp)
[[ "$binding" =~ ^127\.0\.0\.1:([0-9]+)$ ]] || { echo "QA must bind only loopback" >&2; exit 1; }
export R2_QA_PORT="${BASH_REMATCH[1]}"
# Read only the QA password, without printing or shell-evaluating the env file.
R2_QA_PASSWORD=$(bash scripts/java-tools.sh R2QaTools password)
export R2_QA_PASSWORD
exec ./mvnw -B -ntp -Dr2.persistence.qa=true -Dtest=R2PersistenceBrowserQaTest test
