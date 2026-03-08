package com.dictara.bot

import org.telegram.telegrambots.bots.DefaultBotOptions
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.GetFile
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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class DictaraBot(
    private val token: String,
    dictaraUrl: String,
    options: DefaultBotOptions = DefaultBotOptions(),
) : TelegramLongPollingBot(options, token) {

    private val client = DictaraClient(dictaraUrl)
    private val gemini = GeminiClient()
    private val executor = Executors.newCachedThreadPool()
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
        val userId = message.from.id

        when {
            message.hasText() && message.text.startsWith("/start") -> {
                send(chatId, "Send me any audio or voice message and I'll transcribe it.\nUse /settings to configure model and diarization.")
                return
            }
            message.hasText() && message.text.startsWith("/settings") -> {
                sendSettings(chatId, userId)
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
                send(chatId, "Send me an audio or video file. Use /settings to configure preferences.")
                return
            }
        }

        val prefs = UserSettings.get(userId)
        val modelLabel = prefs.model.replaceFirstChar { it.uppercase() }
        val speakersLabel = if (prefs.diarize) "on" else "off"
        val summaryLabel = if (prefs.summarize && gemini.isAvailable()) "on" else "off"
        val baseLabel = "⏳ Transcribing your audio...\n\nModel: $modelLabel | Speakers: $speakersLabel | Summary: $summaryLabel"
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
                    val result = client.transcribe(audioTmp, prefs.model, prefs.diarize, prefs.summarize && gemini.isAvailable()) { progressText ->
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
                    val sentMsg = sendTranscript(chatId, result)
                    try {
                        execute(DeleteMessage.builder().chatId(chatId.toString()).messageId(statusMsg.messageId).build())
                    } catch (_: Exception) {}

                    // Phase 3: summarize and edit caption
                    if (prefs.summarize && gemini.isAvailable()) {
                        try {
                            val doneText = formatDoneText(result.durationSeconds)
                            execute(
                                EditMessageCaption.builder()
                                    .chatId(chatId.toString())
                                    .messageId(sentMsg.messageId)
                                    .caption("$doneText\n✍️ Summarizing...")
                                    .build()
                            )
                            val summary = gemini.summarize(result.text, result.audioDurationSeconds)
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
                                send(chatId, summary)
                            }
                        } catch (e: Exception) {
                            send(chatId, "Summary failed: ${e.message}")
                        }
                    }
                } finally {
                    audioTmp.delete()
                }
            } catch (e: Exception) {
                send(chatId, "Error: ${e.message}")
            }
        }
    }

    private fun sendTranscript(chatId: Long, result: TranscriptResult): Message {
        val dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val txtFile = Files.createTempFile("transcript-", ".txt").toFile()
        try {
            txtFile.writeText(result.text)
            return execute(
                SendDocument.builder()
                    .chatId(chatId.toString())
                    .document(InputFile(txtFile, "transcript_$dateStr.txt"))
                    .caption(formatDoneText(result.durationSeconds))
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
        when {
            cb.data.startsWith("set_model:") ->
                UserSettings.update(userId, model = cb.data.removePrefix("set_model:"))
            cb.data.startsWith("set_diarize:") ->
                UserSettings.update(userId, diarize = cb.data.removePrefix("set_diarize:") == "on")
            cb.data.startsWith("set_summarize:") ->
                UserSettings.update(userId, summarize = cb.data.removePrefix("set_summarize:") == "on")
        }
        val prefs = UserSettings.get(userId)
        execute(
            EditMessageReplyMarkup.builder()
                .chatId(cb.message.chatId.toString())
                .messageId(cb.message.messageId)
                .replyMarkup(buildSettingsKeyboard(prefs))
                .build()
        )
        execute(AnswerCallbackQuery.builder().callbackQueryId(cb.id).build())
    }

    private fun sendSettings(chatId: Long, userId: Long) {
        execute(
            SendMessage.builder()
                .chatId(chatId.toString())
                .text("Settings")
                .replyMarkup(buildSettingsKeyboard(UserSettings.get(userId)))
                .build()
        )
    }

    private fun buildSettingsKeyboard(prefs: UserPrefs): InlineKeyboardMarkup {
        fun btn(label: String, data: String) =
            InlineKeyboardButton.builder().text(label).callbackData(data).build()

        val fastMark = if (prefs.model == "fast") " [x]" else ""
        val accMark = if (prefs.model == "accurate") " [x]" else ""
        val onMark = if (prefs.diarize) " [x]" else ""
        val offMark = if (!prefs.diarize) " [x]" else ""
        val sumOnMark = if (prefs.summarize) " [x]" else ""
        val sumOffMark = if (!prefs.summarize) " [x]" else ""

        return InlineKeyboardMarkup.builder()
            .keyboardRow(listOf(btn("Fast$fastMark", "set_model:fast"), btn("Accurate$accMark", "set_model:accurate")))
            .keyboardRow(listOf(btn("Diarize on$onMark", "set_diarize:on"), btn("Diarize off$offMark", "set_diarize:off")))
            .keyboardRow(listOf(btn("Summarize on$sumOnMark", "set_summarize:on"), btn("Summarize off$sumOffMark", "set_summarize:off")))
            .build()
    }

    private fun send(chatId: Long, text: String): Message =
        execute(SendMessage.builder().chatId(chatId.toString()).text(text).build())
}
