package io.kandra.runtime.driver

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.api.core.cql.Statement
import kotlinx.coroutines.future.await

/** Truly async prepare — avoids blocking a coroutine dispatcher thread on the first prepare call. */
suspend fun CqlSession.prepareSuspend(cql: String): PreparedStatement =
    prepareAsync(cql).toCompletableFuture().await()

suspend fun CqlSession.executeSuspend(statement: Statement<*>): AsyncResultSet =
    executeAsync(statement).toCompletableFuture().await()

suspend fun CqlSession.executeSuspendAll(statement: Statement<*>): List<Row> {
    val rows = mutableListOf<Row>()
    var resultSet = executeAsync(statement).toCompletableFuture().await()
    rows.addAll(resultSet.currentPage())
    while (resultSet.hasMorePages()) {
        resultSet = resultSet.fetchNextPage().toCompletableFuture().await()
        rows.addAll(resultSet.currentPage())
    }
    return rows
}

/**
 * Like [executeSuspendAll], but stops fetching further pages once [cap] rows have been collected —
 * used by [io.kandra.runtime.QueryExecutor]'s unpaged read paths (`findAll`/`find`/`exists`'s IN and
 * direct-CQL branches) to bound in-memory materialization (see ISS-066 / GH #67). May return slightly
 * more than [cap] rows (whatever the last fetched page contained) — the caller truncates to the exact
 * cap and decides how to warn.
 */
suspend fun CqlSession.executeSuspendUpTo(statement: Statement<*>, cap: Int): List<Row> {
    val rows = mutableListOf<Row>()
    var resultSet = executeAsync(statement).toCompletableFuture().await()
    rows.addAll(resultSet.currentPage())
    while (rows.size <= cap && resultSet.hasMorePages()) {
        resultSet = resultSet.fetchNextPage().toCompletableFuture().await()
        rows.addAll(resultSet.currentPage())
    }
    return rows
}
