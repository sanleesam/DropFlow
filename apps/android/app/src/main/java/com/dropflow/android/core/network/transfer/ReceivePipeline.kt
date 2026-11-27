package com.dropflow.android.core.network.transfer

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Where a receive session stores its files. StorageManager on a device; a folder in tests. */
interface ReceiveStorage {
    /** True when every prepared target already owns its final name, so publishing order is free. */
    val publishesInAnyOrder: Boolean

    /** Allocates storage that stays invisible until [completePendingFile]. */
    fun prepareTargetStream(fileName: String): WritableTarget

    /** Publishes already closed and verified data, without replacing another file. */
    fun completePendingFile(target: WritableTarget)

    /** Removes only storage allocated by this transfer. */
    fun cleanupTarget(target: WritableTarget)
}

/** A storage problem on this device, with the short reason the sender is told. */
internal fun storageFailure(error: Throwable): LocalFailureException = when {
    error is LocalFailureException -> error
    isOutOfSpace(error) ->
        LocalFailureException("Not enough storage space on this device", "not enough storage space", error)
    else -> LocalFailureException(error.message ?: "Could not save the received files", "it could not save the files", error)
}

/**
 * Keeps slow storage calls off the network thread.
 *
 * Creating a target, and closing and syncing a finished file, each cost
 * several milliseconds on shared storage. Targets are prepared a few files
 * ahead of the stream, and finished files are flushed, synced and closed in
 * the background. Nothing is published until [awaitDurable] has returned, and
 * [discard] removes everything this session allocated.
 */
internal class ReceivePipeline(
    private val storage: ReceiveStorage,
    private val paths: List<String>
) {
    private val prepareWorkers = workers("dropflow-rx-prepare", PREPARE_THREADS)
    private val finishWorkers = workers("dropflow-rx-finish", FINISH_THREADS)

    private val prepared = arrayOfNulls<Future<WritableTarget>>(paths.size)
    private val taken = arrayOfNulls<WritableTarget>(paths.size)
    private val finishing = ArrayList<Future<*>>()
    private val finishSlots = Semaphore(MAX_FINISHING)
    // The last target queued for each name, so equal names are allocated in manifest order.
    private val sameName = HashMap<String, Future<WritableTarget>>()
    private val failure = AtomicReference<Throwable?>(null)
    private var submitted = 0
    private var lastTick = System.nanoTime()

    @Volatile
    private var discarded = false

    /** The target for file [index]. [tick] runs while this waits and may throw to stop. */
    fun take(index: Int, tick: () -> Unit): WritableTarget {
        failure.get()?.let { throw storageFailure(it) }
        val ahead = minOf(paths.size.toLong(), index + 1L + LOOK_AHEAD).toInt()
        while (submitted < ahead) submitPrepare(submitted++)
        val target = await(checkNotNull(prepared[index]), tick)
        prepared[index] = null
        taken[index] = target
        return target
    }

    private fun submitPrepare(index: Int) {
        val path = paths[index]
        val key = path.lowercase()
        val earlier = sameName[key]
        val task = prepareWorkers.submit<WritableTarget> {
            // Started tasks run in submission order, so this never waits on a later one.
            earlier?.let { runCatching { it.get() } }
            check(!discarded) { "Transfer stopped" }
            val target = storage.prepareTargetStream(path)
            if (discarded) {
                // Nobody will collect this target any more.
                storage.cleanupTarget(target)
                error("Transfer stopped")
            }
            target
        }
        sameName[key] = task
        prepared[index] = task
    }

    /** Hands a completely written file to the background to be flushed, synced and closed. */
    fun finish(index: Int, tick: () -> Unit) {
        failure.get()?.let { throw storageFailure(it) }
        val target = checkNotNull(taken[index])
        // Bounds the files that are open at once when storage is slower than the network.
        while (!finishSlots.tryAcquire(WAIT_STEP_MS, TimeUnit.MILLISECONDS)) pace(tick)
        finishing += try {
            finishWorkers.submit {
                try {
                    target.stream.flush()
                    target.stream.fd.sync()
                    target.stream.close()
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                    throw error
                } finally {
                    finishSlots.release()
                }
            }
        } catch (error: Throwable) {
            finishSlots.release()
            throw error
        }
    }

    /** Returns once every finished file is on disk. Throws if any of them could not be saved. */
    fun awaitDurable(tick: () -> Unit) {
        finishing.forEach { await(it, tick) }
        finishing.clear()
        failure.get()?.let { throw storageFailure(it) }
    }

    /** Makes every file visible. Call only after [awaitDurable] and after the whole session verified. */
    fun publishAll(tick: () -> Unit) {
        val targets = taken.map { checkNotNull(it) }
        if (storage.publishesInAnyOrder) {
            targets.map { target -> finishWorkers.submit { storage.completePendingFile(target) } }
                .forEach { await(it, tick) }
        } else {
            targets.forEach { target ->
                pace(tick)
                try {
                    storage.completePendingFile(target)
                } catch (error: Exception) {
                    throw storageFailure(error)
                }
            }
        }
    }

    /** After a successful session. */
    fun close() {
        prepareWorkers.shutdown()
        finishWorkers.shutdown()
    }

    /** After a failed or cancelled session: stops the workers and removes everything allocated. */
    fun discard() {
        discarded = true
        prepareWorkers.shutdownNow()
        finishWorkers.shutdownNow()
        runCatching {
            prepareWorkers.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS)
            finishWorkers.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS)
        }
        // A prepare task that is still running removes its own target when it ends.
        val leftovers = ArrayList<WritableTarget>()
        for (index in 0 until submitted) {
            val task = prepared[index] ?: continue
            if (task.isDone && !task.isCancelled) runCatching { task.get() }.getOrNull()?.let(leftovers::add)
        }
        taken.filterNotNullTo(leftovers)
        // Removing thousands of targets one by one keeps the device busy for a long time.
        val cleaners = workers("dropflow-rx-cleanup", FINISH_THREADS)
        try {
            leftovers.map { target -> cleaners.submit { storage.cleanupTarget(target) } }
                .forEach { task -> runCatching { task.get() } }
        } finally {
            cleaners.shutdown()
        }
    }

    /** Waits for [task]. [tick] runs about every [WAIT_STEP_MS], also when tasks finish quickly. */
    private fun <T> await(task: Future<T>, tick: () -> Unit): T {
        while (true) {
            pace(tick)
            try {
                return task.get(WAIT_STEP_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // Not done yet.
            } catch (error: ExecutionException) {
                throw storageFailure(error.cause ?: error)
            }
        }
    }

    private fun pace(tick: () -> Unit) {
        val now = System.nanoTime()
        if (now - lastTick >= WAIT_STEP_MS * 1_000_000) {
            lastTick = now
            tick()
        }
    }

    private fun workers(name: String, count: Int): ExecutorService {
        val next = AtomicInteger()
        return Executors.newFixedThreadPool(count) { task ->
            Thread(task, "$name-${next.incrementAndGet()}").apply { isDaemon = true }
        }
    }

    private companion object {
        const val PREPARE_THREADS = 3
        const val FINISH_THREADS = 3
        // Targets prepared ahead of the file being received.
        const val LOOK_AHEAD = 24
        // Finished files waiting to be synced and closed.
        const val MAX_FINISHING = 32
        const val WAIT_STEP_MS = 100L
        const val STOP_WAIT_SECONDS = 10L
    }
}
