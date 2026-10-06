package com.jimmycigs.pocketai.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The on-device language model. The model file lives in the app's private storage and
 * everything runs locally through MediaPipe's LLM Inference engine.
 */
class LocalModel(private val context: Context) {

    private val lock = Mutex()
    private var engine: LlmInference? = null

    private val modelFile = File(context.filesDir, "model.task")

    fun hasModelFile(): Boolean = modelFile.isFile && modelFile.length() > 0

    suspend fun load() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (engine == null) {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(MAX_TOKENS)
                    .build()
                engine = LlmInference.createFromOptions(context, options)
            }
        }
    }

    /** Runs one prompt in a fresh session, reporting the growing reply through [onPartial]. */
    suspend fun generate(
        prompt: String,
        temperature: Float = 0.7f,
        onPartial: (String) -> Unit = {},
    ): String = withContext(Dispatchers.Default) {
        lock.withLock {
            val llm = engine ?: error("The AI model isn't loaded yet.")
            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTemperature(temperature)
                .build()
            val session = LlmInferenceSession.createFromOptions(llm, sessionOptions)
            try {
                session.addQueryChunk(prompt)
                suspendCancellableCoroutine { continuation ->
                    val reply = StringBuilder()
                    val future = session.generateResponseAsync(
                        ProgressListener<String> { partial, done ->
                            if (partial != null) {
                                reply.append(partial)
                                onPartial(reply.toString())
                            }
                            if (done && continuation.isActive) continuation.resume(reply.toString())
                        },
                    )
                    future.addListener({
                        try {
                            future.get()
                        } catch (e: ExecutionException) {
                            if (continuation.isActive) continuation.resumeWithException(e.cause ?: e)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }, Runnable::run)
                }
            } finally {
                session.close()
            }
        }
    }

    /** Copies a model the user picked (e.g. from Downloads) into private storage. */
    suspend fun importFrom(uri: Uri, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = ""
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    if (!cursor.isNull(0)) name = cursor.getString(0)
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        require(!name.endsWith(".litertlm", ignoreCase = true)) {
            "That's a .litertlm file. Pocket AI needs the .task version of the model."
        }

        val partial = File(context.filesDir, "model.task.part")
        val input = resolver.openInputStream(uri) ?: error("Couldn't open that file.")
        input.use { source ->
            partial.outputStream().use { sink ->
                val buffer = ByteArray(1 shl 20)
                var copied = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    copied += read
                    if (size > 0) onProgress((copied.toFloat() / size).coerceAtMost(1f))
                }
            }
        }
        modelFile.delete()
        check(partial.renameTo(modelFile)) { "Couldn't save the model file." }
    }

    fun close() {
        engine?.close()
        engine = null
    }

    companion object {
        /** Prompt plus reply budget, in tokens. */
        const val MAX_TOKENS = 2048
    }
}
