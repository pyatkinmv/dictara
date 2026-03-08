package com.dictara.bot

import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.Executors

class DictaraBot(
    private val token: String,
    dictaraUrl: String,
) : TelegramLongPollingBot(token) {

    private val client = DictaraClient(dictaraUrl)
    private val executor = Executors.newCachedThreadPool()

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
        val diarizeLabel = if (prefs.diarize) "diarization" else "no diarization"
        send(chatId, "Transcribing with ${prefs.model} + $diarizeLabel...")

        executor.submit {
            try {
                val tgFile = execute(GetFile().apply { this.fileId = fileId })
                val ext = tgFile.filePath.substringAfterLast('.', "bin")
                val audioTmp = Files.createTempFile("dictara-", ".$ext").toFile()
                try {
                    URL("https://api.telegram.org/file/bot$token/${tgFile.filePath}")
                        .openStream().use { input ->
                            audioTmp.outputStream().use { input.copyTo(it) }
                        }
                    val result = client.transcribe(audioTmp, prefs.model, prefs.diarize)
                    sendTranscript(chatId, result)
                } finally {
                    audioTmp.delete()
                }
            } catch (e: Exception) {
                send(chatId, "Error: ${e.message}")
            }
        }
    }

    private fun sendTranscript(chatId: Long, result: TranscriptResult) {
        val txtFile = Files.createTempFile("transcript-", ".txt").toFile()
        try {
            txtFile.writeText(result.text)
            val caption = result.durationSeconds?.let {
                val min = (it / 60).toInt()
                val sec = (it % 60).toInt()
                "Done in ${if (min > 0) "${min}m " else ""}${sec}s."
            } ?: "Done."
            execute(
                SendDocument.builder()
                    .chatId(chatId.toString())
                    .document(InputFile(txtFile, "transcript.txt"))
                    .caption(caption)
                    .build()
            )
        } finally {
            txtFile.delete()
        }
    }

    private fun handleCallback(cb: CallbackQuery) {
        val userId = cb.from.id
        when {
            cb.data.startsWith("set_model:") ->
                UserSettings.update(userId, model = cb.data.removePrefix("set_model:"))
            cb.data.startsWith("set_diarize:") ->
                UserSettings.update(userId, diarize = cb.data.removePrefix("set_diarize:") == "on")
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

        return InlineKeyboardMarkup.builder()
            .keyboardRow(listOf(btn("Fast$fastMark", "set_model:fast"), btn("Accurate$accMark", "set_model:accurate")))
            .keyboardRow(listOf(btn("Diarize on$onMark", "set_diarize:on"), btn("Diarize off$offMark", "set_diarize:off")))
            .build()
    }

    private fun send(chatId: Long, text: String) {
        execute(SendMessage.builder().chatId(chatId.toString()).text(text).build())
    }
}
