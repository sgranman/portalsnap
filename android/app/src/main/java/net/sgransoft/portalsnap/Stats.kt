package net.sgransoft.portalsnap

const val TAG = "PSNAP"

/** The last [cap] samples, for medians and tails. Written by one thread, read by another. */
class Rolling(private val cap: Int = 400) {
    private val v = DoubleArray(cap)
    private var n = 0
    private var i = 0

    @Synchronized fun add(x: Double) {
        v[i] = x
        i = (i + 1) % cap
        if (n < cap) n++
    }

    @Synchronized fun pct(p: Double): Double {
        if (n == 0) return 0.0
        val s = v.copyOf(n)
        s.sort()
        return s[((n - 1) * p).toInt()]
    }

    @Synchronized fun clear() {
        n = 0
        i = 0
    }
}

/** Events per second over a trailing window, for the live HUD. */
class RateMeter(private val windowMs: Long = 2000) {
    private val t = ArrayDeque<Long>()

    @Synchronized fun tick() {
        val now = nowMs()
        t.addLast(now)
        trim(now)
    }

    @Synchronized fun fps(): Double {
        trim(nowMs())
        return t.size * 1000.0 / windowMs
    }

    @Synchronized fun clear() = t.clear()

    private fun trim(now: Long) {
        while (t.isNotEmpty() && now - t.first() > windowMs) t.removeFirst()
    }
}

/** A plain event count, for rates over a whole bench phase rather than a window. */
class Counter {
    @Volatile var n = 0L
        private set

    fun inc() { n++ }
}

fun nowMs() = System.nanoTime() / 1_000_000

fun r1(x: Double) = Math.round(x * 10) / 10.0
