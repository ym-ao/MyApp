package com.example.myapp.automation

/** One drag: pick up the tile at box [from] and drop it on box [to]. */
data class Move(
    val from: Int,
    val to: Int,
    val note: String,
    /** True for merges: after the drag, the source box should be empty. */
    val expectSourceEmpty: Boolean = false
)

enum class BoardAction { SORT, MERGE }

/**
 * Works out which drags to make. Pure logic: it never touches the screen.
 *
 * The board is described as one number per box, in box-number order:
 *   > 0  a tile that was read confidently (its level)
 *   0    an empty square
 *   -1   a tile we couldn't read confidently. It is left alone: never picked
 *        up, never dropped onto, and never counted on.
 */
object BoardPlanner {

    const val EMPTY = 0
    const val UNKNOWN = -1

    /**
     * Highest level in the first box, lowest at the end, empty squares last.
     * Boxes holding unreadable tiles stay where they are; the rest is sorted
     * around them.
     *
     * If [swapSupported], dropping a tile on a different tile swaps the two
     * (one drag per fix). If not, each swap is done through a spare empty square
     * (three drags). A drop onto an equal tile is never used, because that would merge.
     */
    fun planSort(ids: List<Int>, values: IntArray, swapSupported: Boolean): List<Move> {
        val n = ids.size
        val cur = values.copyOf()
        val free = (0 until n).filter { cur[it] != UNKNOWN }
        val tiles = free.map { cur[it] }.filter { it > 0 }.sortedDescending()

        val target = cur.copyOf()
        free.forEachIndexed { k, p -> target[p] = if (k < tiles.size) tiles[k] else 0 }

        val moves = mutableListOf<Move>()
        for (k in free.indices) {
            val p = free[k]
            if (cur[p] == target[p]) continue
            val want = target[p]
            if (want <= 0) continue

            // a tile of the wanted level that is itself out of place, further along
            var src = -1
            for (kk in k + 1 until free.size) {
                val q = free[kk]
                if (cur[q] == want && target[q] != want) {
                    src = q
                    break
                }
            }
            if (src == -1) continue

            if (cur[p] == 0) {
                moves.add(Move(ids[src], ids[p], "move the $want"))
                cur[p] = want
                cur[src] = 0
            } else if (swapSupported) {
                moves.add(Move(ids[src], ids[p], "swap, puts the $want here"))
                cur[src] = cur[p]
                cur[p] = want
            } else {
                val spare = (0 until n).firstOrNull { cur[it] == 0 && it != p && it != src } ?: continue
                moves.add(Move(ids[p], ids[spare], "park the ${cur[p]} on a spare square"))
                moves.add(Move(ids[src], ids[p], "move the $want"))
                moves.add(Move(ids[spare], ids[src], "bring the parked ${cur[p]} back"))
                cur[src] = cur[p]
                cur[p] = want
            }
        }
        return moves
    }

    /**
     * Takes the lowest tile (last in box order if tied) that has a twin, merges it onto
     * the twin with the lowest box number, and that box stays the destination: while another
     * tile of the new level exists (taken from the end of the board), it is dragged
     * onto the destination too. A lowest tile with no twin is skipped in favour of the next-lowest.
     */
    fun planMerge(ids: List<Int>, values: IntArray): List<Move> {
        val n = ids.size
        val cur = values.copyOf()
        val order = (0 until n).filter { cur[it] > 0 }
            .sortedWith(compareBy<Int>({ cur[it] }, { -it }))

        val moves = mutableListOf<Move>()
        for (s in order) {
            val d = (0 until n).firstOrNull { it != s && cur[it] == cur[s] } ?: continue
            val first = cur[s]
            moves.add(Move(ids[s], ids[d], "merge two $first into a ${first + 1}", true))
            cur[d] = first + 1
            cur[s] = 0

            var guard = 0
            while (guard < 200 && cur[d] < 99) {
                guard++
                val level = cur[d]
                val q = (n - 1 downTo 0).firstOrNull { it != d && cur[it] == level } ?: break
                moves.add(Move(ids[q], ids[d], "merge two $level into a ${level + 1}", true))
                cur[d] = level + 1
                cur[q] = 0
            }
            break
        }
        return moves
    }
}
