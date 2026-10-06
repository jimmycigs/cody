package com.jimmycigs.pocketai.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jimmycigs.pocketai.ai.Brain
import com.jimmycigs.pocketai.ai.ChatMessage
import com.jimmycigs.pocketai.ai.LocalModel
import com.jimmycigs.pocketai.ai.Memory
import com.jimmycigs.pocketai.ai.Role
import com.jimmycigs.pocketai.data.KnowledgeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { CHAT, MEMORY, SETUP }

sealed interface ModelStatus {
    data object Checking : ModelStatus
    data object Missing : ModelStatus
    data class Importing(val progress: Float) : ModelStatus
    data object Loading : ModelStatus
    data object Ready : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

data class UiState(
    val screen: Screen = Screen.CHAT,
    val model: ModelStatus = ModelStatus.Checking,
    val messages: List<ChatMessage> = emptyList(),
    val memories: List<Memory> = emptyList(),
    /** The reply currently being written by the model, shown live. */
    val pendingReply: String? = null,
    val learning: Boolean = false,
    val autoLearn: Boolean = true,
    val notice: String? = null,
) {
    val busy: Boolean get() = pendingReply != null || learning
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = KnowledgeStore(app)
    private val model = LocalModel(app)
    private val prefs = app.getSharedPreferences("pocketai", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState(autoLearn = prefs.getBoolean(KEY_AUTO_LEARN, true)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { seedBaseKnowledge() }
            refresh()
            if (model.hasModelFile()) {
                loadModel()
            } else {
                _state.update { it.copy(model = ModelStatus.Missing, screen = Screen.SETUP) }
            }
        }
    }

    fun show(screen: Screen) = _state.update { it.copy(screen = screen) }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    fun setAutoLearn(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_LEARN, enabled).apply()
        _state.update { it.copy(autoLearn = enabled) }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(model = ModelStatus.Importing(0f)) }
            try {
                model.close()
                model.importFrom(uri) { progress ->
                    _state.update { it.copy(model = ModelStatus.Importing(progress)) }
                }
                loadModel()
                if (_state.value.model == ModelStatus.Ready) show(Screen.CHAT)
            } catch (e: Exception) {
                _state.update { it.copy(model = ModelStatus.Failed(e.message ?: "Couldn't import the model.")) }
            }
        }
    }

    private suspend fun loadModel() {
        _state.update { it.copy(model = ModelStatus.Loading) }
        try {
            model.load()
            _state.update { it.copy(model = ModelStatus.Ready) }
        } catch (e: Throwable) {
            _state.update {
                it.copy(
                    model = ModelStatus.Failed(e.message ?: "The model couldn't be loaded."),
                    screen = Screen.SETUP,
                )
            }
        }
    }

    fun send(input: String) {
        val text = input.trim()
        if (text.isEmpty() || _state.value.busy) return
        viewModelScope.launch {
            val history = _state.value.messages
            addMessage(Role.USER, text)

            when (val command = Brain.parseCommand(text)) {
                is Brain.Command.Remember -> remember(command.fact)
                is Brain.Command.Forget -> forget(command.topic)
                Brain.Command.ListMemories -> listMemories()
                null -> {
                    if (_state.value.model != ModelStatus.Ready) {
                        addMessage(Role.ASSISTANT, "I'm not ready yet: my AI model is still loading or missing.")
                        return@launch
                    }
                    reply(text, history)
                    if (_state.value.autoLearn && Brain.shouldTryLearning(text)) learnFrom(text)
                }
            }
        }
    }

    private suspend fun reply(text: String, history: List<ChatMessage>) {
        val memories = Brain.relevantMemories(text, _state.value.memories)
        val prompt = Brain.buildChatPrompt(memories, history, text)
        _state.update { it.copy(pendingReply = "") }
        val answer = try {
            Brain.cleanReply(
                model.generate(prompt) { partial ->
                    _state.update { it.copy(pendingReply = Brain.cleanReply(partial)) }
                },
            ).ifEmpty { "…" }
        } catch (e: Exception) {
            "Sorry, something went wrong: ${e.message}"
        }
        _state.update { it.copy(pendingReply = null) }
        addMessage(Role.ASSISTANT, answer)
    }

    /** Asks the model to pull lasting facts out of what the user just said. */
    private suspend fun learnFrom(text: String) {
        _state.update { it.copy(learning = true) }
        try {
            val output = model.generate(Brain.buildExtractionPrompt(text), temperature = 0.1f)
            val learned = Brain.parseExtractedFacts(output).filter { fact ->
                !Brain.isDuplicate(fact, _state.value.memories).also { duplicate ->
                    if (!duplicate) saveMemory(fact)
                }
            }
            if (learned.isNotEmpty()) {
                _state.update { it.copy(notice = "Learned: " + learned.joinToString(" ")) }
            }
        } catch (_: Exception) {
            // Learning is best-effort; the conversation carries on regardless.
        } finally {
            _state.update { it.copy(learning = false) }
        }
    }

    private suspend fun remember(fact: String) {
        if (Brain.isDuplicate(fact, _state.value.memories)) {
            addMessage(Role.ASSISTANT, "I already know that.")
        } else {
            saveMemory(fact)
            addMessage(Role.ASSISTANT, "Got it. I'll remember: $fact")
        }
    }

    private suspend fun forget(topic: String) {
        val matches = Brain.memoriesAbout(topic, _state.value.memories)
        withContext(Dispatchers.IO) { matches.forEach { store.deleteMemory(it.id) } }
        refresh()
        val reply = when (matches.size) {
            0 -> "I couldn't find anything I know about \"$topic\"."
            1 -> "Forgotten: ${matches.first().text}"
            else -> "Forgotten ${matches.size} things:\n" + matches.joinToString("\n") { "• ${it.text}" }
        }
        addMessage(Role.ASSISTANT, reply)
    }

    private suspend fun listMemories() {
        val learned = _state.value.memories.filter { it.source == Memory.SOURCE_CHAT }
        val reply = if (learned.isEmpty()) {
            "You haven't taught me anything yet. Try \"Remember that …\" or just tell me about yourself."
        } else {
            "Here's what you've taught me:\n" + learned.reversed().joinToString("\n") { "• ${it.text}" }
        }
        addMessage(Role.ASSISTANT, reply)
    }

    fun newChat() {
        if (_state.value.busy) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.deleteAllMessages() }
            refresh()
        }
    }

    fun addMemory(text: String) {
        val fact = Brain.cleanFact(text)
        if (fact.isEmpty()) return
        viewModelScope.launch { saveMemory(fact) }
    }

    fun editMemory(id: Long, text: String) {
        val fact = Brain.cleanFact(text)
        if (fact.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.updateMemory(id, fact) }
            refresh()
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.deleteMemory(id) }
            refresh()
        }
    }

    fun forgetEverything() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.deleteAllMemories() }
            refresh()
            _state.update { it.copy(notice = "All memories erased.") }
        }
    }

    fun exportMemories(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val json = store.exportJson()
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray())
                    } ?: error("Couldn't write the file.")
                }
            }
            val count = _state.value.memories.size
            _state.update {
                it.copy(notice = result.fold({ "Saved $count memories." }, { e -> "Backup failed: ${e.message}" }))
            }
        }
    }

    fun importMemories(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val json = getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        it.readBytes().decodeToString()
                    } ?: error("Couldn't read the file.")
                    val existing = store.memories().toMutableList()
                    var added = 0
                    store.parseBackup(json).forEach { (text, source, createdAt) ->
                        if (!Brain.isDuplicate(text, existing)) {
                            val id = store.addMemory(text, source, createdAt)
                            existing += Memory(id, text, source, createdAt)
                            added++
                        }
                    }
                    added
                }
            }
            refresh()
            _state.update {
                it.copy(notice = result.fold({ n -> "Restored $n memories." }, { e -> "Restore failed: ${e.message}" }))
            }
        }
    }

    private suspend fun saveMemory(fact: String) {
        withContext(Dispatchers.IO) { store.addMemory(fact, Memory.SOURCE_CHAT) }
        refresh()
    }

    private suspend fun addMessage(role: Role, text: String) {
        val message = withContext(Dispatchers.IO) { store.addMessage(role, text) }
        _state.update { it.copy(messages = it.messages + message) }
    }

    private suspend fun refresh() {
        val (memories, messages) = withContext(Dispatchers.IO) { store.memories() to store.messages() }
        _state.update { it.copy(memories = memories, messages = messages) }
    }

    /** Loads assets/base_knowledge.txt the first time the app runs. */
    private fun seedBaseKnowledge() {
        if (prefs.getBoolean(KEY_BASE_SEEDED, false)) return
        val lines = getApplication<Application>().assets.open("base_knowledge.txt").bufferedReader().use { reader ->
            reader.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        }
        lines.forEach { store.addMemory(it, Memory.SOURCE_BASE) }
        prefs.edit().putBoolean(KEY_BASE_SEEDED, true).apply()
    }

    override fun onCleared() {
        model.close()
        store.close()
    }

    private companion object {
        const val KEY_AUTO_LEARN = "auto_learn"
        const val KEY_BASE_SEEDED = "base_seeded_v1"
    }
}
