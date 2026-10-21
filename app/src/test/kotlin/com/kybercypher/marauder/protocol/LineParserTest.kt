package com.kybercypher.marauder.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every expected format was read out of firmware v1.17.0, not guessed. */
class LineParserTest {

    @Test fun accessPointRows() {
        val a = LineParser.parse("[0][CH:6] HomeNet -45") as ParsedLine.Ap
        assertEquals(AccessPoint(0, 6, "HomeNet", -45, false), a.value)

        val spaces = LineParser.parse("[3][CH:11] My Home Network -62") as ParsedLine.Ap
        assertEquals("My Home Network", spaces.value.essid)
        assertEquals(-62, spaces.value.rssi)

        val sel = LineParser.parse("[7][CH:1] Guest WiFi -80 (selected)") as ParsedLine.Ap
        assertTrue(sel.value.selected)

        // SSIDs that look numeric must not be mistaken for the RSSI field.
        val numeric = LineParser.parse("[4][CH:6] 12345 -60") as ParsedLine.Ap
        assertEquals("12345", numeric.value.essid)
        assertEquals(-60, numeric.value.rssi)

        // 5 GHz channel numbers are three digits on the dual-band v8.
        val fiveGhz = LineParser.parse("[12][CH:149] FastNet5G -55") as ParsedLine.Ap
        assertEquals(149, fiveGhz.value.channel)
    }

    @Test fun otherListVariants() {
        assertEquals(
            BleDevice(0, -70, "Galaxy Buds"),
            (LineParser.parse("[0][RSSI:-70] Galaxy Buds") as ParsedLine.Ble).value
        )
        assertEquals(
            FlipperDevice(0, "AA:BB:CC:DD:EE:FF", "Fl1pp3r"),
            (LineParser.parse("[0]MAC: AA:BB:CC:DD:EE:FF Fl1pp3r") as ParsedLine.Flipper).value
        )
        assertEquals(
            SimpleEntry(2, "192.168.1.44"),
            (LineParser.parse("[2] 192.168.1.44") as ParsedLine.Simple).value
        )
    }

    @Test fun indentedStationRows() {
        val s = LineParser.parse("  [3] AA:BB:CC:DD:EE:FF") as ParsedLine.StationRow
        assertEquals("AA:BB:CC:DD:EE:FF", s.value.mac)
        val sel = LineParser.parse("  [4] 11:22:33:44:55:66 (selected)") as ParsedLine.StationRow
        assertTrue(sel.value.selected)
    }

    @Test fun liveTelemetry() {
        val a = LineParser.parse("-72 Ch: 6 AA:BB:CC:DD:EE:FF ESSID: HomeNet") as ParsedLine.Sighting
        assertEquals(ApSighting(-72, 6, "AA:BB:CC:DD:EE:FF", "HomeNet"), a.value)

        // The firmware pads these lines with trailing spaces for the TFT.
        val padded = LineParser.parse("-55 Ch: 11 11:22:33:44:55:66 ESSID: My Home Net  ") as ParsedLine.Sighting
        assertEquals("My Home Net", padded.value.essid)

        val p = LineParser.parse("-80 Ch: 1 Client: AA:BB:CC:DD:EE:FF Requesting: CoffeeWiFi") as ParsedLine.Probe
        assertEquals("CoffeeWiFi", p.value.requesting)
    }

    @Test fun controlLines() {
        assertEquals("list -a", (LineParser.parse("#list -a") as ParsedLine.CommandEcho).command)
        assertEquals(2, (LineParser.parse("2 selected") as ParsedLine.Counts).selected)
        assertEquals("wifi", (LineParser.parse("Stopping WiFi tran/recv") as ParsedLine.ScanStopped).what)
        assertEquals("nmea", (LineParser.parse("END OF NMEA STREAM") as ParsedLine.ScanStopped).what)
        assertTrue(LineParser.parse("@MARAUDER:{\"protocol\":1}") is ParsedLine.Machine)

        // startScanFromCLI prints "Starting" with no trailing space.
        assertEquals(
            "Packet Count Scan",
            (LineParser.parse("StartingPacket Count Scan. Stop with stopscan") as ParsedLine.ScanStarted).what
        )
    }

    @Test fun settingsBlock() {
        val n = LineParser.parse("Name: SavePCAP") as ParsedLine.SettingField
        assertEquals("Name" to "SavePCAP", n.key to n.value)
        val v = LineParser.parse("Value: [redacted]") as ParsedLine.SettingField
        assertEquals("[redacted]", v.value)
    }

    @Test fun ordinaryTextIsNotMisclassified() {
        assertTrue(LineParser.parse("Could not successfully update setting") is ParsedLine.Raw)
        assertTrue(LineParser.parse("/wardrive_0.log") is ParsedLine.Raw)
        assertTrue(LineParser.parse("============ Commands ============") is ParsedLine.Raw)
    }
}
