package com.mypet.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mypet.app.PetViewModel
import com.mypet.app.Screen
import com.mypet.app.UiState
import com.mypet.core.Edition
import com.mypet.core.Editions
import com.mypet.core.PetGame

private val Cream = Color(0xFFF1E9D8)
private val Muted = Color(0xFFB4ACBF)

@Composable
fun StartScreen(state: UiState, vm: PetViewModel) {
    var selected by rememberSaveable { mutableStateOf(Editions.all.first().id) }
    var name by rememberSaveable { mutableStateOf("") }
    val hasLivePet = state.pet?.alive == true

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF26252D)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("MyPet", color = Cream, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 30.sp)
                    Text("Choose an edition and hatch your egg.", color = Muted, fontFamily = Mono, fontSize = 13.sp)
                }
                if (state.save.discovered.isNotEmpty()) {
                    TextButton(onClick = { vm.show(Screen.COLLECTION) }) {
                        Text("Collection", color = Muted, fontFamily = Mono)
                    }
                }
            }
        }
        items(Editions.all, key = { it.id }) { edition ->
            EditionCard(edition, selected == edition.id) { selected = edition.id }
        }
        item {
            val edition = Editions.byId(selected)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.filter { c -> c.isLetterOrDigit() || c == ' ' }.take(PetGame.MAX_NAME) },
                    label = { Text("Pet name (optional)", fontFamily = Mono) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Cream, unfocusedTextColor = Cream,
                        focusedBorderColor = argb(edition.shellColor), unfocusedBorderColor = Muted,
                        focusedLabelColor = Cream, unfocusedLabelColor = Muted, cursorColor = Cream,
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (hasLivePet) {
                        WideButton("Cancel", argb(edition.shellAccent), Muted) { vm.show(Screen.DEVICE) }
                    }
                    WideButton("Hatch a ${edition.name} egg", argb(edition.shellAccent), argb(edition.shellColor), weight = 2f) {
                        vm.startNewPet(edition.id, name)
                    }
                }
                if (hasLivePet) {
                    Text(
                        "Hatching a new egg will say goodbye to ${state.pet?.name}.",
                        color = Color(0xFFE5A39B), fontFamily = Mono, fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun EditionCard(edition: Edition, selected: Boolean, onClick: () -> Unit) {
    val shell = argb(edition.shellColor)
    val accent = argb(edition.shellAccent)
    val dark = shell.brightness() < 0.4f
    val textColor = if (dark) Cream else lerpColor(accent, Color.Black, 0.35f)
    val rules = edition.rules
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(shell)
            .border(if (selected) 3.dp else 1.dp, if (selected) accent else accent.copy(alpha = 0.3f), RoundedCornerShape(26.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(argb(edition.lcdColor))
                    .padding(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    PixelIcon(edition.egg.sprite, argb(edition.inkColor), size = 34.dp)
                    PixelIcon(edition.child.sprite, argb(edition.inkColor), size = 30.dp)
                    PixelIcon(edition.adultBest.sprite, argb(edition.inkColor), size = 40.dp)
                }
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(edition.name, color = if (dark) argb(edition.shellAccent) else accent, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text(edition.tagline, color = textColor, fontFamily = Mono, fontSize = 13.sp)
            }
            if (selected) Text("✓", color = if (dark) Cream else accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Text(edition.description, color = textColor, fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(8.dp))
        val growDays = 1.0 + 3 * rules.stageScale
        val facts = listOf(
            "Adult in ~${if (growDays % 1.0 == 0.0) growDays.toInt().toString() else growDays.toString()} days",
            "Bed ${hour(rules.bedtime)}-${hour(rules.wakeTime)}",
            if (rules.gentleEndings) "Gentle endings" else "Classic endings",
            rules.games.joinToString(" / ") { it.title },
        )
        Text(facts.joinToString("  ·  "), color = textColor.copy(alpha = 0.85f), fontFamily = Mono, fontSize = 11.sp)
    }
}

private fun hour(minutes: Int): String {
    val h = minutes / 60
    return when {
        h == 0 -> "12am"
        h < 12 -> "${h}am"
        h == 12 -> "12pm"
        else -> "${h - 12}pm"
    }
}

private fun Color.brightness(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
