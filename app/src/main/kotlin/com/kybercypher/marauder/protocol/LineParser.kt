package com.kybercypher.marauder.protocol

/**
 * Parses the Marauder's console grammar.
 *
 * Every pattern here was read out of the firmware source at v1.17.0, not guessed
 * from sample output. Where the firmware is sloppy, this is tolerant:
 *  - line endings are mixed CRLF and LF (handled upstream by FrameDemuxer)
 *  - startScanFromCLI prints "Starting" with no trailing space, producing
 *    "StartingPacket Count Scan"
 *  - only `list -a`, `-s` and `-c` print a trailing count; the rest have no
 *    terminator at all, which is why CommandQueue uses a sentinel instead.
 */
object LineParser {

    private val AP_RE = Regex("""^\[(\d+)]\[CH:\s*(\d+)]\s+(.*?)\s+(-?\d+)$""")
    private val BLE_RE = Regex("""^\[(\d+)]\[RSSI:\s*(-?\d+)]\s*(.*)$""")
    private val FLIPPER_RE = Regex("""^\[(\d+)]MAC:\s*(\S+)\s*(.*)$""")
    private val SIMPLE_RE = Regex("""^\[(\d+)]\s*(.*)$""")
    private val STATION_RE = Regex("""^\s+\[(\d+)]\s*([0-9A-Fa-f:]{17})(\s+\(selected\))?$""")
    private val COUNTS_RE = Regex("""^(\d+) selected(?:,\s*(\d+) unselected)?$""")
    private val SIGHTING_RE = Regex("""^(-?\d+)\s+Ch:\s*(\d+)\s+(\S+)\s+ESSID:\s?(.*?)\s*$""")
    private val PROBE_RE = Regex("""^(-?\d+)\s+Ch:\s*(\d+)\s+Client:\s*(\S+)\s+Requesting:\s?(.*?)\s*$""")
    private val SETTING_RE = Regex("""^(Name|Type|Value):\s*(.*)$""")

    private const val SELECTED_SUFFIX = " (selected)"

    fun parse(line: String): ParsedLine {
        if (line.isEmpty()) return ParsedLine.Raw(line)

        // The firmware echoes every accepted command prefixed with '#'.
        if (line.startsWith("#")) return ParsedLine.CommandEcho(line, line.substring(1))

        if (line.startsWith("@MARAUDER:")) {
            return ParsedLine.Machine(line, line.removePrefix("@MARAUDER:"))
        }

        when (line) {
            "END OF NMEA STREAM" -> return ParsedLine.ScanStopped(line, "nmea")
            "Stopping GPS data updates" -> return ParsedLine.ScanStopped(line, "gps")
            "Stopping WiFi tran/recv" -> return ParsedLine.ScanStopped(line, "wifi")
        }
        if (line.startsWith("Starting")) {
            // "Starting" + name with no space, then ". Stop with stopscan"
            val what = line.removePrefix("Starting").substringBefore(". Stop with").trim()
            return ParsedLine.ScanStarted(line, what)
        }

        STATION_RE.matchEntire(line)?.let { m ->
            return ParsedLine.StationRow(
                line, Station(m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3].isNotEmpty())
            )
        }

        COUNTS_RE.matchEntire(line)?.let { m ->
            return ParsedLine.Counts(line, m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull())
        }

        SIGHTING_RE.matchEntire(line)?.let { m ->
            return ParsedLine.Sighting(
                line,
                ApSighting(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3], m.groupValues[4])
            )
        }
        PROBE_RE.matchEntire(line)?.let { m ->
            return ParsedLine.Probe(
                line,
                ProbeSighting(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3], m.groupValues[4])
            )
        }

        SETTING_RE.matchEntire(line)?.let { m ->
            return ParsedLine.SettingField(line, m.groupValues[1], m.groupValues[2])
        }

        if (line.startsWith("[")) {
            // Strip the selected marker first; the index/value patterns below
            // then see a clean line.
            val selected = line.endsWith(SELECTED_SUFFIX)
            val body = if (selected) line.dropLast(SELECTED_SUFFIX.length) else line

            AP_RE.matchEntire(body)?.let { m ->
                return ParsedLine.Ap(
                    line,
                    AccessPoint(
                        index = m.groupValues[1].toInt(),
                        channel = m.groupValues[2].toInt(),
                        essid = m.groupValues[3],
                        rssi = m.groupValues[4].toInt(),
                        selected = selected,
                    )
                )
            }
            BLE_RE.matchEntire(body)?.let { m ->
                return ParsedLine.Ble(
                    line, BleDevice(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3])
                )
            }
            FLIPPER_RE.matchEntire(body)?.let { m ->
                return ParsedLine.Flipper(
                    line, FlipperDevice(m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3])
                )
            }
            // `list -c` AP header rows look like "[0] essid -70:" — treat the
            // trailing colon form as a simple entry; the stations follow indented.
            SIMPLE_RE.matchEntire(body)?.let { m ->
                return ParsedLine.Simple(
                    line, SimpleEntry(m.groupValues[1].toInt(), m.groupValues[2])
                )
            }
        }

        return ParsedLine.Raw(line)
    }

    /**
     * `list -s` rows are "[i] essid" and are structurally identical to other
     * simple rows, so the caller tells us which list it asked for.
     */
    fun asSsid(p: ParsedLine): SsidEntry? = when (p) {
        is ParsedLine.Simple -> SsidEntry(p.value.index, p.value.value, p.raw.endsWith(SELECTED_SUFFIX))
        else -> null
    }
}
