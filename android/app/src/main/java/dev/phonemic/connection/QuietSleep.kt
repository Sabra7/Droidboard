package dev.phonemic.connection

/**
 * Waits [ms] between connection attempts. When stop() interrupts the thread the wait just ends early:
 * the caller's loop checks for itself whether it should keep going.
 */
fun sleepQuietly(ms: Long) {
    try { Thread.sleep(ms) } catch (_: InterruptedException) {}
}
