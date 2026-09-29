package dev.minimal.cal.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RepeatTextTest {
    private val wed = LocalDate.of(2026, 9, 30)
    private fun d(rule: String?) = RepeatText.describe(rule, wed)

    @Test fun none() = assertNull(d(null))
    @Test fun daily() = assertEquals("Daily", d("FREQ=DAILY"))
    @Test fun everyThreeDays() = assertEquals("Every 3 days", d("FREQ=DAILY;INTERVAL=3"))
    @Test fun weeklyDefaultsToStartDay() = assertEquals("Weekly on Wed", d("FREQ=WEEKLY"))
    @Test fun weeklyDays() = assertEquals("Every 2 weeks on Mon, Wed", d("FREQ=WEEKLY;INTERVAL=2;BYDAY=WE,MO"))
    @Test fun weekdays() = assertEquals("Weekdays", d("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"))
    @Test fun monthlyDay() = assertEquals("Monthly on day 15", d("FREQ=MONTHLY;BYMONTHDAY=15"))
    @Test fun monthlyDefault() = assertEquals("Monthly on day 30", d("FREQ=MONTHLY"))
    @Test fun monthlyNth() = assertEquals("Monthly on the 2nd Tue", d("FREQ=MONTHLY;BYDAY=2TU"))
    @Test fun monthlyLastFri() = assertEquals("Monthly on the last Fri", d("FREQ=MONTHLY;BYDAY=-1FR"))
    @Test fun monthlyLastDay() = assertEquals("Monthly on the last day", d("FREQ=MONTHLY;BYMONTHDAY=-1"))
    // (UK locale abbreviates September as "Sept", so use October here.)
    @Test fun yearly() = assertEquals("Yearly on 8 Oct", RepeatText.describe("FREQ=YEARLY", LocalDate.of(2026, 10, 8)))
    @Test fun until() = assertEquals("Weekly on Wed until 12 Dec 2026", d("FREQ=WEEKLY;UNTIL=20261212T235959Z"))
    @Test fun count() = assertEquals("Daily (5 times)", d("FREQ=DAILY;COUNT=5"))
    @Test fun unknownFreq() = assertEquals("Repeats", d("FREQ=SECONDLY"))
    @Test fun rrulePrefix() = assertEquals("Daily", d("RRULE:FREQ=DAILY"))
}
