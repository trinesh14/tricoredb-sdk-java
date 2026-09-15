# Release dry run: full build with tests, sources and javadoc jars. No signing, no deploy.
param([string]$Server = $env:TRICORE_SERVER_BIN)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
$args = @('-B', '-DskipTests=false')
if ($Server) { $args += "-Dtricore.server.bin=$Server" }
& .\mvnw.cmd @args verify
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Get-ChildItem target\tricoredb-0.1.0*.jar, target\*.pom -ErrorAction SilentlyContinue | Select-Object Name, Length
