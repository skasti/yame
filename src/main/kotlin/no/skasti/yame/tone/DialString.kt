package no.skasti.yame.tone

/**
 * Normalizes a user- or modem-supplied dial string into the DTMF digits that
 * would actually be sent to the simulated Norwegian PSTN line.
 *
 * A leading '+' is converted to Norway's international access prefix '00'.
 * Common presentation characters such as spaces, dashes and parentheses are
 * ignored. A leading Hayes tone/pulse selector (T/P) is also removed.
 */
internal object DialString {
    private const val DTMF_CHARACTERS = "0123456789*#ABCD"

    fun normalize(value: String): String {
        var dialString = value.trim()

        if (dialString.startsWith("T", ignoreCase = true) ||
            dialString.startsWith("P", ignoreCase = true)
        ) {
            dialString = dialString.drop(1).trimStart()
        }

        if (dialString.startsWith("+")) {
            dialString = "00${dialString.drop(1)}"
        }

        return buildString {
            dialString.forEach { character ->
                val normalized = character.uppercaseChar()
                if (normalized in DTMF_CHARACTERS) {
                    append(normalized)
                }
            }
        }
    }
}
