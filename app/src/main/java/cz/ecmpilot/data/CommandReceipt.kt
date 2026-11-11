package cz.ecmpilot.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import cz.ecmpilot.model.CommandRef
import cz.ecmpilot.model.CommandUi
import cz.ecmpilot.model.Correlation

/** Persist only receipts whose original request still needs a result query. */
internal object CommandReceipt {
    fun encode(command: CommandUi?): String? {
        if (command?.needsRecovery != true) return null
        val ref = requireNotNull(command.ref)
        return JsonObject().apply {
            addProperty("id", ref.id); addProperty("gw", ref.gatewayId); addProperty("boot", ref.boot)
            addProperty("label", ref.label); addProperty("host", ref.host); addProperty("port", ref.port)
            addProperty("phase", command.phase); addProperty("message", command.message); addProperty("revision", command.resultRevision)
            ref.correlation?.let { addProperty("client_session", it.session); addProperty("correlation_id", it.id) }
        }.toString()
    }

    fun decode(text: String): CommandUi? = runCatching {
        val o = JsonParser.parseString(text).asJsonObject
        val correlation = o.get("correlation_id")?.let { Correlation(o["client_session"].asString, it.asLong) }
        CommandUi(CommandRef(o["id"].asString, o["gw"].asString, o["boot"].asString, o["label"].asString,
            o["host"].asString, o["port"].asInt, correlation), o["phase"].asString, o["message"].asString,
            o["revision"].asLong).takeIf { it.needsRecovery }
    }.getOrNull()
}
