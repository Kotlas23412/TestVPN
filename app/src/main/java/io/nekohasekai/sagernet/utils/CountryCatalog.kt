package io.nekohasekai.sagernet.utils

import java.util.Locale

data class CountryInfo(
    val iso2: String,
    val countryNameRu: String,
    val flag: String
)

object CountryCatalog {

    private val blockedCodes = setOf("UN", "XX", "ZZ")

    private fun flagFromCode(code: String): String {
        val upper = code.uppercase(Locale.ROOT)
        if (upper.length != 2) return ""
        return try {
            val first = Character.toChars(0x1F1E6 + (upper[0] - 'A'))
            val second = Character.toChars(0x1F1E6 + (upper[1] - 'A'))
            String(first) + String(second)
        } catch (_: Exception) {
            ""
        }
    }

    val countriesByCode: Map<String, CountryInfo> by lazy {
        buildMap {
            for (code in Locale.getISOCountries()) {
                val upper = code.uppercase(Locale.ROOT)
                if (upper in blockedCodes) continue

                val ruName = Locale("ru", upper).displayCountry
                val enName = Locale("en", upper).displayCountry
                val normalizedName = when {
                    ruName.isNotBlank() && !ruName.equals("Unknown", ignoreCase = true) -> ruName
                    enName.isNotBlank() && !enName.equals("Unknown", ignoreCase = true) -> enName
                    else -> upper
                }

                put(
                    upper,
                    CountryInfo(
                        iso2 = upper,
                        countryNameRu = normalizedName,
                        flag = flagFromCode(upper)
                    )
                )
            }
        }
    }

    private val aliasesToCode: Map<String, String> by lazy {
        val aliases = linkedMapOf<String, String>()

        fun addAlias(alias: String, code: String) {
            val normalizedAlias = alias.uppercase(Locale.ROOT).trim()
            if (normalizedAlias.isNotBlank()) aliases[normalizedAlias] = code
        }

        for ((code, country) in countriesByCode) {
            addAlias(code, code)
            addAlias(Locale("en", code).displayCountry, code)
            addAlias(Locale("ru", code).displayCountry, code)
            val iso3 = runCatching { Locale("en", code).isO3Country }.getOrNull()
            if (!iso3.isNullOrBlank()) addAlias(iso3, code)

            // Часто встречающийся формат в именах прокси.
            addAlias(country.countryNameRu.replace("-", " "), code)
            addAlias(country.countryNameRu.replace("-", ""), code)
        }

        // Распространенные алиасы и разговорные варианты.
        fun addAliases(code: String, vararg values: String) = values.forEach { addAlias(it, code) }

        addAliases("RU", "РФ", "RF", "RUS", "РОССИЯ", "RUSS")
        addAliases("US", "USA", "U.S.A", "UNITEDSTATES", "AMERICA", "СОЕДИНЕННЫЕ ШТАТЫ", "США")
        addAliases("GB", "UK", "U.K", "BRITAIN", "GREAT BRITAIN", "UNITED KINGDOM", "АНГЛИЯ", "ВЕЛИКОБРИТАНИЯ")
        addAliases("AE", "UAE", "U.A.E", "EMIRATES", "ОАЭ", "ЭМИРАТЫ")
        addAliases("TR", "TÜRKIYE", "TURKIYE", "ТУРЦИЯ")
        addAliases("DE", "DEUTSCHLAND", "ГЕРМАНИЯ", "GER")
        addAliases("FR", "ФРАНЦИЯ", "FRA")
        addAliases("IT", "ИТАЛИЯ", "ITA")
        addAliases("ES", "ИСПАНИЯ", "ESP")
        addAliases("PT", "ПОРТУГАЛИЯ", "PRT")
        addAliases("NL", "HOLLAND", "NEDERLAND", "НИДЕРЛАНДЫ")
        addAliases("BE", "БЕЛЬГИЯ", "BEL")
        addAliases("CH", "SWISS", "SCHWEIZ", "SUISSE", "SCHWEIZER", "ШВЕЙЦАРИЯ", "SVIZZERA")
        addAliases("AT", "ÖSTERREICH", "AUSTRIA", "АВСТРИЯ")
        addAliases("SE", "SVERIGE", "ШВЕЦИЯ")
        addAliases("NO", "NORGE", "НОРВЕГИЯ")
        addAliases("DK", "DANMARK", "ДАНИЯ")
        addAliases("FI", "SUOMI", "ФИНЛЯНДИЯ")
        addAliases("PL", "POLSKA", "ПОЛЬША")
        addAliases("CZ", "ČESKO", "ЧЕХИЯ")
        addAliases("SK", "SLOVAKIA", "СЛОВАКИЯ")
        addAliases("SI", "SLOVENIA", "СЛОВЕНИЯ")
        addAliases("HR", "CROATIA", "ХОРВАТИЯ")
        addAliases("RO", "ROMANIA", "РУМЫНИЯ")
        addAliases("BG", "BULGARIA", "БОЛГАРИЯ")
        addAliases("HU", "HUNGARY", "ВЕНГРИЯ")
        addAliases("GR", "GREECE", "ГРЕЦИЯ")
        addAliases("IE", "IRELAND", "ИРЛАНДИЯ")
        addAliases("IS", "ICELAND", "ИСЛАНДИЯ")
        addAliases("CA", "CANADA", "КАНАДА")
        addAliases("AU", "AUSTRALIA", "АВСТРАЛИЯ")
        addAliases("NZ", "NEW ZEALAND", "НОВАЯ ЗЕЛАНДИЯ")
        addAliases("JP", "JAPAN", "ЯПОНИЯ")
        addAliases("KR", "KOREA", "SOUTH KOREA", "KOREA SOUTH", "КОРЕЯ", "ЮЖНАЯ КОРЕЯ")
        addAliases("KP", "NORTH KOREA", "КНДР", "СЕВЕРНАЯ КОРЕЯ")
        addAliases("CN", "PRC", "CHINA", "КИТАЙ")
        addAliases("HK", "HONG KONG", "ГОНКОНГ", "HK SAR")
        addAliases("TW", "TAIWAN", "ТАЙВАНЬ")
        addAliases("SG", "SINGAPORE", "СИНГАПУР")
        addAliases("MY", "MALAYSIA", "МАЛАЙЗИЯ")
        addAliases("TH", "THAILAND", "ТАИЛАНД")
        addAliases("VN", "VIETNAM", "ВЬЕТНАМ")
        addAliases("ID", "INDONESIA", "ИНДОНЕЗИЯ")
        addAliases("PH", "PHILIPPINES", "ФИЛИППИНЫ")
        addAliases("IN", "INDIA", "ИНДИЯ")
        addAliases("PK", "PAKISTAN", "ПАКИСТАН")
        addAliases("KZ", "KAZAKHSTAN", "КАЗАХСТАН")
        addAliases("UZ", "UZBEKISTAN", "УЗБЕКИСТАН")
        addAliases("IL", "ISRAEL", "ИЗРАИЛЬ")
        addAliases("SA", "SAUDI", "SAUDI ARABIA", "САУДОВСКАЯ АРАВИЯ")
        addAliases("QA", "QATAR", "КАТАР")
        addAliases("EG", "EGYPT", "ЕГИПЕТ")
        addAliases("ZA", "SOUTH AFRICA", "ЮАР")
        addAliases("NG", "NIGERIA", "НИГЕРИЯ")
        addAliases("BR", "BRAZIL", "БРАЗИЛИЯ")
        addAliases("AR", "ARGENTINA", "АРГЕНТИНА")
        addAliases("CL", "CHILE", "ЧИЛИ")
        addAliases("CO", "COLOMBIA", "КОЛУМБИЯ")
        addAliases("MX", "MEXICO", "МЕКСИКА")

        aliases
    }

