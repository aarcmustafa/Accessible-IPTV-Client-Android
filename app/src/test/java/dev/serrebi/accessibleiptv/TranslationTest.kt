package dev.serrebi.accessibleiptv

import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every translation (the desktop's 13 languages) has every string, the same placeholders,
 * the plural forms its language needs, and a help file with the same sections. A missing
 * placeholder crashes the screen that formats it; a missing plural form shows a wrong count.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationTest {
	private val res = File("src/main/res")
	private val forms = mapOf(
		"es" to setOf("one", "many", "other"), "fr" to setOf("one", "many", "other"), "it" to setOf("one", "many", "other"),
		"pt-rBR" to setOf("one", "many", "other"), "de" to setOf("one", "other"), "tr" to setOf("one", "other"),
		"hi" to setOf("one", "other"), "hu" to setOf("one", "other"), "ru" to setOf("one", "few", "many", "other"),
		"pl" to setOf("one", "few", "many", "other"), "ar" to setOf("zero", "one", "two", "few", "many", "other"),
		"ja" to setOf("other"), "zh-rCN" to setOf("other"),
	)

	private class Strings(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

	private fun load(f: File): Strings {
		val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
		val s = HashMap<String, String>()
		val p = HashMap<String, Map<String, String>>()
		val nodes = doc.documentElement.childNodes
		for (i in 0 until nodes.length) {
			val e = nodes.item(i) as? org.w3c.dom.Element ?: continue
			if (e.getAttribute("translatable") == "false") continue
			when (e.tagName) {
				"string" -> s[e.getAttribute("name")] = e.textContent
				"plurals" -> p[e.getAttribute("name")] = (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? org.w3c.dom.Element }
					.associate { it.getAttribute("quantity") to it.textContent }
			}
		}
		return Strings(s, p)
	}

	private val placeholder = Regex("%\\d*\\$?[sd]")
	private fun ph(s: String) = placeholder.findAll(s).map { it.value }.sorted().toList()
	private fun sections(f: File) = f.readLines().count { it.startsWith("# ") }

	@Test fun everyLanguageIsComplete() {
		val en = load(File(res, "values/strings.xml"))
		val enSections = sections(File(res, "raw/help.md"))
		assertEquals(forms.keys, res.listFiles { f -> f.name.startsWith("values-") && File(f, "strings.xml").exists() }!!.map { it.name.removePrefix("values-") }.toSet())
		for ((lang, need) in forms) {
			val t = load(File(res, "values-$lang/strings.xml"))
			assertEquals("$lang keys", en.strings.keys, t.strings.keys)
			for ((k, v) in en.strings) assertEquals("$lang $k placeholders", ph(v), ph(t.strings.getValue(k)))
			assertEquals("$lang plurals", en.plurals.keys, t.plurals.keys)
			for ((k, q) in t.plurals) {
				assertEquals("$lang $k forms", need, q.keys)
				q.filterKeys { it !in setOf("zero", "one", "two") }.forEach { (form, text) -> assertTrue("$lang $k $form has %d", "%d" in text) }
			}
			assertEquals("$lang help sections", enSections, sections(File(res, "raw-$lang/help.md")))
		}
	}

	@Test @Config(qualifiers = "fr")
	fun aTranslatedScreenUsesItsLanguage() {
		val a = Robolectric.buildActivity(HelpActivity::class.java).setup().get()
		assertEquals("Aide", a.title.toString())
		val col = ((a.findViewById<android.view.View>(android.R.id.content) as android.view.ViewGroup).getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout
		assertEquals("Démarrer", (col.getChildAt(0) as TextView).text.toString())
		assertEquals("12 chaînes", a.resources.getQuantityString(R.plurals.channel_count, 12, 12))
	}

	@Test @Config(qualifiers = "ru")
	fun russianCountsUseTheRightForm() {
		val r = Robolectric.buildActivity(HelpActivity::class.java).setup().get().resources
		assertEquals("1 канал", r.getQuantityString(R.plurals.channel_count, 1, 1))
		assertEquals("3 канала", r.getQuantityString(R.plurals.channel_count, 3, 3))
		assertEquals("5 каналов", r.getQuantityString(R.plurals.channel_count, 5, 5))
	}
}
