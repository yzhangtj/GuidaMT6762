package com.guidaco.guidaglassesapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.guidaco.guidaglassesapp.ui.theme.GuidaGlassesAppTheme
import kotlin.math.*

// RadarTarget is defined in AlertManager.kt

class RadarViewActivity : ComponentActivity() {
    private lateinit var viewModel: RadarViewModel
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[RadarViewModel::class.java]
        
        setContent {
            GuidaGlassesAppTheme {
                RadarViewScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                    onOpenLogs = {
                        startActivity(Intent(this@RadarViewActivity, LogViewActivity::class.java))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarViewScreen(
    viewModel: RadarViewModel,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit
) {
    val targets by viewModel.targets.collectAsState()
    val stats by viewModel.stats.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Radar View") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Logs button
                    IconButton(onClick = onOpenLogs) {
                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = "View Logs"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Stats section
            RadarStats(stats = stats)
            
            // Radar display
            RadarDisplay(
                targets = targets,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp)
            )
        }
    }
}

@Composable
fun RadarStats(stats: RadarStats) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem("Targets", stats.totalTargets.toString())
            StatItem("Left", stats.leftTargets.toString())
            StatItem("Right", stats.rightTargets.toString())
            StatItem("Moving", stats.movingTargets.toString())
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun RadarDisplay(
    targets: List<RadarTarget>,
    modifier: Modifier = Modifier
) {
    val minDistance = 10f  // 10cm
    val maxDistance = 500f  // 500cm (5m) - updated from 200cm
    val maxSpeed = 6f  // 6 m/s
    val density = LocalDensity.current
    
    Box(
        modifier = modifier
            .background(Color.Black)
            .clip(MaterialTheme.shapes.medium)
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            drawRadarView(targets, size.width, size.height, density.density)
        }
        
        // Center line
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(2.dp)
                .background(Color.Gray)
                .align(Alignment.Center)
        )
        
        // Labels with angle indicators
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "LEFT\n(-90°)",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "CENTER\n(0°)",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "RIGHT\n(+90°)",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun DrawScope.drawRadarView(
    targets: List<RadarTarget>,
    width: Float,
    height: Float,
    density: Float
) {
    val centerX = width / 2f
    val minDistance = 10f // 10 cm minimum
    val maxDistance = 500f // 500cm (5m) - updated from 200cm
    
    // Draw distance circles with better spacing for 5m range
    val circles = listOf(50f, 100f, 200f, 300f, 400f, 500f) // distances in cm
    circles.forEach { distance ->
        // Use square root for nonlinear mapping - gives more precision at closer range
        val normalizedDistance = sqrt((distance - minDistance) / (maxDistance - minDistance))
        val radius = normalizedDistance * (height * 0.4f)
        
        drawCircle(
            color = Color.Gray.copy(alpha = 0.4f),
            radius = radius,
            center = Offset(centerX, height * 0.8f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
        )
        
        // Add distance labels for key circles
        if (distance in listOf(100f, 200f, 300f, 500f)) {
            val labelText = "${distance.toInt()}cm"
            // Note: Text drawing would need native canvas access
            // For now, circles provide visual reference
        }
    }
    
    // Draw targets
    targets.forEach { target ->
        // Nonlinear distance mapping using square root for better close-range precision
        val normalizedDistance = sqrt((target.distance - minDistance) / (maxDistance - minDistance))
        val radius = normalizedDistance * (height * 0.4f)
        
        // Map radar angle (120°) to view angle (180°): -60° to +60° → -90° to +90°
        // Radar angle range: -60° to +60° (120° total)
        // View angle range: -90° to +90° (180° total)
        val mappedAngle = target.angle * (180f / 120f) // Scale factor: 1.5
        
        // Convert mapped angle to position
        val angleRad = Math.toRadians(mappedAngle.toDouble())
        val targetX = centerX + (radius * sin(angleRad)).toFloat()
        val targetY = height * 0.8f - (radius * cos(angleRad)).toFloat()
        
        // Color based on speed
        val speedColor = getSpeedColor(target.speed)
        
        // Draw target with better visibility
        drawCircle(
            color = speedColor,
            radius = 10.dp.toPx(),
            center = Offset(targetX, targetY)
        )
        
        // Draw larger target circle for better visibility
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = 14.dp.toPx(),
            center = Offset(targetX, targetY),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
        )
        
        // Add small confidence indicator
        drawCircle(
            color = Color.Yellow.copy(alpha = 0.6f),
            radius = 4.dp.toPx(),
            center = Offset(targetX, targetY)
        )
    }
}

private fun getSpeedColor(speed: Float): Color {
    val absSpeed = abs(speed)
    val maxSpeed = 6f
    
    // Nonlinear mapping using square root to emphasize small speed differences
    val normalizedSpeed = sqrt(absSpeed / maxSpeed).coerceIn(0f, 1f)
    
    return when {
        absSpeed < 0.1f -> Color.Black // Static/no movement
        absSpeed < 0.5f -> {
            // Very slow movement - fade from black to dark gray
            val factor = (absSpeed - 0.1f) / 0.4f
            Color.Black.copy(alpha = 1f - factor * 0.3f)
        }
        absSpeed < 1.0f -> {
            // Slow movement - fade from dark gray to orange
            val factor = (absSpeed - 0.5f) / 0.5f
            Color(
                red = 0.2f + factor * 0.6f,  // 0.2 to 0.8
                green = 0.2f + factor * 0.4f,  // 0.2 to 0.6
                blue = 0.2f,  // Keep blue low
                alpha = 1f
            )
        }
        absSpeed < 2.0f -> {
            // Medium movement - fade from orange to red
            val factor = (absSpeed - 1.0f) / 1.0f
            Color(
                red = 0.8f + factor * 0.2f,  // 0.8 to 1.0
                green = 0.6f - factor * 0.4f,  // 0.6 to 0.2
                blue = 0.2f - factor * 0.1f,  // 0.2 to 0.1
                alpha = 1f
            )
        }
        absSpeed < 4.0f -> {
            // Fast movement - bright red with increasing intensity
            val factor = (absSpeed - 2.0f) / 2.0f
            Color(
                red = 1f,
                green = 0.2f - factor * 0.2f,  // 0.2 to 0.0
                blue = 0.1f - factor * 0.1f,  // 0.1 to 0.0
                alpha = 0.8f + factor * 0.2f  // 0.8 to 1.0
            )
        }
        else -> Color.Red // Very fast movement (>4 m/s) - pure red
    }
}

data class RadarStats(
    val totalTargets: Int = 0,
    val leftTargets: Int = 0,
    val rightTargets: Int = 0,
    val movingTargets: Int = 0
) 