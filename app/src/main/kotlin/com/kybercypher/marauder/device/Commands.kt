package com.kybercypher.marauder.device

/**
 * The COMPLETE command surface of ESP32 Marauder v1.17.0.
 *
 * Every entry, every argument and every enum value below was read out of
 * CommandLine.h/.cpp at tag v1.17.0 — not from the wiki, and not guessed.
 *
 * GATING (CommandLine.cpp:755): every scan and attack command is wrapped in
 * `if (!wifi_scan_obj.scanning())`. While a scan runs they are SILENTLY
 * DISCARDED — the '#' echo still prints, then nothing happens, with no error.
 * That is the single most likely cause of an app appearing frozen, so each
 * command declares its gating and the session stops the scan first.
 */
enum class Gating { ALWAYS, REQUIRES_IDLE }

/** The firmware's own semantic colour classes, from startScanFromCLI call sites. */
enum class ScanClass { PASSIVE, TRANSMIT, BLE, NAVIGATE, METER, TARGETED, RAW, NONE }

/** Which on-device list an index argument refers to. */
enum class ListSource { AP, STATION, SSID, BLE, FLIPPER, AIRTAG, IP, PROBE, PINEAPPLE, MULTISSID, NONE }

/** An argument the user must supply before the command can be sent. */
sealed interface Param {
    val key: String
    val label: String

    /** One of a fixed set of firmware-accepted values. */
    data class Choice(
        override val key: String,
        override val label: String,
        val options: List<String>,
    ) : Param

    /** An index into one of the device's lists. */
    data class Index(
        override val key: String,
        override val label: String,
        val source: ListSource,
    ) : Param

    /** Free text (SSID name, filename, hex colour, label). */
    data class Text(
        override val key: String,
        override val label: String,
        val optional: Boolean = false,
    ) : Param

    /** A bounded integer (channel, brightness, count). */
    data class Number(
        override val key: String,
        override val label: String,
        val min: Int,
        val max: Int,
    ) : Param
}

enum class Category(val label: String) {
    WIFI_SCAN("wifi // scanners"),
    WIFI_SNIFF("wifi // sniffers"),
    WIFI_ATTACK("wifi // attacks"),
    WIFI_GENERAL("wifi // general"),
    BLUETOOTH("bluetooth"),
    BT_ATTACK("bluetooth // attacks"),
    GPS("gps"),
    LISTS("lists // selection"),
    DEVICE("device"),
}

data class MarauderCommand(
    val id: String,
    val label: String,
    /** Template; {key} placeholders are filled from [params]. */
    val template: String,
    val category: Category,
    val gating: Gating,
    val scanClass: ScanClass = ScanClass.NONE,
    val startsScan: Boolean = false,
    /**
     * True if this mode TRANSMITS. Independent of scanClass — the firmware
     * colours sae/csa/quiet cyan and rickroll yellow, but all of them emit.
     * The confirm gate and auto-stop key off this, not off colour.
     */
    val transmits: Boolean = false,
    val params: List<Param> = emptyList(),
    val description: String = "",
) {
    val needsInput: Boolean get() = params.isNotEmpty()

    fun render(values: Map<String, String> = emptyMap()): String {
        var out = template
        for (p in params) {
            val v = values[p.key].orEmpty().trim()
            out = out.replace("{${p.key}}", v)
        }
        // Collapse the gaps left by omitted optional arguments.
        return out.replace(Regex("\\s+"), " ").trim()
    }
}

object Commands {

    /** A cheap command whose echo terminates a response the firmware never terminates. */
    const val SENTINEL = "channel"

    /**
     * Setting keys the firmware recognises (settings.cpp). Declared FIRST:
     * object properties initialise in declaration order, so a val referenced
     * before its own declaration would still be null at that point.
     */
    val SETTING_KEYS = listOf(
        "SavePCAP", "ChanHop", "ForcePMKID", "ForceProbe", "EPDeauth", "EnableLED",
        "ClientSSID", "ClientPW",
    )

