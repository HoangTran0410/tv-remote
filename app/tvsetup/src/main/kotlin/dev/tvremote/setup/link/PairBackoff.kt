package dev.tvremote.setup.link

/**
 * How long a peer must wait before the code screen goes up on the TV again. Per peer, so somebody on the network burning
 * asks cannot lock out the person at the TV: the first [FREE] code screens are free, then 30 s, 1, 2, 4 and 5 min.
 * Peers are forgotten after [FORGET_MS] and at most [MAX] are kept, so the map cannot grow.
 */
class PairBackoff {
    private class Peer(var asked: Int, var shown: Long)
    private val peers = LinkedHashMap<String, Peer>()

    /** Milliseconds [peer] still has to wait, 0 when the screen may go up. */
    @Synchronized fun wait(peer: String, now: Long): Long {
        prune(now)
        val p = peers[peer] ?: return 0
        val w = if (p.asked < FREE) 0L else minOf(30000L shl (p.asked - FREE).coerceAtMost(4), 5 * 60000L)
        val age = now - p.shown
        return if (age < 0) 0L else maxOf(0L, w - age)            // a clock set back: a mark ahead of it is stale, not a wait of years
    }

    /** The screen went up for [peer]. */
    @Synchronized fun shown(peer: String, now: Long) {
        prune(now)
        val p = peers.remove(peer) ?: Peer(0, now)
        p.asked++; p.shown = now
        peers[peer] = p                                           // newest last
        while (peers.size > MAX) peers.remove(peers.keys.first())
    }

    /** [peer] paired for real: it starts fresh. */
    @Synchronized fun paired(peer: String) { peers.remove(peer) }

    @Synchronized fun size(): Int = peers.size

    private fun prune(now: Long) { peers.values.removeAll { it.shown > now || now - it.shown > FORGET_MS } }

    companion object {
        const val FREE = 3
        const val MAX = 64
        const val FORGET_MS = 15 * 60000L
    }
}
