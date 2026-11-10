package com.mypet.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mypet.app.PetViewModel
import com.mypet.app.Screen
import com.mypet.app.UiState
import com.mypet.core.Edition
import com.mypet.core.Editions

private val Cream = Color(0xFFF1E9D8)
private val Muted = Color(0xFFB4ACBF)

/** Every form you've raised, plus a log of past pets. */
@Composable
fun CollectionScreen(state: UiState, vm: PetViewModel) {
    val discovered = state.save.discovered
    val back = if (state.pet != null) Screen.DEVICE else Screen.START
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF26252D)),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Collection", color = Cream, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 26.sp)
                    val total = Editions.all.sumOf { it.forms.size }
                    Text("${discovered.size} of $total forms found", color = Muted, fontFamily = Mono, fontSize = 13.sp)
                }
                TextButton(onClick = { vm.show(back) }) { Text("Back", color = Cream, fontFamily = Mono) }
            }
        }
        items(Editions.all, key = { it.id }) { edition -> EditionForms(edition, discovered) }
        if (state.save.history.isNotEmpty()) {
            item {
                Text("Past pets", color = Cream, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            items(state.save.history.reversed()) { entry ->
                val edition = Editions.byId(entry.editionId)
                val form = edition.forms.firstOrNull { it.id == entry.formId }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF34323E))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (form != null) PixelIcon(form.sprite, argb(edition.lcdColor), size = 32.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("${entry.name} · ${form?.name ?: "?"}", color = Cream, fontFamily = Mono, fontSize = 14.sp)
                        val ending = if (edition.rules.gentleEndings) entry.ending.gentle else entry.ending.classic
                        Text(
                            "${edition.name} · age ${entry.ageDays} · ${entry.careMistakes} care mistakes",
                            color = Muted, fontFamily = Mono, fontSize = 11.sp,
                        )
                        Text(ending, color = Muted, fontFamily = Mono, fontSize = 11.sp)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun EditionForms(edition: Edition, discovered: Set<String>) {
    val lcd = argb(edition.lcdColor)
    val ink = argb(edition.inkColor)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(argb(edition.shellColor))
            .padding(14.dp),
    ) {
        Text(
            edition.name,
            color = argb(edition.shellAccent),
            fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 18.sp,
        )
        Spacer(Modifier.height(8.dp))
        edition.forms.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                row.forEach { form ->
                    val found = form.id in discovered
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(lcd)
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PixelIcon(form.sprite, if (found) ink else ink.copy(alpha = 0.12f), size = 44.dp)
                        Text(
                            if (found) form.name else "???",
                            color = ink, fontFamily = Mono, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1,
                        )
                        Text(
                            form.stage.label,
                            color = ink.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 9.sp,
                        )
                    }
                }
            }
        }
    }
}
