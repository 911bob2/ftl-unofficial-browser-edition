package xyz.znix.xftl.hs.events

/**
 * `std::unordered_map<std::string, V>` as Hyperspace's Windows build has it (MinGW i686, libstdc++): the same hash
 * (`std::_Hash_bytes`, MurmurHash2 with seed 0xc70f6907, 32-bit), the same prime rehash policy (13 buckets on the
 * first insert) and the same node list, so iteration visits the entries in the order HS does. HS iterates
 * `TriggeredEvent::eventList` this way every frame, and the order decides which triggered event loads first and the
 * order of their random draws.
 *
 * Iteration is by [Node]: [first], [Node.next], and [erase] returns the next node, like `it = map.erase(it)`.
 */
class HsGnuStringMap<V : Any> {
    class Node<V : Any> internal constructor(val key: String, internal val hash: Int, value: V?) {
        @Suppress("UNCHECKED_CAST")
        val value: V get() = valueOrNull as V
        internal var valueOrNull: V? = value
        internal var next: Node<V>? = null
        fun next(): Node<V>? = next
    }

    /** `_M_before_begin`: the list head; buckets point at the node before their first node. */
    private val beforeBegin = Node<V>("", 0, null)
    private var buckets = arrayOfNulls<Node<V>>(1)
    private var nextResize = 0L
    var size = 0
        private set

    val first: Node<V>? get() = beforeBegin.next

    val bucketCount: Int get() = buckets.size

    fun isEmpty() = size == 0

    private fun bucket(hash: Int): Int = Integer.remainderUnsigned(hash, buckets.size)

    private fun findNode(key: String, hash: Int): Node<V>? {
        if (size == 0) return null
        val prev = buckets[bucket(hash)] ?: return null
        var n = prev.next
        val bkt = bucket(hash)
        while (n != null) {
            if (n.hash == hash && n.key == key) return n
            val nn = n.next ?: return null
            if (bucket(nn.hash) != bkt) return null
            n = nn
        }
        return null
    }

    operator fun get(key: String): V? = findNode(key, hash(key))?.valueOrNull

    fun containsKey(key: String) = findNode(key, hash(key)) != null

    /** `emplace`: inserts when the key is new (and returns true); an existing entry is kept, as emplace does. */
    fun emplace(key: String, value: V): Boolean {
        val code = hash(key)
        if (findNode(key, code) != null) return false
        val node = Node(key, code, value)
        val rehash = needRehash(buckets.size.toLong(), size.toLong(), 1)
        if (rehash != 0L) rehash(rehash.toInt())
        insertBucketBegin(bucket(code), node)
        size++
        return true
    }

    /** `erase(key)`. */
    fun remove(key: String): Boolean {
        val n = findNode(key, hash(key)) ?: return false
        erase(n)
        return true
    }

    /** `erase(iterator)`: the node after it. */
    fun erase(n: Node<V>): Node<V>? {
        val bkt = bucket(n.hash)
        var prev = buckets[bkt]!!
        while (prev.next !== n) prev = prev.next!!
        val next = n.next
        if (prev === buckets[bkt]) {
            removeBucketBegin(bkt, next, if (next != null) bucket(next.hash) else 0)
        } else if (next != null) {
            val nextBkt = bucket(next.hash)
            if (nextBkt != bkt) buckets[nextBkt] = prev
        }
        prev.next = next
        size--
        return next
    }

    fun clear() {
        beforeBegin.next = null
        java.util.Arrays.fill(buckets, null)
        size = 0
    }

    fun entries(): List<Node<V>> {
        val out = ArrayList<Node<V>>(size)
        var n = first
        while (n != null) { out.add(n); n = n.next }
        return out
    }

    private fun insertBucketBegin(bkt: Int, node: Node<V>) {
        val head = buckets[bkt]
        if (head != null) {
            node.next = head.next
            head.next = node
        } else {
            node.next = beforeBegin.next
            beforeBegin.next = node
            node.next?.let { buckets[bucket(it.hash)] = node }
            buckets[bkt] = beforeBegin
        }
    }

