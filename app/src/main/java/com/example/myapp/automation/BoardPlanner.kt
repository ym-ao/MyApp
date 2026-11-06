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

/** The drags for a sort, and how many squares could not be fixed (no empty square to work with). */
class SortPlan(val moves: List<Move>, val leftover: Int)

/** One planned merge, and the destination / carrier box to remember for the next one. */
class NextMerge(val move: Move, val newDest: Int?, val newCarrier: Int?)

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
     * Highest level in the first box, lowest at the end, empty squares last. Boxes holding
     * unreadable tiles stay where they are; the rest is sorted around them. The whole path is
     * worked out from one scan.
     *
     * With [swap] (dropping a tile on a tile of a different level swaps the two) each fix is one
     * drag. A drag is only ever aimed at an empty square or at a tile of a DIFFERENT level, never
     * at the same level, because that would merge them; the finished plan is checked for this
     * before it is used. Without [swap], every drag lands on an empty square.
     */
    fun planSort(ids: List<Int>, values: IntArray, swap: Boolean): SortPlan {
        if (swap) {
            val plan = planSortSwap(ids, values)
            if (isSafe(ids, values, plan.moves)) return plan
        }
        return planSortEmptyOnly(ids, values)
    }

    private fun planSortSwap(ids: List<Int>, values: IntArray): SortPlan {
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
            } else {
                // cur[p] differs from want here (otherwise it would be in place), so this
                // drop is onto a tile of a different level and cannot merge
                moves.add(Move(ids[src], ids[p], "swap the $want with the ${cur[p]}"))
                cur[src] = cur[p]
                cur[p] = want
            }
        }
        return SortPlan(moves, 0)
    }

    /**
     * True when no move drops a tile onto an unreadable tile or onto a tile of the same
     * level (which would merge them), and every move picks up a tile.
     */
    fun isSafe(ids: List<Int>, values: IntArray, moves: List<Move>): Boolean {
        val index = HashMap<Int, Int>()
        ids.forEachIndexed { p, id -> index[id] = p }
        val cur = values.copyOf()
        for (m in moves) {
            val a = index[m.from] ?: return false
            val b = index[m.to] ?: return false
            if (cur[a] <= 0 || cur[b] == UNKNOWN) return false
            if (cur[b] > 0) {
                if (cur[b] == cur[a]) return false
                val t = cur[a]
                cur[a] = cur[b]
                cur[b] = t
            } else {
                cur[b] = cur[a]
                cur[a] = 0
            }
        }
        return true
    }

    /** How many moves drop a tile onto another tile (a swap) rather than onto an empty square. */
    fun countDropsOnTiles(ids: List<Int>, values: IntArray, moves: List<Move>): Int {
        val index = HashMap<Int, Int>()
        ids.forEachIndexed { p, id -> index[id] = p }
        val cur = values.copyOf()
        var count = 0
        for (m in moves) {
            val a = index[m.from] ?: continue
            val b = index[m.to] ?: continue
            if (cur[a] <= 0) continue
            if (cur[b] > 0) {
                count++
                val t = cur[a]
                cur[a] = cur[b]
                cur[b] = t
            } else if (cur[b] == 0) {
                cur[b] = cur[a]
                cur[a] = 0
            }
        }
        return count
    }

    /**
     * Highest level in the first box, lowest at the end, empty squares last. Boxes holding
     * unreadable tiles stay where they are; the rest is sorted around them.
     *
     * The whole path is worked out from one scan, and every drag lands on an EMPTY square,
     * so a drag can never merge two tiles, whatever the readings. Tiles that need to trade
     * places (a cycle) go through one spare empty square. [SortPlan.leftover] counts squares
     * that could not be fixed because the board has no empty square to work with.
     */
    private fun planSortEmptyOnly(ids: List<Int>, values: IntArray): SortPlan {
        val n = ids.size
        val cur = values.copyOf()
        val free = (0 until n).filter { cur[it] != UNKNOWN }
        val tiles = free.map { cur[it] }.filter { it > 0 }.sortedDescending()

        val target = cur.copyOf()
        free.forEachIndexed { k, p -> target[p] = if (k < tiles.size) tiles[k] else 0 }

        // squares that want a tile of some level, and tiles that are in the wrong place
        val wanting = HashMap<Int, MutableList<Int>>()
        val surplus = HashMap<Int, MutableList<Int>>()
        for (p in free) {
            if (target[p] > 0 && cur[p] != target[p]) wanting.getOrPut(target[p]) { mutableListOf() }.add(p)
            if (cur[p] > 0 && cur[p] != target[p]) surplus.getOrPut(cur[p]) { mutableListOf() }.add(p)
        }
        // source[p] = the square whose tile should end up on square p
        val source = HashMap<Int, Int>()
        for ((level, squares) in wanting) {
            val tilesOfLevel = surplus[level] ?: continue
            for (k in squares.indices) {
                if (k < tilesOfLevel.size) source[squares[k]] = tilesOfLevel[k]
            }
        }

        val moves = mutableListOf<Move>()
        val done = HashSet<Int>()

        // chains: they start at an empty square that wants a tile, and end at a tile nobody wants
        for (head in source.keys.filter { cur[it] == 0 }) {
            var p = head
            while (true) {
                val s = source[p] ?: break
                moves.add(Move(ids[s], ids[p], "move the ${cur[s]}"))
                cur[p] = cur[s]
                cur[s] = 0
                done.add(p)
                if (source.containsKey(s)) p = s else break
            }
        }

        // cycles: every square holds a tile that belongs elsewhere; use one spare empty square
        for (p0 in source.keys.toList()) {
            if (p0 in done) continue
            val spare = free.firstOrNull { cur[it] == 0 } ?: continue
            moves.add(Move(ids[p0], ids[spare], "set the ${cur[p0]} aside"))
            cur[spare] = cur[p0]
            cur[p0] = 0
            var p = p0
            while (true) {
                val s = source[p] ?: break
                if (s == p0) {
                    moves.add(Move(ids[spare], ids[p], "bring the ${cur[spare]} from the spare square"))
                    cur[p] = cur[spare]
                    cur[spare] = 0
                    done.add(p)
                    break
                }
                moves.add(Move(ids[s], ids[p], "move the ${cur[s]}"))
                cur[p] = cur[s]
                cur[s] = 0
                done.add(p)
                p = s
            }
        }

        return SortPlan(moves, source.keys.count { it !in done })
    }

    /**
     * The next single merge of a merge chain, or null when the chain is over.
     *
     * 1. Start: take the lowest tile (the last one in box order if tied). Merge it onto its
     *    neighbour in the previous box if that has the same level, otherwise onto the last
     *    other tile of that level. That result is the "carrier": it is NOT the destination.
     * 2. Carrier: merge the carrier onto the highest-ranked (lowest box number) other tile of
     *    its level. That tile is the destination from now on.
     * 3. Destination: while another tile of the destination's current level exists (taken from
     *    the end of the board), merge it onto the destination.
     *
     * This only plans ONE merge from the board as it is now. Run it, read the board again, and
     * ask again: the game upgrades other tiles when tiles merge, so a long plan made in advance
     * would go wrong. Unreadable tiles are never used.
     */
    fun planNextMerge(ids: List<Int>, values: IntArray, dest: Int?, carrier: Int?): NextMerge? {
        val n = ids.size
        val index = HashMap<Int, Int>()
        ids.forEachIndexed { p, id -> index[id] = p }

        if (dest != null) {
            val d = index[dest] ?: return null
            val level = values[d]
            if (level <= 0) return null
            val q = (n - 1 downTo 0).firstOrNull { it != d && values[it] == level } ?: return null
            return NextMerge(Move(ids[q], ids[d], "merge two $level into a ${level + 1}", true), dest, null)
        }

        if (carrier != null) {
            val c = index[carrier] ?: return null
            val level = values[c]
            if (level <= 0) return null
            val t = (0 until n).firstOrNull { it != c && values[it] == level } ?: return null
            return NextMerge(
                Move(ids[c], ids[t], "merge two $level into a ${level + 1} (this box becomes the destination)", true),
                ids[t], null
            )
        }

        val order = (0 until n).filter { values[it] > 0 }
            .sortedWith(compareBy<Int>({ values[it] }, { -it }))
        for (s in order) {
            val level = values[s]
            val partner = (if (s > 0 && values[s - 1] == level) s - 1 else null)
                ?: (n - 1 downTo 0).firstOrNull { it != s && values[it] == level }
                ?: continue
            return NextMerge(
                Move(ids[s], ids[partner], "merge the lowest two $level into a ${level + 1} (not the destination)", true),
                null, ids[partner]
            )
        }
        return null
    }

    /** What a merge chain would do if merging were the only thing that changed the board (for the preview). */
    fun simulateMerges(ids: List<Int>, values: IntArray, maxSteps: Int = 60): List<Move> {
        val cur = values.copyOf()
        val moves = mutableListOf<Move>()
        var dest: Int? = null
        var carrier: Int? = null
        while (moves.size < maxSteps) {
            val next = planNextMerge(ids, cur, dest, carrier) ?: break
            applyMoves(ids, cur, listOf(next.move))
            moves.add(next.move)
            dest = next.newDest
            carrier = next.newCarrier
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
