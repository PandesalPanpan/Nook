# Native local search measurement

Measured on the existing API 36 emulator, debug APK, in-memory Room database. Fixture: 2,000 Tasks under one Project and Area (2,002 records). Query: `project:BenchmarkProject area:BenchmarkArea needle`. Each sample returns the existing 100-result limit. One warm-up precedes five timed queries.

| Implementation | Warm median | Five samples (ms) |
| --- | ---: | --- |
| Original repeated reference scan and FTS join | 2143.75 ms | 2081.87, 2117.25, 2143.75, 2196.85, 2428.75 |
| Reference sets once, original FTS join | 1898.48 ms | 1831.28, 1877.35, 1898.48, 1920.21, 2067.29 |
| Reference sets once, matching-ID subquery | 227.02 ms | 188.42, 189.00, 227.02, 232.21, 235.69 |

The measured improvement is approximately 9.4 times for this workload. Profiling after the reference-set change attributed 1736.86 ms to the FTS join, 13.88 ms to parent reads and 131.58 ms to decoding. The matching-ID subquery measured 22.49 ms for FTS/record reads, 15.13 ms for parent reads and 127.56 ms for decoding.

The original plan scanned the account's records first and executed the FTS scan inside that loop. The replacement plan materializes matching IDs and retrieves records through the `(accountId, id)` primary key. Both subquery and record lookup constrain the account, and deleted records remain excluded. Empty-text searchable-record reads use the same matching-ID structure. Context reference sets are built once per query; no database schema or record migration is required.

Evidence: `artifacts/performance/native-search-before.txt`, `native-search-stages-before-query.txt`, and `native-search-after.txt`. Reproduce with `SearchPerformanceTest`; no timing threshold is used as a flaky correctness gate. Native JVM suites and actual offline lifecycle/live-search instrumentation also pass.

This is a warm, debug, in-memory emulator measurement. Cold startup, disk-backed databases, larger mixed accounts, release hardware and attachment-transfer performance remain to measure. It does not establish whole-product performance acceptance.
