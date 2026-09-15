#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
./mvnw -B -DskipTests package
