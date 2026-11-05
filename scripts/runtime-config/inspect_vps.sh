#!/usr/bin/env bash
# Run manually on the VPS. Read-only; never dump env, Compose output or logs.
set -euo pipefail
set +x

case "${1:-}" in
  staging)
    root=/opt/apps/swp391-staging
    container=aurelia-staging-app-1
    ;;
  production)
    root=/opt/apps/swp391
    container=aurelia-production-app-1
    ;;
  *) printf '%s\n' 'Usage: bash inspect_vps.sh staging|production'; exit 2 ;;
esac

command -v timeout >/dev/null || { printf '%s\n' 'BLOCKED: timeout unavailable'; exit 1; }
command -v docker >/dev/null || { printf '%s\n' 'BLOCKED: docker unavailable'; exit 1; }

printf 'environment=%s\n' "$1"
for name in compose.yaml compose.caddy.yaml .env scripts/deploy.sh scripts/deploy-staging.sh; do
  if [[ -f "$root/$name" && ! -L "$root/$name" ]]; then
    # Metadata only. Never read the file contents, even for .env.
    if metadata=$(stat -c '%a:%u:%g' -- "$root/$name" 2>/dev/null); then
      printf 'file.%s=PRESENT mode:uid:gid=%s\n' "$name" "$metadata"
    else
      printf 'file.%s=UNREADABLE\n' "$name"
    fi
  else
    printf 'file.%s=MISSING_OR_SYMLINK\n' "$name"
  fi
done

if ! running=$(timeout 15 docker inspect --format '{{.State.Running}}' "$container" 2>/dev/null) || [[ "$running" != true ]]; then
  printf '%s\n' 'BLOCKED: expected application container not running or Docker access unavailable'
  exit 1
fi

# Docker emits ONLY presence markers; secret values never enter this shell.
for key in GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET MAIL_HOST MAIL_PORT MAIL_USERNAME MAIL_PASSWORD MAIL_AUTH MAIL_STARTTLS MAIL_FROM DEMO_SMS_INBOX VNPAY_TMN_CODE VNPAY_HASH_SECRET VNPAY_PAY_URL VNPAY_API_URL VNPAY_RETURN_URL R2_ENABLED R2_UPLOAD_ENABLED SPRING_PROFILES_ACTIVE SPRING_CONFIG_ADDITIONAL_LOCATION SPRING_CONFIG_LOCATION SPRING_CONFIG_IMPORT SPRING_APPLICATION_JSON JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS JAVA_OPTS BOOTSTRAP_ADMIN_ENABLED; do
  template='{{range .Config.Env}}{{if eq (index (split . "=") 0) "'"$key"'"}}PRESENT{{end}}{{end}}'
  if ! result=$(timeout 15 docker inspect --format "$template" "$container" 2>/dev/null); then
    printf '%s\n' 'BLOCKED: container metadata query failed'; exit 1
  fi
  case "$result" in
    PRESENT) printf 'env.%s=PRESENT\n' "$key" ;;
    '') printf 'env.%s=MISSING\n' "$key" ;;
    *) printf '%s\n' 'BLOCKED: unexpected metadata response'; exit 1 ;;
  esac
done

# Presence above does not establish nonempty values, effective properties or validity.
# Read only allowlisted decisions from profiles/flags, never their raw contents.
for flag in R2_ENABLED R2_UPLOAD_ENABLED; do
  template='{{range .Config.Env}}{{if eq . "'"$flag"'=false"}}FALSE{{end}}{{end}}'
  if ! result=$(timeout 15 docker inspect --format "$template" "$container" 2>/dev/null); then
    printf '%s\n' 'BLOCKED: flag query failed'; exit 1
  fi
  if [[ "$result" == FALSE ]]; then
    printf 'flag.%s=EXPLICIT_FALSE\n' "$flag"
  else
    printf 'flag.%s=NOT_VERIFIED_FALSE\n' "$flag"
  fi
done

template='{{range .Config.Env}}{{if eq (index (split . "=") 0) "SPRING_PROFILES_ACTIVE"}}{{range (split (slice . 23) ",")}}{{if eq . "google"}}GOOGLE{{end}}{{end}}{{end}}{{end}}'
if ! result=$(timeout 15 docker inspect --format "$template" "$container" 2>/dev/null); then
  printf '%s\n' 'profile.google=NOT_VERIFIED'
elif [[ "$result" == GOOGLE ]]; then
  printf '%s\n' 'profile.google=DECLARED_IN_ENV'
else
  printf '%s\n' 'profile.google=NOT_CONFIRMED_IN_ENV'
fi

for destination in /run/aureliabooks-config; do
  template='{{range .Mounts}}{{if eq .Destination "'"$destination"'"}}{{if .RW}}READ_WRITE{{else}}READ_ONLY{{end}}{{end}}{{end}}'
  if ! result=$(timeout 15 docker inspect --format "$template" "$container" 2>/dev/null); then
    printf '%s\n' 'BLOCKED: mount query failed'; exit 1
  fi
  case "$result" in
    READ_ONLY|READ_WRITE) printf 'mount.runtime=%s\n' "$result" ;;
    '') printf '%s\n' 'mount.runtime=MISSING' ;;
    *) printf '%s\n' 'BLOCKED: unexpected mount response'; exit 1 ;;
  esac
done
printf '%s\n' 'integration=NOT_VERIFIED (metadata inventory only; no files or credentials read)'
