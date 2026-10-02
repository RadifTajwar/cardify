package com.cardify

private val EMAIL = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")
private val WEB = Regex(
    """(?i)(https?://|www\.)\S+|\b[\w-]+(\.[\w-]+)*\.(com|net|org|io|co|info|biz|me|app|dev|ai|tech|bd|in|uk)(\.[a-z]{2})?\b(/\S*)?"""
)
private val PHONE = Regex("""\+?\(?\d[\d ().-]{5,}\d""")
private val LABEL = Regex("""(?i)\b(tel|telephone|phone|ph|mobile|mob|cell|office|direct|hotline|fax|e-?mail|web(site)?|url|[tmefwp])\b[.:]?""")
private val ADDRESS = Regex("""(?i)\b(road|rd|street|st|avenue|ave|lane|house|floor|level|block|sector|suite|building|tower|plaza|p\.?o\.? box|zip|city)\b""")
private val COMPANY = Regex(
    """(?i)\b(ltd|limited|inc|llc|llp|plc|corp|corporation|company|group|pvt|bank|technologies|technology|solutions|industries|enterprises?|agency|consulting|studio|associates|university|hospital|foundation|holdings)\b"""
)
private val TITLE = Regex(
    """(?i)\b(ceo|cto|cfo|coo|cio|founder|co-founder|director|manager|engineer|developer|designer|officer|executive|head|lead|president|vp|consultant|analyst|specialist|associate|assistant|coordinator|architect|professor|lecturer|partner|owner|chairman|intern|advisor|representative|supervisor|administrator|accountant|secretary|lawyer|scientist|agent)\b"""
)
private val NAME = Regex("""\p{L}[\p{L}.'-]*(\s+\p{L}[\p{L}.'-]*){1,3}""") // 2–4 words, letters only

/**
 * Best-effort split of OCR'd card text into fields. The user reviews the result before saving.
 * ponytail: keyword heuristics, line by line; swap in an LLM/entity extractor if accuracy disappoints.
 */
fun parseCardText(text: String): Card {
    val emails = mutableListOf<String>()
    val webs = mutableListOf<String>()
    val phones = mutableListOf<String>()
    val address = mutableListOf<String>()
    val rest = mutableListOf<String>()
    var name = ""
    var title = ""
    var company = ""

    for (raw in text.lines()) {
        var line = raw.trim()
        EMAIL.findAll(line).forEach { emails += it.value }
        line = line.replace(EMAIL, " ")
        WEB.findAll(line).forEach { webs += it.value.trimEnd('.', ',', ';') }
        line = line.replace(WEB, " ")
        PHONE.findAll(line).map { it.value.trim() }.filter { it.count(Char::isDigit) in 7..15 }.toList().forEach {
            phones += it
            line = line.replace(it, " ")
        }
        // When contact details came out of this line, what's left is mostly labels like "Mobile:".
        if (line.trim() != raw.trim()) line = line.replace(LABEL, " ").replace(Regex("""[|•·/:;]"""), " ")
        line = line.replace(Regex("""\s+"""), " ").trim(' ', ',', '-', '.')
        if (line.count(Char::isLetter) < 2) continue

        when {
            ADDRESS.containsMatchIn(line) || (line.any(Char::isDigit) && ',' in line) -> address += line
            company.isEmpty() && COMPANY.containsMatchIn(line) -> company = line
            title.isEmpty() && TITLE.containsMatchIn(line) -> title = line
            name.isEmpty() && NAME.matches(line) -> name = line
            else -> rest += line
        }
    }
    if (company.isEmpty() && rest.isNotEmpty()) company = rest.removeAt(0)
    rest.removeAll { company.contains(it, ignoreCase = true) } // e.g. the logo repeating the company name

    return Card(
        name = name,
        title = title,
        company = company,
        phone = phones.distinct().joinToString("\n"),
        email = emails.firstOrNull().orEmpty(),
        website = webs.firstOrNull().orEmpty(),
        address = address.joinToString(", "),
        notes = (rest + emails.drop(1) + webs.drop(1)).joinToString("\n"),
    )
}
