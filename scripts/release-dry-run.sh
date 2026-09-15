#!/usr/bin/env sh
# Release dry run: full build with tests, sources and javadoc jars. No signing, no deploy.
set -eu
cd "$(dirname "$0")/.."
if [ -n "${TRICORE_SERVER_BIN:-}" ]; then
  ./mvnw -B -DskipTests=false "-Dtricore.server.bin=$TRICORE_SERVER_BIN" verify
else
  ./mvnw -B -DskipTests=false verify
fi
ls -l target/tricoredb-0.1.0*.jar
