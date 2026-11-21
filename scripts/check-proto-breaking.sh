#!/usr/bin/env bash
# Fails if contracts/ breaks wire compatibility with BASE (a git ref or commit).
# Skips when there is nothing to compare with (first push, or BASE has no contracts yet).
# Usage: scripts/check-proto-breaking.sh <base-ref>
set -euo pipefail
cd "$(dirname "$0")/.."

base="${1:?usage: check-proto-breaking.sh <base-ref>}"

if [[ "$base" =~ ^0+$ ]] || ! git cat-file -e "${base}^{commit}" 2>/dev/null; then
  echo "proto breaking: skipped (no base commit '$base')"
  exit 0
fi
if ! git cat-file -e "${base}:contracts/buf.yaml" 2>/dev/null; then
  echo "proto breaking: skipped ($base has no contracts/buf.yaml yet)"
  exit 0
fi

buf breaking contracts --against ".git#ref=${base},subdir=contracts"
echo "proto breaking: OK against $base"
