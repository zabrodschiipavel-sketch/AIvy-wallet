package com.ivy.ai.domain

import com.ivy.ai.data.GeminiClient
import com.ivy.ai.data.buildParseSchema
import com.ivy.ai.data.parseTransactionJson
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject

/**
 * Turns a free-form Russian description ("вчера 1500 продукты Пятёрочка")
 * into a [ParsedTransaction] draft using Gemini.
 *
 * The caller passes lists of *valid* category and account names so the model
 * picks from them instead of inventing new ones.
 */
class ParseTransactionUseCase @Inject constructor(
    private val gemini: GeminiClient,
    private val json: Json,
) {
    suspend operator fun invoke(
        userText: String,
        categoryNames: List<String>,
        accountNames: List<String>,
        today: LocalDate = LocalDate.now(),
    ): Result<ParsedTransaction> = runCatching {
        val prompt = buildPrompt(userText, categoryNames, accountNames, today)
        val schema = buildParseSchema(categoryNames, accountNames)
        val raw = gemini.generateStructured(prompt, schema)
        parseTransactionJson(raw, json)
    }

    private fun buildPrompt(
        userText: String,
        categoryNames: List<String>,
        accountNames: List<String>,
        today: LocalDate,
    ): String = buildString {
        appendLine("Ты помощник для учёта личных финансов. Извлеки из текста пользователя одну транзакцию и верни её строго по схеме JSON.")
        appendLine()
        appendLine("Правила:")
        appendLine("- amount — положительное число (даже для расхода).")
        appendLine("- transactionType — EXPENSE если потратил, INCOME если получил, TRANSFER если перевод между своими счетами.")
        appendLine("- date — ISO-формат (YYYY-MM-DD). Сегодня: $today.")
        appendLine("  «сегодня» = $today, «вчера» = ${today.minusDays(1)}, «позавчера» = ${today.minusDays(2)}.")
        appendLine("  Если дата не указана — ставь сегодняшнюю.")
        appendLine("- category — выбери ОДНО значение строго из списка ниже, либо null если не подходит ничего.")
        appendLine("- account — выбери ОДНО значение строго из списка ниже, либо null если пользователь не упомянул счёт.")
        appendLine("- currency — трёхбуквенный код (RUB по умолчанию для русскоязычного ввода, если не указано иное).")
        appendLine("- description — короткое описание (что/где куплено), без суммы и без даты.")
        appendLine()
        if (categoryNames.isNotEmpty()) {
            appendLine("Доступные категории:")
            categoryNames.forEach { appendLine("  - $it") }
        } else {
            appendLine("Список категорий пуст — оставь category = null.")
        }
        appendLine()
        if (accountNames.isNotEmpty()) {
            appendLine("Доступные счета:")
            accountNames.forEach { appendLine("  - $it") }
        } else {
            appendLine("Список счетов пуст — оставь account = null.")
        }
        appendLine()
        appendLine("Текст пользователя:")
        appendLine(userText)
    }
}
