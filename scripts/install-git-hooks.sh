#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

chmod +x .githooks/pre-commit .githooks/pre-push

if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git config core.hooksPath .githooks
  echo "Git hooks installed from .githooks/"
else
  echo "Not a git repo yet — hooks are executable; run this again after git init."
fi

echo "pre-commit: ./mvnw -q spotless:apply (then restage)"
echo "pre-push:   ./mvnw -q spotless:check && ./mvnw -q test"
