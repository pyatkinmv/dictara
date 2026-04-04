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
import io.micrometer.core.instrument.MeterRegistry
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class DictaraBot(
    private val token: String,
    dictaraUrl: String,
    options: DefaultBotOptions = DefaultBotOptions(),
    registry: MeterRegistry,
    private val baseUrl: String = "https://dictary.app",
    private val botApiDataDir: String = System.getenv("BOT_API_DATA_DIR") ?: "/var/lib/telegram-bot-api",
) : TelegramLongPollingBot(options, token) {

    private val log = LoggerFactory.getLogger(DictaraBot::class.java)  // rebuild
    private val client = DictaraClient(dictaraUrl)
    private val executor = Executors.newCachedThreadPool()
    private val supportedExtensions: Set<String> = client.fetchSupportedExtensions()

    private val volumeFilesGauge  = AtomicLong(0).also { registry.gauge("dictara_bot_api_volume_files", it) }
    private val volumeBytesGauge  = AtomicLong(0).also { registry.gauge("dictara_bot_api_volume_bytes", it) }
    private val oldestAgeGauge    = AtomicLong(0).also { registry.gauge("dictara_bot_api_oldest_file_age_seconds", it) }
    private val deletedFilesCounter = registry.counter("dictara_bot_api_cleanup_deleted_files_total")
    private val deletedBytesCounter = registry.counter("dictara_bot_api_cleanup_deleted_bytes_total")

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
                    val pending = client.fetchPendingDeliveries()
                    if (pending.isNotEmpty()) {
                        log.info("Poller: fetched {} pending deliveries: {}", pending.size, pending.map { it.jobId })
                    }
                    for (d in pending) {
                        log.info("Poller: acking jobId={} status={}", d.jobId, d.status)
                        val claimed = try { client.ackDelivery(d.jobId) } catch (e: Exception) {
                            log.error("Poller: ackDelivery failed for jobId={}: {}", d.jobId, e.message, e)
                            false
                        }
                        log.info("Poller: ack result jobId={} claimed={}", d.jobId, claimed)
                        if (!claimed) {
                            log.info("Poller: delivery already claimed by inline handler, skipping: jobId={}", d.jobId)
                            continue
                        }
                        try {
                            when (d.status) {
                                "done" -> {
                                    log.info("Poller: sending transcript for jobId={} chatId={}", d.jobId, d.chatId)
                                    val result = client.fetchJobResult(d.jobId)
                                    val sentMsg = sendTranscript(d.chatId, result, d.telegramMessageId)
                                    client.confirmDelivery(d.jobId)
                                    log.info("Poller: transcript sent for jobId={}", d.jobId)
                                    if (result.summary != null) {
                                        try {
                                            val doneText = formatDoneText(result.durationSeconds)
                                            val caption = "$doneText\n\n${result.summary}"
                                            if (caption.length <= 1024) {
                                                execute(
                                                    EditMessageCaption.builder()
                                                        .chatId(d.chatId.toString())
                                                        .messageId(sentMsg.messageId)
                                                        .caption(caption)
                                                        .build()
                                                )
                                            } else {
                                                val truncated = if (result.summary.length > 4096) result.summary.take(4093) + "…" else result.summary
                                                send(d.chatId, truncated, replyToMessageId = d.telegramMessageId)
                                            }
                                        } catch (e: Exception) {
                                            send(d.chatId, "Summary failed: ${e.message}", replyToMessageId = d.telegramMessageId)
                                        }
                                    }
                                }
                                "failed" -> {
                                    send(d.chatId, "❌ Transcription failed: ${d.error ?: "unknown error"}", replyToMessageId = d.telegramMessageId)
                                    client.confirmDelivery(d.jobId)
                                }
                            }
                        } catch (e: Exception) {
                            val retryAfterS = (e as? TelegramApiRequestException)?.parameters?.retryAfter?.toLong()
                            log.error("Poller: send failed jobId={} retryAfterS={}: {}", d.jobId, retryAfterS, e.message, e)
                            if (retryAfterS != null) {
                                val url = "$baseUrl/transcript?jobId=${d.jobId}"
                                try {
                                    send(d.chatId, "Transcript is ready — download it here: $url", replyToMessageId = d.telegramMessageId)
                                    client.confirmDelivery(d.jobId)
                                    log.info("Poller: delivered as link jobId={}", d.jobId)
                                } catch (linkEx: Exception) {
                                    log.error("Poller: link send also failed jobId={}: {}", d.jobId, linkEx.message)
                                    try { client.failDelivery(d.jobId, retryAfterS) } catch (ex: Exception) {
                                        log.error("Poller: could not report failure for jobId={}: {}", d.jobId, ex.message)
                                    }
                                }
                            } else {
                                try { client.failDelivery(d.jobId, retryAfterS) } catch (ex: Exception) {
                                    log.error("Poller: could not report failure for jobId={}: {}", d.jobId, ex.message)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    log.error("Poller: error fetching deliveries: {}", e.message, e)
                }
            }
        }

        executor.submit {
            while (true) {
                Thread.sleep(60_000)
                try {
                    val root = File(botApiDataDir)
                    if (!root.exists()) continue

                    val allFiles = root.walkTopDown().filter { it.isFile }.toList()
                    volumeFilesGauge.set(allFiles.size.toLong())
                    volumeBytesGauge.set(allFiles.sumOf { it.length() })
                    oldestAgeGauge.set(
                        allFiles.maxOfOrNull { System.currentTimeMillis() - it.lastModified() }
                            ?.div(1000) ?: 0
                    )

                    val tenMinutesAgo = System.currentTimeMillis() - 10 * 60 * 1000L
                    val toDelete = allFiles.filter { it.lastModified() < tenMinutesAgo }
                    toDelete.forEach { f ->
                        val size = f.length()
                        if (f.delete()) {
                            deletedFilesCounter.increment()
                            deletedBytesCounter.increment(size.toDouble())
                            log.debug("Cleanup deleted: path={} size={}b", f.path, size)
                        }
                    }
                    if (toDelete.isNotEmpty())
                        log.info("Cleanup: deleted={} files, freed={}b", toDelete.size, toDelete.sumOf { it.length() })

                } catch (e: Exception) {
                    log.error("Cleanup job error: {}", e.message, e)
                }
            }
        }
    }

    private val fileBaseUrl = System.getenv("TELEGRAM_API_URL") ?: "https://api.telegram.org"

    override fun getBotUsername() = "DictaraBot"

    override fun onUpdateReceived(update: Update) {
        log.debug("Update received: id={} hasMessage={} hasCallback={}", update.updateId, update.hasMessage(), update.hasCallbackQuery())
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

        log.debug("Message from userId={} chatId={} type={}", userId, chatId, message.chat.type)

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
        val originalMessageId = message.messageId.toLong()
        val statusMsg: Message? = try {
            send(chatId, baseLabel, replyToMessageId = originalMessageId)
        } catch (e: Exception) {
            log.warn("Audio: could not send Transcribing status to chatId={}: {}", chatId, e.message)
            null
        }

        log.info("Audio received: fileId={} chatId={} prefs={}/{}/{}/{}", fileId, chatId, prefs.model, if (prefs.diarize) "diarize" else "nodiarize", prefs.language, prefs.summaryMode)
        executor.submit {
            try {
                val audioTmp = downloadAudioFile(fileId)
                try {

                    // Phase 1: transcribe (with live progress updates)
                    log.info("Submitting to gateway: chatId={} fileId={} size={}b", chatId, fileId, audioTmp.length())
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
                        telegramMessageId = originalMessageId,
                    ) { progressText ->
                        if (statusMsg != null) try {
                            execute(
                                EditMessageText.builder()
                                    .chatId(chatId.toString())
                                    .messageId(statusMsg.messageId)
                                    .text("$baseLabel\n$progressText")
                                    .build()
                            )
                        } catch (_: Exception) {}
                    }

                    log.info("Transcription done: chatId={} jobId={} durationS={}", chatId, result.jobId, result.durationSeconds)
                    // Phase 2: atomically claim delivery — only the caller that gets claimed=true sends the transcript.
                    log.info("Inline: acking jobId={}", result.jobId)
                    val claimed = try { client.ackDelivery(result.jobId) } catch (e: Exception) {
                        log.error("Inline: ackDelivery failed for jobId={}: {}", result.jobId, e.message, e)
                        false
                    }
                    log.info("Inline: ack result jobId={} claimed={}", result.jobId, claimed)
                    if (!claimed) {
                        log.warn("Inline: delivery already claimed by background poller, skipping send: jobId={} chatId={}", result.jobId, chatId)
                        if (statusMsg != null) try { execute(DeleteMessage.builder().chatId(chatId.toString()).messageId(statusMsg.messageId).build()) } catch (_: Exception) {}
                        return@submit
                    }
                    log.info("Inline: sending transcript for jobId={} chatId={}", result.jobId, chatId)
                    val sentMsg = try {
                        val msg = sendTranscript(chatId, result, originalMessageId)
                        client.confirmDelivery(result.jobId)
                        log.info("Inline: transcript sent for jobId={}", result.jobId)
                        msg
                    } catch (e: Exception) {
                        val retryAfterS = (e as? TelegramApiRequestException)?.parameters?.retryAfter?.toLong()
                        log.error("Inline: send failed jobId={} retryAfterS={}: {}", result.jobId, retryAfterS, e.message, e)
                        if (retryAfterS != null) {
                            val url = "$baseUrl/transcript?jobId=${result.jobId}"
                            try {
                                send(chatId, "Transcript is ready — download it here: $url", replyToMessageId = originalMessageId)
                                client.confirmDelivery(result.jobId)
                                log.info("Inline: delivered as link jobId={}", result.jobId)
                            } catch (linkEx: Exception) {
                                log.error("Inline: link send also failed jobId={}: {}", result.jobId, linkEx.message)
                                try { client.failDelivery(result.jobId, retryAfterS) } catch (ex: Exception) {
                                    log.error("Inline: could not report failure for jobId={}: {}", result.jobId, ex.message)
                                }
                            }
                        } else {
                            try { client.failDelivery(result.jobId, retryAfterS) } catch (ex: Exception) {
                                log.error("Inline: could not report failure for jobId={}: {}", result.jobId, ex.message)
                            }
                        }
                        return@submit
                    }
                    if (statusMsg != null) try {
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
                log.error("Failed to process audio: chatId={} fileId={} error={}", chatId, fileId, e.message, e)
                send(chatId, "Something went wrong, please try again later", replyToMessageId = originalMessageId)
            }
        }
    }

    private fun downloadAudioFile(fileId: String): File {
        val maxAttempts = 5
        var delayMs = 2000L
        var lastException: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                if (attempt > 1) {
                    log.warn("GetFile retry {}/{}: fileId={} waitMs={}", attempt, maxAttempts, fileId, delayMs)
                    Thread.sleep(delayMs)
                    delayMs *= 2
                }
                log.debug("GetFile attempt {}/{}: fileId={}", attempt, maxAttempts, fileId)
                val tgFile = execute(GetFile().apply { this.fileId = fileId })
                log.debug("GetFile success: fileId={} localPath={} attempt={}", fileId, tgFile.filePath, attempt)
                val ext = tgFile.filePath.substringAfterLast('.', "bin")
                val audioTmp = Files.createTempFile("dictara-", ".$ext").toFile()
                val localPath = tgFile.filePath
                if (fileBaseUrl != "https://api.telegram.org" && localPath.startsWith("/")) {
                    log.debug("Copying local file: localPath={}", localPath)
                    File(localPath).inputStream().use { input ->
                        audioTmp.outputStream().use { input.copyTo(it) }
                    }
                    log.debug("File copied: localPath={} size={}b", localPath, audioTmp.length())
                } else {
                    URL("$fileBaseUrl/file/bot$token/${localPath.trimStart('/')}")
                        .openStream().use { input ->
                            audioTmp.outputStream().use { input.copyTo(it) }
                        }
                }
                return audioTmp
            } catch (e: Exception) {
                lastException = e
                log.warn("GetFile attempt {}/{} failed: fileId={} error={}", attempt, maxAttempts, fileId, e.message)
            }
        }
        log.error("GetFile exhausted all {} attempts: fileId={} lastError={}", maxAttempts, fileId, lastException?.message)
        throw lastException!!
    }

    private fun sendTranscript(chatId: Long, result: TranscriptResult, replyToMessageId: Long? = null): Message {
        val dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val txtFile = Files.createTempFile("transcript-", ".txt").toFile()
        try {
            txtFile.writeText(result.text)
            return execute(
                SendDocument.builder()
                    .chatId(chatId.toString())
                    .document(InputFile(txtFile, "transcript_$dateStr.txt"))
                    .caption(formatDoneText(result.durationSeconds))
                    .apply { if (replyToMessageId != null) replyToMessageId(replyToMessageId.toInt()) }
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

    private fun send(chatId: Long, text: String, replyToMessageId: Long? = null): Message =
        execute(SendMessage.builder().chatId(chatId.toString()).text(text)
            .apply { if (replyToMessageId != null) replyToMessageId(replyToMessageId.toInt()) }
            .build())
}
