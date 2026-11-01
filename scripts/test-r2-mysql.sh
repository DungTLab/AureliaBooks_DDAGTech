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
R2_QA_PASSWORD=$(python3 - <<'PYCODE'
from pathlib import Path
values = dict(line.split('=', 1) for line in
              (Path.home()/'.local/state/aureliabooks-r2-qa/mysql.env').read_text().splitlines()
              if line and not line.startswith('#'))
assert values['MYSQL_DATABASE'] == 'r2_image_qa'
assert values['MYSQL_USER'] == 'r2_qa'
print(values['MYSQL_PASSWORD'])
PYCODE
)
export R2_QA_PASSWORD
exec ./mvnw -B -ntp -Dr2.mysql=true -Dtest=ProductImageVisibilityMySqlTest test
