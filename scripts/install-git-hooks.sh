#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

chmod +x .githooks/pre-push

git config core.hooksPath .githooks

echo "Git hooks installed from .githooks/"
echo "pre-push will run: ./mvnw -q spotless:check && ./mvnw -q test"
echo "Format locally with: ./mvnw spotless:apply"
