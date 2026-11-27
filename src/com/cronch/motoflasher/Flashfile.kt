package com.cronch.motoflasher

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Motorola flashfile.xml parsing.
 *
 * A stock firmware's flashfile.xml lists <step> elements in order, e.g.
 *   <step operation="flash" partition="boot" filename="boot.img" MD5="..." />
 *   <step operation="erase" partition="userdata" />
 *   <step operation="oem" var="fb_mode_set" />
 *   <step operation="reboot" />
 *   <step operation="getvar" var="max-download-size" />
 *
 * Unknown operations are NOT skipped silently: they stop the run (fail-closed).
 */
object Flashfile {

    data class Step(
        val operation: String,
        val partition: String?,
        val filename: String?,
        val varName: String?,
        val raw: String,
    )

    /** Fixed fallback order for a folder with no flashfile.xml. */
    // Flash order (folder mode): the REAL vbmeta family goes FIRST, then
    // everything it verifies, then the sparse images (super/vendor/product/
    // system/odm/oem) last. vbmeta verifies boot/init_boot/vendor_boot and
    // the sparse images, so flashing it first means every image after it is
    // covered the moment it lands. XML mode keeps Motorola's own order.
    val FALLBACK_ORDER = listOf(
        "partition", "bootloader", "radio", "modem", "dsp", "bluetooth",
        "vbmeta", "vbmeta_system", "vbmeta_vendor",
        "boot", "init_boot", "vendor_boot", "dtbo",
        "super", "vendor", "product", "system", "odm", "oem",
        "recovery", "logo", "lk", "lk_b", "preloader",
    )

    val SUPPORTED_OPS = setOf("flash", "erase", "oem", "reboot", "reboot-bootloader", "getvar", "unlock", "lock")

    fun parse(xml: String): List<Step> {
        val steps = mutableListOf<Step>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "step") {
                val op = parser.getAttributeValue(null, "operation") ?: ""
                val part = parser.getAttributeValue(null, "partition")
                val file = parser.getAttributeValue(null, "filename")
                val varName = parser.getAttributeValue(null, "var")
                val raw = buildString {
                    append("operation=").append(op)
                    if (part != null) append(" partition=").append(part)
                    if (file != null) append(" filename=").append(file)
                    if (varName != null) append(" var=").append(varName)
                }
                steps.add(Step(op, part, file, varName, raw))
            }
            event = parser.next()
        }
        return steps
    }

    /** Sort folder .img names into the fallback flash order. Unknown names go last, alphabetical. */
    fun sortFilenames(names: List<String>): List<String> {
        fun partOf(name: String): String = name.substringBeforeLast('.').lowercase()
        fun rank(name: String): Int {
            val idx = FALLBACK_ORDER.indexOf(partOf(name))
            return if (idx >= 0) idx else 1000
        }
        return names.sortedWith(compareBy({ rank(it) }, { it.lowercase() }))
    }
}
