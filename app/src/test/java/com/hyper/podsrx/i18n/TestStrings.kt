package com.hyper.podsrx.i18n

import com.hyper.podsrx.R
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Use the real catalogs in JVM tests without depending on mocked Android Resources. */
object TestStrings {
    private val resourceNames = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    private val resources = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
    private val englishCatalog = catalog(File(resources, "values/strings.xml"))
    private val chineseCatalog = englishCatalog + catalog(File(resources, "values-zh/strings.xml"))
    val english = resolver(englishCatalog, Locale.ENGLISH)
    val chinese = resolver(chineseCatalog, Locale.SIMPLIFIED_CHINESE)

    fun catalog(file: File): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent.removeSurrounding("\"")
                .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
        }
    }

    private fun resolver(catalog: Map<String, String>, locale: Locale) = Strings { id, arguments ->
        val name = checkNotNull(resourceNames[id]) { "Unknown string resource $id" }
        val value = checkNotNull(catalog[name]) { "Missing string resource $name" }
        if (arguments.isEmpty()) value else String.format(locale, value, *arguments)
    }
}
