package com.example

import com.example.data.model.AlertStatus
import com.example.data.model.RuntimeSettings
import com.example.data.model.TruckWeightRecord
import com.example.domain.WeightVerificationEngine
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeightVerificationEngineTest {

    private val defaultSettings = RuntimeSettings()

    @Test
    fun `alert threshold classification matches 0-4 neutral, 5-15 green, 16-19 orange, 20+ red`() {
        assertEquals(0.0 to AlertStatus.NEUTRAL, WeightVerificationEngine.evaluateAlert(6050.0, 6050.0, defaultSettings))
        assertEquals(4.0 to AlertStatus.NEUTRAL, WeightVerificationEngine.evaluateAlert(6054.0, 6050.0, defaultSettings))
        assertEquals(5.0 to AlertStatus.GREEN_SIGNAL, WeightVerificationEngine.evaluateAlert(6055.0, 6050.0, defaultSettings))
        assertEquals(15.0 to AlertStatus.GREEN_SIGNAL, WeightVerificationEngine.evaluateAlert(6035.0, 6050.0, defaultSettings))
        assertEquals(16.0 to AlertStatus.ORANGE_WARNING, WeightVerificationEngine.evaluateAlert(6066.0, 6050.0, defaultSettings))
        assertEquals(19.0 to AlertStatus.ORANGE_WARNING, WeightVerificationEngine.evaluateAlert(6031.0, 6050.0, defaultSettings))
        assertEquals(20.0 to AlertStatus.RED_ALERT, WeightVerificationEngine.evaluateAlert(6070.0, 6050.0, defaultSettings))
        assertEquals(125.0 to AlertStatus.RED_ALERT, WeightVerificationEngine.evaluateAlert(5925.0, 6050.0, defaultSettings))
    }

    @Test
    fun `first weighing without previous record returns neutral and zero diff`() {
        assertEquals(0.0 to AlertStatus.NEUTRAL, WeightVerificationEngine.evaluateAlert(7160.0, null, defaultSettings))
    }

    @Test
    fun `vehicle key normalization matches spaced dash variants while isolating different vehicles`() {
        assertEquals("DMT130806", WeightVerificationEngine.normalizeVehicleKey("DMT -13-0806"))
        assertEquals("DMT130806", WeightVerificationEngine.normalizeVehicleKey("dmt 13 0806"))
        assertEquals("BUGT111633", WeightVerificationEngine.normalizeVehicleKey("BUG T-11-1633"))

        val records = listOf(
            TruckWeightRecord(
                id = "rec_old",
                vehicleNumber = "DMT-13-0806",
                vehicleKey = "DMT130806",
                truckWeight = 10200.0,
                createdAt = Timestamp(1000, 0)
            ),
            TruckWeightRecord(
                id = "rec_latest",
                vehicleNumber = "DMT -13-0806",
                vehicleKey = "DMT130806",
                truckWeight = 10270.0,
                createdAt = Timestamp(2000, 0)
            ),
            TruckWeightRecord(
                id = "rec_other_truck",
                vehicleNumber = "DMT 24-6422",
                vehicleKey = "DMT246422",
                truckWeight = 7160.0,
                createdAt = Timestamp(3000, 0)
            )
        )

        val latestSame = WeightVerificationEngine.findLatestValidPreviousRecord("DMT130806", records)
        assertNotNull(latestSame)
        assertEquals("rec_latest", latestSame?.id)
        assertEquals(10270.0, latestSame?.truckWeight ?: 0.0, 0.001)

        val noMatch = WeightVerificationEngine.findLatestValidPreviousRecord("DMT999999", records)
        assertNull(noMatch)
    }

    @Test
    fun `parseWeightNumber cleans formatted kg strings`() {
        assertEquals(6050.0, WeightVerificationEngine.parseWeightNumber("6,050 Kg") ?: 0.0, 0.001)
        assertEquals(7160.0, WeightVerificationEngine.parseWeightNumber("7,160 kg") ?: 0.0, 0.001)
        assertEquals(10270.0, WeightVerificationEngine.parseWeightNumber("10270") ?: 0.0, 0.001)
        assertNull(WeightVerificationEngine.parseWeightNumber(""))
    }

    @Test
    fun `duplicate candidate detection flags identical vehicle weight and date`() {
        val records = listOf(
            TruckWeightRecord(
                id = "rec_1",
                vehicleNumber = "DMT 24-6422",
                vehicleKey = "DMT246422",
                truckWeight = 7160.0,
                weightDate = "16/11/2026"
            )
        )
        assertTrue(WeightVerificationEngine.isDuplicateCandidate("DMT246422", 7160.0, "16/11/2026", records))
        assertFalse(WeightVerificationEngine.isDuplicateCandidate("DMT246422", 7170.0, "16/11/2026", records))
        assertFalse(WeightVerificationEngine.isDuplicateCandidate("DMT246422", 7160.0, "17/11/2026", records))
    }

    @Test
    fun `csv export escapes commas and preserves all columns`() {
        val csv = WeightVerificationEngine.exportRecordsToCsv(
            listOf(
                TruckWeightRecord(
                    id = "r1",
                    vehicleNumber = "BUG T-11-1633",
                    vehicleKey = "BUGT111633",
                    truckWeight = 6050.0,
                    previousWeight = 6040.0,
                    weightDifference = 10.0,
                    alertStatus = AlertStatus.GREEN_SIGNAL.name,
                    weightDate = "22/11/2026",
                    sourceLabel = "First Weight",
                    ocrConfidence = 0.95
                )
            )
        )
        assertTrue(csv.contains("Vehicle Number,Truck Weight (KG)"))
        assertTrue(csv.contains("BUG T-11-1633,6050.0,6040.0,10.0,GREEN_SIGNAL,22/11/2026"))
    }
}
