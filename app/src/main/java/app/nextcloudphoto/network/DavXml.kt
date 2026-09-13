package app.nextcloudphoto.network

import app.nextcloudphoto.i18n.tr
import app.nextcloudphoto.R

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Reader
import java.time.*
import java.time.format.DateTimeFormatter

data class DavEntry(val href: String, val props: Map<String, String>) {
    val directory get() = props["collection"] == "true"
    val id get() = props["fileid"].orEmpty()
    val mime get() = props["getcontenttype"].orEmpty()
    val modified get() = runCatching { ZonedDateTime.parse(props["getlastmodified"], DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrDefault(0)
    val taken get() = parseTaken(props["metadata-photos-original_date_time"], modified)
}

fun parseTaken(raw: String?, fallback: Long): Long {
    if (raw.isNullOrBlank()) return fallback
    raw.toDoubleOrNull()?.let { if (it > 0) return if (it < 100000000000.0) (it * 1000).toLong() else it.toLong() }
    return runCatching { Instant.parse(raw).toEpochMilli() }.getOrElse {
        runCatching { LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(fallback)
    }
}

/** Stream one DAV response at a time. Only successful propstats are authoritative. */
fun readDav(reader: Reader, consume: (DavEntry) -> Unit) {
    val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
    parser.setInput(reader)
    var href = ""
    var props = linkedMapOf<String, String>()
    var block = linkedMapOf<String, String>()
    var inBlock = false
    var status = ""
    var capture = ""
    var text = StringBuilder()
    var rootSeen = false
    var rootClosed = false
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.DOCDECL -> error(tr(R.string.msg_12_server_responses_containing_dtds_are_not_accepted))
            XmlPullParser.START_TAG -> when (parser.name) {
                "multistatus" -> { require(parser.namespace == "DAV:") { tr(R.string.msg_13_invalid_dav_response) }; rootSeen=true }
                "response" -> { href = ""; props = linkedMapOf() }
                "propstat" -> { inBlock = true; block = linkedMapOf(); status = "" }
                "collection" -> block["collection"] = "true"
                "href", "status" -> { capture = parser.name; text = StringBuilder() }
                else -> if (inBlock && parser.name !in listOf("prop", "resourcetype")) { capture = parser.name; text = StringBuilder() }
            }
            XmlPullParser.TEXT -> if (capture.isNotEmpty()) text.append(parser.text)
            XmlPullParser.ENTITY_REF -> if(capture.isNotEmpty()) text.append(parser.text ?: error(tr(R.string.msg_14_cannot_parse_the_xml_entity)))
            XmlPullParser.END_TAG -> {
                if (parser.name == capture) {
                    when (capture) { "href" -> if (!inBlock) href = text.toString(); "status" -> status = text.toString(); else -> block[capture] = text.toString() }
                    capture = ""
                }
                if (parser.name == "propstat") { if (status.contains(" 200 ")) props.putAll(block); inBlock = false }
                if (parser.name == "response" && href.isNotBlank()) consume(DavEntry(href, props))
                if (parser.name == "multistatus") rootClosed=true
            }
        }
        parser.nextToken()
    }
    require(rootSeen && rootClosed) { tr(R.string.msg_15_the_server_response_is_incomplete_indexing_has_not_fini) }
}

fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
