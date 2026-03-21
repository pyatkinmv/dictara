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
    private val executor = Executors.newCachedThreadPool()
    private val supportedExtensions: Set<String> = client.fetchSupportedExtensions()

    /** userId → messageId of the settings message currently awaiting a language code reply */
    private val awaitingLanguage = ConcurrentHashMap<Long, Int>()

    /** userIds already reported as having a private bot chat this session — avoids redundant gateway calls */
    private val knownBotStarted = ConcurrentHashMap.newKeySet<Long>()

    init {
        try {
            execute(SetMyCommands.builder()
                .commands(listOf(
                    BotCommand("start", "Welcome"),
                    BotCommand("settings", "Configure model, language, speakers, summary"),
                ))
                .build())
        } catch (_: Exception) {}

        executor.submit {
            while (true) {
                Thread.sleep(2_000)
                try {
                    val notifications = client.fetchPendingLoginNotifications()
                    for (n in notifications) {
                        try {
                            execute(SendMessage.builder()
                                .chatId(n.chatId)
                                .text("Confirm login to Dictara on the web?")
                                .replyMarkup(loginKeyboard(n.token))
                                .build())
                            client.ackLoginNotification(n.id)
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }

        executor.submit {
            while (true) {
                Thread.sleep(5_000)
                try {
                    for (d in client.fetchPendingDeliveries()) {
                        try {
                            when (d.status) {
                                "done" -> {
                                    val result = client.fetchJobResult(d.jobId)
                                    sendTranscript(d.chatId, result)
                                }
                                "failed" -> send(d.chatId, "❌ Transcription failed: ${d.error ?: "unknown error"}")
                            }
                            client.ackDelivery(d.jobId)
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }
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

        if (!isGroup && knownBotStarted.add(userId)) {
            executor.submit { try { client.markBotStarted(userId) } catch (_: Exception) {} }
        }

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
                val username = message.from?.userName
                if (username != null) {
                    val token = try { client.getPendingLoginForUsername(username) } catch (_: Exception) { null }
                    if (token != null) {
                        execute(SendMessage.builder()
                            .chatId(chatId.toString())
                            .text("You have a pending login request for Dictara on the web. Confirm?")
                            .replyMarkup(loginKeyboard(token))
                            .build())
                    }
                }
                return
            }
            message.hasText() && message.text.startsWith("/settings") -> {
                sendSettings(chatId)
                return
            }
        }

        val fileId = when {
            message.hasAnimation() -> return  // GIFs — silently ignore, no message
            message.hasAudio() -> message.audio.fileId
            message.hasVoice() -> message.voice.fileId
            message.hasVideoNote() -> message.videoNote.fileId
            message.hasVideo() -> message.video.fileId
            message.hasDocument() -> {
                val doc = message.document
                val mime = doc.mimeType ?: ""
                if (!mime.startsWith("audio/") && !mime.startsWith("video/")) return  // photo, pdf, etc. — silently ignore
                val ext = doc.fileName?.substringAfterLast('.', "")?.lowercase() ?: ""
                if (ext !in supportedExtensions) {
                    send(chatId, "Unsupported format: .$ext\nSupported: ${supportedExtensions.sorted().joinToString(", ")}")
                    return
                }
                doc.fileId
            }
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
            prefs.summaryMode == SummaryMode.OFF -> "off"
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
                        // Synchronize on localPath: same file_id always maps to the same local path,
                        // so concurrent handlers for the same file would race on copy+delete without this.
                        synchronized(localPath.intern()) {
                            val localFile = File(localPath)
                            // Wait up to 10s for telegram-bot-api to finish writing the file to disk.
                            val deadline = System.currentTimeMillis() + 10_000
                            while (!localFile.exists() && System.currentTimeMillis() < deadline) {
                                Thread.sleep(200)
                            }
                            if (!localFile.exists()) {
                                // File consumed by a concurrent handler for the same file_id — discard duplicate.
                                try { execute(DeleteMessage.builder().chatId(chatId.toString()).messageId(statusMsg.messageId).build()) } catch (_: Exception) {}
                                return@submit
                            }
                            localFile.inputStream().use { input ->
                                audioTmp.outputStream().use { input.copyTo(it) }
                            }
                            localFile.delete()
                        }
                    } else {
                        URL("$fileBaseUrl/file/bot$token/${localPath.trimStart('/')}")
                            .openStream().use { input ->
                                audioTmp.outputStream().use { input.copyTo(it) }
                            }
                    }

                    // Phase 1: transcribe (with live progress updates)
                    val sender = message.from
                    val result = client.transcribe(
                        audioTmp, prefs.model, prefs.diarize,
                        prefs.summaryMode,
                        prefs.language, prefs.numSpeakers,
                        telegramUserId = sender.id,
                        telegramUsername = sender.userName,
                        telegramFirstName = sender.firstName,
                        telegramLastName = sender.lastName,
                        chatId = chatId,
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

                    // Phase 2: ack delivery first (prevents background poller from sending a duplicate),
                    // then send transcript and delete status message.
                    // NOTE: if sendTranscript crashes after the ack, the transcript is lost with no retry.
                    // Trade-off accepted: duplicates on every job are worse than a rare lost delivery.
                    try { client.ackDelivery(result.jobId) } catch (_: Exception) {}
                    val sentMsg = sendTranscript(chatId, result, originalMessageId)
                    try {
                        execute(DeleteMessage.builder().chatId(chatId.toString()).messageId(statusMsg.messageId).build())
                    } catch (_: Exception) {}

                    // Phase 3: update caption with summary (already computed by gateway)
                    if (result.summary != null) {
                        try {
                            val doneText = formatDoneText(result.durationSeconds)
                            val caption = "$doneText\n\n${result.summary}"
                            if (caption.length <= 1024) {
                                execute(
                                    EditMessageCaption.builder()
                                        .chatId(chatId.toString())
                                        .messageId(sentMsg.messageId)
                                        .caption(caption)
                                        .build()
                                )
                            } else {
                                val truncated = if (result.summary.length > 4096) result.summary.take(4093) + "…" else result.summary
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
            cb.data.startsWith("confirm_login:") -> {
                val token = cb.data.removePrefix("confirm_login:")
                try {
                    client.confirmLoginCallback(token, cb.from.id, cb.from.userName, cb.from.firstName, cb.from.lastName)
                    execute(EditMessageText.builder().chatId(chatId.toString()).messageId(cb.message.messageId)
                        .text("✓ You are now logged in to Dictara on the web.").build())
                } catch (_: Exception) {
                    execute(EditMessageText.builder().chatId(chatId.toString()).messageId(cb.message.messageId)
                        .text("Login failed. The link may have expired.").build())
                }
                execute(AnswerCallbackQuery.builder().callbackQueryId(cb.id).build())
                return
            }
            cb.data.startsWith("reject_login:") -> {
                val token = cb.data.removePrefix("reject_login:")
                try { client.rejectLogin(token) } catch (_: Exception) {}
                execute(EditMessageText.builder().chatId(chatId.toString()).messageId(cb.message.messageId)
                    .text("Login request rejected.").build())
                execute(AnswerCallbackQuery.builder().callbackQueryId(cb.id).build())
                return
            }
            cb.data.startsWith("set_model:") ->
                UserSettings.update(chatId, model = cb.data.removePrefix("set_model:"))
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
                when (raw) {
                    "off"  -> UserSettings.update(chatId, diarize = false, clearNumSpeakers = true)
                    "auto" -> UserSettings.update(chatId, diarize = true, clearNumSpeakers = true)
                    else   -> UserSettings.update(chatId, diarize = true, numSpeakers = raw.toIntOrNull())
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

        val langLabel = if (prefs.language == "auto") "Auto" else prefs.language.uppercase()
        val spkLabel  = if (!prefs.diarize) "Off" else if (prefs.numSpeakers == null) "Auto" else prefs.numSpeakers.toString()

        return InlineKeyboardMarkup.builder()
            .keyboardRow(listOf(btn("Fast$fastMark", "set_model:fast"), btn("Accurate$accMark", "set_model:accurate")))
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
            val mark = when {
                value == "off"  && !prefs.diarize -> " ✓"
                value != "off"  && prefs.diarize  && (if (prefs.numSpeakers == null) value == "auto" else prefs.numSpeakers.toString() == value) -> " ✓"
                else -> ""
            }
            return InlineKeyboardButton.builder().text("$label$mark").callbackData("set_speakers:$value").build()
        }
        fun plain(label: String, data: String) =
            InlineKeyboardButton.builder().text(label).callbackData(data).build()

        return InlineKeyboardMarkup.builder()
            .keyboardRow(listOf(btn("Off", "off"), btn("Auto", "auto"), btn("1", "1")))
            .keyboardRow(listOf(btn("2", "2"), btn("3", "3"), btn("4", "4"), btn("5+", "5")))
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

    private fun loginKeyboard(token: String): InlineKeyboardMarkup =
        InlineKeyboardMarkup.builder()
            .keyboardRow(listOf(
                InlineKeyboardButton.builder().text("✓ Confirm").callbackData("confirm_login:$token").build(),
                InlineKeyboardButton.builder().text("✗ Reject").callbackData("reject_login:$token").build(),
            ))
            .build()

    private fun send(chatId: Long, text: String): Message =
        execute(SendMessage.builder().chatId(chatId.toString()).text(text).build())
}