    private fun removeBucketBegin(bkt: Int, next: Node<V>?, nextBkt: Int) {
        if (next == null || nextBkt != bkt) {
            if (next != null) buckets[nextBkt] = buckets[bkt]
            if (buckets[bkt] === beforeBegin) beforeBegin.next = next
            buckets[bkt] = null
        }
    }

    /** `_M_rehash_aux` (unique keys). */
    private fun rehash(n: Int) {
        val newBuckets = arrayOfNulls<Node<V>>(n)
        var p = beforeBegin.next
        beforeBegin.next = null
        var bbeginBkt = 0
        while (p != null) {
            val next = p.next
            val bkt = Integer.remainderUnsigned(p.hash, n)
            val head = newBuckets[bkt]
            if (head == null) {
                p.next = beforeBegin.next
                beforeBegin.next = p
                newBuckets[bkt] = beforeBegin
                if (p.next != null) newBuckets[bbeginBkt] = p
                bbeginBkt = bkt
            } else {
                p.next = head.next
                head.next = p
            }
            p = next
        }
        buckets = newBuckets
    }

    /** `_Prime_rehash_policy::_M_need_rehash` (max load factor 1): the new bucket count, or 0. */
    private fun needRehash(nBkt: Long, nElt: Long, nIns: Long): Long {
        if (nElt + nIns <= nextResize) return 0
        val minBkts = maxOf(nElt + nIns, if (nextResize != 0L) 0L else 11L).toDouble()
        if (minBkts >= nBkt) return nextBkt(maxOf(Math.floor(minBkts).toLong() + 1, nBkt * 2))
        nextResize = nBkt
        return 0
    }

    /** `_Prime_rehash_policy::_M_next_bkt`. */
    private fun nextBkt(n: Long): Long {
        if (n < FAST_BKT.size) {
            if (n == 0L) return 1
            nextResize = FAST_BKT[n.toInt()].toLong()
            return nextResize
        }
        val p = PRIMES.firstOrNull { it >= n } ?: error("HsGnuStringMap: more entries than modelled")
        nextResize = p
        return p
    }

    companion object {
        private val FAST_BKT = intArrayOf(2, 2, 2, 3, 5, 5, 7, 7, 11, 11, 11, 11, 13, 13)

        /** The head of libstdc++'s `__prime_list` from 13 on (lower_bound starts at index 6, 17). */
        private val PRIMES = longArrayOf(17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71, 73, 79, 83, 89, 97,
            103, 109, 113, 127, 137, 139, 149, 157, 167, 179, 193, 199, 211, 227, 241, 257, 277, 293, 313, 337, 359, 383,
            409, 439, 467, 503, 541, 577, 619, 661, 709, 761, 823, 887, 953, 1031)

        /** `std::_Hash_bytes(s.data(), s.size(), 0xc70f6907)` for a 32-bit size_t (MurmurHash2). */
        @JvmStatic
        fun hash(key: String): Int {
            val b = key.toByteArray(Charsets.UTF_8)
            val m = 0x5bd1e995
            var len = b.size
            var h = 0xc70f6907.toInt() xor len
            var i = 0
            while (len >= 4) {
                var k = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
                    ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
                k *= m
                k = k xor (k ushr 24)
                k *= m
                h *= m
                h = h xor k
                i += 4
                len -= 4
            }
            if (len >= 3) h = h xor ((b[i + 2].toInt() and 0xff) shl 16)
            if (len >= 2) h = h xor ((b[i + 1].toInt() and 0xff) shl 8)
            if (len >= 1) {
                h = h xor (b[i].toInt() and 0xff)
                h *= m
            }
            h = h xor (h ushr 13)
            h *= m
            h = h xor (h ushr 15)
            return h
        }
    }
}
