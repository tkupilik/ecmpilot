package cz.ecmpilot.data

import android.content.Context
import android.annotation.SuppressLint
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
    )
    fun save(s: Settings) {
        check(prefs.edit().putString("source", s.source.name).putString("host", s.host).putInt("port", s.port)
            .putString("vehicle", s.vehicleName).putString("gateway", s.gatewayId).putBoolean("lab", s.allowLabControls)
            .putString("theme", s.theme.name).putString("scene", s.scene.name).commit()) { "Could not save settings" }
    }
    fun saveCommand(c: CommandUi?) {
        val ref = c?.ref
        val json = if (ref == null) null else JsonObject().apply {
            addProperty("id", ref.id); addProperty("gw", ref.gatewayId); addProperty("boot", ref.boot)
            addProperty("label", ref.label); addProperty("host", ref.host); addProperty("port", ref.port)
            addProperty("phase", c.phase); addProperty("message", c.message); addProperty("revision", c.resultRevision)
            ref.correlation?.let { addProperty("client_session", it.session); addProperty("correlation_id", it.id) }
        }.toString()
        check(prefs.edit().putString("command", json).commit()) { "Could not save command receipt" }
    }
    fun loadCommand(): CommandUi? = runCatching {
        val text = prefs.getString("command", null) ?: return null
        val o = JsonParser.parseString(text).asJsonObject
        val correlation = o.get("correlation_id")?.let { Correlation(o["client_session"].asString, it.asLong) }
        CommandUi(CommandRef(o["id"].asString, o["gw"].asString, o["boot"].asString, o["label"].asString, o["host"].asString, o["port"].asInt, correlation),
            o["phase"].asString, o["message"].asString, o["revision"].asLong)
    }.getOrNull()
    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T) = enumValues<T>().firstOrNull { it.name == value } ?: fallback
}
