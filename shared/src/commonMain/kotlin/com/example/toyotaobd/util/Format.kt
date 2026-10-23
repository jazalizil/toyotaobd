package com.example.toyotaobd.util

/**
 * Format à la printf pour les gabarits de l'application (%d, %X, %x, %c, %s, %f, avec largeur,
 * drapeaux « 0 » et « , ») : String.format de Java n'existe pas en Kotlin Multiplatform.
 * Les nombres sont formatés indépendamment de la langue (séparateur décimal point, milliers
 * séparés par une espace fine) ; les gabarits utilisés n'ont pas d'argument indexé.
 */
fun String.fmt(vararg args: Any?): String {
    val out = StringBuilder()
    var index = 0
    var argIndex = 0
    while (index < length) {
        if (this[index] != '%') {
            out.append(this[index])
            index++
            continue
        }
        if (index + 1 < length && this[index + 1] == '%') {
            out.append('%')
            index += 2
            continue
        }
        var i = index + 1
        val flags = buildString {
            while (i < length && this@fmt[i] in "0,-+ ") {
                append(this@fmt[i])
                i++
            }
        }
        var width = 0
        while (i < length && this[i].isDigit()) {
            width = width * 10 + (this[i] - '0')
            i++
        }
        var precision = -1
        if (i < length && this[i] == '.') {
            i++
            precision = 0
            while (i < length && this[i].isDigit()) {
                precision = precision * 10 + (this[i] - '0')
                i++
            }
        }
        if (i >= length) break
        val arg = args.getOrNull(argIndex++)
        out.append(
            when (val conversion = this[i]) {
                'd' -> {
                    val digits = (arg as Number).toLong().toString()
                    if (',' in flags) groupThousands(digits) else digits
                }
                'X' -> (arg as Number).toLong().toString(16).uppercase().padStart(width, '0')
                'x' -> (arg as Number).toLong().toString(16).padStart(width, '0')
                'c', 's' -> arg.toString()
                'f' -> formatDecimal((arg as Number).toDouble(), if (precision >= 0) precision else 6)
                else -> throw IllegalArgumentException("Gabarit inconnu : %$conversion")
            },
        )
        index = i + 1
    }
    return out.toString()
}

/** Milliers séparés par une espace fine, signe conservé. */
private fun groupThousands(digits: String): String {
    val negative = digits.startsWith("-")
    val body = if (negative) digits.substring(1) else digits
    val grouped = buildString {
        for ((position, digit) in body.withIndex()) {
            if (position > 0 && (body.length - position) % 3 == 0) append(' ')
            append(digit)
        }
    }
    return (if (negative) "-" else "") + grouped
}

/** [value] arrondi à [decimals] chiffres après le point (les demi vers le haut), point décimal. */
private fun formatDecimal(value: Double, decimals: Int): String {
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    val scaled = kotlin.math.round(value * factor).toLong()
    val sign = if (scaled < 0) "-" else ""
    val abs = kotlin.math.abs(scaled)
    val whole = abs / factor.toLong()
    val fraction = (abs % factor.toLong()).toString().padStart(decimals, '0')
    return if (decimals == 0) sign + whole.toString() else "$sign$whole.$fraction"
}
