package com.guidaco.guidaglassesapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.guidaco.guidaglassesapp.ui.theme.GuidaGlassesAppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class LogViewActivity : ComponentActivity() {
    private lateinit var viewModel: LogViewModel
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[LogViewModel::class.java]
        
        setContent {
            GuidaGlassesAppTheme {
                LogViewScreen(
                    viewModel = viewModel,
                    onBack = { finish() }
                )
            }
        }
    }
}

class LogViewModel : ViewModel() {
    private val radarDataManager = RadarDataManager.getInstance()
    
    private val _logs = MutableStateFlow<List<TargetLog>>(emptyList())
    val logs: StateFlow<List<TargetLog>> = _logs.asStateFlow()
    
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()
    
    private val _logCount = MutableStateFlow(0)
    val logCount: StateFlow<Int> = _logCount.asStateFlow()
    
    init {
        // Observe target logs from RadarDataManager
        viewModelScope.launch {
            radarDataManager.targetLogs.collect { targetLogs ->
                if (!_isPaused.value) {
                    _logs.value = targetLogs.reversed() // Show newest first
                }
                _logCount.value = targetLogs.size
            }
        }
    }
    
    fun togglePause() {
        _isPaused.value = !_isPaused.value
    }
    
    fun clearLogs() {
        radarDataManager.clearLogs()
        _logs.value = emptyList()
        _logCount.value = 0
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewScreen(
    viewModel: LogViewModel,
    onBack: () -> Unit
) {
    val logs by viewModel.logs.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val logCount by viewModel.logCount.collectAsState()
    val listState = rememberLazyListState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Target Logs")
                        Text(
                            text = "${logs.size} displayed / $logCount total",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Pause/Resume button
                    IconButton(onClick = { viewModel.togglePause() }) {
                        Icon(
                            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Close,
                            contentDescription = if (isPaused) "Resume" else "Pause",
                            tint = if (isPaused) Color.Red else MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    // Clear logs button
                    IconButton(onClick = { viewModel.clearLogs() }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear Logs"
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
            // Status bar
            LogStatusBar(
                isPaused = isPaused,
                totalLogs = logCount,
                displayedLogs = logs.size
            )
            
            // Log list
            if (logs.isEmpty()) {
                EmptyLogView()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs) { targetLog ->
                        LogItem(targetLog = targetLog)
                    }
                }
                
                // Auto-scroll to top when new logs arrive (if not paused)
                LaunchedEffect(logs.size, isPaused) {
                    if (!isPaused && logs.isNotEmpty()) {
                        listState.animateScrollToItem(0)
                    }
                }
            }
        }
    }
}

@Composable
fun LogStatusBar(
    isPaused: Boolean,
    totalLogs: Int,
    displayedLogs: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPaused) 
                MaterialTheme.colorScheme.errorContainer 
            else 
                MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isPaused) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = if (isPaused) 
                        MaterialTheme.colorScheme.onErrorContainer 
                    else 
                        MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isPaused) "PAUSED" else "LIVE",
                    fontWeight = FontWeight.Bold,
                    color = if (isPaused) 
                        MaterialTheme.colorScheme.onErrorContainer 
                    else 
                        MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            
            Text(
                text = "Showing $displayedLogs of $totalLogs logs",
                fontSize = 12.sp,
                color = if (isPaused) 
                    MaterialTheme.colorScheme.onErrorContainer 
                else 
                    MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun EmptyLogView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.List,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No validated targets logged yet",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Validated targets from TargetTracker will appear here",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun LogItem(targetLog: TargetLog) {
    val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header with timestamp and target ID
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Target ${targetLog.target.id}",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Confidence indicator
                    val confidenceColor = when {
                        targetLog.target.confidence >= 0.8f -> Color.Green
                        targetLog.target.confidence >= 0.6f -> Color.Yellow
                        else -> Color.Red
                    }
                    
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = confidenceColor,
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = timeFormat.format(Date(targetLog.timestamp)),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Target details
            Text(
                text = targetLog.logMessage,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
} 