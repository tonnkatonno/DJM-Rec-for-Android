package com.audiopro.djmrec.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.audio.RecordingGain
import com.audiopro.djmrec.ui.theme.TextSecondary
import kotlin.math.roundToInt

/**
 * Whole-dB slider over [RecordingGain.MIN_DB]..[RecordingGain.MAX_DB]. It has a position per dB
 * (so TalkBack steps by 1 dB), but that many tick marks would read as a ruler, so they are hidden.
 */
@Composable
fun GainSlider(
    gainDb: Int,
    onGainChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Slider(
        value = gainDb.toFloat(),
        onValueChange = { onGainChange(it.roundToInt()) },
        modifier = modifier.semantics {
            contentDescription = "Recording gain in decibels"
            stateDescription = RecordingGain.label(gainDb)
        },
        enabled = enabled,
        valueRange = RecordingGain.MIN_DB.toFloat()..RecordingGain.MAX_DB.toFloat(),
        steps = RecordingGain.MAX_DB - RecordingGain.MIN_DB - 1,
        colors = SliderDefaults.colors(
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
            disabledActiveTickColor = Color.Transparent,
            disabledInactiveTickColor = Color.Transparent
        )
    )
}

/**
 * Live input gain for the recorder's signal panel: label, dB readout and slider under the
 * meters, so the effect of a change is visible straight away. [singleRow] folds it into one
 * line for short panels.
 */
@Composable
fun InputGainControl(
    gainDb: Int,
    onGainChange: (Int) -> Unit,
    singleRow: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val readout = RecordingGain.label(gainDb)
    if (singleRow) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("GAIN", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            GainSlider(gainDb, onGainChange, Modifier.weight(1f).padding(horizontal = 8.dp), enabled)
            GainReadout(readout)
        }
    } else {
        Column(modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("INPUT GAIN", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                GainReadout(readout)
            }
            GainSlider(gainDb, onGainChange, Modifier.fillMaxWidth(), enabled)
        }
    }
}

@Composable
private fun GainReadout(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.End,
        modifier = Modifier.width(56.dp)
    )
}
