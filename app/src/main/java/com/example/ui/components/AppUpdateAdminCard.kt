package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToDrive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.data.model.AppUpdateRelease
import com.example.data.remote.AppUpdateUploadService
import com.example.data.remote.AppUpdateUrlValidator
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AlertRedContainer
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NavyContainer
import com.example.ui.theme.NavyPrimary
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SignalGreen
import com.example.ui.theme.SignalGreenContainer
import com.example.ui.theme.SlateBackground
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateTextSecondary
import com.example.ui.theme.WarningOrange
import com.example.ui.theme.WarningOrangeContainer
import com.example.ui.viewmodel.AppUpdatePublishState
import com.example.ui.viewmodel.StagedApkState
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun AppUpdateAdminCard(
    currentRelease: AppUpdateRelease?,
    stagedApkState: StagedApkState,
    publishState: AppUpdatePublishState,
    hasDriveAccess: Boolean,
    onDriveTokenGranted: (String) -> Unit,
    onDriveAuthError: (String) -> Unit,
    onPickApkUri: (Uri) -> Unit,
    onClearStagedApk: () -> Unit,
    onPublishUpdate: (
        versionCodeText: String,
        versionNameText: String,
        releaseNotesText: String,
        manualHttpsUrlText: String,
        isMandatory: Boolean,
        driveAccessTokenOverride: String?
    ) -> Unit,
    onRemovePublishedUpdate: () -> Unit,
    onDismissPublishState: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var versionCodeText by remember { mutableStateOf((BuildConfig.VERSION_CODE + 1).toString()) }
    var versionNameText by remember { mutableStateOf("1.1.0") }
    var releaseNotesText by remember { mutableStateOf("") }
    var manualHttpsUrlText by remember { mutableStateOf("") }
    var isMandatory by remember { mutableStateOf(false) }

    var isAuthorizingDrive by remember { mutableStateOf(false) }
    var pendingPublishAfterDriveAuth by remember { mutableStateOf(false) }

    LaunchedEffect(stagedApkState) {
        if (stagedApkState is StagedApkState.Ready) {
            val apk = stagedApkState.apk
            val suggestedCode = maxOf(apk.versionCode, BuildConfig.VERSION_CODE.toLong() + 1L)
            versionCodeText = suggestedCode.toString()
            versionNameText = if (apk.versionName.isNotBlank() && apk.versionName != BuildConfig.VERSION_NAME) {
                apk.versionName
            } else {
                "1.${suggestedCode}.0"
            }
        }
    }

    LaunchedEffect(publishState) {
        if (publishState is AppUpdatePublishState.Success) {
            releaseNotesText = ""
            manualHttpsUrlText = ""
            isMandatory = false
        }
    }

    val driveAuthIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        isAuthorizingDrive = false
        val shouldPublishNow = pendingPublishAfterDriveAuth
        pendingPublishAfterDriveAuth = false

        if (activityResult.resultCode != Activity.RESULT_OK || activityResult.data == null) {
            onDriveAuthError("Google Drive permission was cancelled. Grant Drive access or paste a direct HTTPS link below.")
            return@rememberLauncherForActivityResult
        }

        try {
            val authResult = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(activityResult.data)
            val accessToken = authResult.accessToken?.trim().orEmpty()
            if (accessToken.isEmpty()) {
                onDriveAuthError("Google Drive did not return an access token. Please try granting permission again.")
                return@rememberLauncherForActivityResult
            }
            onDriveTokenGranted(accessToken)
            if (shouldPublishNow) {
                onPublishUpdate(
                    versionCodeText,
                    versionNameText,
                    releaseNotesText,
                    manualHttpsUrlText,
                    isMandatory,
                    accessToken
                )
            }
        } catch (e: ApiException) {
            onDriveAuthError(
                e.localizedMessage ?: "Google Drive authorization failed (code ${e.statusCode})."
            )
        } catch (e: Exception) {
            onDriveAuthError(e.localizedMessage ?: "Unable to read Google Drive authorization result.")
        }
    }

    fun requestGoogleDriveAuthorization(triggerPublishOnSuccess: Boolean) {
        val activity = context.findActivity()
        if (activity == null) {
            onDriveAuthError("Unable to open Google Drive permission dialog from this context.")
            return
        }
        onDismissPublishState()
        isAuthorizingDrive = true
        pendingPublishAfterDriveAuth = triggerPublishOnSuccess

        val request = AppUpdateUploadService.buildGoogleDriveAuthorizationRequest()
        Identity.getAuthorizationClient(activity)
            .authorize(request)
            .addOnSuccessListener { authorizationResult ->
                if (authorizationResult.hasResolution()) {
                    val pendingIntent = authorizationResult.pendingIntent
                    if (pendingIntent != null) {
                        driveAuthIntentLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                    } else {
                        isAuthorizingDrive = false
                        pendingPublishAfterDriveAuth = false
                        onDriveAuthError("Google Drive permission prompt could not be launched.")
                    }
                } else {
                    isAuthorizingDrive = false
                    pendingPublishAfterDriveAuth = false
                    val token = authorizationResult.accessToken?.trim().orEmpty()
                    if (token.isNotEmpty()) {
                        onDriveTokenGranted(token)
                        if (triggerPublishOnSuccess) {
                            onPublishUpdate(
                                versionCodeText,
                                versionNameText,
                                releaseNotesText,
                                manualHttpsUrlText,
                                isMandatory,
                                token
                            )
                        }
                    } else {
                        onDriveAuthError("Google Drive access was granted without an access token. Please retry.")
                    }
                }
            }
            .addOnFailureListener { e ->
                isAuthorizingDrive = false
                pendingPublishAfterDriveAuth = false
                onDriveAuthError(
                    e.localizedMessage
                        ?: "Google Drive authorization request failed. Ensure Drive API & OAuth consent are enabled."
                )
            }
    }

    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onPickApkUri(uri)
        }
    }

    val isUploading = publishState is AppUpdatePublishState.Uploading
    val isInspecting = stagedApkState is StagedApkState.Inspecting
    val isBusy = isUploading || isInspecting || isAuthorizingDrive

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = PureWhite),
        border = BorderStroke(1.5.dp, NavyPrimary.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    tint = NavyPrimary
                )
                Column {
                    Text(
                        text = "Upload App Update (.apk)",
                        style = MaterialTheme.typography.titleMedium,
                        color = NavyPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Owner & Admins only • Uploads APK to Google Drive & notifies all Users",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateTextSecondary
                    )
                }
            }

            // Google Drive Permission Banner / Connect Button
            Surface(
                color = if (hasDriveAccess) SignalGreenContainer else WarningOrangeContainer,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (hasDriveAccess) SignalGreen else WarningOrange),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AddToDrive,
                            contentDescription = "Google Drive Access",
                            tint = if (hasDriveAccess) SignalGreen else WarningOrange
                        )
                        Column {
                            Text(
                                text = if (hasDriveAccess) {
                                    "Google Drive Access Granted"
                                } else {
                                    "Google Drive Access Needed"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = NavyPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (hasDriveAccess) {
                                    "Ready to upload & share new APK updates from Drive."
                                } else {
                                    "Allow Google Drive permission to host & auto-share .apk files."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = NavyPrimary
                            )
                        }
                    }

                    if (!hasDriveAccess) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { requestGoogleDriveAuthorization(triggerPublishOnSuccess = false) },
                            enabled = !isBusy,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("admin_grant_drive_button")
                        ) {
                            if (isAuthorizingDrive) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = PureWhite,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = "Allow",
                                    color = PureWhite,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            if (currentRelease != null) {
                Surface(
                    color = SignalGreenContainer,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, SignalGreen),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Active Pop-up: v${currentRelease.versionName} (#${currentRelease.versionCode})",
                                style = MaterialTheme.typography.titleSmall,
                                color = NavyPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = onRemovePublishedUpdate,
                                enabled = !isBusy,
                                border = BorderStroke(1.dp, AlertRed.copy(alpha = 0.6f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertRed),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("admin_clear_update_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteOutline,
                                    contentDescription = "Clear active update",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clear", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            }
                        }
                        Text(
                            text = "${currentRelease.fileName} (${AppUpdateUrlValidator.formatByteSize(currentRelease.fileSizeBytes)}) • By ${currentRelease.publishedByEmail}",
                            style = MaterialTheme.typography.bodySmall,
                            color = NavyPrimary
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    onDismissPublishState()
                    apkPickerLauncher.launch(
                        arrayOf(
                            AppUpdateUploadService.APK_MIME_TYPE,
                            "application/octet-stream",
                            "*/*"
                        )
                    )
                },
                enabled = !isBusy,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, NavyPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .testTag("admin_select_apk_button")
            ) {
                if (isInspecting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = NavyPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Inspecting APK...")
                } else {
                    Icon(imageVector = Icons.Filled.FolderOpen, contentDescription = null, tint = NavyPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (stagedApkState is StagedApkState.Ready) {
                            "Choose Different .apk File"
                        } else {
                            "Select New App File (.apk)"
                        },
                        color = NavyPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            when (stagedApkState) {
                is StagedApkState.Error -> {
                    Surface(
                        color = AlertRedContainer,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, AlertRed)
                    ) {
                        Text(
                            text = stagedApkState.message,
                            color = AlertRed,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                is StagedApkState.Ready -> {
                    val apk = stagedApkState.apk
                    Surface(
                        color = SlateBackground,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, SlateBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = apk.fileName,
                                style = MaterialTheme.typography.titleSmall,
                                color = NavyPrimary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Size: ${AppUpdateUrlValidator.formatByteSize(apk.fileSizeBytes)} • APK Version: v${apk.versionName} (#${apk.versionCode})",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = SlateTextSecondary
                            )
                            Text(
                                text = "SHA-256: ${apk.sha256Checksum.take(24)}...",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = SlateTextSecondary
                            )
                            if (!apk.matchesAppPackage) {
                                Text(
                                    text = "Warning: APK package (${apk.packageName}) differs from ${BuildConfig.APPLICATION_ID}.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = WarningOrange
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = versionCodeText,
                            onValueChange = {
                                versionCodeText = it
                                onDismissPublishState()
                            },
                            label = { Text("Build # (>${BuildConfig.VERSION_CODE})") },
                            singleLine = true,
                            enabled = !isBusy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("admin_apk_version_code_input")
                        )
                        OutlinedTextField(
                            value = versionNameText,
                            onValueChange = {
                                versionNameText = it
                                onDismissPublishState()
                            },
                            label = { Text("Version Name") },
                            singleLine = true,
                            enabled = !isBusy,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("admin_apk_version_name_input")
                        )
                    }

                    OutlinedTextField(
                        value = releaseNotesText,
                        onValueChange = {
                            releaseNotesText = it
                            onDismissPublishState()
                        },
                        label = { Text("Update Message for Users") },
                        placeholder = { Text("e.g. New weighbridge OCR improvements. Please update.") },
                        enabled = !isBusy,
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_apk_notes_input")
                    )

                    OutlinedTextField(
                        value = manualHttpsUrlText,
                        onValueChange = {
                            manualHttpsUrlText = it
                            onDismissPublishState()
                        },
                        label = { Text("Custom HTTPS Link (Optional)") },
                        placeholder = { Text("Leave blank to upload directly to Google Drive") },
                        singleLine = true,
                        enabled = !isBusy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_apk_url_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Mandatory Update Pop-up",
                                style = MaterialTheme.typography.titleSmall,
                                color = NavyPrimary
                            )
                            Text(
                                text = "Users cannot dismiss the pop-up until they tap Download",
                                style = MaterialTheme.typography.bodySmall,
                                color = SlateTextSecondary
                            )
                        }
                        Switch(
                            checked = isMandatory,
                            onCheckedChange = { isMandatory = it },
                            enabled = !isBusy
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onClearStagedApk,
                            enabled = !isBusy,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.heightIn(min = 50.dp)
                        ) {
                            Text("Cancel", maxLines = 1)
                        }
                        Button(
                            onClick = {
                                if (manualHttpsUrlText.isBlank() && !hasDriveAccess) {
                                    requestGoogleDriveAuthorization(triggerPublishOnSuccess = true)
                                } else {
                                    onPublishUpdate(
                                        versionCodeText,
                                        versionNameText,
                                        releaseNotesText,
                                        manualHttpsUrlText,
                                        isMandatory,
                                        null
                                    )
                                }
                            },
                            enabled = !isBusy,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 50.dp)
                                .testTag("admin_publish_apk_button")
                        ) {
                            if (isAuthorizingDrive) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = PureWhite,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Authorizing...",
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.CloudUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Upload & Notify",
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                else -> {
                    Surface(
                        color = NavyContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Select your new .apk file from storage and tap Upload & Notify. It will upload straight to Google Drive and pop up a download alert for all Users.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NavyPrimary,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }

            when (publishState) {
                is AppUpdatePublishState.Uploading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = publishState.statusLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = NavyPrimary
                        )
                        LinearProgressIndicator(
                            progress = { (publishState.progressPercent.coerceIn(0, 100)) / 100f },
                            color = SignalGreen,
                            trackColor = SlateBorder,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                is AppUpdatePublishState.Success -> {
                    Surface(
                        color = SignalGreenContainer,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, SignalGreen)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SignalGreen)
                            Text(publishState.message, color = NavyPrimary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                is AppUpdatePublishState.Error -> {
                    Surface(
                        color = AlertRedContainer,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AlertRed)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = AlertRed)
                            Text(publishState.message, color = AlertRed, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                else -> Unit
            }
        }
    }
}
