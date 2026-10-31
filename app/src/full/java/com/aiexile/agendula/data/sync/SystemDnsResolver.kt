package com.aiexile.agendula.data.sync

import android.net.DnsResolver as PlatformDnsResolver
import android.os.CancellationSignal
import android.os.Looper
import com.aiexile.caldav.DnsAnswers
import com.aiexile.caldav.DnsRecords
import com.aiexile.caldav.DnsResolver
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * SRV/TXT lookups through the system resolver, so discovery uses the current
 * network's DNS and honours Private DNS rather than sending plain queries of
 * its own. A failed or timed-out lookup reads as no records.
 *
 * The platform delivers answers through the main looper, so this must run on a
 * worker thread; waiting on the main one would deadlock until the timeout.
 * The wait gives up as soon as [isCancelled] says so, and answered lookups are
 * kept briefly so the authenticated walk does not ask again.
 */
class SystemDnsResolver(
    private val isCancelled: () -> Boolean = { false },
) : DnsResolver {

    override fun lookup(name: String): DnsRecords {
        check(!Looper.getMainLooper().isCurrentThread) { "DNS lookup on the main thread" }
        val now = System.nanoTime()
        cache[name]?.takeIf { now - it.at < CACHE_NANOS }?.let { return it.records }
        val srv = Query(name, DnsAnswers.TYPE_SRV)
        val txt = Query(name, DnsAnswers.TYPE_TXT)
        val answered = try {
            val deadline = now + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            srv.await(deadline, isCancelled) && txt.await(deadline, isCancelled)
        } finally {
            srv.cancel()
            txt.cancel()
        }
        val records = DnsRecords(
            srv.answer()?.let(DnsAnswers::srv).orEmpty(),
            txt.answer()?.let(DnsAnswers::txt).orEmpty(),
        )
        if (answered) cache[name] = Cached(now, records)
        return records
    }

    private class Cached(val at: Long, val records: DnsRecords)

    private class Query(name: String, type: Int) {
        private val done = CountDownLatch(1)
        private val signal = CancellationSignal()
        private val result = AtomicReference<ByteArray?>()

        init {
            try {
                PlatformDnsResolver.getInstance().rawQuery(
                    null,
                    name,
                    PlatformDnsResolver.CLASS_IN,
                    type,
                    PlatformDnsResolver.FLAG_EMPTY,
                    Runnable::run,
                    signal,
                    object : PlatformDnsResolver.Callback<ByteArray> {
                        override fun onAnswer(answer: ByteArray, rcode: Int) {
                            result.set(answer)
                            done.countDown()
                        }

                        override fun onError(error: PlatformDnsResolver.DnsException) {
                            done.countDown()
                        }
                    },
                )
            } catch (e: RuntimeException) {
                done.countDown()
            }
        }

        /** Whether the query finished before [deadline] and before [isCancelled]. */
        fun await(deadline: Long, isCancelled: () -> Boolean): Boolean {
            while (!isCancelled()) {
                val left = deadline - System.nanoTime()
                if (left <= 0) return false
                if (done.await(minOf(left, SLICE_NANOS), TimeUnit.NANOSECONDS)) return true
            }
            return false
        }

        fun cancel() = signal.cancel()

        fun answer(): ByteArray? = result.get()
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        val SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(100)
        val CACHE_NANOS = TimeUnit.MINUTES.toNanos(1)
        val cache = ConcurrentHashMap<String, Cached>()
    }
}
