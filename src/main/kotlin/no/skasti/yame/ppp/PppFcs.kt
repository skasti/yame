package no.skasti.yame.ppp

object PppFcs {
    private const val INITIAL = 0xffff
    private const val GOOD = 0xf0b8

    fun calculate(bytes: ByteArray): Int =
        bytes.fold(INITIAL) { current, byte ->
            var fcs = current xor (byte.toInt() and 0xff)
            repeat(8) {
                fcs = if ((fcs and 1) != 0) {
                    (fcs ushr 1) xor 0x8408
                } else {
                    fcs ushr 1
                }
            }
            fcs
        } and 0xffff

    fun isValid(frameIncludingFcs: ByteArray): Boolean =
        calculate(frameIncludingFcs) == GOOD
}
