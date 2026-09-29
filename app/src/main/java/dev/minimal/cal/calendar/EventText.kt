package dev.minimal.cal.calendar

/** Pure text helpers for the event view (unit tested). */
object EventText {

    private val MEETING = Regex(
        """https://(?:teams\.microsoft\.com|teams\.live\.com)/[^\s<>"')\]]+""" +
            """|https://[\w.-]*zoom\.us/(?:j|my|w)/[^\s<>"')\]]+""" +
            """|https://meet\.google\.com/[a-z0-9-]+""" +
            """|https://meet\.proton\.me/[^\s<>"')\]]+""",
        RegexOption.IGNORE_CASE,
    )
    private val HTML_TAG = Regex("<(?:br|p|div|span|a|b|i|u|ul|li|table|tr|td|html|body)\\b", RegexOption.IGNORE_CASE)
    private val RULE_LINE = Regex("""^[\s_\-=~*·.]{8,}$""")
    private val BLANK_RUN = Regex("\n{3,}")

    /** First online-meeting link (Teams, Zoom, Google Meet) in the text, if any. */
    fun meetingLink(text: String): String? = MEETING.find(text)?.value?.trimEnd('.', ',', ';', '>')

    /**
     * Readable description: HTML converted to text (via [htmlToText], Android's Html in the app),
     * divider lines of underscores/dashes (Outlook's meeting boilerplate) dropped, blank runs collapsed.
     */
    fun cleanDescription(raw: String, htmlToText: (String) -> String): String {
        val text = if (HTML_TAG.containsMatchIn(raw)) htmlToText(raw) else raw
        return text.replace("\r\n", "\n")
            .lines()
            .filterNot { RULE_LINE.matches(it) }
            .joinToString("\n") { it.trimEnd() }
            .replace(BLANK_RUN, "\n\n")
            .trim()
    }
}
