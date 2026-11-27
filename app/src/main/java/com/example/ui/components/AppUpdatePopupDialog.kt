package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AppUpdateRelease
import com.example.data.remote.AppUpdateUrlValidator
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

fun launchSafeApkDownload(context: Context, rawUrl: String): Result<Unit> {
    val cleanUrl = rawUrl.trim()
    if (!AppUpdateUrlValidator.isSafeHttpsDownloadUrl(cleanUrl)) {
        return Result.failure(IllegalArgumentException("Invalid or insecure APK download link."))
    }
    return runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

@Composable
fun AppUpdatePopupDialog(
    release: AppUpdateRelease,
    currentVersionName: String,
    currentVersionCode: Long,
    onDownloadClick: () -> Unit,
    onLaterClick: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (!release.isMandatory) onLaterClick()
        },
        icon = {
            Surface(
                color = SignalGreenContainer,
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.SystemUpdate,
                    contentDescription = "New App Update",
                    tint = SignalGreen,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(28.dp)
                )
            }
        },
        title = {
            Text(
                text = "New App Update Available",
                style = MaterialTheme.typography.titleLarge,
                color = NavyPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = if (release.isMandatory) WarningOrangeContainer else NavyContainer,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = if (release.isMandatory) {
                            "REQUIRED ADMIN UPDATE • ${release.publishedByRole}"
                        } else {
                            "PUBLISHED BY ${release.publishedByRole}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (release.isMandatory) WarningOrange else NavyPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Text(
                    text = "A new version of TruckWeight Guard has been uploaded by ${release.publishedByEmail.ifBlank { "Admin" }}. Please download and install the latest app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NavyPrimary
                )

                Surface(
                    color = SlateBackground,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, SlateBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Installed: v$currentVersionName (#$currentVersionCode)",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = SlateTextSecondary
                            )
                            Text(
                                text = "New: v${release.versionName} (#${release.versionCode})",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = SignalGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "${release.fileName} • ${AppUpdateUrlValidator.formatByteSize(release.fileSizeBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = NavyPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (release.sha256Checksum.length >= 12) {
                            Text(
                                text = "SHA-256: ${release.sha256Checksum.take(16)}...",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = SlateTextSecondary
                            )
                        }
                    }
                }

                if (release.releaseNotes.isNotBlank()) {
                    Text(
                        text = release.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateTextSecondary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDownloadClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("update_popup_download_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = null,
                    tint = PureWhite,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Download New App",
                    color = PureWhite,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            if (!release.isMandatory) {
                TextButton(
                    onClick = onLaterClick,
                    modifier = Modifier.testTag("update_popup_later_button")
                ) {
                    Text("Later", color = SlateTextSecondary)
                }
            }
        }
    )
}
