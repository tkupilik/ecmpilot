package cz.ecmpilot.model

data class ObdAdapter(val address: String, val addressType: String, val rssi: Int? = null)
data class ElmStatus(val revision: Long, val adapter: ObdAdapter?, val busy: Boolean,
    val generation: Long, val completed: Long, val operation: Int, val result: Int,
    val adapters: List<ObdAdapter>) {
    val scanning get() = busy && operation == 1
}
data class ObdPage(val page: Int, val readings: Map<String, Reading>)
data class ObdField(val key: String, val label: String, val unit: String, val decimals: Int,
    val group: String, val range: ClosedFloatingPointRange<Double>)

/** Vehicle-validated readings; keys and units match the gateway's varstore observations. */
object ObdFields {
    val all = listOf(
        ObdField("energy_remaining_kwh", "Remaining energy", "kWh", 2, "Traction battery", 0.0..66.0),
        ObdField("energy_total_kwh", "Total energy", "kWh", 2, "Traction battery", 0.0..66.0),
        ObdField("soc_pct", "OBD SOC", "%", 1, "Traction battery", 0.0..100.0),
        ObdField("voltage_v", "Voltage", "V", 1, "Traction battery", 0.0..500.0),
        ObdField("current_a", "Current", "A", 1, "Traction battery", 0.0..204.7),
        ObdField("charge_remaining_min", "Charging time remaining", "min", 0, "Traction battery", 0.0..4093.0),
        ObdField("battery_min_c", "Minimum temperature", "°C", 1, "Traction battery", -40.0..210.0),
        ObdField("battery_average_c", "Average temperature", "°C", 1, "Traction battery", -40.0..210.0),
        ObdField("cell_min_v", "Minimum cell voltage", "V", 3, "Traction battery", 1.0..5.0),
        ObdField("cell_max_v", "Maximum cell voltage", "V", 3, "Traction battery", 1.0..5.0),
        ObdField("cell_spread_mv", "Cell voltage spread", "mV", 0, "Traction battery", 0.0..4000.0),
        ObdField("insulation_kohm", "Insulation resistance", "kΩ", 0, "Traction battery", 0.0..60000.0),
        ObdField("soh_capacity_pct", "Capacity SOH", "%", 0, "Traction battery", 0.0..100.0),
        ObdField("soh_resistance_pct", "Resistance SOH", "%", 0, "Traction battery", 0.0..100.0),
        ObdField("dcdc_c", "DC/DC temperature", "°C", 1, "Powertrain", -40.0..214.0),
        ObdField("inverter_c", "Inverter temperature", "°C", 1, "Powertrain", -40.0..214.0),
        ObdField("stator_c", "Motor stator temperature", "°C", 1, "Powertrain", -40.0..214.0),
        ObdField("compressor_current_a", "Compressor current", "A", 1, "Heating & cooling", 0.0..50.0),
        ObdField("hvac_upstream_c", "Upstream air temperature", "°C", 1, "Heating & cooling", -40.0..87.0),
        ObdField("heater_outlet_c", "Heater outlet temperature", "°C", 1, "Heating & cooling", -40.0..87.0),
        ObdField("heater_setpoint_c", "Heater setpoint", "°C", 1, "Heating & cooling", 0.0..127.0),
        ObdField("evaporator_setpoint_c", "Evaporator setpoint", "°C", 1, "Heating & cooling", 0.0..15.5),
        ObdField("evaporator_c", "Evaporator temperature", "°C", 1, "Heating & cooling", -40.0..164.7),
        ObdField("airflow_gps", "Airflow", "g/s", 1, "Heating & cooling", 0.0..255.5),
        ObdField("coolant_c", "Coolant temperature", "°C", 1, "Heating & cooling", -40.0..214.0),
        ObdField("short_loop_c", "Short-loop temperature", "°C", 1, "Heating & cooling", -40.0..214.0),
        ObdField("chiller_request", "Chiller request", "", 0, "Heating & cooling", 0.0..1.0),
    )
    val byKey = all.associateBy { it.key }
    const val PAGES = 5
}

fun elmError(code: String): String = when (code) {
    "elm_busy" -> "The OBD adapter is busy. Wait for the current scan or reading to finish."
    "settings_conflict" -> "Adapter settings changed. Reload and select the adapter again."
    "wrong_boot" -> "Gateway restarted. Reload before trying again."
    "storage_error" -> "The gateway could not save the adapter."
    "invalid_adapter_settings", "invalid_argument" -> "Select a valid Bluetooth adapter."
    else -> "OBD adapter request failed ($code)."
}

fun elmScanError(result: Int): String? = when (result) {
    0, 1, 2 -> null
    4 -> "Bluetooth scan timed out. Try again near the adapter."
    6 -> "This adapter's Bluetooth profile is not supported."
    11 -> "This adapter requires Bluetooth pairing that the gateway does not support."
    12 -> "Not enough gateway memory to start Bluetooth."
    else -> "Bluetooth scan failed (code $result). Try again."
}
