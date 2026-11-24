package com.linguareader.app.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One shared instance per owner serializes both vocabulary IO and Main publication. Every
 * mutation and load/publish path must use that same instance. The publisher is non-suspending;
 * notifications/callbacks belong after this function returns. Exceptions and cancellation
 * propagate without converting a cancelled request into a successful publication.
 */
class VocabularyStateTransactions(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val publicationDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {
    private val mutex = Mutex()

    suspend fun <T> withLock(transaction: suspend () -> T, publish: (T) -> Unit): T = mutex.withLock {
        val result = withContext(ioDispatcher) { transaction() }
        withContext(publicationDispatcher) { publish(result) }
        result
    }
}
