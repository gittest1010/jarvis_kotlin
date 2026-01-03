package com.example.jarvisai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var voiceManager: VoiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        voiceManager = VoiceManager(this)

        setContent {
            JarvisTheme {
                // Permission Launcher
                val context = LocalContext.current
                var hasPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }

                val launcher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { granted -> hasPermission = granted }
                )

                LaunchedEffect(Unit) {
                    if (!hasPermission) {
                        launcher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                if (hasPermission) {
                    JarvisScreen(voiceManager)
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Microphone Permission Required", color = Color.White)
                    }
                }
            }
        }
    }
}

// --- The UI Components ---

val CyanNeon = Color(0xFF00E5FF)
val DarkBackground = Color(0xFF050505)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            background = DarkBackground,
            surface = DarkBackground,
            primary = CyanNeon
        ),
        content = content
    )
}

@Composable
fun JarvisScreen(voiceManager: VoiceManager) {
    val state by voiceManager.uiState.collectAsState()

    // Animation for pulse
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    
    // Scale animation triggers only when listening or speaking
    val targetScale = if (state.isListening || state.isSpeaking) 1.2f else 1.0f
    val scaleAnim by animateFloatAsState(targetValue = targetScale, label = "scale")

    // Opacity pulse for the outer glow
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ), label = "alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        
        // Top: Status Header
        Text(
            text = state.status.uppercase(),
            color = CyanNeon,
            fontSize = 18.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 4.sp,
            modifier = Modifier.padding(top = 40.dp)
        )

        // Center: The Core
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(300.dp)
                .clickable {
                    // Tap to toggle listening
                    if (state.isListening) voiceManager.stopListening() else voiceManager.startListening()
                }
        ) {
            // Outer Glow Rings
            Canvas(modifier = Modifier.fillMaxSize().scale(scaleAnim)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(CyanNeon.copy(alpha = 0.3f), Color.Transparent)
                    ),
                    radius = size.minDimension / 2
                )
                drawCircle(
                    color = CyanNeon.copy(alpha = alphaAnim),
                    radius = (size.minDimension / 2) - 20f,
                    style = Stroke(width = 4f)
                )
                // Rotating Arc (Simulated via static drawing here, normally rotate infinite)
                drawArc(
                    color = CyanNeon,
                    startAngle = 0f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = 2f, cap = StrokeCap.Round),
                    size = androidx.compose.ui.geometry.Size(size.width - 100f, size.height - 100f),
                    topLeft = Offset(50f, 50f)
                )
            }
            
            // Inner Core Text (Mic Icon placeholder)
            Text(
                text = if (state.isListening) "MIC ON" else "TAP",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        // Bottom: Live Transcript
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .border(1.dp, CyanNeon.copy(alpha = 0.5f), shape = CircleShape.copy(all = androidx.compose.foundation.shape.CornerSize(16.dp)))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = state.transcript.ifEmpty { "System Ready..." },
                color = if (state.transcript.isEmpty()) Color.Gray else Color.White,
                textAlign = TextAlign.Center,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp
            )
        }
    }
}