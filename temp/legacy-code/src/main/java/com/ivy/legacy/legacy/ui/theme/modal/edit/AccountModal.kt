package com.ivy.wallet.ui.theme.modal.edit

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.domain.legacy.ui.IvyColorPicker
import com.ivy.legacy.IvyWalletPreview
import com.ivy.legacy.datamodel.Account
import com.ivy.base.model.AccountSaveResult
import com.ivy.ui.account.AccountSaveController
import com.ivy.legacy.utils.isNotNullOrBlank
import com.ivy.legacy.utils.onScreenStart
import com.ivy.legacy.utils.selectEndTextFieldValue
import com.ivy.legacy.utils.toLowerCaseLocal
import com.ivy.legacy.utils.toUpperCaseLocal
import com.ivy.ui.R
import com.ivy.wallet.domain.data.IvyCurrency
import com.ivy.wallet.domain.deprecated.logic.model.CreateAccountData
import com.ivy.wallet.ui.theme.Gray
import com.ivy.wallet.ui.theme.Ivy
import com.ivy.wallet.ui.theme.components.IvyCheckboxWithText
import com.ivy.wallet.ui.theme.modal.ChooseIconModal
import com.ivy.wallet.ui.theme.modal.CurrencyModal
import com.ivy.wallet.ui.theme.modal.IvyModal
import com.ivy.wallet.ui.theme.modal.ModalAddSave
import com.ivy.wallet.ui.theme.modal.ModalAmountSection
import com.ivy.wallet.ui.theme.modal.ModalTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.util.UUID

@Deprecated("Old design system. Use `:ivy-design` and Material3")
data class AccountModalData(
    val account: Account?,
    val baseCurrency: String,
    val balance: Double,
    val adjustBalanceMode: Boolean = false,
    val forceNonZeroBalance: Boolean = false,
    val autoFocusKeyboard: Boolean = true,
    val id: UUID = UUID.randomUUID()
)

@Deprecated("Old design system. Use `:ivy-design` and Material3")
@Composable
fun BoxWithConstraintsScope.AccountModal(
    modal: AccountModalData?,
    onCreateAccount: suspend (CreateAccountData) -> AccountSaveResult,
    onEditAccount: suspend (Account, balance: Double) -> AccountSaveResult,
    dismiss: () -> Unit,
) {
    val account = modal?.account
    var nameTextFieldValue by remember(modal) {
        mutableStateOf(selectEndTextFieldValue(account?.name))
    }
    var color by remember(modal) {
        mutableStateOf(account?.color?.let { Color(it) } ?: Ivy)
    }
    var amount by remember(modal) {
        mutableStateOf(modal?.balance ?: 0.0)
    }
    var currencyCode by remember(modal) {
        mutableStateOf(account?.currency ?: modal?.baseCurrency ?: "")
    }
    var icon by remember(modal) {
        mutableStateOf(account?.icon)
    }
    var includeInBalance by remember(modal) {
        mutableStateOf(account?.includeInBalance ?: true)
    }

    var amountModalVisible by remember { mutableStateOf(false) }
    var currencyModalVisible by remember { mutableStateOf(false) }
    var chooseIconModalVisible by remember(modal) {
        mutableStateOf(false)
    }

    val forceNonZeroBalance = modal?.forceNonZeroBalance ?: false
    val scope = rememberCoroutineScope()
    val modalId = modal?.id
    val currentId by rememberUpdatedState(modalId)
    val currentDismiss by rememberUpdatedState(dismiss)
    val refreshError = stringResource(R.string.account_saved_refresh_failed)
    val appContext = LocalContext.current.applicationContext
    val saveController = remember(modalId) {
        AccountSaveController(
            scope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job])),
            isCurrent = { modalId != null && currentId == modalId },
            onSaved = { result ->
                if (result == AccountSaveResult.RefreshFailed) {
                    Toast.makeText(appContext, refreshError, Toast.LENGTH_LONG).show()
                }
                currentDismiss()
            },
        )
    }
    DisposableEffect(saveController) {
        onDispose { saveController.dispose() }
    }
    fun submit(balance: Double) {
        saveController.submit {
            save(
                account = account,
                nameTextFieldValue = nameTextFieldValue,
                currency = currencyCode,
                color = color,
                icon = icon,
                amount = balance,
                includeInBalance = includeInBalance,
                onCreateAccount = onCreateAccount,
                onEditAccount = onEditAccount,
            )
        }
    }

    IvyModal(
        id = modal?.id,
        visible = modal != null,
        dismiss = { saveController.editFields { dismiss() } },
        shiftIfKeyboardShown = false,
        PrimaryAction = {
            ModalAddSave(
                item = modal?.account,
                enabled = !saveController.isSaving && nameTextFieldValue.text.isNotNullOrBlank() &&
                    (!forceNonZeroBalance || amount > 0)
            ) {
                submit(amount)
            }
        }
    ) {
        onScreenStart {
            if (modal?.adjustBalanceMode == true) {
                amountModalVisible = true
            }
        }

        Spacer(Modifier.height(32.dp))

        ModalTitle(
            text = if (modal?.account != null) {
                stringResource(
                    R.string.edit_account
                )
            } else {
                stringResource(R.string.new_account)
            },
        )

        Spacer(Modifier.height(24.dp))

        AccountSaveStatus(failed = saveController.failed, saving = saveController.isSaving)

        IconNameRow(
            hint = stringResource(R.string.account_name),
            defaultIcon = R.drawable.ic_custom_account_m,
            color = color,
            icon = icon,

            autoFocusKeyboard = modal?.autoFocusKeyboard ?: true,

            nameTextFieldValue = nameTextFieldValue,
            setNameTextFieldValue = { saveController.editFields { nameTextFieldValue = it } },
            showChooseIconModal = {
                saveController.editFields { chooseIconModalVisible = true }
            }
        )

        Spacer(Modifier.height(24.dp))

        IvyColorPicker(
            selectedColor = color,
            onColorSelected = { saveController.editFields { color = it } }
        )

        Spacer(modifier = Modifier.height(40.dp))

        ModalAmountSection(
            Header = {
                AccountBalanceHeader(
                    currencyCode = currencyCode,
                    includeInBalance = includeInBalance,
                    checkboxModifier = Modifier.align(Alignment.Start),
                    onCurrencyClick = { saveController.editFields { currencyModalVisible = true } },
                    onIncludeChange = { saveController.editFields { includeInBalance = it } },
                )
            },
            label = stringResource(R.string.enter_account_balance).uppercase(),
            currency = currencyCode,
            amount = amount,
            amountPaddingTop = 40.dp,
            amountPaddingBottom = 40.dp,
        ) {
            saveController.editFields { amountModalVisible = true }
        }
    }

    val amountModalId = remember(modal, amount) {
        UUID.randomUUID()
    }
    AmountModal(
        id = amountModalId,
        visible = amountModalVisible,
        currency = currencyCode,
        initialAmount = amount,
        showPlusMinus = true,
        dismiss = { amountModalVisible = false }
    ) { newAmount ->
        saveController.editFields {
            amount = newAmount
            if (modal?.adjustBalanceMode == true) submit(newAmount)
        }
    }

    val context = LocalContext.current
    CurrencyModal(
        title = stringResource(R.string.choose_currency),
        initialCurrency = IvyCurrency.fromCode(currencyCode),
        visible = currencyModalVisible,
        dismiss = { currencyModalVisible = false }
    ) {
        saveController.editFields { currencyCode = it }

//        if (IvyCurrency.fromCode(it)?.isCrypto == true) {
//            if (getCustomIconId(context = context, iconName = it, size = "m") != null) {
//                icon = it
//            }
//        }
    }

    ChooseIconModal(
        visible = chooseIconModalVisible,
        initialIcon = icon ?: "account",
        color = color,
        dismiss = { chooseIconModalVisible = false }
    ) {
        saveController.editFields { icon = it }
    }
}

