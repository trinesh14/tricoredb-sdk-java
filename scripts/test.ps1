# Pass -Server <path> or set TRICORE_SERVER_BIN; without a binary the live tests are skipped.
param([string]$Server = $env:TRICORE_SERVER_BIN)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if ($Server) {
    & .\mvnw.cmd -B "-Dtricore.server.bin=$Server" test
} else {
    & .\mvnw.cmd -B test
}
exit $LASTEXITCODE
