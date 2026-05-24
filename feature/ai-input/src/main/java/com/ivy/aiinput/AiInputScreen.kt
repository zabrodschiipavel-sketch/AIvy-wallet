package com.ivy.aiinput

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ivy.ai.domain.ParsedTransaction
import com.ivy.ai.domain.TransactionTypeGuess
import com.ivy.navigation.navigation
import com.ivy.navigation.screenScopedViewModel

@Composable
fun AiInputScreenImpl(
    viewModel: AiInputViewModel = screenScopedViewModel(),
) {
    AiInputUi(
        uiState = viewModel.uiState(),
        onEvent = viewModel::onEvent,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiInputUi(
    uiState: AiInputState,
    onEvent: (AiInputEvent) -> Unit,
) {
    val nav = navigation()

    // When save succeeds the VM transitions to Phase.Saved; we close the screen here.
    LaunchedEffect(uiState.phase) {
        if (uiState.phase == Phase.Saved) nav.back()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Добавить через AI") },
                navigationIcon = {
                    IconButton(onClick = { nav.back() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InputCard(uiState = uiState, onEvent = onEvent)

            when (uiState.phase) {
                Phase.Preview, Phase.Saving -> uiState.parsed?.let {
                    PreviewCard(
                        parsed = it,
                        defaultCurrency = uiState.defaultCurrency,
                        saving = uiState.phase == Phase.Saving,
                        onSave = { onEvent(AiInputEvent.Save) },
                        onReset = { onEvent(AiInputEvent.Reset) },
                    )
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun InputCard(
    uiState: AiInputState,
    onEvent: (AiInputEvent) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Опишите транзакцию",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Например: «вчера 1500 продукты Пятёрочка» или «зарплата 80000».",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = uiState.inputText,
                onValueChange = { onEvent(AiInputEvent.InputChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Текст") },
                minLines = 2,
                maxLines = 5,
                enabled = uiState.phase != Phase.Parsing && uiState.phase != Phase.Saving,
            )
            Spacer(Modifier.height(4.dp))

            val canParse = uiState.inputText.isNotBlank() &&
                uiState.phase != Phase.Parsing &&
                uiState.phase != Phase.Saving &&
                !uiState.loadingContext
            Button(
                onClick = { onEvent(AiInputEvent.Parse) },
                modifier = Modifier.fillMaxWidth(),
                enabled = canParse,
            ) {
                if (uiState.phase == Phase.Parsing) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("  Распознаю…")
                } else {
                    Text("Распознать")
                }
            }
            if (uiState.loadingContext) {
                Text(
                    text = "Загружаю список ваших категорий и счетов…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PreviewCard(
    parsed: ParsedTransaction,
    defaultCurrency: String,
    saving: Boolean,
    onSave: () -> Unit,
    onReset: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Распознано",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            FieldRow("Сумма", parsed.amount?.let { "%.2f".format(it) } ?: "не определена")
            FieldRow("Валюта", parsed.currency ?: defaultCurrency)
            FieldRow("Тип", parsed.transactionType?.label() ?: "—")
            FieldRow("Категория", parsed.category ?: "—")
            FieldRow("Счёт", parsed.account ?: "— (возьмётся первый)")
            FieldRow("Дата", parsed.date ?: "сегодня")
            FieldRow("Описание", parsed.description ?: "—")

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                    enabled = !saving,
                ) { Text("Начать заново") }
                val amount = parsed.amount
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    enabled = !saving && amount != null && amount > 0.0,
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text("  Сохраняю…")
                    } else {
                        Text("Сохранить")
                    }
                }
            }
            Text(
                text = "Сумму, категорию и счёт можно изменить позже, отредактировав транзакцию в списке.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.65f),
        )
    }
}

private fun TransactionTypeGuess.label(): String = when (this) {
    TransactionTypeGuess.EXPENSE -> "Расход"
    TransactionTypeGuess.INCOME -> "Доход"
    TransactionTypeGuess.TRANSFER -> "Перевод (будет сохранён как расход)"
}