    fun fromCode(code: String?): CountryInfo? {
        if (code.isNullOrBlank()) return null
        return countriesByCode[code.uppercase(Locale.ROOT)]
    }

    fun resolveFromText(text: String?): CountryInfo? {
        if (text.isNullOrBlank()) return null
        val upperText = text.uppercase(Locale.ROOT)

        // 1) Пробуем вытащить по эмодзи-флагу
        val flagRegex = Regex("[\uD83C][\uDDE6-\uDDFF][\uD83C][\uDDE6-\uDDFF]")
        val flagMatch = flagRegex.find(text)
        if (flagMatch != null) {
            runCatching {
                val flag = flagMatch.value
                val c1 = flag.codePointAt(0) - 0x1F1E6 + 'A'.code
                val c2 = flag.codePointAt(2) - 0x1F1E6 + 'A'.code
                val code = "${c1.toChar()}${c2.toChar()}"
                fromCode(code)
            }.getOrNull()?.let { return it }
        }

        // 2) По алиасам (названия стран, ISO2/ISO3, разговорные варианты)
        for ((alias, code) in aliasesToCode) {
            if (Regex("(^|[^\\p{L}\\p{N}])${Regex.escape(alias)}([^\\p{L}\\p{N}]|$)").containsMatchIn(upperText)) {
                fromCode(code)?.let { return it }
            }
        }

        // 3) Мягкая проверка полного вхождения (например, SWITZERLAND[*CIDR])
        for ((alias, code) in aliasesToCode) {
            if (alias.length >= 4 && upperText.contains(alias)) {
                fromCode(code)?.let { return it }
            }
        }

        return null
    }
}