private inline fun AccountSaveController.editFields(action: () -> Unit) {
    if (!isSaving) action()
}

@Composable
private fun AccountSaveStatus(failed: Boolean, saving: Boolean) {
    if (failed || saving) {
        Text(
            text = stringResource(if (failed) R.string.account_save_failed else R.string.saving_account),
            modifier = Modifier.padding(horizontal = 24.dp).testTag("account_save_status"),
            style = UI.typo.b2.style(),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AccountBalanceHeader(
    currencyCode: String,
    includeInBalance: Boolean,
    checkboxModifier: Modifier,
    onCurrencyClick: () -> Unit,
    onIncludeChange: (Boolean) -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    AccountCurrency(currencyCode = currencyCode, onClick = onCurrencyClick)
    Spacer(Modifier.height(16.dp))
    IvyCheckboxWithText(
        modifier = checkboxModifier.padding(start = 16.dp),
        text = stringResource(R.string.include_account),
        checked = includeInBalance,
        onCheckedChange = onIncludeChange,
    )
}

private suspend fun save(
    account: Account?,
    nameTextFieldValue: TextFieldValue,
    currency: String,
    color: Color,
    icon: String?,
    amount: Double,
    includeInBalance: Boolean,

    onCreateAccount: suspend (CreateAccountData) -> AccountSaveResult,
    onEditAccount: suspend (Account, balance: Double) -> AccountSaveResult,
): AccountSaveResult {
    return if (account != null) {
        onEditAccount(
            account.copy(
                name = nameTextFieldValue.text.trim(),
                currency = currency,
                includeInBalance = includeInBalance,
                icon = icon,
                color = color.toArgb()
            ),
            amount
        )
    } else {
        onCreateAccount(
            CreateAccountData(
                name = nameTextFieldValue.text.trim(),
                currency = currency,
                color = color,
                icon = icon,
                balance = amount,
                includeBalance = includeInBalance
            )
        )
    }
}

@Composable
private fun AccountCurrency(
    currencyCode: String,

    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .background(UI.colors.medium, UI.shapes.r4)
            .clip(UI.shapes.r4)
            .clickable {
                onClick()
            }
            .padding(vertical = 24.dp)
            .testTag("account_modal_currency"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(32.dp))

        Text(
            text = currencyCode.toUpperCaseLocal(),
            style = UI.typo.b1.style(
                fontWeight = FontWeight.ExtraBold
            )
        )

        Spacer(Modifier.weight(1f))

        val currencyName = IvyCurrency.fromCode(currencyCode)?.name ?: ""
        Text(
            text = "-$currencyName".toLowerCaseLocal(),
            style = UI.typo.b2.style(
                fontWeight = FontWeight.SemiBold,
                color = Gray
            )
        )

        Spacer(Modifier.width(24.dp))
    }
}

@Preview
@Composable
private fun Preview() {
    IvyWalletPreview {
        AccountModal(
            modal = AccountModalData(
                account = null,
                baseCurrency = "BGN",
                balance = 0.0
            ),
            onCreateAccount = { AccountSaveResult.Saved },
            onEditAccount = { _, _ -> AccountSaveResult.Saved }
        ) {
        }
    }
}
