package com.phynex.NexLink.ui.screens

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phynex.NexLink.ui.theme.*
import com.phynex.NexLink.viewmodel.MainViewModel
import kotlinx.coroutines.launch

// ─── Vibration helper ─────────────────────────────────────────────────────────
private fun vibrate(context: Context, ms: Long = 30) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v?.vibrate(ms)
        }
    }
}

// ─── Keyboard key data ────────────────────────────────────────────────────────
data class KeyInfo(val label: String, val code: String, val widthWeight: Float = 1f)

private val keyboardRows = listOf(
    // Row 0 – Esc + F1-F12
    listOf(
        KeyInfo("Esc","Escape"), KeyInfo("F1","F1"), KeyInfo("F2","F2"), KeyInfo("F3","F3"),
        KeyInfo("F4","F4"), KeyInfo("F5","F5"), KeyInfo("F6","F6"), KeyInfo("F7","F7"),
        KeyInfo("F8","F8"), KeyInfo("F9","F9"), KeyInfo("F10","F10"), KeyInfo("F11","F11"), KeyInfo("F12","F12")
    ),
    // Row 1 – ` 1-9 0 - = Bksp
    listOf(
        KeyInfo("`","`"), KeyInfo("1","1"), KeyInfo("2","2"), KeyInfo("3","3"), KeyInfo("4","4"),
        KeyInfo("5","5"), KeyInfo("6","6"), KeyInfo("7","7"), KeyInfo("8","8"), KeyInfo("9","9"),
        KeyInfo("0","0"), KeyInfo("-","-"), KeyInfo("=","="), KeyInfo("Bksp","Back",2f)
    ),
    // Row 2 – Tab + QWERTY row
    listOf(
        KeyInfo("Tab","Tab",1.5f), KeyInfo("Q","Q"), KeyInfo("W","W"), KeyInfo("E","E"),
        KeyInfo("R","R"), KeyInfo("T","T"), KeyInfo("Y","Y"), KeyInfo("U","U"),
        KeyInfo("I","I"), KeyInfo("O","O"), KeyInfo("P","P"), KeyInfo("[","["), KeyInfo("]","]"), KeyInfo("\\","\\")
    ),
    // Row 3 – Caps + ASDF row
    listOf(
        KeyInfo("Caps","CapsLock",1.7f), KeyInfo("A","A"), KeyInfo("S","S"), KeyInfo("D","D"),
        KeyInfo("F","F"), KeyInfo("G","G"), KeyInfo("H","H"), KeyInfo("J","J"),
        KeyInfo("K","K"), KeyInfo("L","L"), KeyInfo(";",";"), KeyInfo("'","'"), KeyInfo("Enter","Enter",2.3f)
    ),
    // Row 4 – Shift + ZXCV row
    listOf(
        KeyInfo("Shift","Shift",2.2f), KeyInfo("Z","Z"), KeyInfo("X","X"), KeyInfo("C","C"),
        KeyInfo("V","V"), KeyInfo("B","B"), KeyInfo("N","N"), KeyInfo("M","M"),
        KeyInfo(",",","), KeyInfo(".","."), KeyInfo("/","/"), KeyInfo("Shift","Shift",2.2f)
    ),
    // Row 5 – Bottom row
    listOf(
        KeyInfo("Ctrl","Control",1.3f), KeyInfo("Fn","Fn",1f), KeyInfo("Win","Win",1f),
        KeyInfo("Alt","Alt",1.3f), KeyInfo("Space","Space",5f), KeyInfo("Alt","Alt",1f),
        KeyInfo("Home","Home",1f), KeyInfo("End","End",1f), KeyInfo("PgUp","PageUp",1f),
        KeyInfo("PgDn","PageDown",1f), KeyInfo("Del","Delete",1f)
    )
)

