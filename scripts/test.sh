#!/usr/bin/env sh
# Set TRICORE_SERVER_BIN (or pass the path as $1); without a binary the live tests are skipped.
set -eu
cd "$(dirname "$0")/.."
SERVER="${1:-${TRICORE_SERVER_BIN:-}}"
if [ -n "$SERVER" ]; then
  ./mvnw -B "-Dtricore.server.bin=$SERVER" test
else
  ./mvnw -B test
fi
