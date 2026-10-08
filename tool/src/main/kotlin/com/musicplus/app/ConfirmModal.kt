package com.musicplus.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightModal
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.CompletableDeferred

/**
 * Full-screen "are you sure?" confirmation for a destructive action, shown via
 * LightModalManager — pulled out of QueueScreen's original "Clear queue?"
 * modal once deleting a server and clearing all local data needed the
 * identical shape. Timing out without a choice behaves as Deny (onExpired is
 * a no-op) — the safe default for an unconfirmed destructive action.
 */
class ConfirmModal(
    private val title: String,
    private val message: String,
    private val confirmContentDescription: String = "Confirm",
    private val onConfirm: () -> Unit,
) : LightModal {
    private val dismissSignal = CompletableDeferred<Unit>()

    override val onExpired: () -> Unit = {}

    override fun dismiss() {
        dismissSignal.complete(Unit)
    }

    override suspend fun awaitDismiss() {
        dismissSignal.await()
    }

    @Composable
    override fun Content() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background.copy(alpha = 0.96f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp()),
            ) {
                LightText(text = title, variant = LightTextVariant.Heading, align = TextAlign.Center)
                LightText(
                    text = message,
                    variant = LightTextVariant.Detail,
                    align = TextAlign.Center,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp(), bottom = 1.5f.gridUnitsAsDp()),
                )
                Row {
                    LightIcon(
                        icon = LightIcons.DENY,
                        size = 2f,
                        contentDescription = "Cancel",
                        modifier = Modifier
                            .lightClickable { dismiss() }
                            .padding(horizontal = 2f.gridUnitsAsDp()),
                    )
                    LightIcon(
                        icon = LightIcons.ACCEPT,
                        size = 2f,
                        contentDescription = confirmContentDescription,
                        modifier = Modifier
                            .lightClickable {
                                onConfirm()
                                dismiss()
                            }
                            .padding(horizontal = 2f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}
