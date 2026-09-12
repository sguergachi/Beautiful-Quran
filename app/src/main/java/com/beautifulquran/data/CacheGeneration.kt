package com.beautifulquran.data

/** Coordinates derived-cache publication with invalidation, without locking during IO. */
internal class CacheGeneration {
    private var generation = 0L

    @Synchronized
    fun invalidate(clear: () -> Unit) {
        generation++
        clear()
    }

    /** A build overtaken by invalidation must retry, never return or retain its old snapshot. */
    fun <T : Any> getOrBuild(read: () -> T?, write: (T) -> Unit, build: () -> T): T {
        while (true) {
            val started = synchronized(this) {
                read()?.let { return it }
                generation
            }
            val value = build()
            synchronized(this) {
                if (started == generation) {
                    read()?.let { return it }
                    write(value)
                    return value
                }
            }
        }
    }
}
