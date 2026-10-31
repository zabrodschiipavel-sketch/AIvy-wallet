package com.ivy.wallet.ui.theme.modal

import android.widget.Toast
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ivy.base.model.LoanSaveResult
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.ui.R
import com.ivy.ui.SaveController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.util.UUID

@Composable
internal fun rememberLoanSaveController(id: UUID?, dismiss: () -> Unit): SaveController<LoanSaveResult> {
    val scope = rememberCoroutineScope()
    val currentId by rememberUpdatedState(id)
    val currentDismiss by rememberUpdatedState(dismiss)
    val context = LocalContext.current.applicationContext
    val refreshError = stringResource(R.string.loan_saved_refresh_failed)
    val controller = remember(id) {
        SaveController<LoanSaveResult>(
            scope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job])),
            isCurrent = { id != null && id == currentId },
            onSaved = { result ->
                if (result == LoanSaveResult.RefreshFailed) {
                    Toast.makeText(context, refreshError, Toast.LENGTH_LONG).show()
                }
                currentDismiss()
            },
        )
    }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    return controller
}

internal inline fun SaveController<LoanSaveResult>.editFields(action: () -> Unit) {
    if (!isSaving) action()
}

@Composable
internal fun ColumnScope.LoanSaveStatus(controller: SaveController<LoanSaveResult>) {
    if (controller.failed || controller.isSaving) {
        Text(
            text = stringResource(if (controller.failed) R.string.loan_save_failed else R.string.saving_loan),
            modifier = Modifier.padding(horizontal = 24.dp).testTag("loan_save_status"),
            style = UI.typo.b2.style(),
        )
        Spacer(Modifier.height(16.dp))
    }
}
