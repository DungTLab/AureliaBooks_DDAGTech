#!/usr/bin/env bash
# Runner-side transport: no secret-bearing payload files, outputs or artifacts.
set +x
set -euo pipefail
umask 077
[[ $# -eq 2 ]] || { printf '%s\n' 'CONFIG_STAGE_REJECTED' >&2; exit 2; }
target=$1
revision=$2
[[ "$target" == staging || "$target" == production ]] || exit 2
[[ "$revision" =~ ^[a-f0-9]{40}$ ]] || exit 2
[[ "${VPS_USER:-}" =~ ^[A-Za-z_][A-Za-z0-9_-]*$ ]] || exit 2
[[ "${VPS_HOST:-}" =~ ^[A-Za-z0-9][A-Za-z0-9.:-]*$ ]] || exit 2
[[ -n "${VPS_SSH_KEY:-}" && -n "${VPS_SSH_HOST_KEY:-}" && -n "${RUNNER_TEMP:-}" ]] || exit 2
private=$(mktemp -d "$RUNNER_TEMP/aurelia-config.XXXXXXXX")
cleanup() {
  rm -f -- "$private/identity" "$private/known_hosts"
  rmdir -- "$private"
}
trap cleanup EXIT
printf '%s\n' "$VPS_SSH_KEY" > "$private/identity"
printf '%s\n' "$VPS_SSH_HOST_KEY" > "$private/known_hosts"
if ! env -i PATH="$PATH" ssh-keygen -y -f "$private/identity" > /dev/null 2>&1; then
  printf '%s\n' 'CONFIG_STAGE_SSH_KEY_INVALID' >&2; exit 2
fi
remote() {
  env -i PATH="$PATH" timeout 90 ssh -F /dev/null -T -i "$private/identity" \
    -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes \
    -o "UserKnownHostsFile=$private/known_hosts" -o ConnectTimeout=15 \
    -o ServerAliveInterval=10 -o ServerAliveCountMax=2 \
    -- "$VPS_USER@$VPS_HOST" \
    "sudo -n /usr/local/sbin/aureliabooks-runtime-stage $1 $target $revision" 2>/dev/null
}
if ! response=$(remote check </dev/null) || [[ "$response" != READY_STAGE_ONLY_V1 ]]; then
  printf '%s\n' 'CONFIG_STAGE_SERVER_NOT_READY' >&2; exit 2
fi
generation=$(openssl rand -hex 16)
[[ "$generation" =~ ^[a-f0-9]{32}$ ]] || exit 2
printf 'STAGE_REQUEST %s %s\n' "$target" "$generation"
if ! response=$(java -jar target/runtime-config-tool.jar prepare "$target" "$revision" "$generation" | remote stage); then
  printf '%s\n' 'CONFIG_STAGE_FAILED_OR_UNCERTAIN_DO_NOT_RETRY_BLINDLY' >&2; exit 2
fi
if [[ "$response" != "STAGED_NOT_ACTIVE $generation" ]]; then
  printf '%s\n' 'CONFIG_STAGE_UNEXPECTED_RECEIPT_REVIEW_REQUIRED' >&2; exit 2
fi
printf 'STAGED_NOT_ACTIVE %s\n' "$generation"
