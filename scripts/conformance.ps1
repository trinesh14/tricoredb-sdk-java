# Runs the shared cross-SDK matrix for this SDK. The harness builds via conformance/runner.json
# and starts its own server. Override the spec location with TRICORE_SPEC.
$ErrorActionPreference = 'Stop'
$repo = Resolve-Path (Join-Path $PSScriptRoot '..')
$spec = if ($env:TRICORE_SPEC) { $env:TRICORE_SPEC } else { Join-Path $repo '..\tricoredb-sdk-spec' }
& node (Join-Path $spec 'conformance\matrix.js') java
exit $LASTEXITCODE
