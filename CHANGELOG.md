# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [0.3.0] - 2026-10-13

### Added

A typed method for each of the 116 operations the server gained after
0.1.0-rc.2. **They need `tricore-server` 0.1.0-rc.3 or later; an older server
answers each of them with an error**, raised as `TriCoreException`. Everything
that was in 0.2.0 still works against an older server.

- **Cache** (53): sorted sets (`cacheZAdd` ... `cacheZIncrBy`), locks with
  fencing tokens (`cacheLockAcquire`, `cacheLockRenew`, `cacheLockRelease`),
  bitmaps (`cacheSetBit` ... `cacheBitOp`), HyperLogLog (`cachePfAdd`,
  `cachePfCount`, `cachePfMerge`), many keys at once (`cacheMGet`,
  `cacheMSet`, `cacheWarm`), conditional and atomic writes (`cacheSetIf`,
  `cacheCompareAndSwap`, `cacheGetDel`, `cacheGetSet`, `cacheGetEx`,
  `cacheAppend`, `cacheExpireAt`, `cacheHIncrBy`), stale-while-revalidate
  (`cacheSetFresh`, `cacheGetFresh`, `cacheSetMissing`), list and set
  additions (`cacheLMove`, `cacheLTrim`, `cacheBLPop`, `cacheBRPop`,
  `cacheSUnion`, `cacheSInter`, `cacheSDiff`, `cacheSRandMember`), batches
  (`cachePipeline`, `cacheTransaction`, `cacheScriptLoad`, `cacheScriptRun`,
  built from `CacheCommand`), Pub/Sub by polling (`cachePublish`,
  `cachePoll`), namespace limits, `cacheStats`, `cacheHotKeys`,
  `cachePurgeExpired` and `cacheFlushDatabase`.
- **Documents** (13): `documentReplace`, `documentInsertMany`,
  `documentDeleteMany`, `documentBulkWrite` (a list of `BulkOp.insert`,
  `BulkOp.updateOne`, `BulkOp.replace` and `BulkOp.delete`),
  `documentExplain`, `documentRenameCollection`, `documentPurgeExpired`,
  `documentRebuildIndex`, collection options (`documentSetCollectionOptions`,
  `documentGetCollectionOptions`, `CollectionOptions`) and transactions
  spanning requests (`documentBeginTransaction`, `documentCommitTransaction`,
  `documentRollbackTransaction`).
- **Vectors** (21): `vectorUpsertMany`, `vectorDeleteMany`,
  `vectorSearchBatch`, `vectorSearchMulti`, `vectorSearchByExamples`,
  `vectorDeleteByFilter`, `vectorUpdateByFilter`, `vectorCount`, named vectors
  (`vectorAddNamedVector`, `vectorUpsertNamed`), aliases (`vectorSetAlias`,
  `vectorListAliases`), the built-in embedder (`vectorEmbed`,
  `vectorUpsertText`), `vectorListPartitions`, `vectorRenameCollection`,
  `vectorCopyCollection`, `vectorSetCollectionConfig`, `vectorRebuildIndex`,
  `vectorIndexStats` and `vectorMeasureRecall`.
- **Graph** (29): `graphAddMany`, `graphRemoveMany`, `graphUpdateNode`,
  `graphUpdateEdge`, `graphCopy`, `graphImportCsv`, `graphDfs`,
  `graphSubgraph`, `graphKShortestPaths`, `graphAllPaths`,
  `graphConnectedComponents`, `graphDetectCycle`, `graphCentrality`,
  `graphCommunities`, `graphTriangleCount`, `graphNodeSimilarity`,
  `graphPageRank`, `graphStats`, `graphSchema`, `graphCheckConsistency`,
  indexes and constraints (`graphCreateNodeIndex`, `graphCreateConstraint`,
  `graphDropSchemaObject`, `graphListSchemaObjects`, `graphFindNodes`), Cypher
  writes (`graphExecute`) and graph transactions (`graphBeginTransaction`,
  `graphCommitTransaction`, `graphRollbackTransaction`).
- `TriCore.setRequestTxn` / `requestTxn()`: the document or graph transaction
  every request from the connection runs inside. Committing or rolling that
  transaction back clears it.

### Changed

- A `BEGIN` inside an open session transaction is no longer refused by the
  server: from 0.1.0-rc.3 it opens a nested block backed by a savepoint, whose
  `COMMIT` releases it and whose `ROLLBACK` undoes only that block. `begin()`,
  `commit()` and `rollback()` used to throw `ProtocolException` on the answer
  to such a statement; they now return it, `TransactionResult.nestedDepth()`
  reports how many nested blocks remain open, and `inTransaction()` stays true
  until the outer block ends. `TransactionResult` gained that fifth component;
  the four-argument constructor remains. The live test that pinned the old
  refusal now checks the nested behaviour.

### Notes

- A wide diagnostic answer (`cacheStats`, `documentExplain`, `graphStats`,
  `graphSchema`, `graphCheckConsistency`, `graphExecute`,
  `graphCommitTransaction`, and the vector index reports) comes back as the
  server's own JSON object rather than a record, because its fields depend on
  the server's configuration and on what it found.
- `cacheHotKeys` returns the server's JSON undecoded. The server refuses it
  unless `[cache] hot_keys` is above zero, and it was not exercised against a
  server that has it on.
- `VectorSearchOptions` carries the search settings this driver names; the
  server's remaining search settings can be sent with `request`.
- JSON cannot carry an infinite number, so an open end of a score range is
  `ScoreBound.min()` or `ScoreBound.max()`, the largest finite doubles.

## [0.2.0] - 2026-09-17

### Added

- Following the leader: a request refused with `not_leader` is sent again, to
  the leader the refusal names (over a new connection that replaces the
  current one) or, while no leader is known, to the same node after a pause.
  On by default; `TriCore.setFollowLeader(false)` restores the previous
  behaviour, and `setLeaderRetries` (5) and `setLeaderRetryDelayMs` (250)
  bound it. `Pool` has the same setters. Never inside a session transaction.
- `TriCoreException.ERROR_CODE_OUTCOME_UNKNOWN`: a request that may or may not
  have been applied. It is never sent again.
- `TriCore.address()`.

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
