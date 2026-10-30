package com.voicetasks

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDateTime

/**
 * Picks a parser: Gemini Nano if the phone has it, else a Gemma model you imported,
 * else [RuleParser]. Everything runs on the phone.
 */
object TaskParser {

    enum class Engine(val label: String) {
        NANO("Gemini Nano"),
        GEMMA("Gemma"),
        RULES("Simple rules (no AI)"),
    }

    data class Result(val tasks: List<ParsedTask>, val engine: Engine)

    private const val TIMEOUT_MS = 30_000L

    private val nano by lazy { Generation.getClient() }

    private val gemmaLock = Mutex()
    private var gemma: LlmInference? = null

    fun gemmaModelFile(context: Context) = File(context.filesDir, "gemma.task")

    suspend fun nanoStatus(): Int =
        runCatching { nano.checkStatus() }.getOrDefault(FeatureStatus.UNAVAILABLE)

    fun downloadNano(): Flow<DownloadStatus> = nano.download()

    /** The engine [parse] will use right now. */
    suspend fun activeEngine(context: Context): Engine = when {
        nanoStatus() == FeatureStatus.AVAILABLE -> Engine.NANO
        gemmaModelFile(context).exists() -> Engine.GEMMA
        else -> Engine.RULES
    }

    /** Loads the model ahead of time. Capture calls this while you're still talking. */
    suspend fun warmUp(context: Context) {
        runCatching {
            when (activeEngine(context)) {
                Engine.NANO -> nano.warmup()
                Engine.GEMMA -> loadGemma(context)
                Engine.RULES -> Unit
            }
        }
    }

    suspend fun parse(
        context: Context,
        transcript: String,
        now: LocalDateTime = LocalDateTime.now(),
        places: List<Place> = PlaceStore(context).all(),
    ): Result {
        val engine = activeEngine(context)
        if (engine != Engine.RULES) {
            val reply = runCatching {
                withTimeoutOrNull(TIMEOUT_MS) { generate(context, engine, LlmPrompt.build(transcript, now)) }
            }.getOrNull()
            val tasks = reply?.let { LlmPrompt.parse(it, now, places, transcript) }
            if (!tasks.isNullOrEmpty()) return Result(tasks, engine)
        }
        return Result(RuleParser.parse(transcript, now, places), Engine.RULES)
    }

    /** Frees the Gemma model, e.g. after its file is removed. */
    suspend fun unloadGemma() = gemmaLock.withLock {
        gemma?.close()
        gemma = null
    }

    private suspend fun generate(context: Context, engine: Engine, prompt: String): String = when (engine) {
        Engine.NANO -> nano.generateContent(
            generateContentRequest(TextPart(prompt)) {
                temperature = 0f
                maxOutputTokens = 256
            },
        ).candidates.firstOrNull()?.text.orEmpty()
        Engine.GEMMA -> {
            val model = loadGemma(context)
            withContext(Dispatchers.Default) { model.generateResponse(prompt) }
        }
        Engine.RULES -> ""
    }

    private suspend fun loadGemma(context: Context): LlmInference = gemmaLock.withLock {
        gemma ?: withContext(Dispatchers.Default) {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(gemmaModelFile(context).absolutePath)
                .setMaxTokens(1024)
                .build()
            LlmInference.createFromOptions(context.applicationContext, options)
        }.also { gemma = it }
    }
}
