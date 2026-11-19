package com.audiopro.djmrec.blind

/*
 * Blind grading ("vak próba", from the double_possiblity_searcher research): the searcher predicts which moments of a
 * recorded mix clash and which sound really good, and the user grades those moments without knowing the prediction.
 * The SoundCloud extension has the same lists for the desk; here they are for listening in the car ([BlindAutoService]).
 * Everything in this file is plain Kotlin, unit-tested on the JVM.
 */

enum class BlindGrade(val id: String, val symbol: String, val label: String) {
    BAD("bad", "✗", "disszonáns"),
    MEH("meh", "~", "semleges"),
    OK("ok", "✓", "harmonikus"),
    STAR("star", "★", "nagyon jó");

    companion object {
        fun of(id: String): BlindGrade? = entries.firstOrNull { it.id == id }
    }
}

/** One moment: the two tracks sound together from [start] to [end] (seconds); listening starts [BlindSession.LEAD_IN] before. */
data class BlindMoment(val n: Int, val start: Double, val end: Double, val from: String, val to: String, val pair: String) {
    val leadStart: Double get() = (start - BlindSession.LEAD_IN).coerceAtLeast(0.0)
}

/**
 * [match]: the recording's name (compared by letters and digits only) -- the file in Music/DJMRec and the SoundCloud track.
 * [seed]: grades already given elsewhere (the SoundCloud extension).
 */
data class BlindList(
    val id: String,
    val match: String,
    val title: String,
    val moments: List<BlindMoment>,
    val seed: Map<Int, BlindGrade> = emptyMap(),
)

/** A grade given in the car; [grade] null = cleared (which also hides a seeded grade). */
data class GradeEntry(val grade: BlindGrade?, val at: Long, val source: String = SOURCE_CAR) {
    companion object {
        const val SOURCE_CAR = "car"
        const val SOURCE_SEED = "extension"
    }
}

/** Letters and digits only, lower case: "mix_20261118_203038_520" and "Mix 20261118-203038-520" are the same name. */
fun squashName(text: String): String = text.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

/** One list's grades and the navigation between its moments, in the order they are heard. */
class BlindSession(val list: BlindList, entries: Map<Int, GradeEntry> = emptyMap()) {
    private val car = entries.filterKeys { n -> list.moments.any { it.n == n } }.toMutableMap()
    private val byTime = list.moments.sortedBy { it.start }

    /** The grades given in the car (cleared ones too), for storing. */
    val entries: Map<Int, GradeEntry> get() = car.toMap()
    val total: Int get() = list.moments.size
    val done: Int get() = list.moments.count { gradeOf(it.n) != null }

    fun moment(n: Int): BlindMoment? = list.moments.firstOrNull { it.n == n }

    fun gradeOf(n: Int): BlindGrade? = if (car.containsKey(n)) car[n]?.grade else list.seed[n]

    /** Where the grade in effect came from: [GradeEntry.SOURCE_CAR], [GradeEntry.SOURCE_SEED] or null (ungraded). */
    fun sourceOf(n: Int): String? = when {
        car.containsKey(n) -> car[n]?.takeIf { it.grade != null }?.source
        list.seed.containsKey(n) -> GradeEntry.SOURCE_SEED
        else -> null
    }

    fun gradedAt(n: Int): Long? = car[n]?.takeIf { it.grade != null }?.at

    /** Sets the grade of moment [n]; the same grade again clears it. Returns the grade now in effect. */
    fun grade(n: Int, grade: BlindGrade, at: Long): BlindGrade? {
        if (moment(n) == null) return null
        val next = if (gradeOf(n) == grade) null else grade
        car[n] = GradeEntry(next, at)
        return next
    }

    /** The moment whose grading window holds second [t]: from its lead-in until [GRADE_TAIL] after it ends. */
    fun momentAt(t: Double): BlindMoment? = byTime.firstOrNull { t >= it.leadStart && t <= it.end + GRADE_TAIL }

    /** Where a new session starts: the first ungraded moment in time; with all graded, the first moment. */
    fun firstTarget(): BlindMoment? = byTime.firstOrNull { gradeOf(it.n) == null } ?: byTime.firstOrNull()

    /** ⏭: the next ungraded moment after second [t] (wrapping round); with every moment graded, simply the next one. */
    fun nextTarget(t: Double): BlindMoment? {
        val ahead = { m: BlindMoment -> m.leadStart > t + 0.5 }
        return byTime.firstOrNull { ahead(it) && gradeOf(it.n) == null }
            ?: byTime.firstOrNull { gradeOf(it.n) == null }
            ?: byTime.firstOrNull(ahead)
            ?: byTime.firstOrNull()
    }

    /** ⏮: the start of the moment being heard; within its first [RESTART_GRACE] seconds (or between moments) the one before. */
    fun previousTarget(t: Double): BlindMoment? {
        val current = momentAt(t)
        if (current != null && t - current.leadStart > RESTART_GRACE) return current
        val before = current?.leadStart ?: t
        return byTime.lastOrNull { it.leadStart < before } ?: current
    }

    /** One line per moment in list order, for the diagnostic report (pasted back to the searcher). */
    fun reportLines(formatTime: (Long) -> String): List<String> = list.moments.map { m ->
        val grade = gradeOf(m.n)
        val given = when (sourceOf(m.n)) {
            GradeEntry.SOURCE_CAR -> "car " + (gradedAt(m.n)?.let(formatTime) ?: "")
            GradeEntry.SOURCE_SEED -> "extension"
            else -> ""
        }
        "${m.n}. ${m.from}–${m.to}  " + (grade?.let { "${it.id} ${it.symbol}  ($given)" } ?: "–")
    }

    companion object {
        const val LEAD_IN = 4.0
        const val GRADE_TAIL = 6.0
        const val RESTART_GRACE = 3.0
    }
}

/** The car's grades as text: one "n<TAB>grade<TAB>at<TAB>source" line per moment ("none" = cleared). */
internal object GradeCodec {
    fun encode(entries: Map<Int, GradeEntry>): String =
        entries.toSortedMap().entries.joinToString("\n") { (n, e) -> "$n\t${e.grade?.id ?: "none"}\t${e.at}\t${e.source}" }

    fun decode(text: String): Map<Int, GradeEntry> = text.lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        val n = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val id = parts.getOrNull(1) ?: return@mapNotNull null
        val grade = if (id == "none") null else BlindGrade.of(id) ?: return@mapNotNull null
        n to GradeEntry(grade, parts.getOrNull(2)?.toLongOrNull() ?: 0L, parts.getOrNull(3)?.ifBlank { null } ?: GradeEntry.SOURCE_CAR)
    }.toMap()
}
