package com.mypet.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mypet.app.Action
import com.mypet.app.GamePhase
import com.mypet.app.Menu
import com.mypet.app.PetViewModel
import com.mypet.app.Screen
import com.mypet.app.UiState
import com.mypet.core.GameKind
import com.mypet.core.PixelArt
import com.mypet.core.Stage
import kotlinx.coroutines.delay

val Mono = FontFamily.Monospace

@Composable
fun DeviceScreen(state: UiState, vm: PetViewModel, onSettings: () -> Unit) {
    val pet = state.pet ?: return
    val edition = pet.edition
    val shell = argb(edition.shellColor)
    val accent = argb(edition.shellAccent)
    val lcd = argb(edition.lcdColor)
    val ink = argb(edition.inkColor)
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(pet.call, pet.callStartMinute) {
        if (pet.call != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF34323E), Color(0xFF1E1D24))))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("MyPet", color = Color(0xFFF1E9D8), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.show(Screen.COLLECTION) }) {
                Text("Collection", color = Color(0xFFD9CFBD), fontFamily = Mono)
            }
            TextButton(onClick = onSettings) { Text("Settings", color = Color(0xFFD9CFBD), fontFamily = Mono) }
        }
        Spacer(Modifier.weight(0.5f))

        // The handheld shell.
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .shadow(18.dp, RoundedCornerShape(44.dp))
                .clip(RoundedCornerShape(44.dp))
                .background(Brush.verticalGradient(listOf(lerpColor(shell, Color.White, 0.25f), shell, lerpColor(shell, Color.Black, 0.12f))))
                .border(2.dp, lerpColor(shell, Color.Black, 0.2f), RoundedCornerShape(44.dp))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    edition.name.uppercase(),
                    color = accent, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 15.sp,
                )
                Text("  ${edition.tagline}", color = accent.copy(alpha = 0.75f), fontFamily = Mono, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                CallLed(active = pet.alive && pet.call != null)
                Spacer(Modifier.size(8.dp))
                val m = pet.minuteOfDay
                Text("%02d:%02d".format(m / 60, m % 60), color = accent, fontFamily = Mono, fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(lerpColor(accent, Color.Black, 0.45f))
                    .padding(12.dp),
            ) {
                LcdView(
                    state = state,
                    lcdColor = lcd,
                    inkColor = ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = state.menu == Menu.STATUS) { vm.nextStatusPage() },
                )
            }
            Spacer(Modifier.height(10.dp))
            Caption(state, vm, color = lerpColor(accent, Color.Black, 0.25f))
            Spacer(Modifier.height(12.dp))
            Controls(state, vm, shell, accent)
        }

        Spacer(Modifier.weight(1f))
        val speed = state.save.settings.speed
        Text(
            when {
                state.save.paused -> "Paused: time is frozen"
                speed == 1 -> "Real time: 1 pet day = 1 day"
                else -> "${speed}x speed"
            },
            color = Color(0xFF9C95A8), fontFamily = Mono, fontSize = 12.sp,
        )
    }
}

@Composable
private fun CallLed(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "led")
    val pulse by transition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "pulse",
    )
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (active) Color(0xFFE5483B).copy(alpha = pulse) else Color(0x33000000)),
    )
}

@Composable
private fun Caption(state: UiState, vm: PetViewModel, color: Color) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.messageAt) {
        now = System.currentTimeMillis()
        delay(MESSAGE_MILLIS + 50)
        now = System.currentTimeMillis()
    }
    val pet = state.pet ?: return
    val recent = state.message != null && now - state.messageAt < MESSAGE_MILLIS
    val text = when {
        !pet.alive -> vm.endingText(state)
        recent -> state.message
        state.save.paused -> "Paused."
        pet.stage == Stage.EGG -> "Something is stirring..."
        state.menu == Menu.STATUS -> "Tap the screen for the next page."
        state.menu == Menu.PLAY -> state.game?.kind?.help
        vm.callText(state) != null -> vm.callText(state)
        pet.asleep -> if (pet.lightsOn) "${pet.name} fell asleep." else "Sweet dreams, ${pet.name}."
        else -> "${pet.name} the ${pet.form.name}"
    } ?: ""
    Text(
        text,
        color = color,
        fontFamily = Mono,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
    )
}

