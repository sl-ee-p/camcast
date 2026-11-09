package com.slp.camcast

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the localisation-first rule: every English string has an Italian twin and vice versa. */
class StringsParityTest {
    private fun keys(dir: String): Set<String> {
        val file = File("src/main/res/$dir/strings.xml")
        assertTrue("missing ${file.path}", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) }
            .filter { it.attributes.getNamedItem("translatable")?.nodeValue != "false" }
            .map { it.attributes.getNamedItem("name").nodeValue }
            .toSet()
    }

    @Test
    fun italianMatchesEnglish() {
        assertEquals(keys("values"), keys("values-it"))
    }

    @Test
    fun pluralsMatchAndKeepTheFormatArgument() {
        val en = plurals("values")
        val it = plurals("values-it")
        assertEquals(en.keys, it.keys)
        (en + it).forEach { (name, items) -> assertTrue("$name needs %d", items.all { text -> "%d" in text }) }
    }

    /** plural name -> the text of each of its items. */
    private fun plurals(dir: String): Map<String, List<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("plurals")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            val items = node.childNodes
            node.attributes.getNamedItem("name").nodeValue to
                (0 until items.length).map { items.item(it) }.filter { it.nodeName == "item" }.map { it.textContent }
        }
    }
}
