#!/usr/bin/env bash
# Compile JDK-only maintenance tools; never starts Spring or reads application secrets.
set +x
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
[[ $# -ge 1 && "$1" =~ ^(CatalogSeed|WorkflowGate|R2QaTools)$ ]] || exit 2
tool=$1
shift
classes=$(mktemp -d "${TMPDIR:-/tmp}/aurelia-java-tools.XXXXXXXX")
trap 'rm -rf -- "$classes"' EXIT
javac --release 21 -d "$classes" src/main/java/com/ddagtech/aureliabooks/tooling/*.java
java -cp "$classes" "com.ddagtech.aureliabooks.tooling.$tool" "$@"
