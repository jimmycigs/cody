package com.jimmycigs.pocketai.ai

/**
 * Pure logic for Pocket AI's knowledge base: understanding "remember"/"forget" commands,
 * picking which memories are relevant to a message, and building prompts for the model.
 *
 * Nothing here touches Android, so it is covered by plain JVM unit tests.
 */
object Brain {

    const val ASSISTANT_NAME = "Pocket AI"

    sealed interface Command {
        data class Remember(val fact: String) : Command
        data class Forget(val topic: String) : Command
        data object ListMemories : Command
    }

    private val rememberPattern =
        Regex("""^(?:please\s+)?(?:remember|note)(?:\s+that)?\s*[:,]?\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val forgetPattern =
        Regex("""^(?:please\s+)?forget(?:\s+about|\s+that)?\s*[:,]?\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val listPattern =
        Regex("""^what\s+do\s+you\s+(?:know|remember)(?:\s+about\s+me)?\s*\??$""", RegexOption.IGNORE_CASE)

    /** Recognizes explicit teaching commands, which work even before the model is loaded. */
    fun parseCommand(input: String): Command? {
        val text = input.trim()
        if (listPattern.matches(text)) return Command.ListMemories
        rememberPattern.matchEntire(text)?.let { match ->
            val fact = cleanFact(match.groupValues[1])
            if (fact.isNotEmpty()) return Command.Remember(fact)
        }
        forgetPattern.matchEntire(text)?.let { match ->
            val topic = match.groupValues[1].trim().trimEnd('.', '!', '?')
            if (topic.isNotEmpty()) return Command.Forget(topic)
        }
        return null
    }

    /** Turns "my dog is called Rex." into "My dog is called Rex." */
    fun cleanFact(raw: String): String {
        val text = raw.trim().trimEnd('.', '!').trim()
        if (text.isEmpty()) return text
        return text.replaceFirstChar { it.uppercaseChar() } + "."
    }

    private val stopWords = setOf(
        "the", "and", "for", "are", "but", "not", "you", "your", "all", "any", "can", "had", "her", "was",
        "one", "our", "out", "has", "have", "him", "his", "how", "its", "may", "who", "did", "get", "got",
        "let", "say", "she", "too", "use", "that", "this", "with", "what", "when", "where", "which", "why",
        "will", "would", "could", "should", "from", "they", "them", "then", "than", "there", "their",
        "been", "were", "about", "into", "just", "like", "some", "also", "very", "does", "dont", "doing",
        "user", "users", "know", "tell", "please", "thing", "things", "really", "want", "much", "more",
    )

    /** Lowercased content words with a crude plural stem, used for matching memories to messages. */
    fun keywords(text: String): Set<String> =
        text.lowercase()
            .replace("'", "")
            .split(Regex("[^a-z0-9]+"))
            .asSequence()
            .filter { it.length >= 3 && it !in stopWords }
            .map { if (it.length > 3 && it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }
            .toSet()

    /**
     * Chooses the memories to show the model for [query]: the best keyword matches first,
     * then the most recent memories to fill up to [limit].
     */
    fun relevantMemories(query: String, memories: List<Memory>, limit: Int = 10): List<Memory> {
        if (memories.size <= limit) return memories.sortedBy { it.createdAt }
        val queryWords = keywords(query)
        val scored = memories
            .map { it to keywords(it.text).count { word -> word in queryWords } }
            .sortedWith(compareByDescending<Pair<Memory, Int>> { it.second }.thenByDescending { it.first.createdAt })
        val matches = scored.filter { it.second > 0 }.map { it.first }.take(limit)
        val recent = memories.sortedByDescending { it.createdAt }.filter { it !in matches }
        return (matches + recent).take(limit).sortedBy { it.createdAt }
    }

    /** Memories that contain every keyword of [topic], used by "forget ...". */
    fun memoriesAbout(topic: String, memories: List<Memory>): List<Memory> {
        val topicWords = keywords(topic)
        if (topicWords.isEmpty()) return emptyList()
        return memories.filter { keywords(it.text).containsAll(topicWords) }
    }

    fun isDuplicate(fact: String, memories: List<Memory>): Boolean {
        val normalized = normalize(fact)
        val words = keywords(fact)
        return memories.any { existing ->
            if (normalize(existing.text) == normalized) return@any true
            val other = keywords(existing.text)
            if (words.isEmpty() || other.isEmpty()) return@any false
            val overlap = words.intersect(other).size.toDouble() / words.union(other).size
            overlap >= 0.8
        }
    }

    private fun normalize(text: String) = text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    fun buildChatPrompt(memories: List<Memory>, history: List<ChatMessage>, userInput: String): String {
        val facts = if (memories.isEmpty()) {
            "(nothing yet)"
        } else {
            memories.joinToString("\n") { "- ${it.text}" }
        }
        val conversation = history.takeLast(MAX_HISTORY_MESSAGES).joinToString("\n") { message ->
            val speaker = if (message.role == Role.USER) "User" else "Assistant"
            "$speaker: ${message.text.take(MAX_HISTORY_CHARS)}"
        }
        return buildString {
            appendLine(
                "You are $ASSISTANT_NAME, a private assistant that runs entirely offline on the user's phone. " +
                    "Be friendly, helpful and concise.",
            )
            appendLine(
                "Everything you have learned is listed under \"Things you know\". Treat it as true and use it " +
                    "when relevant. If you don't know something, say so honestly instead of guessing.",
            )
            appendLine()
            appendLine("Things you know:")
            appendLine(facts)
            appendLine()
            appendLine("Conversation:")
            if (conversation.isNotEmpty()) appendLine(conversation)
            appendLine("User: ${userInput.trim()}")
            append("Assistant:")
        }
    }

    /** Removes anything the model writes after its own turn (e.g. inventing the user's next line). */
    fun cleanReply(raw: String): String {
        var text = raw.trimStart()
        if (text.startsWith("Assistant:")) text = text.removePrefix("Assistant:").trimStart()
        val cut = Regex("""\n\s*(User|Assistant)\s*:""").find(text)?.range?.first
        if (cut != null) text = text.substring(0, cut)
        return text.trim()
    }

    /** Cheap check to avoid running fact extraction on questions and small talk. */
    fun shouldTryLearning(input: String): Boolean {
        val text = input.trim()
        if (text.length < 8 || text.endsWith("?")) return false
        val words = text.lowercase().replace("’", "'").split(Regex("[^a-z']+")).toSet()
        return words.any { it in personalWords }
    }

    private val personalWords = setOf("i", "i'm", "im", "i've", "ive", "i'd", "my", "me", "mine", "we", "our", "we're")

    fun buildExtractionPrompt(userInput: String): String = buildString {
        appendLine(
            "Read the message below, written by the user. List any lasting facts it states about the user, " +
                "their life, preferences, plans, or the people and things in their life.",
        )
        appendLine(
            "Write each fact as a short sentence about \"the user\", one per line, starting with \"- \". " +
                "Ignore questions, requests and small talk. If there are no lasting facts, write only NONE.",
        )
        appendLine()
        appendLine("Message: \"${userInput.trim()}\"")
        append("Facts:")
    }

    fun parseExtractedFacts(output: String): List<String> =
        output.lines()
            .map { it.trim() }
            .filter { it.startsWith("-") || it.startsWith("*") || it.startsWith("•") }
            .map { cleanFact(it.trimStart('-', '*', '•', ' ')) }
            .filter { it.length in 6..200 && !it.uppercase().startsWith("NONE") }
            .distinct()
            .take(MAX_FACTS_PER_MESSAGE)

    private const val MAX_HISTORY_MESSAGES = 6
    private const val MAX_HISTORY_CHARS = 600
    private const val MAX_FACTS_PER_MESSAGE = 3
}
