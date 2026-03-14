package com.dictara.bot

import org.telegram.telegrambots.bots.DefaultBotOptions
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageCaption
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class DictaraBot(
    private val token: String,
    dictaraUrl: String,
    options: DefaultBotOptions = DefaultBotOptions(),
) : TelegramLongPollingBot(options, token) {

    private val client = DictaraClient(dictaraUrl)
    private val gemini = GeminiClient()
    private val executor = Executors.newCachedThreadPool()

    /** userId → messageId of the settings message currently awaiting a language code reply */
    private val awaitingLanguage = ConcurrentHashMap<Long, Int>()

    init {
        try {
            execute(SetMyCommands.builder()
                .commands(listOf(
                    BotCommand("start", "Welcome"),
                    BotCommand("settings", "Configure model, language, speakers, summary"),
                ))
                .build())
        } catch (_: Exception) {}
    }

    private val fileBaseUrl = System.getenv("TELEGRAM_API_URL") ?: "https://api.telegram.org"

    override fun getBotUsername() = "DictaraBot"

    override fun onUpdateReceived(update: Update) {
        when {
            update.hasCallbackQuery() -> handleCallback(update.callbackQuery)
            update.hasMessage() -> handleMessage(update.message)
        }
    }

    private fun handleMessage(message: Message) {
        val chatId = message.chatId
        val userId = message.from?.id ?: return  // channels have no sender; ignore silently
        val isGroup = message.chat.type != "private"

        // Commands always take priority — cancel any pending awaiting state
        if (message.hasText() && message.text.startsWith("/")) {
            awaitingLanguage.remove(userId)
        }

        // Intercept free-text language code if user tapped "Other..."
        if (message.hasText() && awaitingLanguage.containsKey(userId)) {
            val code = message.text.trim().lowercase()
            val displayName = java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH)
            if (displayName.isBlank() || displayName.equals(code, ignoreCase = true)) {
                send(chatId, "\"${message.text.trim()}\" is not a recognized language code. Try a 2-letter ISO code (e.g. `en`, `ru`, `zh`, `ja`). Try again:")
                return
            }
            val msgId = awaitingLanguage.remove(userId)!!
            UserSettings.update(chatId, language = code)
            val prefs = UserSettings.get(chatId)
            execute(
                EditMessageText.builder()
                    .chatId(chatId.toString())
                    .messageId(msgId)
                    .text("Settings")
                    .replyMarkup(buildSettingsKeyboard(prefs))
                    .build()
            )
            return
        }

        when {
            message.hasText() && message.text.startsWith("/start") -> {
                send(chatId, "Send me any audio or voice message and I'll transcribe it.\nUse /settings to configure model and diarization.")
                return
            }
            message.hasText() && message.text.startsWith("/settings") -> {
                sendSettings(chatId)
                return
            }
        }

        val fileId = when {
            message.hasAudio() -> message.audio.fileId
            message.hasVoice() -> message.voice.fileId
            message.hasVideoNote() -> message.videoNote.fileId
            message.hasVideo() -> message.video.fileId
            message.hasDocument() -> message.document.fileId
            else -> {
                if (!isGroup) send(chatId, "Send me an audio or video file. Use /settings to configure preferences.")
                return
            }
        }

        val prefs = UserSettings.get(chatId)
        val modelLabel = prefs.model.replaceFirstChar { it.uppercase() }
        val langLabel = if (prefs.language == "auto") "Auto" else prefs.language.uppercase()
        val spkLabel  = if (prefs.numSpeakers != null) " (${prefs.numSpeakers})" else ""
        val speakersLabel = if (prefs.diarize) "on$spkLabel" else "off"
        val summaryLabel = when {
            prefs.summaryMode == SummaryMode.OFF || !gemini.isAvailable() -> "off"
            prefs.summaryMode == SummaryMode.AUTO -> "on"
            else -> prefs.summaryMode.label  // "Brief", "Concise", or "Full"
        }
        val senderTag = if (isGroup) message.from?.userName?.let { "@$it" } ?: message.from?.firstName ?: "Someone" else null
        val who = if (senderTag != null) "$senderTag's" else "your"
        val baseLabel = "⏳ Transcribing $who audio...\n\nModel: $modelLabel | Speakers: $speakersLabel | Lang: $langLabel | Summary: $summaryLabel"
        val originalMessageId = if (isGroup) message.messageId else null
        val statusMsg = send(chatId, baseLabel)

        executor.submit {
            try {
                val tgFile = execute(GetFile().apply { this.fileId = fileId })
                val ext = tgFile.filePath.substringAfterLast('.', "bin")
                val audioTmp = Files.createTempFile("dictara-", ".$ext").toFile()
                try {
                    val localPath = tgFile.filePath
                    if (fileBaseUrl != "https://api.telegram.org" && localPath.startsWith("/")) {
                        File(localPath).inputStream().use { input ->
                            audioTmp.outputStream().use { input.copyTo(it) }
                        }
                        File(localPath).delete()
                    } else {
                        URL("$fileBaseUrl/file/bot$token/${localPath.trimStart('/')}")
                            .openStream().use { input ->
                                audioTmp.outputStream().use { input.copyTo(it) }
                            }
                    }

                    // Phase 1: transcribe (with live progress updates)
                    val result = client.transcribe(
                        audioTmp, prefs.model, prefs.diarize,
                        prefs.summaryMode != SummaryMode.OFF && gemini.isAvailable(),
                        prefs.language, prefs.numSpeakers
                    ) { progressText ->
                        try {
                            execute(
                                EditMessageText.builder()
                                    .chatId(chatId.toString())
                                    .messageId(statusMsg.messageId)
                                    .text("$baseLabel\n$progressText")
                                    .build()
                            )
                        } catch (_: Exception) {}
                    }

                    // Phase 2: send transcript immediately, delete status message
                    val sentMsg = sendTranscript(chatId, result, originalMessageId)
                    try {
                        execute(DeleteMessage.builder().chatId(chatId.toString()).messageId(statusMsg.messageId).build())
                    } catch (_: Exception) {}

                    // Phase 3: summarize and edit caption
                    if (prefs.summaryMode != SummaryMode.OFF && gemini.isAvailable()) {
                        try {
                            val doneText = formatDoneText(result.durationSeconds)
                            execute(
                                EditMessageCaption.builder()
                                    .chatId(chatId.toString())
                                    .messageId(sentMsg.messageId)
                                    .caption("$doneText\n✍️ Summarizing...")
                                    .build()
                            )
                            val summary = gemini.summarize(
                                result.text,
                                result.audioDurationSeconds,
                                prefs.summaryMode,
                                prefs.language,
                            )
                            val caption = "$doneText\n\n$summary"
                            if (caption.length <= 1024) {
                                execute(
                                    EditMessageCaption.builder()
                                        .chatId(chatId.toString())
                                        .messageId(sentMsg.messageId)
                                        .caption(caption)
                                        .build()
                                )
                            } else {
                                execute(
                                    EditMessageCaption.builder()
                                        .chatId(chatId.toString())
                                        .messageId(sentMsg.messageId)
                                        .caption(doneText)
                                        .build()
                                )
                                val truncated = if (summary.length > 4096) summary.take(4093) + "…" else summary
                                send(chatId, truncated)
                            }
                        } catch (e: Exception) {
                            send(chatId, "Summary failed: ${e.message}")
                        }
                    }
                } finally {
                    audioTmp.delete()
                }
            } catch (e: Exception) {
                send(chatId, if (senderTag != null) "Error transcribing $senderTag's audio: ${e.message}" else "Error: ${e.message}")
            }
        }
    }

    private fun sendTranscript(chatId: Long, result: TranscriptResult, replyToMessageId: Int? = null): Message {
        val dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val txtFile = Files.createTempFile("transcript-", ".txt").toFile()
        try {
            txtFile.writeText(result.text)
            return execute(
                SendDocument.builder()
                    .chatId(chatId.toString())
                    .document(InputFile(txtFile, "transcript_$dateStr.txt"))
                    .caption(formatDoneText(result.durationSeconds))
                    .apply { if (replyToMessageId != null) replyToMessageId(replyToMessageId) }
                    .build()
            )
        } finally {
            txtFile.delete()
        }
    }

    private fun formatDoneText(durationSeconds: Double?): String =
        durationSeconds?.let {
            val min = (it / 60).toInt()
            val sec = (it % 60).toInt()
            "Done in ${if (min > 0) "${min}m " else ""}${sec}s."
        } ?: "Done."

    private fun handleCallback(cb: CallbackQuery) {
        val userId = cb.from.id
        val chatId = cb.message.chatId
        when {
            cb.data.startsWith("set_model:") ->
                UserSettings.update(chatId, model = cb.data.removePrefix("set_model:"))
            cb.data.startsWith("set_diarize:") ->
                UserSettings.update(chatId, diarize = cb.data.removePrefix("set_diarize:") == "on")
            cb.data.startsWith("set_language:") -> {
                val code = cb.data.removePrefix("set_language:")
                UserSettings.update(chatId, language = code)
            }
            cb.data == "lang_custom" -> {
                awaitingLanguage[userId] = cb.message.messageId
                execute(
                    EditMessageText.builder()
                        .chatId(chatId.toString())
                        .messageId(cb.message.messageId)
                        .text("Type a 2-letter ISO language code (e.g. `ja`, `zh`, `ar`, `uk`):")
                        .build()
                )
                execute(AnswerCallbackQuery.builder().callbackQueryId(cb.id).build())
                return
            }
            cb.data == "open_language" -> {
                val prefs = UserSettings.get(chatId)
                execute(
                    EditMessageReplyMarkup.builder()
                        .chatId(chatId.toString())
                        .messageId(cb.message.messageId)
                        .replyMarkup(buildLanguageKeyboard(prefs))
                        .build()
                )
                execute(AnswerCallbackQuery.builder().callbackQueryId(cb.id).build())
                return
            }
            cb.data == "open_speakers" -> {
                val prefs = UserSettings.get(chatId)
                execute(
                    EditMessageReplyMarkup.builder()
                        .chatId(chatId.toString())
                        .messageId(cb.message.messageId)
                        .replyMarkup(buildSpeakersKeyboard(prefs))
                        .build()
                )
                execute(AnswerCallbackQuery.builder().callbackQueryId(cb.id).build())
                return
            }
            cb.data == "open_summary_mode" -> {
                val prefs = UserSettings.get(chatId)
                execute(
                    EditMessageReplyMarkup.builder()
                        .chatId(chatId.toString())
                        .messageId(cb.message.messageId)
                        .replyMarkup(buildSummaryModeKeyboard(prefs))
                        .build()
                )
                execute(AnswerCallbackQuery.builder().callbackQueryId(cb.id).build())
                return
            }
            cb.data.startsWith("set_summary_mode:") -> {
                val raw = cb.data.removePrefix("set_summary_mode:")
                val mode = SummaryMode.entries.find { it.name.lowercase() == raw }
                if (mode != null) UserSettings.update(chatId, summaryMode = mode)
                // unknown raw value: silently ignore — enum is closed, can't happen in practice
            }
            cb.data == "back_settings" -> { /* fall through to keyboard update below */ }
            cb.data.startsWith("set_speakers:") -> {
                val raw = cb.data.removePrefix("set_speakers:")
                if (raw == "auto") {
                    UserSettings.update(chatId, clearNumSpeakers = true)
                } else {
                    UserSettings.update(chatId, numSpeakers = raw.toIntOrNull())
                }
            }
        }
        val prefs = UserSettings.get(chatId)
        execute(
            EditMessageReplyMarkup.builder()
                .chatId(cb.message.chatId.toString())
                .messageId(cb.message.messageId)
                .replyMarkup(buildSettingsKeyboard(prefs))
                .build()
        )
        execute(AnswerCallbackQuery.builder().callbackQueryId(cb.id).build())
    }

    private fun sendSettings(chatId: Long) {
        execute(
            SendMessage.builder()
                .chatId(chatId.toString())
                .text("Settings")
                .replyMarkup(buildSettingsKeyboard(UserSettings.get(chatId)))
                .build()
        )
    }

    private fun buildSettingsKeyboard(prefs: UserPrefs): InlineKeyboardMarkup {
        fun btn(label: String, data: String) =
            InlineKeyboardButton.builder().text(label).callbackData(data).build()

        val fastMark = if (prefs.model == "fast")     " ✓" else ""
        val accMark  = if (prefs.model == "accurate")  " ✓" else ""
        val onMark   = if (prefs.diarize)              " ✓" else ""
        val offMark  = if (!prefs.diarize)             " ✓" else ""

        val langLabel = if (prefs.language == "auto") "Auto" else prefs.language.uppercase()
        val spkLabel  = if (prefs.numSpeakers == null) "Auto" else prefs.numSpeakers.toString()

        return InlineKeyboardMarkup.builder()
            .keyboardRow(listOf(btn("Fast$fastMark", "set_model:fast"), btn("Accurate$accMark", "set_model:accurate")))
            .keyboardRow(listOf(btn("Diarize on$onMark", "set_diarize:on"), btn("Diarize off$offMark", "set_diarize:off")))
            .keyboardRow(listOf(btn("📋 Summary: ${prefs.summaryMode.label}", "open_summary_mode")))
            .keyboardRow(listOf(btn("🌐 Language: $langLabel", "open_language")))
            .keyboardRow(listOf(btn("👥 Speakers: $spkLabel", "open_speakers")))
            .build()
    }

    private fun buildLanguageKeyboard(prefs: UserPrefs): InlineKeyboardMarkup {
        fun btn(label: String, code: String): InlineKeyboardButton {
            val mark = if (prefs.language == code) " ✓" else ""
            return InlineKeyboardButton.builder().text("$label$mark").callbackData("set_language:$code").build()
        }
        fun plain(label: String, data: String) =
            InlineKeyboardButton.builder().text(label).callbackData(data).build()

        return InlineKeyboardMarkup.builder()
            .keyboardRow(listOf(btn("Auto", "auto"), btn("EN", "en"), btn("RU", "ru")))
            .keyboardRow(listOf(btn("DE", "de"), btn("ES", "es"), btn("FR", "fr")))
            .keyboardRow(listOf(plain("Other...", "lang_custom")))
            .keyboardRow(listOf(plain("← Back", "back_settings")))
            .build()
    }

    private fun buildSpeakersKeyboard(prefs: UserPrefs): InlineKeyboardMarkup {
        fun btn(label: String, value: String): InlineKeyboardButton {
            val current = if (prefs.numSpeakers == null) "auto" else prefs.numSpeakers.toString()
            val mark = if (current == value) " ✓" else ""
            return InlineKeyboardButton.builder().text("$label$mark").callbackData("set_speakers:$value").build()
        }
        fun plain(label: String, data: String) =
            InlineKeyboardButton.builder().text(label).callbackData(data).build()

        return InlineKeyboardMarkup.builder()
            .keyboardRow(listOf(btn("Auto", "auto"), btn("1", "1"), btn("2", "2")))
            .keyboardRow(listOf(btn("3", "3"), btn("4", "4"), btn("5+", "auto")))
            .keyboardRow(listOf(plain("← Back", "back_settings")))
            .build()
    }

    private fun buildSummaryModeKeyboard(prefs: UserPrefs): InlineKeyboardMarkup {
        fun plain(label: String, data: String) =
            InlineKeyboardButton.builder().text(label).callbackData(data).build()

        val rows = SummaryMode.entries.map { mode ->
            val mark = if (prefs.summaryMode == mode) " ✓" else ""
            listOf(InlineKeyboardButton.builder()
                .text("${mode.label}$mark")
                .callbackData("set_summary_mode:${mode.name.lowercase()}")
                .build())
        }.toMutableList()
        rows += listOf(listOf(plain("← Back", "back_settings")))

        return InlineKeyboardMarkup.builder().keyboard(rows).build()
    }

    private fun send(chatId: Long, text: String): Message =
        execute(SendMessage.builder().chatId(chatId.toString()).text(text).build())
}
