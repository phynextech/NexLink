package com.phynex.NexLink.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phynex.NexLink.ui.theme.background
import com.phynex.NexLink.ui.theme.primary
import com.phynex.NexLink.ui.theme.primaryContainer
import com.phynex.NexLink.ui.theme.secondaryContainer
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onSplashFinished: () -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }

    LaunchedEffect(key1 = true) {
        startAnimation = true
        delay(2500)
        onSplashFinished()
    }

    val progressAnim = animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 2000, easing = LinearOutSlowInEasing)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        // Decorative Radial Glows (Brand Aura)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(500.dp)
                .blur(100.dp)
                .background(primaryContainer.copy(alpha = 0.05f), CircleShape)
        )
        Box(
            modifier = Modifier
                .offset(x = (-100).dp, y = (-100).dp)
                .size(250.dp)
                .blur(120.dp)
                .background(secondaryContainer.copy(alpha = 0.1f), CircleShape)
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Glowing Node Network forming "N"
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .padding(bottom = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                val nodeColor = primaryContainer
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val color = nodeColor
                    val strokeWidth = 6.dp.toPx()
                    val p1 = Offset(size.width * 0.2f, size.height * 0.8f)
                    val p2 = Offset(size.width * 0.2f, size.height * 0.2f)
                    val p3 = Offset(size.width * 0.8f, size.height * 0.8f)
                    val p4 = Offset(size.width * 0.8f, size.height * 0.2f)

                    // Draw circles
                    drawCircle(color, radius = 4.dp.toPx(), center = p1)
                    drawCircle(color, radius = 4.dp.toPx(), center = p2)
                    drawCircle(color, radius = 4.dp.toPx(), center = p3)
                    drawCircle(color, radius = 4.dp.toPx(), center = p4)

                    // Draw solid "N"
                    drawLine(color, p1, p2, strokeWidth, Stroke.DefaultCap)
                    drawLine(color, p2, p3, strokeWidth, Stroke.DefaultCap)
                    drawLine(color, p3, p4, strokeWidth, Stroke.DefaultCap)

                    // Draw dashed connected nodes (approximation)
                    drawLine(color.copy(alpha = 0.4f), p2, p4, 2.dp.toPx(), Stroke.DefaultCap)
                    drawLine(color.copy(alpha = 0.4f), p1, p3, 2.dp.toPx(), Stroke.DefaultCap)
                }
            }

            // Brand Typography
            Text(
                text = "NexLink",
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 42.sp),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Your PC. Anywhere.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }

        // Bottom Loading & Info Section
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Progress Bar Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction = progressAnim.value)
                        .background(primaryContainer)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Status Indicator
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(primaryContainer)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "INITIALIZING LINK",
                    style = MaterialTheme.typography.labelSmall,
                    color = primary,
                    letterSpacing = 2.sp
                )
            }
            Spacer(modifier = Modifier.height(24.dp))

            // Version Tag
            Text(
                text = "v1.0.0",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }

        // Decorative Foreground Asset (Abstract UI Hint)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = 100.dp)
                .size(width = 600.dp, height = 300.dp)
                .blur(80.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(primaryContainer.copy(alpha = 0.1f), Color.Transparent)
                    )
                )
        )
    }
}
