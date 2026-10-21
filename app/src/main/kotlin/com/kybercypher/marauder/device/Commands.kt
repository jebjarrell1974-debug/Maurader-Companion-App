package com.kybercypher.marauder.device

/**
 * The firmware's command surface, and crucially WHICH COMMANDS ARE GATED.
 *
 * CommandLine.cpp:755 wraps every scan and attack command in
 * `if (!wifi_scan_obj.scanning())`. While a scan is running those commands are
 * SILENTLY DISCARDED — the '#' echo still prints, then nothing happens, with no
 * error. That is the single most likely cause of an app appearing frozen, so the
 * app models it explicitly rather than firing and hoping.
 */
enum class Gating {
    /** Runs at any time. */
    ALWAYS,
    /** Ignored while a scan is active; CommandQueue stops the scan first. */
    REQUIRES_IDLE,
}

enum class ScanClass { PASSIVE, TRANSMIT, BLE, NAVIGATE, METER, TARGETED, RAW, NONE }

data class MarauderCommand(
    val id: String,
    val label: String,
    val command: String,
    val gating: Gating,
    /** Firmware's own semantic color class, from startScanFromCLI call sites. */
    val scanClass: ScanClass = ScanClass.NONE,
    /** True if this command puts the device into a running scan mode. */
    val startsScan: Boolean = false,
    val description: String = "",
)

object Commands {

    // ---- always available -------------------------------------------------
    val STOPSCAN = MarauderCommand("stopscan", "Stop", "stopscan", Gating.ALWAYS)
    val STOPSCAN_FORCE = MarauderCommand("stopscan_f", "Stop (disconnect WiFi)", "stopscan -f", Gating.ALWAYS)
    val SETTINGS = MarauderCommand("settings", "Settings", "settings", Gating.ALWAYS)
    val CHANNEL = MarauderCommand("channel", "Channel", "channel", Gating.ALWAYS)
    val REBOOT = MarauderCommand("reboot", "Reboot board", "reboot", Gating.ALWAYS)
    val CLEARLIST_AP = MarauderCommand("clear_a", "Clear APs", "clearlist -a", Gating.ALWAYS)
    val CLEARLIST_ST = MarauderCommand("clear_c", "Clear stations", "clearlist -c", Gating.ALWAYS)
    val CLEARLIST_SSID = MarauderCommand("clear_s", "Clear SSIDs", "clearlist -s", Gating.ALWAYS)
    val GPS_DATA = MarauderCommand("gpsdata", "GPS data", "gpsdata", Gating.ALWAYS, startsScan = true)
    val NMEA = MarauderCommand("nmea", "NMEA stream", "nmea", Gating.ALWAYS, startsScan = true)

    /** Lists run during a scan, but polling them starves capture — see CommandQueue. */
    val LIST_AP = MarauderCommand("list_a", "Access points", "list -a", Gating.ALWAYS)
    val LIST_STATIONS = MarauderCommand("list_c", "Stations", "list -c", Gating.ALWAYS)
    val LIST_SSIDS = MarauderCommand("list_s", "SSIDs", "list -s", Gating.ALWAYS)
    val LIST_PROBES = MarauderCommand("list_p", "Probes", "list -p", Gating.ALWAYS)
    val LIST_BLE = MarauderCommand("list_b", "BLE devices", "list -b", Gating.ALWAYS)
    val LIST_IPS = MarauderCommand("list_i", "IPs", "list -i", Gating.ALWAYS)
    val LIST_FLIPPERS = MarauderCommand("list_f", "Flippers", "list -f", Gating.ALWAYS)

    // ---- gated: scanners / sniffers ---------------------------------------
    val SCANALL = MarauderCommand("scanall", "Scan all", "scanall", Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val SNIFF_BEACON = MarauderCommand("sniffbeacon", "Beacon sniff", "sniffbeacon", Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val SNIFF_PROBE = MarauderCommand("sniffprobe", "Probe sniff", "sniffprobe", Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val SNIFF_PWN = MarauderCommand("sniffpwn", "Pwnagotchi sniff", "sniffpwn", Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val SNIFF_PINESCAN = MarauderCommand("sniffpinescan", "Pineapple sniff", "sniffpinescan", Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val SNIFF_MULTISSID = MarauderCommand("sniffmultissid", "Multi-SSID sniff", "sniffmultissid", Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val SNIFF_DEAUTH = MarauderCommand("sniffdeauth", "Deauth sniff", "sniffdeauth", Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val SNIFF_PMKID = MarauderCommand("sniffpmkid", "PMKID sniff", "sniffpmkid", Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val SNIFF_SAE = MarauderCommand("sniffsae", "SAE sniff", "sniffsae", Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val SNIFF_RAW = MarauderCommand("sniffraw", "Raw capture", "sniffraw", Gating.REQUIRES_IDLE, ScanClass.RAW, true)
    val PACKET_COUNT = MarauderCommand("packetcount", "Packet rate", "packetcount", Gating.REQUIRES_IDLE, ScanClass.METER, true)
    val WARDRIVE = MarauderCommand("wardrive", "Wardrive", "wardrive", Gating.REQUIRES_IDLE, ScanClass.NAVIGATE, true)
    val MAC_TRACK = MarauderCommand("mactrack", "MAC track", "mactrack", Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val ARP_SCAN = MarauderCommand("arpscan", "ARP scan", "arpscan", Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val PING_SCAN = MarauderCommand("pingscan", "Ping scan", "pingscan", Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)

    // ---- gated: bluetooth --------------------------------------------------
    val SNIFF_BT = MarauderCommand("sniffbt", "BLE sniff", "sniffbt", Gating.REQUIRES_IDLE, ScanClass.BLE, true)
    val FIND_MY = MarauderCommand("findmy", "AirTag / FindMy", "findmy", Gating.REQUIRES_IDLE, ScanClass.RAW, true)
    val SNIFF_SKIM = MarauderCommand("sniffskim", "Skimmer sniff", "sniffskim", Gating.REQUIRES_IDLE, ScanClass.BLE, true)

    /** Everything the browsable menu offers, excluding transmit-class modes. */
    val passiveCatalog: List<MarauderCommand> = listOf(
        SCANALL, SNIFF_BEACON, SNIFF_PROBE, SNIFF_PWN, SNIFF_PINESCAN, SNIFF_MULTISSID,
        SNIFF_DEAUTH, SNIFF_PMKID, SNIFF_SAE, SNIFF_RAW, PACKET_COUNT, WARDRIVE,
        MAC_TRACK, ARP_SCAN, PING_SCAN, SNIFF_BT, FIND_MY, SNIFF_SKIM,
    )

    val lists: List<MarauderCommand> = listOf(
        LIST_AP, LIST_STATIONS, LIST_SSIDS, LIST_PROBES, LIST_BLE, LIST_IPS, LIST_FLIPPERS,
    )

    /**
     * Append the serial-stream flag. Re-evaluated by the firmware on EVERY
     * scan/attack command and defaults to false (CommandLine.cpp:757), so it has
     * to be re-supplied each time streaming is wanted.
     */
    fun withSerial(cmd: MarauderCommand, stream: Boolean): String =
        if (stream) "${cmd.command} -serial" else cmd.command

    /** A cheap command whose echo terminates a response that has no terminator. */
    const val SENTINEL = "channel"
}
