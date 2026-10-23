package com.jarvis.core.skills

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySkillTest {
    private val store = FakeStore()
    private val skill = MemorySkill(store)

    @Test
    fun remembersAnEnglishIsFact() = runBlocking {
        val call = skill.match("Remember that my favorite tea is mint.")
        assertEquals("remember", call?.slots?.get("action"))
        assertEquals("favorite tea", call?.slots?.get("key"))
        assertEquals("mint", call?.slots?.get("value"))
        assertEquals("en", call?.slots?.get("lang"))
        val result = skill.run(call!!)
        assertEquals("I'll remember that favorite tea is mint.", result.text)
        assertEquals("favorite tea" to "mint", store.remembered.single().let { it.first to it.second })
        assertEquals("voice", store.remembered.single().third)
    }

    @Test
    fun statementWithoutIsUsesTheWholeLine() {
        val call = skill.match("remember that I prefer the window closed")
        assertEquals("i prefer the window closed", call?.slots?.get("value"))
        assertEquals(call?.slots?.get("value"), call?.slots?.get("key"))
    }

    @Test
    fun recallsAndForgets() = runBlocking {
        store.facts += StoredFact("tea", "mint")
        val recall = skill.match("What do you remember about tea?")
        assertEquals("tea", recall?.slots?.get("query"))
        assertEquals("tea: mint", skill.run(recall!!).text)
        val everything = skill.match("what do you remember about me")
        assertEquals("me", everything?.slots?.get("query"))
        val forget = skill.match("forget about tea")
        assertEquals("Forgotten.", skill.run(forget!!).text)
        assertTrue(skill.run(skill.match("forget tea")!!).success.not())
    }

    @Test
    fun arabicRememberRecallAndForget() = runBlocking {
        val remember = skill.match("تذكر أنني أحب الشاي")
        assertEquals("remember", remember?.slots?.get("action"))
        assertEquals("ar", remember?.slots?.get("lang"))
        assertEquals("احب الشاي", remember?.slots?.get("value"))
        assertTrue(skill.run(remember!!).text.contains("احب الشاي"))

        val saved = skill.match("احفظ ان القهوه سوده")
        assertEquals("القهوه سوده", saved?.slots?.get("value"))

        val recall = skill.match("ماذا تتذكر عن الشاي؟")
        assertEquals("recall", recall?.slots?.get("action"))
        assertEquals("الشاي", recall?.slots?.get("query"))
        assertEquals("احب الشاي", skill.run(recall!!).text)

        val forget = skill.match("انسَ الشاي")
        assertEquals("forget", forget?.slots?.get("action"))
        assertEquals("الشاي", forget?.slots?.get("query"))
        assertEquals("تم النسيان.", skill.run(forget!!).text)

        val dialect = skill.match("شو تتذكر عن العمل")
        assertEquals("recall", dialect?.slots?.get("action"))
        assertEquals("العمل", dialect?.slots?.get("query"))
    }

    @Test
    fun doesNotClaimOtherSkills() {
        assertNull(skill.match("what time is it"))
        assertNull(skill.match("set a timer for 5 minutes"))
        assertNull(skill.match("turn on the flashlight"))
        assertNull(skill.match("don't forget the keys"))
        assertNull(skill.match("remember"))
    }

    private class FakeStore : MemoryStore {
        val remembered = mutableListOf<Triple<String, String, String>>()
        val facts = mutableListOf<StoredFact>()

        override suspend fun remember(key: String, value: String, source: String) {
            remembered += Triple(key, value, source)
            facts += StoredFact(key, value)
        }

        override suspend fun recall(query: String): List<StoredFact> {
            if (query.isBlank() || query == "me") return facts.toList()
            return facts.filter { it.key.contains(query) || it.value.contains(query) }
        }

        override suspend fun forget(query: String): Int {
            val removed = facts.filter { it.key.contains(query) || it.value.contains(query) }
            facts.removeAll { it.key.contains(query) || it.value.contains(query) }
            return removed.size
        }
    }
}
