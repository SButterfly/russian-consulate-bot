package com.github.russianconsulatebot.services

import com.github.russianconsulatebot.utils.executeAsync
import com.github.russianconsulatebot.utils.offset
import com.github.russianconsulatebot.utils.timeout
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.TelegramException
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.GetUpdates
import com.pengrad.telegrambot.request.SendMessage
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import kotlin.math.max

@Service
class TelegramBotService(
    private val bot: TelegramBot,
    private val lastChecks: LastChecks,
    private val businessLogicCoroutineDispatcher: ExecutorCoroutineDispatcher,
    @Value("\${telegram_bot.enabled:true}")
    private val startTelegramBot: Boolean,
) {
    private val scope: CoroutineScope = CoroutineScope(businessLogicCoroutineDispatcher)
    private val log = LoggerFactory.getLogger(TelegramBotService::class.java)

    @Volatile
    private var job: Job? = null

    @PostConstruct
    fun start() {
        if (!startTelegramBot) {
            return
        }

        log.info("Starting GetUpdates telegram background process")
        job?.cancel()

        val handler = CoroutineExceptionHandler { _, exception ->
            log.error("Caught $exception during telegram job runner", exception)
        }
        job = scope.launch(context = handler) {
            var request = GetUpdates().timeout(30) // secs
            while (true) {
                try {
                    val lastUpdateId = processRequest(request)
                    if (lastUpdateId != null) {
                        request = request.offset(lastUpdateId + 1)
                    }
                } catch (e: Exception) {
                    log.error("Got an exception trying to process for {} timeout secs and {} offset",
                        request.timeout, request.offset, e)
                }

                yield()
            }
        }
        log.info("Started GetUpdates telegram background process")
    }

    @PreDestroy
    fun stop() {
        if (!startTelegramBot) {
            return
        }

        log.info("Cancelling telegram background process")
        job?.cancel()
        log.info("Cancelled telegram background process")
    }

    private suspend fun processRequest(request: GetUpdates): Int? {
        log.debug("Started a check for {} timeout secs and {} offset",
            request.timeout, request.offset)

        val response = bot.executeAsync(request)

        log.debug("Received {}", response)

        if (!response.isOk) {
            throw TelegramException("${response.errorCode()} ${response.description()}", response)
        }

        val updates = response.updates()
        for (update: Update in updates) {
            try {
                processUpdate(update)
            } catch (e: Exception) {
                log.error("Failed to process an update: $update", e)
            }
        }

        return updates.lastOrNull()?.updateId()
    }

    private suspend fun processUpdate(update: Update) {
        val message = update.message()

        when (message.text()) {
            "/start" -> {
                val request = SendMessage(
                    message.chat().id(),
                    "Bot started. Your char_id is ${update.message()!!.chat().id()}"
                )
                bot.executeAsync(request)
            }

            "/log" -> {
                val successful = lastChecks.successfulNumberOfAttempts
                val totalNumber = lastChecks.totalNumberOfAttempts
                val rate = successful * 100 / (totalNumber.takeIf { it != 0 } ?: 1)
                val stats = "Successful attempts: $successful/$totalNumber ($rate%)"

                val checks = lastChecks.get().joinToString("\n") { "* $it" }
                // No more, that 4096 symbols in total, so keep last entries
                val trimmedText = checks.substring(max(checks.length - 3900, 0), checks.length)

                val request = SendMessage(message.chat().id(), "Last checks:\n$trimmedText\n\n$stats")
                val result = bot.executeAsync(request)
                if (!result.isOk) {
                    log.error(
                        "Got an error while sending a response to telegram API: {} {}",
                        result.errorCode(),
                        result.description()
                    )
                    bot.executeAsync(
                        SendMessage(
                            message.chat().id(),
                            "Error while sending a response to telegram API: $result"
                        )
                    )
                }
            }

            "/ping" -> {
                val request =
                    SendMessage(message.chat().id(), "Pong. Your char_id is ${update.message().chat().id()}")
                bot.executeAsync(request)
            }

            else -> {
                val request = SendMessage(
                    message.chat().id(), "Unsupported message. " +
                        "Supported only: /start, /log, /ping"
                )
                val response = bot.executeAsync(request)
                if (!response.isOk) {
                    log.error("Got an error while sending a response to telegram API: {}", response)
                }
            }
        }
    }

    suspend fun sendMessage(chatId: Long, message: String) {
        val result = bot.executeAsync(SendMessage(chatId, message))
        if (!result.isOk) {
            throw IllegalStateException("Failed to send a ${result.errorCode()}")
        }
    }
}