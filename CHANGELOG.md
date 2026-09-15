# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [0.1.0] - 2026-09-15

First standalone release: `com.tricoredb:tricoredb`. Requires Java 17 or
newer; tested on Java 17, 21 and 25 against a live TriCoreDB server.

### Added
- Ported from the in-tree TriCoreDB Java driver, which already passed all 82
  conformance operations. Its behaviour is unchanged.
- Java package `com.tricoredb` (the package the in-tree driver already used),
  matching the Maven groupId. The JAR's `Automatic-Module-Name` is the same.
- Maven build with the Maven Wrapper. Produces the main, sources and javadoc
  jars, targets Java 17, and has no runtime dependencies.
- The in-tree test programs are now JUnit 5 tests. The live tests start their
  own `tricore-server`.
- A conformance runner (`conformance/Runner.java`, `conformance/runner.json`)
  for the shared cross-SDK matrix.
- An opt-in `release` profile for GPG signing and Central publishing, off by
  default.

### Behaviour carried over
- `BigDecimal` goes out as text via `toPlainString()`, both as a server-side
  parameter and as a client-side literal. Binding one into a DOUBLE column fails
  by name. `BigInteger` is unchanged.
- `byte[]` goes out as `0x` followed by hex.
- Server-side parameters (`SERVER_PARAMS`) and session transactions
  (`SESSION_TXN`) are negotiated in the handshake. If the server does not grant
  one, calls that need it throw `TriCoreException` naming the missing feature;
  there is no silent fallback.
- `TriCoreException.errorCode()`, `leaderHint()` and `isNotLeader()` expose a
  leader redirect. The driver does not follow redirects itself.