    // ------------------------------------------------------------ wifi scanners
    val SCANALL = MarauderCommand("scanall", "Scan all", "scanall",
        Category.WIFI_SCAN, Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true,
        description = "Scans for APs and stations.")
    val PACKET_COUNT = MarauderCommand("packetcount", "Packet rate", "packetcount",
        Category.WIFI_SCAN, Gating.REQUIRES_IDLE, ScanClass.METER, true,
        description = "Counts packets per second.")
    val WARDRIVE = MarauderCommand("wardrive", "Wardrive", "wardrive",
        Category.WIFI_SCAN, Gating.REQUIRES_IDLE, ScanClass.NAVIGATE, true,
        description = "Logs APs with GPS fixes.")
    val MAC_TRACK = MarauderCommand("mactrack", "MAC track", "mactrack",
        Category.WIFI_SCAN, Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val PING_SCAN = MarauderCommand("pingscan", "Ping scan", "pingscan",
        Category.WIFI_SCAN, Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true,
        description = "Requires a joined network.")
    val ARP_SCAN = MarauderCommand("arpscan", "ARP scan", "arpscan",
        Category.WIFI_SCAN, Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val ARP_SCAN_F = MarauderCommand("arpscan_f", "ARP scan (full)", "arpscan -f",
        Category.WIFI_SCAN, Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val PORT_SCAN_SVC = MarauderCommand("portscan_s", "Port scan (service)", "portscan -s {svc}",
        Category.WIFI_SCAN, Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true,
        params = listOf(Param.Choice("svc", "Service",
            listOf("ssh", "telnet", "dns", "http", "smtp", "https", "rdp"))))
    val PORT_SCAN_ALL = MarauderCommand("portscan_a", "Port scan (all, one IP)", "portscan -a -t {ip}",
        Category.WIFI_SCAN, Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true,
        params = listOf(Param.Index("ip", "IP", ListSource.IP)))
    val FOXHUNT_AP = MarauderCommand("foxhunt_w", "Fox hunt: AP", "foxhunt -w {ap}",
        Category.WIFI_SCAN, Gating.REQUIRES_IDLE, ScanClass.NAVIGATE, true,
        params = listOf(Param.Index("ap", "Access point", ListSource.AP)),
        description = "Signal-strength hunt toward one AP.")
    val FOXHUNT_STA = MarauderCommand("foxhunt_s", "Fox hunt: station", "foxhunt -s {ap} {sta}",
        Category.WIFI_SCAN, Gating.REQUIRES_IDLE, ScanClass.NAVIGATE, true,
        params = listOf(Param.Index("ap", "Access point", ListSource.AP),
                        Param.Index("sta", "Station", ListSource.STATION)))
    val FOXHUNT_BLE = MarauderCommand("foxhunt_b", "Fox hunt: BLE", "foxhunt -b {dev}",
        Category.WIFI_SCAN, Gating.REQUIRES_IDLE, ScanClass.NAVIGATE, true,
        params = listOf(Param.Index("dev", "BLE device", ListSource.BLE)))
    val FOXHUNT_TAG = MarauderCommand("foxhunt_t", "Fox hunt: AirTag", "foxhunt -t {tag}",
        Category.WIFI_SCAN, Gating.REQUIRES_IDLE, ScanClass.NAVIGATE, true,
        params = listOf(Param.Index("tag", "AirTag", ListSource.AIRTAG)))
    val FOXHUNT_FLIPPER = MarauderCommand("foxhunt_f", "Fox hunt: Flipper", "foxhunt -f {dev}",
        Category.WIFI_SCAN, Gating.REQUIRES_IDLE, ScanClass.NAVIGATE, true,
        params = listOf(Param.Index("dev", "Flipper", ListSource.FLIPPER)))
    val FOXHUNT_PINE = MarauderCommand("foxhunt_p", "Fox hunt: Pineapple", "foxhunt -p {dev}",
        Category.WIFI_SCAN, Gating.REQUIRES_IDLE, ScanClass.NAVIGATE, true,
        params = listOf(Param.Index("dev", "Pineapple", ListSource.PINEAPPLE)))
    val FOXHUNT_MULTI = MarauderCommand("foxhunt_m", "Fox hunt: multi-SSID", "foxhunt -m {dev}",
        Category.WIFI_SCAN, Gating.REQUIRES_IDLE, ScanClass.NAVIGATE, true,
        params = listOf(Param.Index("dev", "Multi-SSID AP", ListSource.MULTISSID)))

    // ------------------------------------------------------------ wifi sniffers
    val SNIFF_RAW = MarauderCommand("sniffraw", "Raw capture", "sniffraw",
        Category.WIFI_SNIFF, Gating.REQUIRES_IDLE, ScanClass.RAW, true)
    val SNIFF_BEACON = MarauderCommand("sniffbeacon", "Beacon sniff", "sniffbeacon",
        Category.WIFI_SNIFF, Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val SNIFF_PROBE = MarauderCommand("sniffprobe", "Probe sniff", "sniffprobe",
        Category.WIFI_SNIFF, Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val SNIFF_PWN = MarauderCommand("sniffpwn", "Pwnagotchi sniff", "sniffpwn",
        Category.WIFI_SNIFF, Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val SNIFF_PINESCAN = MarauderCommand("sniffpinescan", "Pineapple sniff", "sniffpinescan",
        Category.WIFI_SNIFF, Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val SNIFF_MULTISSID = MarauderCommand("sniffmultissid", "Multi-SSID sniff", "sniffmultissid",
        Category.WIFI_SNIFF, Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val SNIFF_DEAUTH = MarauderCommand("sniffdeauth", "Deauth sniff", "sniffdeauth",
        Category.WIFI_SNIFF, Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true,
        description = "Listens for deauth frames. Does not transmit.")
    val SNIFF_SAE = MarauderCommand("sniffsae", "SAE sniff", "sniffsae",
        Category.WIFI_SNIFF, Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val SNIFF_PMKID = MarauderCommand("sniffpmkid", "PMKID sniff", "sniffpmkid",
        Category.WIFI_SNIFF, Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)
    val SNIFF_PMKID_CH = MarauderCommand("sniffpmkid_c", "PMKID sniff (channel)", "sniffpmkid -c {ch}",
        Category.WIFI_SNIFF, Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true,
        params = listOf(Param.Number("ch", "Channel", 1, 165)))
    val SNIFF_PMKID_DEAUTH = MarauderCommand("sniffpmkid_d", "PMKID sniff + deauth", "sniffpmkid -d",
        Category.WIFI_SNIFF, Gating.REQUIRES_IDLE, ScanClass.TRANSMIT, true, transmits = true,
        description = "Sends deauth frames to force a handshake.")
    val SNIFF_PMKID_LIST = MarauderCommand("sniffpmkid_l", "PMKID sniff (selected)", "sniffpmkid -l",
        Category.WIFI_SNIFF, Gating.REQUIRES_IDLE, ScanClass.PASSIVE, true)

    // ------------------------------------------------------------ wifi attacks
    val ATK_DEAUTH = MarauderCommand("atk_deauth", "Deauth", "attack -t deauth",
        Category.WIFI_ATTACK, Gating.REQUIRES_IDLE, ScanClass.TRANSMIT, true, transmits = true,
        description = "Sends deauthentication frames at selected targets.")
    val ATK_DEAUTH_C = MarauderCommand("atk_deauth_c", "Deauth (continuous)", "attack -t deauth -c",
        Category.WIFI_ATTACK, Gating.REQUIRES_IDLE, ScanClass.TRANSMIT, true, transmits = true)
    val ATK_DEAUTH_MANUAL = MarauderCommand("atk_deauth_m", "Deauth (manual src/dst)",
        "attack -t deauth -s {src} -d {dst}",
        Category.WIFI_ATTACK, Gating.REQUIRES_IDLE, ScanClass.TRANSMIT, true, transmits = true,
        params = listOf(Param.Text("src", "Source MAC"), Param.Text("dst", "Dest MAC")))
    val ATK_BEACON_LIST = MarauderCommand("atk_beacon_l", "Beacon spam (SSID list)", "attack -t beacon -l",
        Category.WIFI_ATTACK, Gating.REQUIRES_IDLE, ScanClass.BLE, true, transmits = true)
    val ATK_BEACON_RANDOM = MarauderCommand("atk_beacon_r", "Beacon spam (random)", "attack -t beacon -r",
        Category.WIFI_ATTACK, Gating.REQUIRES_IDLE, ScanClass.BLE, true, transmits = true)
    val ATK_BEACON_AP = MarauderCommand("atk_beacon_a", "Beacon spam (targeted AP)", "attack -t beacon -a",
        Category.WIFI_ATTACK, Gating.REQUIRES_IDLE, ScanClass.BLE, true, transmits = true)
    val ATK_PROBE = MarauderCommand("atk_probe", "Probe flood", "attack -t probe",
        Category.WIFI_ATTACK, Gating.REQUIRES_IDLE, ScanClass.TRANSMIT, true, transmits = true)
    val ATK_FUNNY = MarauderCommand("atk_funny", "Funny beacons", "attack -t funny",
        Category.WIFI_ATTACK, Gating.REQUIRES_IDLE, ScanClass.BLE, true, transmits = true)
    val ATK_RICKROLL = MarauderCommand("atk_rickroll", "Rickroll beacons", "attack -t rickroll",
        Category.WIFI_ATTACK, Gating.REQUIRES_IDLE, ScanClass.TARGETED, true, transmits = true)
    val ATK_BADMSG = MarauderCommand("atk_badmsg", "Bad EAPOL msg", "attack -t badmsg",
        Category.WIFI_ATTACK, Gating.REQUIRES_IDLE, ScanClass.TRANSMIT, true, transmits = true)
    val ATK_BADMSG_C = MarauderCommand("atk_badmsg_c", "Bad EAPOL msg (continuous)", "attack -t badmsg -c",
        Category.WIFI_ATTACK, Gating.REQUIRES_IDLE, ScanClass.TRANSMIT, true, transmits = true)
    val ATK_SLEEP = MarauderCommand("atk_sleep", "Sleep frames", "attack -t sleep",
        Category.WIFI_ATTACK, Gating.REQUIRES_IDLE, ScanClass.TRANSMIT, true, transmits = true)
    val ATK_SLEEP_C = MarauderCommand("atk_sleep_c", "Sleep frames (continuous)", "attack -t sleep -c",
        Category.WIFI_ATTACK, Gating.REQUIRES_IDLE, ScanClass.TRANSMIT, true, transmits = true)
    val ATK_SAE = MarauderCommand("atk_sae", "SAE commit", "attack -t sae",
        Category.WIFI_ATTACK, Gating.REQUIRES_IDLE, ScanClass.BLE, true, transmits = true)
    val ATK_CSA = MarauderCommand("atk_csa", "Channel switch", "attack -t csa",
        Category.WIFI_ATTACK, Gating.REQUIRES_IDLE, ScanClass.BLE, true, transmits = true)
    val ATK_QUIET = MarauderCommand("atk_quiet", "Quiet element", "attack -t quiet",
        Category.WIFI_ATTACK, Gating.REQUIRES_IDLE, ScanClass.BLE, true, transmits = true)
    val KARMA = MarauderCommand("karma", "Karma", "karma -p {idx}",
        Category.WIFI_ATTACK, Gating.REQUIRES_IDLE, ScanClass.TRANSMIT, true, transmits = true,
        params = listOf(Param.Index("idx", "Probe", ListSource.PROBE)),
        description = "Answers a probe request with a matching AP.")
    val EVIL_PORTAL = MarauderCommand("evilportal", "Evil portal", "evilportal -c start",
        Category.WIFI_ATTACK, Gating.REQUIRES_IDLE, ScanClass.METER, true, transmits = true,
        description = "Starts a captive portal AP.")
    val EVIL_PORTAL_HTML = MarauderCommand("evilportal_w", "Evil portal (html)", "evilportal -c start -w {file}",
        Category.WIFI_ATTACK, Gating.REQUIRES_IDLE, ScanClass.METER, true, transmits = true,
        params = listOf(Param.Text("file", "HTML filename on SD")))
    val EVIL_PORTAL_SETHTML = MarauderCommand("evilportal_set", "Evil portal: set html",
        "evilportal -c sethtml {file}", Category.WIFI_ATTACK, Gating.REQUIRES_IDLE,
        params = listOf(Param.Text("file", "HTML filename on SD")))

    // ------------------------------------------------------------ bluetooth
    val SNIFF_BT = MarauderCommand("sniffbt", "BLE sniff", "sniffbt",
        Category.BLUETOOTH, Gating.REQUIRES_IDLE, ScanClass.BLE, true)
    val SNIFF_BT_TYPE = MarauderCommand("sniffbt_t", "BLE sniff (filtered)", "sniffbt -t {kind}",
        Category.BLUETOOTH, Gating.REQUIRES_IDLE, ScanClass.BLE, true,
        params = listOf(Param.Choice("kind", "Device type",
            listOf("airtag", "flipper", "flock", "meta"))))
    val SNIFF_SKIM = MarauderCommand("sniffskim", "Skimmer sniff", "sniffskim",
        Category.BLUETOOTH, Gating.REQUIRES_IDLE, ScanClass.BLE, true)
    val FIND_MY = MarauderCommand("findmy", "FindMy / AirTag", "findmy -t {idx}",
        Category.BLUETOOTH, Gating.REQUIRES_IDLE, ScanClass.RAW, true,
        params = listOf(Param.Index("idx", "AirTag", ListSource.AIRTAG)))

    val BLE_SPAM = MarauderCommand("blespam", "BLE spam", "blespam -t {kind}",
        Category.BT_ATTACK, Gating.REQUIRES_IDLE, ScanClass.BLE, true, transmits = true,
        params = listOf(Param.Choice("kind", "Spam type",
            listOf("sourapple", "applejuice", "google", "samsung", "windows", "flipper", "all"))),
        description = "Floods BLE pairing advertisements.")
    val SPOOF_AT = MarauderCommand("spoofat", "Spoof AirTag", "spoofat -t {idx}",
        Category.BT_ATTACK, Gating.REQUIRES_IDLE, ScanClass.BLE, true, transmits = true,
        params = listOf(Param.Index("idx", "AirTag", ListSource.AIRTAG)))

    // ------------------------------------------------------------ gps
    val GPS_DATA = MarauderCommand("gpsdata", "GPS data", "gpsdata",
        Category.GPS, Gating.ALWAYS, ScanClass.NAVIGATE, true)
    val NMEA = MarauderCommand("nmea", "NMEA stream", "nmea",
        Category.GPS, Gating.ALWAYS, ScanClass.NAVIGATE, true,
        description = "While this runs, ALL commands except stop are dropped with no echo.")
    val GPS_FIELD = MarauderCommand("gps_g", "GPS field", "gps -g {field}",
        Category.GPS, Gating.ALWAYS,
        params = listOf(Param.Choice("field", "Field",
            listOf("fix", "sat", "lon", "lat", "alt", "date", "accuracy", "text", "nmea"))))
    val GPS_SYSTEM = MarauderCommand("gps_n", "GPS constellation", "gps -n {sys}",
        Category.GPS, Gating.ALWAYS,
        params = listOf(Param.Choice("sys", "System",
            listOf("native", "all", "gps", "glonass", "galileo", "navic", "qzss", "beidou"))))
    val GPS_POI_SAVE = MarauderCommand("gpspoi_s", "POI: save", "gpspoi -s", Category.GPS, Gating.ALWAYS)
    val GPS_POI_MARK = MarauderCommand("gpspoi_m", "POI: mark", "gpspoi -m", Category.GPS, Gating.ALWAYS)
    val GPS_POI_END = MarauderCommand("gpspoi_e", "POI: end", "gpspoi -e", Category.GPS, Gating.ALWAYS)
    val WARDRIVE_POI = MarauderCommand("wardrivepoi", "Tag POI while wardriving", "wardrivepoi {label}",
        Category.GPS, Gating.REQUIRES_IDLE,
        params = listOf(Param.Text("label", "Label", optional = true)))
    val GEOFENCE_LIST = MarauderCommand("geofence_l", "Geofences: list", "geofence list",
        Category.GPS, Gating.ALWAYS)
    val GEOFENCE_SET = MarauderCommand("geofence_s", "Geofence: set",
        "geofence set {slot} {lat} {lon} {radius} \"{name}\"",
        Category.GPS, Gating.ALWAYS,
        params = listOf(Param.Number("slot", "Slot", 1, 5), Param.Text("lat", "Latitude"),
            Param.Text("lon", "Longitude"), Param.Text("radius", "Radius (0.1-1.0 mi)"),
            Param.Text("name", "Name")))
    val GEOFENCE_CLEAR = MarauderCommand("geofence_c", "Geofence: clear", "geofence clear {slot}",
        Category.GPS, Gating.ALWAYS, params = listOf(Param.Number("slot", "Slot", 1, 5)))

    // ------------------------------------------------------------ lists & selection
    val LIST_AP = MarauderCommand("list_a", "Access points", "list -a", Category.LISTS, Gating.ALWAYS)
    val LIST_STATIONS = MarauderCommand("list_c", "Stations", "list -c", Category.LISTS, Gating.ALWAYS)
    val LIST_SSIDS = MarauderCommand("list_s", "SSIDs", "list -s", Category.LISTS, Gating.ALWAYS)
    val LIST_PROBES = MarauderCommand("list_p", "Probes", "list -p", Category.LISTS, Gating.ALWAYS)
    val LIST_BLE = MarauderCommand("list_b", "BLE devices", "list -b", Category.LISTS, Gating.ALWAYS)
    val LIST_AIRTAGS = MarauderCommand("list_t", "AirTags", "list -t", Category.LISTS, Gating.ALWAYS)
    val LIST_IPS = MarauderCommand("list_i", "IPs", "list -i", Category.LISTS, Gating.ALWAYS)
    val LIST_FLIPPERS = MarauderCommand("list_f", "Flippers", "list -f", Category.LISTS, Gating.ALWAYS)
    val LIST_PINEAPPLE = MarauderCommand("list_x", "Pineapples", "list -x", Category.LISTS, Gating.ALWAYS)
    val LIST_MULTISSID = MarauderCommand("list_m", "Multi-SSID APs", "list -m", Category.LISTS, Gating.ALWAYS)

    val INFO = MarauderCommand("info", "Device info", "info", Category.LISTS, Gating.ALWAYS)
    val INFO_AP = MarauderCommand("info_a", "AP detail", "info -a {idx}", Category.LISTS, Gating.ALWAYS,
        params = listOf(Param.Index("idx", "Access point", ListSource.AP)))

    val SELECT_AP = MarauderCommand("sel_a", "Select APs", "select -a {idx}", Category.LISTS, Gating.ALWAYS,
        params = listOf(Param.Text("idx", "Indices (comma separated, or -f for all)")))
    val SELECT_SSID = MarauderCommand("sel_s", "Select SSIDs", "select -s {idx}", Category.LISTS, Gating.ALWAYS,
        params = listOf(Param.Text("idx", "Indices (comma separated)")))
    val SELECT_STATION = MarauderCommand("sel_c", "Select stations", "select -c {idx}", Category.LISTS, Gating.ALWAYS,
        params = listOf(Param.Text("idx", "Indices (comma separated)")))

    val CLEAR_AP = MarauderCommand("clear_a", "Clear APs", "clearlist -a", Category.LISTS, Gating.ALWAYS)
    val CLEAR_STATIONS = MarauderCommand("clear_c", "Clear stations", "clearlist -c", Category.LISTS, Gating.ALWAYS)
    val CLEAR_SSIDS = MarauderCommand("clear_s", "Clear SSIDs", "clearlist -s", Category.LISTS, Gating.ALWAYS)

    val SSID_ADD_NAME = MarauderCommand("ssid_n", "Add SSID", "ssid -a -n {name}", Category.LISTS, Gating.ALWAYS,
        params = listOf(Param.Text("name", "SSID name")))
    val SSID_ADD_RANDOM = MarauderCommand("ssid_g", "Generate SSIDs", "ssid -a -g {count}",
        Category.LISTS, Gating.ALWAYS, params = listOf(Param.Number("count", "How many", 1, 100)))
    val SSID_REMOVE = MarauderCommand("ssid_r", "Remove SSID", "ssid -r {idx}", Category.LISTS, Gating.ALWAYS,
        params = listOf(Param.Index("idx", "SSID", ListSource.SSID)))

    val ADD_AP = MarauderCommand("add_a", "Add AP by MAC", "add -a -b {mac} -ch {ch} -e {ssid}",
        Category.LISTS, Gating.ALWAYS,
        params = listOf(Param.Text("mac", "BSSID"), Param.Number("ch", "Channel", 1, 165),
            Param.Text("ssid", "SSID", optional = true)))
    val ADD_STATION = MarauderCommand("add_c", "Add station by MAC", "add -c -b {mac} -ap {ap}",
        Category.LISTS, Gating.ALWAYS,
        params = listOf(Param.Text("mac", "Station MAC"), Param.Index("ap", "Access point", ListSource.AP)))

    val SAVE_AP = MarauderCommand("save_a", "Save APs to SD", "save -a", Category.LISTS, Gating.ALWAYS)
    val SAVE_SSID = MarauderCommand("save_s", "Save SSIDs to SD", "save -s", Category.LISTS, Gating.ALWAYS)
    val LOAD_AP = MarauderCommand("load_a", "Load APs from SD", "load -a", Category.LISTS, Gating.ALWAYS)
    val LOAD_SSID = MarauderCommand("load_s", "Load SSIDs from SD", "load -s", Category.LISTS, Gating.ALWAYS)

    // ------------------------------------------------------------ wifi general
    val JOIN = MarauderCommand("join", "Join network", "join -a {idx} -p {pw}",
        Category.WIFI_GENERAL, Gating.ALWAYS,
        params = listOf(Param.Index("idx", "Access point", ListSource.AP),
            Param.Text("pw", "Password")))
    val JOIN_SAVED = MarauderCommand("join_s", "Join saved network", "join -s",
        Category.WIFI_GENERAL, Gating.ALWAYS)
    val RAND_AP_MAC = MarauderCommand("randapmac", "Randomise AP MAC", "randapmac",
        Category.WIFI_GENERAL, Gating.REQUIRES_IDLE)
    val RAND_STA_MAC = MarauderCommand("randstamac", "Randomise station MAC", "randstamac",
        Category.WIFI_GENERAL, Gating.REQUIRES_IDLE)
    val CLONE_AP_MAC = MarauderCommand("cloneapmac", "Clone AP MAC", "cloneapmac -a {idx}",
        Category.WIFI_GENERAL, Gating.REQUIRES_IDLE,
        params = listOf(Param.Index("idx", "Access point", ListSource.AP)))
    val CLONE_STA_MAC = MarauderCommand("clonestamac", "Clone station MAC", "clonestamac -s {idx}",
        Category.WIFI_GENERAL, Gating.REQUIRES_IDLE,
        params = listOf(Param.Index("idx", "Station", ListSource.STATION)))
    val UPLOAD = MarauderCommand("upload", "Upload wardrive logs", "upload -d {dest}",
        Category.WIFI_GENERAL, Gating.REQUIRES_IDLE,
        params = listOf(Param.Choice("dest", "Destination", listOf("wigle", "wdg", "both"))),
        description = "Joins saved WiFi and uploads logs from SD.")
    val RECON_WIFI = MarauderCommand("recon_w", "Recon: wifi", "recon wifi",
        Category.WIFI_GENERAL, Gating.REQUIRES_IDLE, ScanClass.PASSIVE)
    val RECON_BLE = MarauderCommand("recon_b", "Recon: ble", "recon ble",
        Category.WIFI_GENERAL, Gating.REQUIRES_IDLE, ScanClass.BLE)
    val RECON_STATUS = MarauderCommand("recon_s", "Recon: status", "recon status",
        Category.WIFI_GENERAL, Gating.ALWAYS)
    val RECON_STOP = MarauderCommand("recon_x", "Recon: stop", "recon stop",
        Category.WIFI_GENERAL, Gating.ALWAYS)

    // ------------------------------------------------------------ device
    val STOPSCAN = MarauderCommand("stopscan", "Stop", "stopscan", Category.DEVICE, Gating.ALWAYS)
    val STOPSCAN_FORCE = MarauderCommand("stopscan_f", "Stop + disconnect WiFi", "stopscan -f",
        Category.DEVICE, Gating.ALWAYS)
    val CHANNEL = MarauderCommand("channel", "Show channel", "channel", Category.DEVICE, Gating.ALWAYS)
    val CHANNEL_SET = MarauderCommand("channel_s", "Set channel", "channel -s {ch}",
        Category.DEVICE, Gating.ALWAYS, params = listOf(Param.Number("ch", "Channel", 1, 165)))
    val SETTINGS = MarauderCommand("settings", "Show settings", "settings", Category.DEVICE, Gating.ALWAYS)
    val SETTING_ENABLE = MarauderCommand("set_en", "Enable setting", "settings -s {key} enable",
        Category.DEVICE, Gating.ALWAYS,
        params = listOf(Param.Choice("key", "Setting", SETTING_KEYS)))
    val SETTING_DISABLE = MarauderCommand("set_dis", "Disable setting", "settings -s {key} disable",
        Category.DEVICE, Gating.ALWAYS,
        params = listOf(Param.Choice("key", "Setting", SETTING_KEYS)))
    val SETTINGS_RESET = MarauderCommand("set_reset", "Reset settings to default", "settings -r",
        Category.DEVICE, Gating.ALWAYS)
    val LED_COLOR = MarauderCommand("led_s", "LED colour", "led -s {hex}", Category.DEVICE, Gating.ALWAYS,
        params = listOf(Param.Text("hex", "Hex colour, e.g. FF00FF")))
    val LED_RAINBOW = MarauderCommand("led_p", "LED rainbow", "led -p rainbow", Category.DEVICE, Gating.ALWAYS)
    val BRIGHTNESS_SET = MarauderCommand("bright_s", "Brightness", "brightness -s {level}",
        Category.DEVICE, Gating.REQUIRES_IDLE, params = listOf(Param.Number("level", "Level", 0, 9)))
    val BRIGHTNESS_CYCLE = MarauderCommand("bright_c", "Cycle brightness", "brightness -c cycle",
        Category.DEVICE, Gating.REQUIRES_IDLE)
    val LS = MarauderCommand("ls", "List SD directory", "ls {dir}", Category.DEVICE, Gating.ALWAYS,
        params = listOf(Param.Text("dir", "Directory, e.g. /")))
    val PROTOCOL_INFO = MarauderCommand("protocolinfo", "Protocol info", "protocolinfo",
        Category.DEVICE, Gating.ALWAYS)
    val BACKUP_SPIFFS = MarauderCommand("backupspiffs", "Backup SPIFFS to SD", "backupspiffs",
        Category.DEVICE, Gating.ALWAYS)
    val BACKUP_STATUS = MarauderCommand("backupstatus", "SPIFFS backup status", "backupstatus",
        Category.DEVICE, Gating.ALWAYS)
    val RESTORE_SPIFFS = MarauderCommand("restorespiffs", "Restore SPIFFS from SD", "restorespiffs",
        Category.DEVICE, Gating.ALWAYS, description = "Reboots the board when it completes.")
    val UPDATE_SD = MarauderCommand("update_s", "Update firmware from SD", "update -s",
        Category.DEVICE, Gating.REQUIRES_IDLE, description = "Flashes firmware from the SD card.")
    val UPDATE_WEB = MarauderCommand("update_w", "Update firmware over WiFi", "update -w",
        Category.DEVICE, Gating.REQUIRES_IDLE)
    val REBOOT = MarauderCommand("reboot", "Reboot board", "reboot", Category.DEVICE, Gating.ALWAYS)
    val HELP = MarauderCommand("help", "Help", "help", Category.DEVICE, Gating.ALWAYS)


    /**
     * NOT IMPLEMENTED BY THE FIRMWARE, deliberately absent:
     *   scansta, btwardrive  - commented out in CommandLine.h; they do not exist
     *   gpstracker           - declared and shown in `help`, but has no dispatch
     *                          branch, so it is accepted and silently ignored
     *   sourapple / swiftpair / samsungblespam / btspamall
     *                        - help text only; use `blespam -t <type>` instead
     *
     * This matters because the firmware echoes '#<line>' for ANY input it
     * receives, including commands it does not recognise, and then does
     * nothing. An app that treats the echo as success reports these as working.
     */
    /** Everything, in menu order. */
    val all: List<MarauderCommand> = listOf(
        SCANALL, PACKET_COUNT, WARDRIVE, MAC_TRACK, PING_SCAN, ARP_SCAN, ARP_SCAN_F,
        PORT_SCAN_SVC, PORT_SCAN_ALL, FOXHUNT_AP, FOXHUNT_STA, FOXHUNT_BLE, FOXHUNT_TAG,
        FOXHUNT_FLIPPER, FOXHUNT_PINE, FOXHUNT_MULTI,
        SNIFF_RAW, SNIFF_BEACON, SNIFF_PROBE, SNIFF_PWN, SNIFF_PINESCAN, SNIFF_MULTISSID,
        SNIFF_DEAUTH, SNIFF_SAE, SNIFF_PMKID, SNIFF_PMKID_CH, SNIFF_PMKID_DEAUTH, SNIFF_PMKID_LIST,
        ATK_DEAUTH, ATK_DEAUTH_C, ATK_DEAUTH_MANUAL, ATK_BEACON_LIST, ATK_BEACON_RANDOM,
        ATK_BEACON_AP, ATK_PROBE, ATK_FUNNY, ATK_RICKROLL, ATK_BADMSG, ATK_BADMSG_C,
        ATK_SLEEP, ATK_SLEEP_C, ATK_SAE, ATK_CSA, ATK_QUIET, KARMA,
        EVIL_PORTAL, EVIL_PORTAL_HTML, EVIL_PORTAL_SETHTML,
        SNIFF_BT, SNIFF_BT_TYPE, SNIFF_SKIM, FIND_MY,
        BLE_SPAM, SPOOF_AT,
        GPS_DATA, NMEA, GPS_FIELD, GPS_SYSTEM, GPS_POI_SAVE, GPS_POI_MARK, GPS_POI_END, WARDRIVE_POI,
        GEOFENCE_LIST, GEOFENCE_SET, GEOFENCE_CLEAR,
        LIST_AP, LIST_STATIONS, LIST_SSIDS, LIST_PROBES, LIST_BLE, LIST_AIRTAGS, LIST_IPS,
        LIST_FLIPPERS, LIST_PINEAPPLE, LIST_MULTISSID, INFO, INFO_AP,
        SELECT_AP, SELECT_SSID, SELECT_STATION, CLEAR_AP, CLEAR_STATIONS, CLEAR_SSIDS,
        SSID_ADD_NAME, SSID_ADD_RANDOM, SSID_REMOVE, ADD_AP, ADD_STATION,
        SAVE_AP, SAVE_SSID, LOAD_AP, LOAD_SSID,
        JOIN, JOIN_SAVED, RAND_AP_MAC, RAND_STA_MAC, CLONE_AP_MAC, CLONE_STA_MAC, UPLOAD,
        RECON_WIFI, RECON_BLE, RECON_STATUS, RECON_STOP,
        STOPSCAN, STOPSCAN_FORCE, CHANNEL, CHANNEL_SET, SETTINGS, SETTING_ENABLE, SETTING_DISABLE,
        SETTINGS_RESET, LED_COLOR, LED_RAINBOW, BRIGHTNESS_SET, BRIGHTNESS_CYCLE, LS,
        PROTOCOL_INFO, BACKUP_SPIFFS, BACKUP_STATUS, RESTORE_SPIFFS, UPDATE_SD, UPDATE_WEB,
        REBOOT, HELP,
    )

    fun byCategory(c: Category) = all.filter { it.category == c }

    /** Append the stream flag. Re-evaluated by the firmware on EVERY scan/attack
     *  command and defaulting to false, so it must be re-supplied each time. */
    fun withSerial(rendered: String, stream: Boolean) = if (stream) "$rendered -serial" else rendered
}
