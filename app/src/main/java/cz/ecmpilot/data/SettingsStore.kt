package cz.ecmpilot.data

import android.content.Context
import android.annotation.SuppressLint
import cz.ecmpilot.model.*
import cz.ecmpilot.protocol.newId

// The commit result must be checked before handing a new command to the network; KTX edit discards it.
@SuppressLint("UseKtx")
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("pilot", Context.MODE_PRIVATE)
    val clientId: String = prefs.getString("client", null) ?: newId().also { check(prefs.edit().putString("client", it).commit()) }
    fun load(): Settings = Settings(
        source = enumValue(prefs.getString("source", null), Source.DEMO),
        host = prefs.getString("host", "ecmpilot-gw.lan")!!, port = prefs.getInt("port", 42840),
        vehicleName = prefs.getString("vehicle", "Opel Corsa-e")!!, gatewayId = prefs.getString("gateway", "")!!,
        allowLabControls = prefs.getBoolean("lab", false), theme = enumValue(prefs.getString("theme", null), ThemeMode.SYSTEM),
        scene = enumValue(prefs.getString("scene", null), DemoScene.CHARGING),
        autoDiscovery = prefs.getBoolean("auto_discovery", true),
    )
    fun save(s: Settings) {
        check(prefs.edit().putString("source", s.source.name).putString("host", s.host).putInt("port", s.port)
            .putString("vehicle", s.vehicleName).putString("gateway", s.gatewayId).putBoolean("lab", s.allowLabControls)
            .putString("theme", s.theme.name).putString("scene", s.scene.name)
            .putBoolean("auto_discovery", s.autoDiscovery).commit()) { "Could not save settings" }
    }
    fun saveCommand(c: CommandUi?) {
        check(prefs.edit().putString("command", CommandReceipt.encode(c)).commit()) { "Could not save command receipt" }
    }
    fun loadCommand(): CommandUi? {
        val text = prefs.getString("command", null) ?: return null
        val command = CommandReceipt.decode(text)
        // Also remove completed receipts written by older app versions.
        if (command == null) saveCommand(null)
        return command
    }
    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T) = enumValues<T>().firstOrNull { it.name == value } ?: fallback
}
