package dev.minimal.cal.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventTextTest {

    @Test fun teamsLink() {
        val d = "Microsoft Teams meeting\nJoin: https://teams.microsoft.com/l/meetup-join/19%3ameeting_abc%40thread.v2/0?context=%7b%22Tid%22%7d\nMeeting ID: 123"
        assertEquals("https://teams.microsoft.com/l/meetup-join/19%3ameeting_abc%40thread.v2/0?context=%7b%22Tid%22%7d", EventText.meetingLink(d))
    }

    @Test fun zoomAndMeet() {
        assertEquals("https://us02web.zoom.us/j/8812345678?pwd=abc", EventText.meetingLink("Join Zoom <https://us02web.zoom.us/j/8812345678?pwd=abc>."))
        assertEquals("https://meet.google.com/abc-defg-hij", EventText.meetingLink("Video call: https://meet.google.com/abc-defg-hij"))
    }

    @Test fun noLink() = assertNull(EventText.meetingLink("Lunch at https://example.com/menu"))

    @Test fun outlookBoilerplateCleaned() {
        val raw = "Agenda below\r\n\r\n\r\n\r\n________________________________________________________________________________\r\nMicrosoft Teams meeting\r\n________________________________________________________________________________\r\n"
        assertEquals("Agenda below\n\nMicrosoft Teams meeting", EventText.cleanDescription(raw) { it })
    }

    @Test fun htmlConverted() {
        val out = EventText.cleanDescription("<p>Hello <b>there</b></p>") { "Hello there" }
        assertEquals("Hello there", out)
    }

    @Test fun plainTextUntouched() = assertEquals("a < b and c", EventText.cleanDescription("a < b and c") { "WRONG" })
}
