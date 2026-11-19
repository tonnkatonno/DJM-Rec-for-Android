package com.audiopro.djmrec.blind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlindSessionTest {
    // in time order: #2 (100 s), #1 (400 s), #3 (3000 s); #1 already graded in the extension
    private val list = BlindList(
        id = "L", match = "mix_20261118_203038_520", title = "test",
        moments = listOf(
            BlindMoment(1, 400.0, 420.0, "6:36", "7:00", "A → B"),
            BlindMoment(2, 100.0, 110.0, "1:36", "1:50", "C → D"),
            BlindMoment(3, 3000.0, 3024.0, "49:56", "50:24", "E → F"),
        ),
        seed = mapOf(1 to BlindGrade.MEH),
    )

    @Test fun seededGradesCountAndCanBeChangedOrCleared() {
        val s = BlindSession(list)
        assertEquals(1, s.done)
        assertEquals(BlindGrade.MEH, s.gradeOf(1))
        assertEquals(GradeEntry.SOURCE_SEED, s.sourceOf(1))
        assertEquals(BlindGrade.STAR, s.grade(1, BlindGrade.STAR, 5))
        assertEquals(GradeEntry.SOURCE_CAR, s.sourceOf(1))
        assertNull("the same grade again clears it", s.grade(1, BlindGrade.STAR, 6))
        assertNull("a cleared seed stays cleared", s.gradeOf(1))
        assertEquals(0, s.done)
        assertNull("an unknown moment is not graded", s.grade(99, BlindGrade.OK, 7))
        assertEquals(setOf(1), s.entries.keys)
    }

    @Test fun momentWindowRunsFromTheLeadInToSixSecondsAfterTheEnd() {
        val s = BlindSession(list)
        assertNull(s.momentAt(95.9))
        assertEquals(2, s.momentAt(96.0)?.n)
        assertEquals(2, s.momentAt(116.0)?.n)
        assertNull(s.momentAt(116.1))
        assertEquals(1, s.momentAt(410.0)?.n)
    }

    @Test fun nextGoesToTheNextUngradedMomentInTimeAndWraps() {
        val s = BlindSession(list)
        assertEquals("starts at the first ungraded in time", 2, s.firstTarget()?.n)
        assertEquals("#1 is graded: skipped", 3, s.nextTarget(150.0)?.n)
        assertEquals("from inside #2: not #2 again", 3, s.nextTarget(105.0)?.n)
        assertEquals("wraps round", 2, s.nextTarget(3010.0)?.n)
        s.grade(2, BlindGrade.OK, 1)
        s.grade(3, BlindGrade.BAD, 2)
        assertEquals("all graded: simply the next moment", 1, s.nextTarget(150.0)?.n)
        assertEquals("all graded, after the last: the first", 2, s.nextTarget(3500.0)?.n)
        assertEquals(2, s.firstTarget()?.n)
    }

    @Test fun previousRestartsTheMomentOrGoesToTheOneBefore() {
        val s = BlindSession(list)
        assertEquals("well inside #1: restart it", 1, s.previousTarget(410.0)?.n)
        assertEquals("just after #1's lead-in: the one before", 2, s.previousTarget(397.0)?.n)
        assertEquals("between moments: the last one heard", 1, s.previousTarget(1000.0)?.n)
        assertEquals("at the first moment's start: itself", 2, s.previousTarget(97.0)?.n)
        assertNull("before every moment", s.previousTarget(10.0))
    }

    @Test fun codecRoundTripsAndSkipsBrokenLines() {
        val entries = mapOf(3 to GradeEntry(BlindGrade.OK, 1700000000000), 1 to GradeEntry(null, 5))
        val text = GradeCodec.encode(entries)
        assertEquals("1\tnone\t5\tcar\n3\tok\t1700000000000\tcar", text)
        assertEquals(entries, GradeCodec.decode(text))
        assertEquals(mapOf(2 to GradeEntry(BlindGrade.BAD, 0)), GradeCodec.decode("x\tok\t1\n2\tbad\n4\tnope\t1\n\n"))
    }

    @Test fun reportListsEveryMomentInListOrder() {
        val s = BlindSession(list)
        s.grade(3, BlindGrade.STAR, 1000)
        assertEquals(
            listOf("1. 6:36–7:00  meh ~  (extension)", "2. 1:36–1:50  –", "3. 49:56–50:24  star ★  (car T1000)"),
            s.reportLines { "T$it" },
        )
    }

    @Test fun namesCompareByLettersAndDigits() {
        assertEquals(squashName("mix_20261118_203038_520"), squashName("Mix 20261118-203038-520"))
        assertTrue(squashName("mix_20261118_203038_520 (edit)") != squashName("mix_20261118_203038_520"))
    }

    @Test fun shippedListIsSaneAndItsWindowsDoNotOverlap() {
        val l = BlindLists.all.single()
        assertEquals("mix_20261118_203038_520", l.match)
        assertEquals((1..24).toList(), l.moments.map { it.n })
        assertEquals(13, l.seed.size)
        val s = BlindSession(l)
        for (m in l.moments) {
            assertTrue(m.end > m.start)
            assertEquals("#${m.n}: its whole window belongs to it", m.n, s.momentAt(m.leadStart)?.n)
            assertEquals(m.n, s.momentAt(m.end + BlindSession.GRADE_TAIL)?.n)
        }
        assertEquals("the car starts with the first moment not graded in the extension", 18, s.firstTarget()?.n)
    }
}
