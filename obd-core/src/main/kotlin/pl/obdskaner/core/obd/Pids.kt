package pl.obdskaner.core.obd

import java.util.Locale

/**
 * Parametr trybu 01 (dane na żywo).
 *
 * @param bytes ile bajtów danych zwraca sterownik (A, B, …)
 * @param decode wzór z SAE J1979 zamieniający bajty na wartość
 */
class Pid(
    val code: Int,
    val name: String,
    val unit: String,
    val bytes: Int,
    val min: Double,
    val max: Double,
    val decimals: Int = 0,
    val decode: (IntArray) -> Double,
) {
    fun format(value: Double): String = String.format(POLISH, "%.${decimals}f", value)

    override fun toString() = "Pid(%02X %s)".format(code, name)

    private companion object {
        val POLISH: Locale = Locale.forLanguageTag("pl-PL")
    }
}

data class PidValue(val pid: Pid, val value: Double) {
    val formatted: String get() = pid.format(value)

    /** Położenie wartości w zakresie [Pid.min]–[Pid.max], od 0 do 1 (np. do paska). */
    val fraction: Float
        get() = ((value - pid.min) / (pid.max - pid.min)).coerceIn(0.0, 1.0).toFloat()
}

/** Najczęściej używane parametry trybu 01. Wzory: SAE J1979. */
object Pids {
    private fun u16(d: IntArray) = d[0] * 256 + d[1]

    val ENGINE_LOAD = Pid(0x04, "Obciążenie silnika", "%", 1, 0.0, 100.0) { it[0] * 100.0 / 255 }
    val COOLANT_TEMP = Pid(0x05, "Temperatura płynu chłodzącego", "°C", 1, -40.0, 130.0) { it[0] - 40.0 }
    val SHORT_FUEL_TRIM_1 = Pid(0x06, "Krótkoterminowa korekta paliwa (bank 1)", "%", 1, -25.0, 25.0, 1) { (it[0] - 128) * 100.0 / 128 }
    val LONG_FUEL_TRIM_1 = Pid(0x07, "Długoterminowa korekta paliwa (bank 1)", "%", 1, -25.0, 25.0, 1) { (it[0] - 128) * 100.0 / 128 }
    val INTAKE_PRESSURE = Pid(0x0B, "Ciśnienie w kolektorze ssącym", "kPa", 1, 0.0, 255.0) { it[0].toDouble() }
    val RPM = Pid(0x0C, "Obroty silnika", "obr/min", 2, 0.0, 7000.0) { u16(it) / 4.0 }
    val SPEED = Pid(0x0D, "Prędkość", "km/h", 1, 0.0, 200.0) { it[0].toDouble() }
    val TIMING_ADVANCE = Pid(0x0E, "Kąt wyprzedzenia zapłonu", "°", 1, -64.0, 64.0, 1) { it[0] / 2.0 - 64 }
    val INTAKE_TEMP = Pid(0x0F, "Temperatura powietrza dolotowego", "°C", 1, -40.0, 80.0) { it[0] - 40.0 }
    val MAF = Pid(0x10, "Przepływ powietrza (MAF)", "g/s", 2, 0.0, 300.0, 1) { u16(it) / 100.0 }
    val THROTTLE = Pid(0x11, "Położenie przepustnicy", "%", 1, 0.0, 100.0) { it[0] * 100.0 / 255 }
    val RUN_TIME = Pid(0x1F, "Czas od uruchomienia silnika", "s", 2, 0.0, 3600.0) { u16(it).toDouble() }
    val DISTANCE_WITH_MIL = Pid(0x21, "Dystans z zapaloną kontrolką", "km", 2, 0.0, 65535.0) { u16(it).toDouble() }
    val FUEL_LEVEL = Pid(0x2F, "Poziom paliwa", "%", 1, 0.0, 100.0) { it[0] * 100.0 / 255 }
    val DISTANCE_SINCE_CLEAR = Pid(0x31, "Dystans od skasowania błędów", "km", 2, 0.0, 65535.0) { u16(it).toDouble() }
    val BAROMETRIC = Pid(0x33, "Ciśnienie atmosferyczne", "kPa", 1, 0.0, 120.0) { it[0].toDouble() }
    val MODULE_VOLTAGE = Pid(0x42, "Napięcie sterownika", "V", 2, 10.0, 16.0, 1) { u16(it) / 1000.0 }
    val AMBIENT_TEMP = Pid(0x46, "Temperatura otoczenia", "°C", 1, -40.0, 60.0) { it[0] - 40.0 }
    val OIL_TEMP = Pid(0x5C, "Temperatura oleju", "°C", 1, -40.0, 150.0) { it[0] - 40.0 }
    val FUEL_RATE = Pid(0x5E, "Chwilowe zużycie paliwa", "l/h", 2, 0.0, 30.0, 1) { u16(it) / 20.0 }

    val ALL: List<Pid> = listOf(
        RPM, SPEED, COOLANT_TEMP, ENGINE_LOAD, THROTTLE, INTAKE_TEMP, INTAKE_PRESSURE, MAF,
        OIL_TEMP, MODULE_VOLTAGE, FUEL_LEVEL, FUEL_RATE, SHORT_FUEL_TRIM_1, LONG_FUEL_TRIM_1,
        TIMING_ADVANCE, AMBIENT_TEMP, BAROMETRIC, RUN_TIME, DISTANCE_SINCE_CLEAR, DISTANCE_WITH_MIL,
    )

    fun byCode(code: Int): Pid? = ALL.firstOrNull { it.code == code }
}
