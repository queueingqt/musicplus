package com.musicplus.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.ui.LightModal
import com.thelightphone.sdk.ui.LightModalManager
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Duration.Companion.seconds

/**
 * A short line over whatever is on screen that goes away by itself after a couple of seconds, or at a tap — for
 * "Server not reachable" and "Skipped a song", where a whole confirmation dialog would be too much. It has no buttons and
 * nothing to decide, and the SDK has no lighter notice than a modal.
 */
class NoteModal(private val text: String) : LightModal {
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
                .background(LightThemeTokens.colors.background.copy(alpha = 0.96f))
                .lightClickable { dismiss() },
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = text,
                variant = LightTextVariant.Heading,
                align = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp()),
            )
        }
    }

    companion object {
        private val SHOWN_FOR = 2.5.seconds

        fun show(text: String) {
            LightModalManager.show(NoteModal(text), duration = SHOWN_FOR)
        }
    }
}
