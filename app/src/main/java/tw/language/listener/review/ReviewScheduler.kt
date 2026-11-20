package tw.language.listener.review

import kotlinx.serialization.Serializable
import tw.language.listener.data.Progress
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

@Serializable enum class Rating { AGAIN,HARD,GOOD,EASY }

/** Transparent SM-2-inspired intervals. Ratings never change learned/favorite/skip flags. */
object ReviewScheduler {
    fun schedule(old: Progress,rating: Rating,now: Long,zone: ZoneId=ZoneId.systemDefault()): Progress {
        validate(old);require(now>0)
        val interval=when(rating) {
            Rating.AGAIN->0
            Rating.HARD->if(old.intervalDays==0) 1 else (old.intervalDays*1.2).roundToInt().coerceAtLeast(old.intervalDays+1)
            Rating.GOOD->when { old.repetitions==0->1;old.repetitions==1->3;else->(old.intervalDays*old.ease).roundToInt().coerceAtLeast(old.intervalDays+1) }
            Rating.EASY->if(old.repetitions==0) 4 else (old.intervalDays*old.ease*1.3).roundToInt().coerceAtLeast(old.intervalDays+1)
        }.coerceIn(0,3650)
        val ease=(old.ease+when(rating) { Rating.AGAIN->-.2;Rating.HARD->-.15;Rating.GOOD->0.0;Rating.EASY->.15 }).coerceIn(1.3,3.0)
        val due=if(interval==0) Math.addExact(now,10*60_000L) else Instant.ofEpochMilli(now).atZone(zone).plusDays(interval.toLong()).toInstant().toEpochMilli()
        return old.copy(nextReviewAt=due,lastReviewedAt=now,intervalDays=interval,ease=ease,
            repetitions=if(rating==Rating.AGAIN) 0 else (old.repetitions.toLong()+1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            lapses=if(rating==Rating.AGAIN) (old.lapses.toLong()+1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else old.lapses,modifiedAt=now)
    }
    fun validate(p: Progress) {
        require(p.nextReviewAt>=0 && p.lastReviewedAt>=0 && p.intervalDays in 0..3650 && p.ease.isFinite() && p.ease in 1.3..3.0 && p.repetitions>=0 && p.lapses>=0) { "複習排程資料不合法" }
        require((p.lastReviewedAt==0L && p.nextReviewAt==0L) || (p.lastReviewedAt>0 && p.nextReviewAt>=p.lastReviewedAt)) { "複習排程時間不合法" }
    }
    fun clear(old: Progress,now: Long)=old.copy(nextReviewAt=0,lastReviewedAt=0,intervalDays=0,ease=2.5,repetitions=0,lapses=0,modifiedAt=now)
}
