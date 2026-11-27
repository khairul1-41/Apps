package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.FirebaseConfigSnippetParser
import com.example.core.OwnerFirebaseConfig
import com.example.core.OwnerPolicy
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AlertRedContainer
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NavyPrimary
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SignalGreen
import com.example.ui.theme.SignalGreenContainer
import com.example.ui.theme.SlateBackground
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurfaceVariant
import com.example.ui.theme.SlateTextSecondary
import com.example.ui.theme.WarningOrange
import com.example.ui.theme.WarningOrangeContainer
import com.example.ui.viewmodel.OwnerConfigActionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnerSettingsScreen(
    userEmail: String,
    isOwner: Boolean,
    savedConfig: OwnerFirebaseConfig?,
    actionState: OwnerConfigActionState,
    onSaveConfig: (OwnerFirebaseConfig) -> Unit,
    onClearConfig: () -> Unit,
    onDismissActionState: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBackClick)

    var apiKey by remember(savedConfig) { mutableStateOf(savedConfig?.apiKey.orEmpty()) }
    var authDomain by remember(savedConfig) { mutableStateOf(savedConfig?.authDomain.orEmpty()) }
    var databaseUrl by remember(savedConfig) { mutableStateOf(savedConfig?.databaseUrl.orEmpty()) }
    var projectId by remember(savedConfig) { mutableStateOf(savedConfig?.projectId.orEmpty()) }
    var storageBucket by remember(savedConfig) { mutableStateOf(savedConfig?.storageBucket.orEmpty()) }
    var messagingSenderId by remember(savedConfig) { mutableStateOf(savedConfig?.messagingSenderId.orEmpty()) }
    var appId by remember(savedConfig) { mutableStateOf(savedConfig?.appId.orEmpty()) }
    var measurementId by remember(savedConfig) { mutableStateOf(savedConfig?.measurementId.orEmpty()) }

    var showApiKey by remember { mutableStateOf(false) }
    var showSnippetImporter by remember { mutableStateOf(false) }
    var snippetText by remember { mutableStateOf("") }
    var snippetError by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.owner_settings_title),
                            color = PureWhite,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.owner_settings_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = SlateSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("owner_settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = PureWhite
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NavyPrimary)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SlateBackground)
                .padding(innerPadding)
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            if (!isOwner) {
                OwnerAccessDeniedCard(
                    userEmail = userEmail,
                    onBackClick = onBackClick,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 840.dp)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Encrypted Vault Status Header
                    item {
                        EncryptedStorageStatusCard(
                            userEmail = userEmail,
                            savedConfig = savedConfig
                        )
                    }

                    // Optional Quick Snippet Auto-Fill
                    item {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = PureWhite),
                            border = BorderStroke(1.dp, SlateBorder)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Quick Import Firebase Snippet",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = NavyPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Paste a Firebase config snippet or JSON object to auto-fill all fields below.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = SlateTextSecondary
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedButton(
                                        onClick = {
                                            showSnippetImporter = !showSnippetImporter
                                            snippetError = null
                                        },
                                        modifier = Modifier.testTag("owner_cfg_toggle_importer_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.ContentPaste,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(if (showSnippetImporter) "Hide" else "Paste")
                                    }
                                }

                                AnimatedVisibility(visible = showSnippetImporter) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        OutlinedTextField(
                                            value = snippetText,
                                            onValueChange = {
                                                snippetText = it
                                                snippetError = null
                                            },
                                            label = { Text("Firebase Config Snippet or JSON") },
                                            placeholder = {
                                                Text("const firebaseConfig = { apiKey: \"...\", projectId: \"...\", ... };")
                                            },
                                            minLines = 4,
                                            maxLines = 8,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("owner_cfg_snippet_input")
                                        )
                                        if (snippetError != null) {
                                            Text(
                                                text = snippetError.orEmpty(),
                                                color = AlertRed,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                val parsed = FirebaseConfigSnippetParser.parse(snippetText)
                                                if (parsed == null) {
                                                    snippetError = "Could not detect apiKey, projectId, or appId in pasted text."
                                                } else {
                                                    apiKey = parsed.apiKey
                                                    authDomain = parsed.authDomain
                                                    databaseUrl = parsed.databaseUrl
                                                    projectId = parsed.projectId
                                                    storageBucket = parsed.storageBucket
                                                    messagingSenderId = parsed.messagingSenderId
                                                    appId = parsed.appId
                                                    measurementId = parsed.measurementId
                                                    showSnippetImporter = false
                                                    snippetText = ""
                                                    snippetError = null
                                                    onDismissActionState()
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("owner_cfg_parse_snippet_button")
                                        ) {
                                            Text("Auto-Fill Form Fields")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Main Firebase Project Configuration Fields
                    item {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = PureWhite),
                            border = BorderStroke(1.dp, SlateBorder)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Firebase Project Parameters",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = NavyPrimary,
                                    fontWeight = FontWeight.Bold
                                )

                                OutlinedTextField(
                                    value = apiKey,
                                    onValueChange = {
                                        apiKey = it
                                        onDismissActionState()
                                    },
                                    label = { Text("API Key (apiKey) *") },
                                    placeholder = { Text("AIza...") },
                                    singleLine = true,
                                    visualTransformation = if (showApiKey) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { showApiKey = !showApiKey },
                                            modifier = Modifier.testTag("owner_cfg_toggle_apikey_visibility")
                                        ) {
                                            Icon(
                                                imageVector = if (showApiKey) {
                                                    Icons.Filled.VisibilityOff
                                                } else {
                                                    Icons.Filled.Visibility
                                                },
                                                contentDescription = if (showApiKey) "Hide API Key" else "Show API Key"
                                            )
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Ascii,
                                        imeAction = ImeAction.Next
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("owner_cfg_api_key_input")
                                )

                                OutlinedTextField(
                                    value = projectId,
                                    onValueChange = {
                                        projectId = it
                                        onDismissActionState()
                                    },
                                    label = { Text("Project ID (projectId) *") },
                                    placeholder = { Text("my-project-id") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Ascii,
                                        imeAction = ImeAction.Next
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("owner_cfg_project_id_input")
                                )

                                OutlinedTextField(
                                    value = appId,
                                    onValueChange = {
                                        appId = it
                                        onDismissActionState()
                                    },
                                    label = { Text("App ID (appId) *") },
                                    placeholder = { Text("1:123456789:web:...") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Ascii,
                                        imeAction = ImeAction.Next
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("owner_cfg_app_id_input")
                                )

                                OutlinedTextField(
                                    value = authDomain,
                                    onValueChange = {
                                        authDomain = it
                                        onDismissActionState()
                                    },
                                    label = { Text("Auth Domain (authDomain)") },
                                    placeholder = { Text("my-project.firebaseapp.com") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Next
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("owner_cfg_auth_domain_input")
                                )

                                OutlinedTextField(
                                    value = databaseUrl,
                                    onValueChange = {
                                        databaseUrl = it
                                        onDismissActionState()
                                    },
                                    label = { Text("Database URL (databaseURL)") },
                                    placeholder = { Text("https://my-project-default-rtdb.firebaseio.com") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Next
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("owner_cfg_database_url_input")
                                )

                                OutlinedTextField(
                                    value = storageBucket,
                                    onValueChange = {
                                        storageBucket = it
                                        onDismissActionState()
                                    },
                                    label = { Text("Storage Bucket (storageBucket)") },
                                    placeholder = { Text("my-project.firebasestorage.app") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Next
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("owner_cfg_storage_bucket_input")
                                )

                                OutlinedTextField(
                                    value = messagingSenderId,
                                    onValueChange = {
                                        messagingSenderId = it
                                        onDismissActionState()
                                    },
                                    label = { Text("Messaging Sender ID (messagingSenderId)") },
                                    placeholder = { Text("864156557103") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Next
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("owner_cfg_sender_id_input")
                                )

                                OutlinedTextField(
                                    value = measurementId,
                                    onValueChange = {
                                        measurementId = it
                                        onDismissActionState()
                                    },
                                    label = { Text("Measurement ID (measurementId)") },
                                    placeholder = { Text("G-XXXXXXXXXX") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Ascii,
                                        imeAction = ImeAction.Done
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("owner_cfg_measurement_id_input")
                                )
                            }
                        }
                    }

                    // Feedback Banner
                    item {
                        when (actionState) {
                            is OwnerConfigActionState.Success -> {
                                Surface(
                                    color = SignalGreenContainer,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, SignalGreen)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SignalGreen)
                                        Text(
                                            text = actionState.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = NavyPrimary
                                        )
                                    }
                                }
                            }
                            is OwnerConfigActionState.Error -> {
                                Surface(
                                    color = AlertRedContainer,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, AlertRed)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = AlertRed)
                                        Text(
                                            text = actionState.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AlertRed
                                        )
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }

                    // Save & Clear Actions
                    item {
                        val isSaving = actionState is OwnerConfigActionState.Saving
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    onSaveConfig(
                                        OwnerFirebaseConfig(
                                            apiKey = apiKey,
                                            authDomain = authDomain,
                                            databaseUrl = databaseUrl,
                                            projectId = projectId,
                                            storageBucket = storageBucket,
                                            messagingSenderId = messagingSenderId,
                                            appId = appId,
                                            measurementId = measurementId
                                        )
                                    )
                                },
                                enabled = !isSaving,
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .testTag("owner_cfg_save_button")
                            ) {
                                if (isSaving) {
                                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = PureWhite)
                                } else {
                                    Icon(imageVector = Icons.Filled.Save, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.save_firebase_config),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (savedConfig != null) {
                                OutlinedButton(
                                    onClick = { showClearConfirm = true },
                                    enabled = !isSaving,
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, AlertRed),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertRed),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("owner_cfg_clear_button")
                                ) {
                                    Icon(imageVector = Icons.Filled.DeleteOutline, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Clear Saved Preferences")
                                }
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Saved Firebase Config?") },
            text = {
                Text("This permanently removes the encrypted Firebase project configuration stored in local application preferences.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        apiKey = ""
                        authDomain = ""
                        databaseUrl = ""
                        projectId = ""
                        storageBucket = ""
                        messagingSenderId = ""
                        appId = ""
                        measurementId = ""
                        onClearConfig()
                    }
                ) {
                    Text("Clear", color = AlertRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun EncryptedStorageStatusCard(
    userEmail: String,
    savedConfig: OwnerFirebaseConfig?
) {
    val updatedFormatted = remember(savedConfig?.updatedAtEpochMs) {
        val ts = savedConfig?.updatedAtEpochMs ?: 0L
        if (ts > 0L) {
            SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date(ts))
        } else {
            null
        }
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = NavyPrimary),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.EnhancedEncryption,
                    contentDescription = null,
                    tint = SignalGreen,
                    modifier = Modifier.size(26.dp)
                )
                Column {
                    Text(
                        text = "Encrypted Application Preferences",
                        style = MaterialTheme.typography.titleMedium,
                        color = PureWhite,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "AES-256-GCM (Android Keystore) + Jetpack DataStore",
                        style = MaterialTheme.typography.labelSmall,
                        color = SlateSurfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.AdminPanelSettings,
                    contentDescription = null,
                    tint = SignalGreen,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Verified Owner: $userEmail",
                    style = MaterialTheme.typography.bodySmall,
                    color = PureWhite.copy(alpha = 0.9f)
                )
            }

            Surface(
                color = PureWhite.copy(alpha = 0.08f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, PureWhite.copy(alpha = 0.18f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (savedConfig != null) {
                        Text(
                            text = "SAVED PROJECT: ${savedConfig.projectId}",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = SignalGreen,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "API Key: ${savedConfig.maskedApiKey()}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = PureWhite
                        )
                        if (updatedFormatted != null) {
                            Text(
                                text = "Last Encrypted Save: $updatedFormatted",
                                style = MaterialTheme.typography.labelSmall,
                                color = SlateSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = "STATUS: NOT CONFIGURED IN LOCAL PREFERENCES",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = WarningOrange,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Enter your Firebase project fields below or paste a config snippet to encrypt and store locally.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OwnerAccessDeniedCard(
    userEmail: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.widthIn(max = 520.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = WarningOrangeContainer),
        border = BorderStroke(1.dp, WarningOrange)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = WarningOrange,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = "Owner Access Required",
                    style = MaterialTheme.typography.titleMedium,
                    color = NavyPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "Signed in as $userEmail. Only the verified owner (${OwnerPolicy.OWNER_EMAIL}) can view or edit encrypted Firebase project settings.",
                style = MaterialTheme.typography.bodySmall,
                color = NavyPrimary
            )
            Button(
                onClick = onBackClick,
                colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Return")
            }
        }
    }
}
