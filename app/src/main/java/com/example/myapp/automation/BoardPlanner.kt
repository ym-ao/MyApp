package com.example.myapp.automation

import com.example.myapp.model.GridProfile
import com.example.myapp.model.ReadStatus
import com.example.myapp.model.Reading

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
 * The board as the app currently believes it to be. It is built from one scan, then
 * updated in place as moves are made, so later steps don't need another scan.
 */
class BoardState(
    val ids: List<Int>,
    val values: IntArray,
    val points: Map<Int, Pair<Float, Float>>,
    val known: Int,
    val unknown: Int
) {
    /** Boxes that should have no tile on screen right now. */
    fun expectedEmptyIds(): Set<Int> =
        ids.filterIndexed { i, _ -> values[i] == BoardPlanner.EMPTY }.toSet()

    companion object {
        fun build(profile: GridProfile, width: Int, height: Int, readings: List<Reading>): BoardState {
            val sx = width.toFloat() / profile.screenWidth
            val sy = height.toFloat() / profile.screenHeight
            val boxes = profile.allBoxes()
            val points = boxes.associate { it.id to Pair(it.x * sx, it.y * sy) }
            val ids = boxes.map { it.id }.sorted()
            val byId = readings.associateBy { it.id }
            val values = IntArray(ids.size) { i ->
                val r = byId[ids[i]]
                when {
                    r == null -> BoardPlanner.UNKNOWN
                    r.status == ReadStatus.EMPTY -> BoardPlanner.EMPTY
                    r.status == ReadStatus.READ -> r.value ?: BoardPlanner.UNKNOWN
                    else -> BoardPlanner.UNKNOWN
                }
            }
            return BoardState(ids, values, points, values.count { it > 0 }, values.count { it == BoardPlanner.UNKNOWN })
        }
    }
}

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

    /**
     * Updates [values] as if [moves] had been performed: a drop on an empty square moves the
     * tile, a drop on an equal tile (in a merge) makes one tile a level higher, any other
     * drop swaps the two.
     */
    fun applyMoves(ids: List<Int>, values: IntArray, moves: List<Move>) {
        val index = HashMap<Int, Int>()
        ids.forEachIndexed { p, id -> index[id] = p }
        for (m in moves) {
            val a = index[m.from] ?: continue
            val b = index[m.to] ?: continue
            val va = values[a]
            val vb = values[b]
            if (va <= 0) continue
            when {
                vb == 0 -> {
                    values[b] = va
                    values[a] = 0
                }
                vb > 0 && vb == va && m.expectSourceEmpty -> {
                    values[b] = vb + 1
                    values[a] = 0
                }
                vb > 0 -> {
                    values[a] = vb
                    values[b] = va
                }
            }
        }
    }
}