private const val MESSAGE_MILLIS = 3500L

@Composable
private fun Controls(state: UiState, vm: PetViewModel, shell: Color, accent: Color) {
    val pet = state.pet ?: return
    if (!pet.alive) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WideButton("New egg", accent, shell) { vm.show(Screen.START) }
            WideButton("Collection", accent, shell) { vm.show(Screen.COLLECTION) }
        }
        return
    }
    when (state.menu) {
        Menu.FEED -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WideButton("Meal", accent, shell) { vm.feed(meal = true) }
            WideButton("Snack", accent, shell) { vm.feed(meal = false) }
            WideButton("Back", accent, shell, weight = 0.7f) { vm.closeMenu() }
        }
        Menu.PLAY_PICK -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pet.edition.rules.games.forEach { kind ->
                    WideButton(kind.title, accent, shell) { vm.startGame(kind) }
                }
            }
            Row { WideButton("Back", accent, shell) { vm.closeMenu() } }
        }
        Menu.PLAY -> GameControls(state, vm, shell, accent)
        Menu.STATUS -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WideButton("Next", accent, shell) { vm.nextStatusPage() }
            WideButton("Done", accent, shell) { vm.closeMenu() }
        }
        Menu.NONE -> ActionGrid(state, vm, shell, accent)
    }
}

@Composable
private fun ActionGrid(state: UiState, vm: PetViewModel, shell: Color, accent: Color) {
    val paused = state.save.paused
    val rows = listOf(
        listOf(
            Triple(Action.STATUS, ActionIcons.status, "Status"),
            Triple(Action.FEED, ActionIcons.feed, "Feed"),
            Triple(Action.PLAY, ActionIcons.play, "Play"),
            Triple(Action.CLEAN, ActionIcons.clean, "Clean"),
        ),
        listOf(
            Triple(Action.MEDICINE, ActionIcons.medicine, "Medicine"),
            Triple(Action.LIGHT, ActionIcons.light, "Light"),
            Triple(Action.SCOLD, ActionIcons.scold, "Scold"),
            Triple(Action.PAUSE, if (paused) ActionIcons.resume else ActionIcons.pause, if (paused) "Resume" else "Pause"),
        ),
    )
    val haptics = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (action, icon, label) ->
                    val enabled = !paused || action == Action.PAUSE
                    ActionButton(icon, label, accent, shell, enabled) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        vm.onAction(action)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.ActionButton(
    icon: PixelArt,
    label: String,
    accent: Color,
    shell: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val face = lerpColor(shell, Color.White, 0.35f)
    Column(
        Modifier
            .weight(1f)
            .aspectRatio(1f)
            .shadow(3.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) face else face.copy(alpha = 0.5f))
            .border(1.5.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PixelIcon(icon, if (enabled) accent else accent.copy(alpha = 0.4f), size = 26.dp)
        Spacer(Modifier.height(4.dp))
        Text(label, color = accent, fontFamily = Mono, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
fun RowScope.WideButton(
    label: String,
    accent: Color,
    shell: Color,
    weight: Float = 1f,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .weight(weight)
            .height(54.dp)
            .shadow(3.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(lerpColor(shell, Color.White, 0.35f))
            .border(1.5.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun GameControls(state: UiState, vm: PetViewModel, shell: Color, accent: Color) {
    val game = state.game ?: return
    val waiting = game.phase == GamePhase.WAITING
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (game.kind) {
                GameKind.WHICH_WAY -> {
                    WideButton("< Left", accent, shell) { if (waiting) vm.gameInput(0) }
                    WideButton("Right >", accent, shell) { if (waiting) vm.gameInput(1) }
                }
                GameKind.HI_LO -> {
                    WideButton("Higher", accent, shell) { if (waiting) vm.gameInput(1) }
                    WideButton("Lower", accent, shell) { if (waiting) vm.gameInput(0) }
                }
                GameKind.CATCH -> WideButton("STOP!", accent, shell) {
                    if (waiting) {
                        val x = catchStarX(game, System.currentTimeMillis())
                        vm.gameInput(if (catchIsHit(x)) 1 else 0)
                    }
                }
            }
        }
        Row { WideButton("Quit game", accent, shell) { vm.quitGame() } }
    }
}
