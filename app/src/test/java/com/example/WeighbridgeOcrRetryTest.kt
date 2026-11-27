package com.example

import com.example.data.ocr.WeighbridgeOcrService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeighbridgeOcrRetryTest {

    @Test
    fun `transient high demand error messages trigger fallback retry`() {
        val highDemand = IllegalStateException(
            "This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later."
        )
        assertTrue(WeighbridgeOcrService.isTransientDemandError(highDemand))
        assertTrue(WeighbridgeOcrService.isTransientDemandError(RuntimeException("HTTP 503 Service Unavailable")))
        assertTrue(WeighbridgeOcrService.isTransientDemandError(RuntimeException("RESOURCE_EXHAUSTED: 429 too many requests")))
    }

    @Test
    fun `app check and auth errors do not trigger futile retry`() {
        assertFalse(WeighbridgeOcrService.isTransientDemandError(IllegalStateException("Firebase App Check token is invalid.")))
        assertFalse(WeighbridgeOcrService.isTransientDemandError(IllegalStateException("PERMISSION_DENIED: Caller unauthenticated")))
    }

    @Test
    fun `layout A outward loading selects smaller First Weight even when strictSecondWeightPriority is true`() {
        // MAA-BABAR DUA TRADING: First Weight 6,050 Kg (22/11/26), Second Weight 23,370 Kg, Net 17,320 Kg
        val resolved = WeighbridgeOcrService.resolveTruckWeightSelection(
            modelTruckWeightKg = 23370.0, // simulate model accidentally picking Second Weight
            tareWeightKg = null,
            grossWeightKg = null,
            netWeightKg = 17320.0,
            firstWeightKg = 6050.0,
            secondWeightKg = 23370.0,
            modelWeightDate = "22/11/26 9:15:26 am",
            tareDate = "",
            firstWeightDate = "22/11/26",
            secondWeightDate = "22/11/26",
            modelSourceLabel = "Second Weight",
            strictSecondWeightPriority = true
        )

        assertEquals(6050.0, resolved.truckWeightKg!!, 0.01)
        assertEquals("22/11/26", resolved.weightDate)
        assertEquals("First Weight", resolved.sourceLabel)
    }

    @Test
    fun `layout A inward unloading selects smaller Second Weight as empty truck weight`() {
        val resolved = WeighbridgeOcrService.resolveTruckWeightSelection(
            modelTruckWeightKg = 23370.0,
            tareWeightKg = null,
            grossWeightKg = null,
            netWeightKg = 17320.0,
            firstWeightKg = 23370.0,
            secondWeightKg = 6050.0,
            modelWeightDate = "22/11/26",
            tareDate = "",
            firstWeightDate = "21/11/26",
            secondWeightDate = "22/11/26",
            modelSourceLabel = "First Weight",
            strictSecondWeightPriority = true
        )

        assertEquals(6050.0, resolved.truckWeightKg!!, 0.01)
        assertEquals("22/11/26", resolved.weightDate)
        assertEquals("Second Weight", resolved.sourceLabel)
    }

    @Test
    fun `layout B Mahira Enterprise prefers explicit Tare Weight and Tare Date over Gross Weight`() {
        // MAHIRA ENTERPRISE: Gross 22,730 Kg (5:53PM), Tare Weight 7,160 Kg (16/11/2026 3:18:44PM), Net 15,570 Kg
        val resolved = WeighbridgeOcrService.resolveTruckWeightSelection(
            modelTruckWeightKg = 22730.0,
            tareWeightKg = 7160.0,
            grossWeightKg = 22730.0,
            netWeightKg = 15570.0,
            firstWeightKg = null,
            secondWeightKg = null,
            modelWeightDate = "16/11/2026 5:53:02PM",
            tareDate = "Date : 16/11/2026 3:18:44PM",
            firstWeightDate = "",
            secondWeightDate = "",
            modelSourceLabel = "Tare Weight",
            strictSecondWeightPriority = true
        )

        assertEquals(7160.0, resolved.truckWeightKg!!, 0.01)
        assertEquals("16/11/2026", resolved.weightDate)
        assertEquals("Tare Weight", resolved.sourceLabel)
    }

    @Test
    fun `layout C Kazi Ghat prefers Tare weight and Tare Time date without clock time`() {
        // KAZI GHAT: Tare 10270, Tare Time: 2026-11-22 9:33:25 AM
        val resolved = WeighbridgeOcrService.resolveTruckWeightSelection(
            modelTruckWeightKg = 10270.0,
            tareWeightKg = 10270.0,
            grossWeightKg = 29750.0,
            netWeightKg = 19480.0,
            firstWeightKg = null,
            secondWeightKg = null,
            modelWeightDate = "2026-11-22 12:18:03 PM",
            tareDate = "2026-11-22 9:33:25 AM",
            firstWeightDate = "",
            secondWeightDate = "",
            modelSourceLabel = "Tare",
            strictSecondWeightPriority = true
        )

        assertEquals(10270.0, resolved.truckWeightKg!!, 0.01)
        assertEquals("2026-11-22", resolved.weightDate)
        assertEquals("Tare", resolved.sourceLabel)
    }
}
