package com.holotower.app.ui.globalentry

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.holotower.app.viewmodel.GlobalEntryUiState
import com.holotower.app.viewmodel.GlobalEntryViewModel

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun GlobalEntryScreen(
    onBack: () -> Unit,
    vm: GlobalEntryViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tokenInput by remember { mutableStateOf("") }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Global Entry", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("<", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF111111))
            )
        },
        containerColor = Color(0xFF0A0A0A)
    ) { padding ->
        when (val s = state) {
            is GlobalEntryUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF00FF9F))
                }
            }

            is GlobalEntryUiState.Error -> {
                Box(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Error: ${s.message}", color = Color(0xFFFF6A6A))
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { vm.refresh(force = true) }) {
                            Text("Retry")
                        }
                    }
                }
            }

            is GlobalEntryUiState.Success -> {
                val data = s.status
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1B1D21), RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Status", color = Color(0xFFBFC5CC), fontSize = 12.sp)
                            Text(
                                data.statusText.ifBlank { "Unknown" },
                                color = Color(0xFFEAEAEA),
                                fontWeight = FontWeight.SemiBold
                            )
                            if (data.postCount != null && data.threshold != null) {
                                Text(
                                    "Post count: ${data.postCount} / ${data.threshold}",
                                    color = Color(0xFFD0D5DB)
                                )
                            }
                            data.currentIp?.takeIf { it.isNotBlank() }?.let { ip ->
                                Text("Current IP: $ip", color = Color(0xFF99A0A8), fontSize = 13.sp)
                            }
                            data.issuedToken?.takeIf { it.isNotBlank() }?.let { token ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Issued token", color = Color(0xFFBFC5CC), fontSize = 12.sp)
                                Text(token, color = Color(0xFF00FF9F), fontSize = 12.sp)
                            }
                        }
                    }

                    if (data.successMessages.isNotEmpty()) {
                        MessageBox("Success", data.successMessages, Color(0xFF00FF9F))
                    }
                    if (data.errorMessages.isNotEmpty()) {
                        MessageBox("Errors", data.errorMessages, Color(0xFFFF6A6A))
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1B1D21), RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Attach an existing token", color = Color.White, fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(
                                value = tokenInput,
                                onValueChange = { tokenInput = it },
                                label = { Text("Existing token") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = TextStyle(color = Color(0xFFEAEAEA)),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color(0xFFEAEAEA),
                                    unfocusedTextColor = Color(0xFFEAEAEA),
                                    focusedBorderColor = Color(0xFF00FF9F),
                                    unfocusedBorderColor = Color(0xFF2A2F35),
                                    focusedLabelColor = Color(0xFFBFC5CC),
                                    unfocusedLabelColor = Color(0xFF9CA3AF),
                                    cursorColor = Color(0xFF00FF9F)
                                )
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (tokenInput.isNotBlank()) {
                                    Button(
                                        onClick = { vm.attachToken(tokenInput) },
                                        enabled = !s.attaching
                                    ) {
                                        if (s.attaching) {
                                            CircularProgressIndicator(
                                                color = Color.Black,
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.height(16.dp)
                                            )
                                        } else {
                                            Text("Attach this device")
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Button(onClick = { vm.refresh(force = true) }, enabled = !s.attaching) {
                                    Text("Refresh")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBox(title: String, messages: List<String>, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1B1D21), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = color, fontWeight = FontWeight.Bold)
            messages.forEach { msg ->
                Text(msg, color = Color(0xFFE8EBEF), fontSize = 13.sp)
            }
        }
    }
}
