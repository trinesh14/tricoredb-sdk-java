$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
& .\mvnw.cmd -B -DskipTests package
exit $LASTEXITCODE
