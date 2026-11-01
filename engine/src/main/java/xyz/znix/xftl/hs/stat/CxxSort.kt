// The algorithm follows GCC libstdc++ bits/stl_algo.h (GPL-3.0-or-later WITH GCC-exception-3.1).
package xyz.znix.xftl.hs.stat

/**
 * `std::sort` as GCC's libstdc++ implements it (introsort: median-of-three quicksort above 16 elements, heap sort at
 * the depth limit, final insertion sort). Hyperspace is built with MinGW GCC, and its stat code sorts boosts with an
 * unstable `std::sort` on priority, so boosts of equal priority end up in exactly this order. Kotlin's stable sort
 * would only agree for up to 16 elements.
 */
object CxxSort {
    private const val THRESHOLD = 16

    @JvmStatic
    fun <T> sort(a: MutableList<T>, less: (T, T) -> Boolean) {
        val n = a.size
        if (n < 2) return
        introsortLoop(a, 0, n, lg(n) * 2, less)
        finalInsertionSort(a, 0, n, less)
    }

    private fun lg(n: Int): Int = 31 - Integer.numberOfLeadingZeros(n)

    private fun <T> swap(a: MutableList<T>, i: Int, j: Int) {
        val t = a[i]; a[i] = a[j]; a[j] = t
    }

    private fun <T> introsortLoop(a: MutableList<T>, first: Int, lastIn: Int, depthIn: Int, less: (T, T) -> Boolean) {
        var last = lastIn
        var depth = depthIn
        while (last - first > THRESHOLD) {
            if (depth == 0) {
                heapSort(a, first, last, less)
                return
            }
            depth--
            val cut = partitionPivot(a, first, last, less)
            introsortLoop(a, cut, last, depth, less)
            last = cut
        }
    }

    private fun <T> partitionPivot(a: MutableList<T>, first: Int, last: Int, less: (T, T) -> Boolean): Int {
        val mid = first + (last - first) / 2
        moveMedianToFirst(a, first, first + 1, mid, last - 1, less)
        return unguardedPartition(a, first + 1, last, first, less)
    }

    private fun <T> moveMedianToFirst(a: MutableList<T>, result: Int, x: Int, y: Int, z: Int, less: (T, T) -> Boolean) {
        if (less(a[x], a[y])) {
            if (less(a[y], a[z])) swap(a, result, y)
            else if (less(a[x], a[z])) swap(a, result, z)
            else swap(a, result, x)
        } else if (less(a[x], a[z])) swap(a, result, x)
        else if (less(a[y], a[z])) swap(a, result, z)
        else swap(a, result, y)
    }

    private fun <T> unguardedPartition(a: MutableList<T>, firstIn: Int, lastIn: Int, pivot: Int, less: (T, T) -> Boolean): Int {
        var first = firstIn
        var last = lastIn
        while (true) {
            while (less(a[first], a[pivot])) first++
            last--
            while (less(a[pivot], a[last])) last--
            if (first >= last) return first
            swap(a, first, last)
            first++
        }
    }

    private fun <T> finalInsertionSort(a: MutableList<T>, first: Int, last: Int, less: (T, T) -> Boolean) {
        if (last - first > THRESHOLD) {
            insertionSort(a, first, first + THRESHOLD, less)
            for (i in first + THRESHOLD until last) unguardedLinearInsert(a, i, less)
        } else {
            insertionSort(a, first, last, less)
        }
    }

    private fun <T> insertionSort(a: MutableList<T>, first: Int, last: Int, less: (T, T) -> Boolean) {
        if (first == last) return
        for (i in first + 1 until last) {
            if (less(a[i], a[first])) {
                val v = a[i]
                for (k in i downTo first + 1) a[k] = a[k - 1]
                a[first] = v
            } else {
                unguardedLinearInsert(a, i, less)
            }
        }
    }

    private fun <T> unguardedLinearInsert(a: MutableList<T>, lastIn: Int, less: (T, T) -> Boolean) {
        var last = lastIn
        val v = a[last]
        var next = last - 1
        while (less(v, a[next])) {
            a[last] = a[next]
            last = next
            next--
        }
        a[last] = v
    }

    // std::__partial_sort(first, last, last): make_heap then sort_heap.
    private fun <T> heapSort(a: MutableList<T>, first: Int, last: Int, less: (T, T) -> Boolean) {
        val len = last - first
        if (len >= 2) {
            var parent = (len - 2) / 2
            while (true) {
                adjustHeap(a, first, parent, len, a[first + parent], less)
                if (parent == 0) break
                parent--
            }
        }
        var end = last
        while (end - first > 1) {
            end--
            val v = a[end]
            a[end] = a[first]
            adjustHeap(a, first, 0, end - first, v, less)
        }
    }

    private fun <T> adjustHeap(a: MutableList<T>, first: Int, holeIn: Int, len: Int, value: T, less: (T, T) -> Boolean) {
        var hole = holeIn
        val top = hole
        var child = hole
        while (child < (len - 1) / 2) {
            child = 2 * (child + 1)
            if (less(a[first + child], a[first + child - 1])) child--
            a[first + hole] = a[first + child]
            hole = child
        }
        if ((len and 1) == 0 && child == (len - 2) / 2) {
            child = 2 * (child + 1)
            a[first + hole] = a[first + child - 1]
            hole = child - 1
        }
        var parent = (hole - 1) / 2
        while (hole > top && less(a[first + parent], value)) {
            a[first + hole] = a[first + parent]
            hole = parent
            parent = (hole - 1) / 2
        }
        a[first + hole] = value
    }
}
