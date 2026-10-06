package com.jimmycigs.pocketai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainTest {

    private fun memory(id: Long, text: String, createdAt: Long = id) =
        Memory(id, text, Memory.SOURCE_CHAT, createdAt)

    @Test
    fun parsesRememberCommands() {
        assertEquals(
            Brain.Command.Remember("Master's dog is called Rex."),
            Brain.parseCommand("Remember that my dog is called Rex"),
        )
        assertEquals(
            Brain.Command.Remember("Master takes Master's coffee black."),
            Brain.parseCommand("please remember: I take my coffee black!"),
        )
        assertEquals(Brain.Command.Remember("Gym on Mondays."), Brain.parseCommand("note gym on Mondays."))
    }

    @Test
    fun parsesForgetAndListCommands() {
        assertEquals(Brain.Command.Forget("Rex"), Brain.parseCommand("Forget about Rex."))
        assertEquals(Brain.Command.ListMemories, Brain.parseCommand("What do you know about me?"))
        assertEquals(Brain.Command.ListMemories, Brain.parseCommand("what do you remember"))
    }

    @Test
    fun ordinaryMessagesAreNotCommands() {
        assertNull(Brain.parseCommand("I remember when we went to Rome"))
        assertNull(Brain.parseCommand("What's the capital of France?"))
        assertNull(Brain.parseCommand("remember"))
    }

    @Test
    fun relevantMemoriesPreferKeywordMatches() {
        val memories = (1L..20L).map { memory(it, "Filler fact number $it.") } +
            memory(100, "The user's dog is called Rex.", createdAt = 0)
        val chosen = Brain.relevantMemories("What should I feed my dog?", memories, limit = 5)
        assertEquals(5, chosen.size)
        assertTrue(chosen.any { it.id == 100L })
    }

    @Test
    fun allMemoriesUsedWhenFew() {
        val memories = listOf(memory(2, "B."), memory(1, "A."))
        assertEquals(listOf(1L, 2L), Brain.relevantMemories("anything", memories).map { it.id })
    }

    @Test
    fun forgetMatchesAllTopicWords() {
        val memories = listOf(memory(1, "My dog is called Rex."), memory(2, "My cat is called Tom."))
        assertEquals(listOf(1L), Brain.memoriesAbout("Rex", memories).map { it.id })
        assertEquals(listOf(2L), Brain.memoriesAbout("my cats", memories).map { it.id })
        assertTrue(Brain.memoriesAbout("the", memories).isEmpty())
    }

    @Test
    fun detectsDuplicates() {
        val memories = listOf(memory(1, "My dog is called Rex."))
        assertTrue(Brain.isDuplicate("my dog is called rex", memories))
        assertFalse(Brain.isDuplicate("My cat is called Tom.", memories))
    }

    @Test
    fun cleanReplyStopsAtNextTurn() {
        assertEquals("Hello there!", Brain.cleanReply(" Assistant: Hello there!\nUser: hi again\nAssistant: ..."))
        assertEquals("Line one\nLine two", Brain.cleanReply("Line one\nLine two"))
        assertEquals("Yes, Master.", Brain.cleanReply("Bernard: Yes, Master.\nMaster: thanks"))
    }

    @Test
    fun promptContainsMemoriesHistoryAndInput() {
        val prompt = Brain.buildChatPrompt(
            listOf(memory(1, "The user's name is Sam.")),
            listOf(ChatMessage(1, Role.USER, "Hi", 1), ChatMessage(2, Role.ASSISTANT, "Hello!", 2)),
            "What's my name?",
        )
        assertTrue(prompt.contains("- The user's name is Sam."))
        assertTrue(prompt.contains("Master: Hi\nBernard: Hello!\nMaster: What's my name?"))
        assertTrue(prompt.contains("You are Bernard"))
        assertTrue(prompt.endsWith("Bernard:"))
    }

    @Test
    fun learningOnlyForPersonalStatements() {
        assertTrue(Brain.shouldTryLearning("My sister lives in Denver"))
        assertTrue(Brain.shouldTryLearning("I'm allergic to peanuts."))
        assertFalse(Brain.shouldTryLearning("What is my sister's name?"))
        assertFalse(Brain.shouldTryLearning("Tell a joke"))
    }

    @Test
    fun parsesExtractedFacts() {
        val output = """
            - The user's sister lives in Denver.
            * The user likes hiking
            NONE
            Some extra chatter
        """.trimIndent()
        assertEquals(
            listOf("Master's sister lives in Denver.", "Master likes hiking."),
            Brain.parseExtractedFacts(output),
        )
        assertTrue(Brain.parseExtractedFacts("NONE").isEmpty())
        assertTrue(Brain.parseExtractedFacts("- NONE").isEmpty())
    }

    @Test
    fun rewritesFirstPersonAsMaster() {
        assertEquals("Master's dog is Rex", Brain.toThirdPerson("my dog is Rex"))
        assertEquals("Master lives in Ohio", Brain.toThirdPerson("I live in Ohio"))
        assertEquals("Master is a carpenter", Brain.toThirdPerson("I'm a carpenter"))
        assertEquals("Master has two kids", Brain.toThirdPerson("I have two kids"))
        assertEquals("Master doesn't like onions", Brain.toThirdPerson("I don't like onions"))
        assertEquals("Master watches football", Brain.toThirdPerson("i watch football"))
        assertEquals("Master studies at night", Brain.toThirdPerson("I study at night"))
        assertEquals("Bernard's job is to help Master", Brain.toThirdPerson("your job is to help me"))
    }

    @Test
    fun namesNeverChange() {
        val renameMe = (Brain.parseCommand("Remember that my name is John") as Brain.Command.Remember).fact
        assertEquals("Master's name is John.", renameMe)
        assertEquals(Brain.CoreCheck.CONFLICTS, Brain.checkAgainstCore(renameMe))
        assertEquals(Brain.CoreCheck.CONFLICTS, Brain.checkAgainstCore(Brain.toThirdPerson("your name is Max")))
        assertEquals(Brain.CoreCheck.CONFLICTS, Brain.checkAgainstCore(Brain.toThirdPerson("call me Jim")))
        assertEquals(Brain.CoreCheck.CONFIRMS, Brain.checkAgainstCore(Brain.toThirdPerson("my name is Master")))
        assertEquals(Brain.CoreCheck.UNRELATED, Brain.checkAgainstCore("Master's dog is called Rex."))
        assertEquals(Brain.CoreCheck.UNRELATED, Brain.checkAgainstCore("Master's sister's name is Ana."))
        assertTrue(Brain.parseExtractedFacts("- The user's name is John.\n- The user likes tea.").none { "John" in it })
    }
}
