package com.example

import com.example.core.AppCheckTokenManager
import com.example.data.model.AdsBannerConfig
import com.example.data.model.AdsDisplayMode
import com.example.data.model.SlipFormatTemplate
import com.example.data.model.SlipRegionBox
import com.example.data.ocr.WeighbridgeOcrService
import com.example.ui.components.AdsCodeValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerControlsAndTemplatesTest {

    @Test
    fun `app check debug token validator accepts requested token and rejects invalid inputs`() {
        val requestedToken = "95158850-D125-410E-A80F-3CF42093E1BB"
        val lowerToken = "95158850-d125-410e-a80f-3cf42093e1bb"
        assertEquals(requestedToken, AppCheckTokenManager.FALLBACK_DEFAULT_DEBUG_TOKEN)
        assertTrue(AppCheckTokenManager.isValidDebugTokenUuid(requestedToken))
        assertTrue(AppCheckTokenManager.isValidDebugTokenUuid("  $lowerToken  "))
        assertEquals(
            lowerToken,
            AppCheckTokenManager.normalizeToken("  $lowerToken ")
        )
        assertEquals(
            requestedToken,
            AppCheckTokenManager.normalizeToken("  $requestedToken ")
        )

        assertFalse(AppCheckTokenManager.isValidDebugTokenUuid(null))
        assertFalse(AppCheckTokenManager.isValidDebugTokenUuid(""))
        assertFalse(AppCheckTokenManager.isValidDebugTokenUuid("not-a-valid-uuid"))
        assertFalse(AppCheckTokenManager.isValidDebugTokenUuid("95158850-D125-410E-A80F-3CF42093E1B"))
        assertFalse(AppCheckTokenManager.isValidDebugTokenUuid("95158850-D125-410E-A80F-3CF42093E1BZ"))

        // Precedence: intent extra > configured default token > fallback default token
        assertEquals(
            requestedToken,
            AppCheckTokenManager.resolvePreferredDebugToken(
                intentToken = null,
                defaultToken = requestedToken
            )
        )
        assertEquals(
            requestedToken,
            AppCheckTokenManager.resolvePreferredDebugToken(
                intentToken = "   ",
                defaultToken = "invalid-token"
            )
        )
        assertEquals(
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            AppCheckTokenManager.resolvePreferredDebugToken(
                intentToken = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                defaultToken = requestedToken
            )
        )

        assertTrue(WeighbridgeOcrService.isAppCheckError(IllegalStateException("Firebase App Check token is invalid.")))
        assertFalse(WeighbridgeOcrService.isAppCheckError(IllegalStateException("503 Service Unavailable")))
    }

    @Test
    fun `ads validator enforces https and blocks insecure schemes while accepting valid units and html`() {
        val admobUnit = "ca-app-pub-3940256099942544/6300978111"
        assertTrue(AdsCodeValidator.isAdMobUnitId(admobUnit))
        assertFalse(AdsCodeValidator.isAdMobUnitId("ca-app-pub-123/456"))

        val admobConfig = AdsBannerConfig(
            adsEnabled = true,
            adsMode = AdsDisplayMode.AD_CODE.name,
            adCodeSnippet = admobUnit
        )
        assertNull(AdsCodeValidator.validateAdsConfig(admobConfig))
        // AdMob unit IDs validate cleanly even though WebView rendering skips raw ID strings
        assertFalse(AdsCodeValidator.shouldRenderAdBanner(admobConfig))

        val httpsBannerConfig = AdsBannerConfig(
            adsEnabled = true,
            adsMode = AdsDisplayMode.AD_CODE.name,
            adCodeSnippet = "https://cdn.example.com/banners/truck-safety.html"
        )
        assertNull(AdsCodeValidator.validateAdsConfig(httpsBannerConfig))
        assertTrue(AdsCodeValidator.shouldRenderAdBanner(httpsBannerConfig))

        val htmlBannerConfig = AdsBannerConfig(
            adsEnabled = true,
            adsMode = AdsDisplayMode.AD_CODE.name,
            adCodeSnippet = """<a href="https://example.com"><img src="https://example.com/ad.png" /></a>"""
        )
        assertNull(AdsCodeValidator.validateAdsConfig(htmlBannerConfig))
        assertTrue(AdsCodeValidator.shouldRenderAdBanner(htmlBannerConfig))

        val insecureHttpConfig = AdsBannerConfig(
            adsEnabled = true,
            adsMode = AdsDisplayMode.AD_CODE.name,
            adCodeSnippet = "http://insecure.example.com/ad.js"
        )
        assertNotNull(AdsCodeValidator.validateAdsConfig(insecureHttpConfig))
        assertFalse(AdsCodeValidator.shouldRenderAdBanner(insecureHttpConfig))

        val javascriptSchemeConfig = AdsBannerConfig(
            adsEnabled = true,
            adsMode = AdsDisplayMode.AD_CODE.name,
            adCodeSnippet = """<a href="javascript:alert(1)">Click</a>"""
        )
        assertNotNull(AdsCodeValidator.validateAdsConfig(javascriptSchemeConfig))
        assertFalse(AdsCodeValidator.shouldRenderAdBanner(javascriptSchemeConfig))

        val validSponsored = AdsBannerConfig(
            adsEnabled = true,
            adsMode = AdsDisplayMode.SPONSORED_CARD.name,
            sponsorTitle = "Highway Tire Partner",
            sponsorSubtitle = "Verified depot service",
            sponsorCtaLabel = "Visit",
            sponsorTargetUrl = "https://partners.example.com/tires"
        )
        assertNull(AdsCodeValidator.validateAdsConfig(validSponsored))
        assertTrue(AdsCodeValidator.shouldRenderAdBanner(validSponsored))

        val invalidSponsoredHttp = validSponsored.copy(sponsorTargetUrl = "http://partners.example.com")
        assertNotNull(AdsCodeValidator.validateAdsConfig(invalidSponsoredHttp))
    }

    @Test
    fun `slip region box normalizes bounds and ocr prompt injects active custom templates only`() {
        val reversedBox = SlipRegionBox(left = 0.65, top = 0.50, right = 0.20, bottom = 0.30)
        assertFalse(reversedBox.isValid())

        val clamped = reversedBox.normalizedClamped()
        assertTrue(clamped.isValid())
        assertEquals(0.20, clamped.left, 0.0001)
        assertEquals(0.30, clamped.top, 0.0001)
        assertEquals(0.65, clamped.right, 0.0001)
        assertEquals(0.50, clamped.bottom, 0.0001)
        assertEquals("X: 20%–65%, Y: 30%–50%", clamped.toPercentSummary())

        val activeTemplate = SlipFormatTemplate(
            id = "tpl_1",
            formatName = "Padma Digital Scale",
            headerHint = "PADMA SCALE HOUSE",
            vehicleBox = SlipRegionBox(0.10, 0.22, 0.48, 0.31),
            vehicleLabelHint = "Lorry No:",
            weightBox = SlipRegionBox(0.52, 0.44, 0.88, 0.56),
            weightLabelHint = "Empty Wt:",
            dateBox = SlipRegionBox(0.12, 0.10, 0.45, 0.18),
            dateLabelHint = "Slip Date:",
            notes = "Ignore gross cargo weight at top right.",
            isActive = true
        )
        val inactiveTemplate = activeTemplate.copy(
            id = "tpl_2",
            formatName = "Archived Old Scale",
            isActive = false
        )

        val prompt = WeighbridgeOcrService.buildPromptText(
            strictSecondWeightPriority = true,
            customTemplates = listOf(activeTemplate, inactiveTemplate)
        )

        assertTrue(prompt.contains("CUSTOM OWNER-MARKED SLIP FORMATS"))
        assertTrue(prompt.contains("Padma Digital Scale"))
        assertTrue(prompt.contains("PADMA SCALE HOUSE"))
        assertTrue(prompt.contains("X: 10%–48%, Y: 22%–31%"))
        assertTrue(prompt.contains("Lorry No:"))
        assertTrue(prompt.contains("X: 52%–88%, Y: 44%–56%"))
        assertTrue(prompt.contains("Empty Wt:"))
        assertTrue(prompt.contains("X: 12%–45%, Y: 10%–18%"))
        assertTrue(prompt.contains("Slip Date:"))
        assertFalse(prompt.contains("Archived Old Scale"))
    }
}
