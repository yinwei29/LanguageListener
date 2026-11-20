package tw.language.listener

import org.junit.Assert.*
import org.junit.Test
import tw.language.listener.data.*
import tw.language.listener.review.*
import java.time.*

class ReviewSchedulerTest {
    private val now=Instant.parse("2026-11-20T12:00:00Z").toEpochMilli()
    private val zone=ZoneId.of("UTC")
    @Test fun goodReviewsGrowIntervalsAndAgainRelearnsInTenMinutes() {
        var state=Progress("s","i")
        state=ReviewScheduler.schedule(state,Rating.GOOD,now,zone);assertEquals(1,state.intervalDays)
        state=ReviewScheduler.schedule(state,Rating.GOOD,state.nextReviewAt,zone);assertEquals(3,state.intervalDays)
        state=ReviewScheduler.schedule(state,Rating.GOOD,state.nextReviewAt,zone);assertEquals(8,state.intervalDays)
        val again=ReviewScheduler.schedule(state,Rating.AGAIN,now,zone)
        assertEquals(now+600000,again.nextReviewAt);assertEquals(0,again.repetitions);assertEquals(1,again.lapses)
        assertEquals(1,ReviewScheduler.schedule(again,Rating.GOOD,again.nextReviewAt,zone).intervalDays)
    }
    @Test fun hardAndEasyAffectIntervalsWithoutChangingOtherLearningFlags() {
        val old=Progress("s","i",learned=true,favorite=true,skipUntil=123,reviewPriority=4,playCount=7)
        val hard=ReviewScheduler.schedule(old,Rating.HARD,now,zone);val easy=ReviewScheduler.schedule(old,Rating.EASY,now,zone)
        assertEquals(1,hard.intervalDays);assertEquals(4,easy.intervalDays)
        assertTrue(hard.ease<old.ease);assertTrue(easy.ease>old.ease)
        for(p in listOf(hard,easy)) { assertTrue(p.learned);assertTrue(p.favorite);assertEquals(123L,p.skipUntil);assertEquals(4,p.reviewPriority);assertEquals(7L,p.playCount) }
    }
    @Test fun calendarDaySchedulingPreservesLocalTimeAcrossDaylightSaving() {
        val dst=ZoneId.of("America/New_York");val before=ZonedDateTime.of(2026,3,7,12,0,0,0,dst)
        val next=ReviewScheduler.schedule(Progress("s","i"),Rating.GOOD,before.toInstant().toEpochMilli(),dst)
        assertEquals(23*3600000L,next.nextReviewAt-before.toInstant().toEpochMilli())
        assertEquals(12,Instant.ofEpochMilli(next.nextReviewAt).atZone(dst).hour)
    }
    @Test fun easeIntervalsAndCountersRemainBounded() {
        var state=Progress("s","i")
        repeat(30) { state=ReviewScheduler.schedule(state,Rating.AGAIN,now,zone) };assertEquals(1.3,state.ease,0.00001)
        repeat(100) { state=ReviewScheduler.schedule(state,Rating.EASY,now,zone) }
        assertEquals(3.0,state.ease,0.00001);assertEquals(3650,state.intervalDays)
        assertThrows(IllegalArgumentException::class.java) { ReviewScheduler.schedule(state.copy(ease=Double.NaN),Rating.GOOD,now,zone) }
    }
    @Test fun resetOnlyClearsSchedule() {
        val state=ReviewScheduler.schedule(Progress("s","i",favorite=true,playCount=5),Rating.EASY,now,zone)
        val cleared=ReviewScheduler.clear(state,now+1000)
        assertEquals(0L,cleared.nextReviewAt);assertEquals(0L,cleared.lastReviewedAt);assertTrue(cleared.favorite);assertEquals(5L,cleared.playCount)
        ReviewScheduler.validate(cleared)
    }
}
