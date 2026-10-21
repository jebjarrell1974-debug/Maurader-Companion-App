package com.kybercypher.marauder.device

/** One console line plus enough metadata to colour and key it. */
data class LogLine(val seq: Long, val text: String, val kind: LineKind)

enum class LineKind { ECHO, SIGHTING, LIST, SETTING, STATE, MACHINE, ANOMALY, PLAIN }

/**
 * Bounded console scrollback.
 *
 * ArrayDeque, NOT SnapshotStateList: a snapshot list observes reads per element,
 * so iterating a 2000-line buffer generates thousands of invalidation records.
 * Google's own current Compose performance guidance says to back whole-list
 * updates with mutableStateOf(list, neverEqualPolicy()) rather than
 * mutableStateListOf(), and explicitly warns against adding @Immutable/@Stable
 * to collection models or swapping in ImmutableList — annotating forces an O(n)
 * equals() where strong skipping would have done an O(1) reference check.
 *
 * Cap reasoning: a phone viewport shows 30-45 lines, so 2000 is ~50 screens of
 * scrollback at roughly 250-500 KB. At 200 lines/sec that is only a 10-second
 * window, which is why the UI buffer is a VIEW onto the tail and the full
 * stream is what gets written to disk.
 *
 * Single-threaded confinement: only the parser coroutine touches this.
 */
class ConsoleBuffer(private val cap: Int = 2_000) {
    private val dq = ArrayDeque<LogLine>(cap)
    private var seq = 0L
    private var dirty = false

    var totalLines = 0L; private set

    fun add(text: String, kind: LineKind) {
        if (dq.size == cap) dq.removeFirst()
        dq.addLast(LogLine(seq++, text, kind))
        totalLines++
        dirty = true
    }

    /**
     * Returns a fresh snapshot only if something changed since the last call.
     * The ArrayList copy is ~2000 pointer copies; at 15 Hz that is negligible.
     * Do not over-engineer this into a zero-copy scheme.
     */
    fun snapshotIfDirty(): List<LogLine>? =
        if (dirty) { dirty = false; ArrayList(dq) } else null

    fun clear() { dq.clear(); dirty = true }
}