@Composable
fun TrackpadScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // Force landscape
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    var sensitivity   by remember { mutableIntStateOf(5) }          // 1-10
    var showKeyboard  by remember { mutableStateOf(false) }
    var showSettings  by remember { mutableStateOf(false) }

    // Gradient background matching reference image
    val bgGradient = Brush.verticalGradient(listOf(Color(0xFF050B18), Color(0xFF0A122A)))

    Box(Modifier.fillMaxSize().background(bgGradient)) {

        Column(Modifier.fillMaxSize()) {
            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Logo area
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "N", color = primary, fontSize = 24.sp, fontWeight = FontWeight.Black,
                            fontStyle = FontStyle.Italic
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("NexLink", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Seamless Control. Effortless Connection.", color = outline.copy(0.6f), fontSize = 9.sp)
                }

                Spacer(Modifier.weight(1f))

                // Keyboard toggle
                Box(
                    Modifier.size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, primary.copy(0.4f), RoundedCornerShape(10.dp))
                        .background(if (showKeyboard) primary.copy(0.2f) else Color(0xFF0D1526))
                        .clickable { showKeyboard = !showKeyboard },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (showKeyboard) Icons.Default.KeyboardHide else Icons.Default.Keyboard,
                        null, tint = primary, modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                // Settings toggle
                Box(
                    Modifier.size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, outline.copy(0.3f), RoundedCornerShape(10.dp))
                        .background(Color(0xFF0D1526))
                        .clickable { showSettings = !showSettings },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Settings, null, tint = outline, modifier = Modifier.size(20.dp))
                }
            }

            // ── Main area ─────────────────────────────────────────────────────
            Row(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── Trackpad ─────────────────────────────────────────────────
                val trackpadBg = Color(0xFF080F20)
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.5.dp, primary.copy(0.35f), RoundedCornerShape(20.dp))
                        .background(trackpadBg)
                        .pointerInput(sensitivity) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val s = sensitivity * 0.4f
                                viewModel.sendMouseMove(dragAmount.x * s, dragAmount.y * s)
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { viewModel.sendMouseTap(); vibrate(context, 20) },
                                onLongPress = { viewModel.sendMouseRightTap(); vibrate(context, 40) }
                            )
                        }
                ) {
                    // Subtle wave/dot grid background
                    Box(Modifier.fillMaxSize().drawBehind {
                        val cols = (size.width / 28).toInt()
                        val rows = (size.height / 28).toInt()
                        for (r in 0..rows) for (c in 0..cols) {
                            drawCircle(
                                color = androidx.compose.ui.graphics.Color(0xFF1A3A6A).copy(alpha = 0.4f),
                                radius = 1.5f,
                                center = Offset(c * 28f + 14f, r * 28f + 14f)
                            )
                        }
                    })
                    // Diagonal wave lines (decorative)
                    Box(Modifier.fillMaxSize().drawBehind {
                        val path = androidx.compose.ui.graphics.Path()
                        path.moveTo(0f, size.height * 0.55f)
                        path.cubicTo(size.width * 0.25f, size.height * 0.35f, size.width * 0.5f, size.height * 0.7f, size.width, size.height * 0.5f)
                        drawPath(path, color = androidx.compose.ui.graphics.Color(0xFF1E4A9A).copy(alpha = 0.3f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 40f))
                    })
                    // 'N' center logo
                    Text(
                        "N",
                        color = primary.copy(0.18f),
                        fontSize = 120.sp,
                        fontWeight = FontWeight.Black,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    // gesture hint
                    Text(
                        "Use gestures for smooth navigation",
                        color = outline.copy(0.45f),
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                    )
                }

                // ── Right panel: Scroll + H-Scroll ───────────────────────────
                Column(
                    Modifier.width(110.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Vertical scroll strip
                    Column(
                        Modifier.weight(1f).fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .border(1.dp, outline.copy(0.25f), RoundedCornerShape(18.dp))
                            .background(Color(0xFF0B1322))
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("SCROLL", color = outline.copy(0.7f), fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        // Up arrow
                        Box(
                            Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0F1E3A))
                                .clickable { viewModel.sendMouseScroll(-80f); vibrate(context, 15) },
                            contentAlignment = Alignment.Center
                        ) { Text("∧", color = primary, fontSize = 16.sp, fontWeight = FontWeight.Bold) }

                        Spacer(Modifier.height(8.dp))
                        // Drag strip
                        Box(
                            Modifier.weight(1f).width(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF0D1A30))
                                .border(1.dp, primary.copy(0.2f), RoundedCornerShape(14.dp))
                                .pointerInput(sensitivity) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        viewModel.sendMouseScroll(drag.y * sensitivity * 0.5f)
                                    }
                                }
                        ) {
                            // tick marks
                            Column(
                                Modifier.fillMaxSize().padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                repeat(8) {
                                    Box(Modifier.height(1.dp).fillMaxWidth(0.5f).background(primary.copy(0.3f)))
                                }
                            }
                            // Thumb
                            Box(
                                Modifier.size(36.dp).align(Alignment.Center)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(primary.copy(0.8f))
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        // Down arrow
                        Box(
                            Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0F1E3A))
                                .clickable { viewModel.sendMouseScroll(80f); vibrate(context, 15) },
                            contentAlignment = Alignment.Center
                        ) { Text("∨", color = primary, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(4.dp))
                    }

                    // Horizontal scroll strip
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, outline.copy(0.25f), RoundedCornerShape(14.dp))
                            .background(Color(0xFF0B1322))
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("HORIZONTAL SCROLL", color = outline.copy(0.7f), fontSize = 7.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Box(
                                Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF0F1E3A))
                                    .clickable { viewModel.sendMouseHScroll(-80f); vibrate(context, 15) },
                                contentAlignment = Alignment.Center
                            ) { Text("<", color = primary, fontSize = 14.sp, fontWeight = FontWeight.Bold) }

                            // H-Scroll drag strip
                            Box(
                                Modifier.weight(1f).height(26.dp).padding(horizontal = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0D1A30))
                                    .border(1.dp, primary.copy(0.2f), RoundedCornerShape(8.dp))
                                    .pointerInput(sensitivity) {
                                        detectDragGestures { change, drag ->
                                            change.consume()
                                            viewModel.sendMouseHScroll(drag.x * sensitivity * 0.5f)
                                        }
                                    }
                            ) {
                                Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    repeat(6) { Box(Modifier.width(1.dp).fillMaxHeight(0.5f).background(primary.copy(0.3f))) }
                                }
                                Box(Modifier.size(22.dp).align(Alignment.CenterEnd).clip(RoundedCornerShape(6.dp)).background(primary.copy(0.8f)))
                            }

                            Box(
                                Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF0F1E3A))
                                    .clickable { viewModel.sendMouseHScroll(80f); vibrate(context, 15) },
                                contentAlignment = Alignment.Center
                            ) { Text(">", color = primary, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }

            // ── Mouse Buttons ─────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(52.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MouseButton("☰ LEFT", primary, Modifier.weight(1f)) {
                    vibrate(context, 30); viewModel.sendMouseTap()
                }
                MouseButton("⊙ MIDDLE", outline, Modifier.weight(1f)) {
                    vibrate(context, 30); viewModel.sendMouseMiddleTap()
                }
                MouseButton("☰ RIGHT", outline, Modifier.weight(1f)) {
                    vibrate(context, 30); viewModel.sendMouseRightTap()
                }
            }

            // Powered by footer
            Text(
                "Powered by Phynex",
                color = outline.copy(0.35f),
                fontSize = 9.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                textAlign = TextAlign.Center
            )
        }

        // ── Settings panel (sensitivity) ──────────────────────────────────────
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                Modifier.fillMaxWidth(),
                color = Color(0xFF0D1526),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                border = BorderStroke(1.dp, primary.copy(0.3f))
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("SENSITIVITY", color = outline, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        (1..10).forEach { n ->
                            Box(
                                Modifier.size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (sensitivity == n) primary else Color(0xFF1A2A45))
                                    .border(1.dp, if (sensitivity == n) primary else outline.copy(0.2f), RoundedCornerShape(10.dp))
                                    .clickable { sensitivity = n; vibrate(context, 20) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("$n", color = if (sensitivity == n) Color.White else outline, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Current: $sensitivity — Higher = Faster cursor", color = outline.copy(0.6f), fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showSettings = false }) { Text("Close", color = primary) }
                    }
                }
            }
        }

        // ── Keyboard panel ────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = showKeyboard,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                Modifier.fillMaxWidth(),
                color = Color(0xFF080F1E),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                border = BorderStroke(1.dp, primary.copy(0.25f))
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                    keyboardRows.forEach { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            row.forEach { key ->
                                KeyboardKey(
                                    key = key,
                                    modifier = Modifier.weight(key.widthWeight).height(40.dp)
                                ) {
                                    vibrate(context, 18)
                                    viewModel.sendKeyPress(key.code)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun MouseButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.fillMaxHeight().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF0D1526),
        border = BorderStroke(1.dp, color.copy(0.35f))
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, color = color, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.5.sp)
        }
    }
}

@Composable
private fun KeyboardKey(key: KeyInfo, modifier: Modifier, onClick: () -> Unit) {
    val isSpecial = key.label.length > 1 && key.label !in listOf("Tab","Bksp","Caps","Shift","Ctrl","Alt","Win","Enter","Space","Fn","Del","Home","End","PgUp","PgDn","Esc","F1","F2","F3","F4","F5","F6","F7","F8","F9","F10","F11","F12")
    val bgColor = when {
        key.label == "Space" -> Color(0xFF1A2A45)
        key.label in listOf("Enter","Bksp","Shift","Caps","Tab","Ctrl","Alt","Win","Fn","Del","Home","End","PgUp","PgDn") -> Color(0xFF0F1E38)
        else -> Color(0xFF111E35)
    }
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(7.dp),
        color = bgColor,
        border = BorderStroke(0.5.dp, primary.copy(0.15f))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                key.label,
                color = Color(0xFFCDD8F0),
                fontSize = if (key.label.length > 3) 8.sp else 11.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}
