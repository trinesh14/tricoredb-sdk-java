#!/usr/bin/env sh
# Runs the shared cross-SDK matrix for this SDK. The harness builds via conformance/runner.json
# and starts its own server. Override the spec location with TRICORE_SPEC.
# Note: runner.json invokes ./mvnw.cmd, so the manifest as shipped targets Windows.
set -eu
cd "$(dirname "$0")/.."
SPEC="${TRICORE_SPEC:-../tricoredb-sdk-spec}"
node "$SPEC/conformance/matrix.js" java
