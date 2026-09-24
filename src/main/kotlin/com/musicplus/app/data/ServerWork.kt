package com.musicplus.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlin.coroutines.coroutineContext

/**
 * Background work that belongs to one server, so removing that server can stop it. A refresh or a capability probe that is still
 * running when its server is removed would otherwise write the server's rows, sync time or capabilities back after they were
 * forgotten; a fixed sleep and a second sweep used to stand in for this.
 *
 * Work is run with [run] under the id of the server it is for, and [stop] cancels everything running for that id and returns once it
 * has finished, so nothing of it can land afterwards.
 */
class ServerWork {
    private val lock = Any()
    private val running = HashMap<String, MutableSet<Deferred<*>>>()

    /**
     * Runs [block] for [serverId] and returns its result, or null if [stop] cancelled it first. The caller is not itself cancelled by a
     * stop (it carries on with whatever it does next, e.g. the next server), and a cancellation of the caller propagates as usual.
     */
    suspend fun <T : Any> run(serverId: String, block: suspend () -> T): T? = coroutineScope {
        val work = async(start = CoroutineStart.LAZY) { block() }
        synchronized(lock) { running.getOrPut(serverId) { HashSet() } += work }
        try {
            work.await()
        } catch (e: CancellationException) {
            // The work was stopped, not us: tell the two apart by whether our own scope is still alive.
            if (!coroutineContext.isActive) throw e
            null
        } finally {
            synchronized(lock) { running[serverId]?.let { it -= work; if (it.isEmpty()) running.remove(serverId) } }
        }
    }

    /** Cancels everything running for [serverId] and waits until it has stopped. */
    suspend fun stop(serverId: String) {
        val jobs = synchronized(lock) { running[serverId]?.toList() } ?: return
        jobs.forEach { it.cancel() }
        jobs.joinAll()
    }
}
