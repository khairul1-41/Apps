package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.AdsBannerConfig
import com.example.data.model.AdsDisplayMode
import com.example.data.remote.AppUpdateUrlValidator
import com.example.ui.theme.NavyContainer
import com.example.ui.theme.NavyPrimary
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateTextSecondary

object AdsCodeValidator {
    private val ADMOB_UNIT_ID_REGEX = Regex("^ca-app-pub-\\d{16}/\\d{10}$")

    fun isAdMobUnitId(raw: String): Boolean {
        return ADMOB_UNIT_ID_REGEX.matches(raw.trim())
    }

    fun isSafeHtmlOrHttpsSnippet(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 4096) return false
        val lower = trimmed.lowercase()
        if (lower.contains("javascript:") ||
            lower.contains("file://") ||
            lower.contains("content://") ||
            lower.contains("intent://") ||
            lower.contains("http://")
        ) {
            return false
        }
        return AppUpdateUrlValidator.isSafeHttpsDownloadUrl(trimmed) ||
            lower.contains("<") && lower.contains(">")
    }

    fun validateAdsConfig(config: AdsBannerConfig): String? {
        if (!config.adsEnabled) return null
        return when (config.resolvedMode) {
            AdsDisplayMode.SPONSORED_CARD -> {
                if (config.sponsorTitle.trim().isEmpty()) {
                    return "Enter a Banner Title for your sponsored card."
                }
                val url = config.sponsorTargetUrl.trim()
                if (url.isNotEmpty() && !AppUpdateUrlValidator.isSafeHttpsDownloadUrl(url)) {
                    return "Target URL must begin with https:// and point to a valid host."
                }
                null
            }
            AdsDisplayMode.AD_CODE -> {
                val code = config.adCodeSnippet.trim()
                if (code.isEmpty()) {
                    return "Paste your HTML/HTTPS ad code, HTTPS banner URL, or AdMob Ad Unit ID."
                }
                val lower = code.lowercase()
                if (lower.contains("javascript:") ||
                    lower.contains("file://") ||
                    lower.contains("http://")
                ) {
                    return "Only secure HTTPS ad URLs/scripts are permitted (no http:// or javascript: links)."
                }
                if (!isAdMobUnitId(code) && !isSafeHtmlOrHttpsSnippet(code)) {
                    return "Enter a valid AdMob Unit ID (ca-app-pub-...), HTTPS URL, or HTML ad tag."
                }
                null
            }
        }
    }

    fun shouldRenderAdBanner(config: AdsBannerConfig): Boolean {
        if (!config.adsEnabled) return false
        return when (config.resolvedMode) {
            AdsDisplayMode.SPONSORED_CARD -> config.sponsorTitle.trim().isNotEmpty()
            AdsDisplayMode.AD_CODE -> {
                val snippet = config.adCodeSnippet.trim()
                snippet.isNotEmpty() && !isAdMobUnitId(snippet) && isSafeHtmlOrHttpsSnippet(snippet)
            }
        }
    }
}

@Composable
fun OwnerAdBannerView(
    adsConfig: AdsBannerConfig,
    modifier: Modifier = Modifier
) {
    if (!AdsCodeValidator.shouldRenderAdBanner(adsConfig)) return

    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PureWhite),
        border = BorderStroke(1.dp, SlateBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("owner_ad_banner_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = NavyContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "AD • SPONSORED",
                        style = MaterialTheme.typography.labelSmall,
                        color = NavyPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            when (adsConfig.resolvedMode) {
                AdsDisplayMode.SPONSORED_CARD -> {
                    val cleanUrl = adsConfig.sponsorTargetUrl.trim()
                    val hasValidLink = AppUpdateUrlValidator.isSafeHttpsDownloadUrl(cleanUrl)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = adsConfig.sponsorTitle.trim(),
                                style = MaterialTheme.typography.titleSmall,
                                color = NavyPrimary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (adsConfig.sponsorSubtitle.isNotBlank()) {
                                Text(
                                    text = adsConfig.sponsorSubtitle.trim(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SlateTextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (hasValidLink) {
                            OutlinedButton(
                                onClick = { openSafeExternalHttpsUrl(context, cleanUrl) },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, NavyPrimary),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = NavyPrimary)
                            ) {
                                Text(adsConfig.sponsorCtaLabel.trim().ifEmpty { "Open" })
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "Open sponsored link",
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    }
                }

                AdsDisplayMode.AD_CODE -> {
                    SandboxedAdCodeWebView(
                        rawSnippet = adsConfig.adCodeSnippet.trim(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SandboxedAdCodeWebView(
    rawSnippet: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDirectHttpsUrl = remember(rawSnippet) {
        AppUpdateUrlValidator.isSafeHttpsDownloadUrl(rawSnippet)
    }
    val wrappedHtml = remember(rawSnippet) {
        """
        <!DOCTYPE html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
          <style>
            html, body { margin: 0; padding: 0; background: transparent; overflow: hidden; display: flex; justify-content: center; align-items: center; min-height: 64px; }
            img, iframe { max-width: 100%; border: 0; }
          </style>
        </head>
        <body>$rawSnippet</body>
        </html>
        """.trimIndent()
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setSupportMultipleWindows(false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString().orEmpty()
                        if (AppUpdateUrlValidator.isSafeHttpsDownloadUrl(url)) {
                            openSafeExternalHttpsUrl(context, url)
                        }
                        return true
                    }
                }
            }
        },
        update = { webView ->
            if (isDirectHttpsUrl) {
                webView.loadUrl(rawSnippet)
            } else {
                webView.loadDataWithBaseURL(
                    "https://ads.local/",
                    wrappedHtml,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        }
    )
}

private fun openSafeExternalHttpsUrl(context: Context, url: String) {
    if (!AppUpdateUrlValidator.isSafeHttpsDownloadUrl(url)) return
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
