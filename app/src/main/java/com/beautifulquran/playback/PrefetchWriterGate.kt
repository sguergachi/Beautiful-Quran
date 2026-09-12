package com.beautifulquran.playback

/**
 * One generation of blocking [androidx.media3.datasource.cache.CacheWriter]
 * work. [kotlinx.coroutines.Job.cancel] does not interrupt `CacheWriter.cache()`,
 * so supersession has to cancel the writers themselves.
 *
 * Register and the cancelled check share one lock so a writer cannot start
 * after this generation has been superseded — the same race RecitationDownloads
 * closes around its single active writer.
 */
internal class PrefetchWriterGate {
    private val lock = Any()
    private var cancelled = false
    private val onCancel = ArrayList<() -> Unit>(2)

    fun cancel() {
        val snapshot = synchronized(lock) {
            cancelled = true
            val running = onCancel.toList()
            onCancel.clear()
            running
        }
        snapshot.forEach { it() }
    }

    /** False if this generation was already cancelled; [work] is then skipped. */
    fun run(cancelWork: () -> Unit, work: () -> Unit): Boolean {
        synchronized(lock) {
            if (cancelled) return false
            onCancel += cancelWork
        }
        try {
            work()
            return true
        } finally {
            synchronized(lock) { onCancel.remove(cancelWork) }
        }
    }
}
