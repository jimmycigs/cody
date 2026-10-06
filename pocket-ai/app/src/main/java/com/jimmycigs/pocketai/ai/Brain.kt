package com.jimmycigs.pocketai.ai

/**
 * Pure logic for Bernard's knowledge base: understanding "remember"/"forget" commands,
 * picking which memories are relevant to a message, and building prompts for the model.
 *
 * Nothing here touches Android, so it is covered by plain JVM unit tests.
 */
object Brain {

    const val ASSISTANT_NAME = "Bernard"
    const val USER_NAME = "Master"

    /** Who is who. Built into every prompt; can never be edited or forgotten. */
    val CORE_IDENTITY = listOf(
        "I am $ASSISTANT_NAME, your AI.",
        "You are $USER_NAME. When you say \"I\", \"me\" or \"my\", you mean yourself.",
    )

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
            val fact = cleanFact(toThirdPerson(match.groupValues[1]))
            if (fact.isNotEmpty()) return Command.Remember(fact)
        }
        forgetPattern.matchEntire(text)?.let { match ->
            val topic = match.groupValues[1].trim().trimEnd('.', '!', '?')
            if (topic.isNotEmpty()) return Command.Forget(topic)
        }
        return null
    }

    /** Turns "Master's dog is called Rex" into "Master's dog is called Rex." */
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
        "master", "masters", "bernard", "bernards",
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

    enum class CoreCheck { UNRELATED, CONFIRMS, CONFLICTS }

    private val namePattern = Regex(
        """\b(master|bernard)'s\s+(?:real\s+|new\s+|first\s+|full\s+|actual\s+)?name\s+is\s+([a-z]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val callPattern = Regex("""\bcall(?:s|ed)?\s+(master|bernard)\s+([a-z]+)""", RegexOption.IGNORE_CASE)

    /** Checks a third-person fact against the built-in names, which must never change. */
    fun checkAgainstCore(fact: String): CoreCheck {
        val match = namePattern.find(fact) ?: callPattern.find(fact) ?: return CoreCheck.UNRELATED
        val owner = match.groupValues[1]
        val name = match.groupValues[2]
        val correct = if (owner.equals(USER_NAME, ignoreCase = true)) USER_NAME else ASSISTANT_NAME
        return if (name.equals(correct, ignoreCase = true)) CoreCheck.CONFIRMS else CoreCheck.CONFLICTS
    }

    private val ignoreCase = setOf(RegexOption.IGNORE_CASE)

    /** Rewrites, in order, so "my" from Master is stored as "Master's" and "your" as "Bernard's". */
    private val perspectiveRules: List<Pair<Regex, String>> = listOf(
        """\bthe user's\b""" to "$USER_NAME's",
        """\bthe user\b""" to USER_NAME,
        """\bthe (?:AI|assistant)'s\b""" to "$ASSISTANT_NAME's",
        """\bthe (?:AI|assistant)\b""" to ASSISTANT_NAME,
        """\b(?:I'm|I am|Im)\b""" to "$USER_NAME is",
        """\bI've\b""" to "$USER_NAME has",
        """\bI'll\b""" to "$USER_NAME will",
        """\bI'd\b""" to "$USER_NAME would",
        """\bmyself\b""" to USER_NAME,
        """\bmine\b""" to "$USER_NAME's",
        """\bmy\b""" to "$USER_NAME's",
        """\bme\b""" to USER_NAME,
        """\b(?:you're|you are)\b""" to "$ASSISTANT_NAME is",
        """\byou've\b""" to "$ASSISTANT_NAME has",
        """\byourself\b""" to ASSISTANT_NAME,
        """\byours\b""" to "$ASSISTANT_NAME's",
        """\byour\b""" to "$ASSISTANT_NAME's",
        """\byou\b""" to ASSISTANT_NAME,
    ).map { (pattern, replacement) -> Regex(pattern, ignoreCase) to replacement }

    private val iVerbPattern = Regex("""\bI\s+([A-Za-z']+)""", RegexOption.IGNORE_CASE)
    private val loneIPattern = Regex("""\bI\b""", RegexOption.IGNORE_CASE)

    private val irregularVerbs = mapOf(
        "am" to "is", "have" to "has", "do" to "does", "don't" to "doesn't", "dont" to "doesn't",
        "go" to "goes", "was" to "was", "were" to "was",
    )
    private val keepVerbs = setOf(
        "can", "could", "will", "would", "should", "must", "may", "might", "did", "had", "also", "really",
        "always", "never", "just", "still", "usually", "often", "only", "now", "and", "or", "to", "too",
    )

    private fun thirdPersonVerb(verb: String): String {
        val lower = verb.lowercase()
        irregularVerbs[lower]?.let { return it }
        return when {
            lower in keepVerbs || lower.endsWith("ed") || lower.endsWith("n't") ||
                lower.endsWith("ly") || lower.endsWith("s") -> verb
            lower.length > 1 && lower.endsWith("y") && lower[lower.length - 2] !in "aeiou" -> verb.dropLast(1) + "ies"
            lower.endsWith("sh") || lower.endsWith("ch") || lower.endsWith("x") || lower.endsWith("z") ||
                lower.endsWith("o") -> verb + "es"
            else -> verb + "s"
        }
    }

    /** "my dog is Rex" -> "Master's dog is Rex"; "I live in Ohio" -> "Master lives in Ohio". */
    fun toThirdPerson(text: String): String {
        var result = text.replace('\u2019', '\'')
        perspectiveRules.forEach { (pattern, replacement) -> result = pattern.replace(result, replacement) }
        result = iVerbPattern.replace(result) { "$USER_NAME " + thirdPersonVerb(it.groupValues[1]) }
        return loneIPattern.replace(result, USER_NAME)
    }

    fun buildChatPrompt(memories: List<Memory>, history: List<ChatMessage>, userInput: String): String {
        val facts = if (memories.isEmpty()) {
            "(nothing yet)"
        } else {
            memories.joinToString("\n") { "- ${it.text}" }
        }
        val conversation = history.takeLast(MAX_HISTORY_MESSAGES).joinToString("\n") { message ->
            "${speaker(message.role)}: ${message.text.take(MAX_HISTORY_CHARS)}"
        }
        return buildString {
            appendLine("You are $ASSISTANT_NAME, a private AI assistant that runs entirely offline on $USER_NAME's phone.")
            appendLine()
            appendLine("Who is who (this never changes):")
            appendLine("- You are $ASSISTANT_NAME, the AI. Your name is $ASSISTANT_NAME.")
            appendLine("- The person talking to you is $USER_NAME. Their name is $USER_NAME. Always address them as $USER_NAME.")
            appendLine(
                "- When $USER_NAME says \"I\", \"me\" or \"my\", that means $USER_NAME, not you. " +
                    "When $USER_NAME says \"you\" or \"your\", that means you, $ASSISTANT_NAME.",
            )
            appendLine("- Never claim to be $USER_NAME, and never take a different name.")
            appendLine()
            appendLine(
                "Be friendly, helpful and concise. Use the facts below when relevant. " +
                    "If you don't know something, say so honestly instead of guessing.",
            )
            appendLine()
            appendLine("Facts you have learned:")
            appendLine(facts)
            appendLine()
            appendLine("Conversation:")
            if (conversation.isNotEmpty()) appendLine(conversation)
            appendLine("$USER_NAME: ${userInput.trim()}")
            append("$ASSISTANT_NAME:")
        }
    }

    private fun speaker(role: Role) = if (role == Role.USER) USER_NAME else ASSISTANT_NAME

    private val turnLabel = Regex("""^\s*(?:$ASSISTANT_NAME|Assistant)\s*:\s*""")
    private val nextTurn = Regex("""\n\s*(?:User|Assistant|$USER_NAME|$ASSISTANT_NAME)\s*:""")

    /** Removes anything the model writes after its own turn (e.g. inventing Master's next line). */
    fun cleanReply(raw: String): String {
        var text = turnLabel.replaceFirst(raw, "")
        val cut = nextTurn.find(text)?.range?.first
        if (cut != null) text = text.substring(0, cut)
        return text.trim()
    }

    /** Cheap check to avoid running fact extraction on questions and small talk. */
    fun shouldTryLearning(input: String): Boolean {
        val text = input.trim()
        if (text.length < 8 || text.endsWith("?")) return false
        val words = text.lowercase().replace("\u2019", "'").split(Regex("[^a-z']+")).toSet()
        return words.any { it in personalWords }
    }

    private val personalWords = setOf("i", "i'm", "im", "i've", "ive", "i'd", "my", "me", "mine", "we", "our", "we're")

    fun buildExtractionPrompt(userInput: String): String = buildString {
        appendLine(
            "The message below was written by $USER_NAME (the user) to $ASSISTANT_NAME (the AI). In it, " +
                "\"I\", \"me\" and \"my\" mean $USER_NAME, and \"you\" and \"your\" mean $ASSISTANT_NAME.",
        )
        appendLine(
            "List any lasting facts it states about $USER_NAME: $USER_NAME's life, preferences, plans, " +
                "or the people and things in $USER_NAME's life.",
        )
        appendLine(
            "Write each fact as a short sentence starting with \"$USER_NAME\", one per line, starting with \"- \" " +
                "(for example: - $USER_NAME's sister lives in Denver.). Ignore questions, requests and small talk. " +
                "If there are no lasting facts, write only NONE.",
        )
        appendLine()
        appendLine("Message: \"${userInput.trim()}\"")
        append("Facts:")
    }

    fun parseExtractedFacts(output: String): List<String> =
        output.lines()
            .map { it.trim() }
            .filter { it.startsWith("-") || it.startsWith("*") || it.startsWith("\u2022") }
            .map { cleanFact(toThirdPerson(it.trimStart('-', '*', '\u2022', ' '))) }
            .filter { it.length in 6..200 && !it.uppercase().startsWith("NONE") }
            .filter { checkAgainstCore(it) == CoreCheck.UNRELATED }
            .distinct()
            .take(MAX_FACTS_PER_MESSAGE)

    private const val MAX_HISTORY_MESSAGES = 6
    private const val MAX_HISTORY_CHARS = 600
    private const val MAX_FACTS_PER_MESSAGE = 3
}
