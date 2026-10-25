package io.kandra.ktor

import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.runTestApplication
import io.ktor.test.dispatcher.runTestWithRealTime
import kotlinx.coroutines.test.TestResult
import kotlin.time.Duration.Companion.minutes

/**
 * [io.ktor.server.testing.testApplication] with a 10-minute budget. Ktor 3.6's `testApplication`
 * calls `runTestWithRealTime(timeout = 60.seconds)` and exposes no parameter, and the kotlinx
 * `default_timeout` property does not apply to an explicit timeout. `install(Kandra)` is real DDL:
 * 30-200 s against a WAN cluster (1-5 s per statement with schema agreement; ScyllaDB Cloud,
 * 2026-10-25), and 39-73 s for the two installs of `AutoCreateRedeployTest` on the Docker
 * container depending on machine load. A hang still fails, just later.
 */
internal fun kandraTestApplication(block: suspend ApplicationTestBuilder.() -> Unit): TestResult =
    runTestWithRealTime(timeout = 10.minutes) { runTestApplication(block = block) }
