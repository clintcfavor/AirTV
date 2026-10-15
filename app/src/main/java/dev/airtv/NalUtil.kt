package dev.airtv

/** Helpers for Annex-B (start-code delimited) H.264 / H.265 streams. */
object NalUtil {
    private class Nal(val start: Int, val payload: Int, val end: Int)

    private fun split(data: ByteArray): List<Nal> {
        val starts = mutableListOf<Pair<Int, Int>>() // (start code offset, payload offset)
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) {
                    starts += i to i + 3
                    i += 3
                    continue
                }
                if (i + 4 <= data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    starts += i to i + 4
                    i += 4
                    continue
                }
            }
            i++
        }
        return starts.mapIndexed { n, (start, payload) ->
            Nal(start, payload, if (n + 1 < starts.size) starts[n + 1].first else data.size)
        }
    }

    private fun type(data: ByteArray, nal: Nal, isH265: Boolean): Int {
        if (nal.payload >= data.size) return -1
        val header = data[nal.payload].toInt() and 0xff
        return if (isH265) (header shr 1) and 0x3f else header and 0x1f
    }

    private fun isParameterSet(type: Int, isH265: Boolean) =
        if (isH265) type in 32..34 else type == 7 || type == 8

    /** The leading parameter-set NAL units of an access unit, or null if it has none. */
    fun parameterSets(data: ByteArray, isH265: Boolean): ByteArray? {
        val nals = split(data)
        val config = nals.takeWhile { isParameterSet(type(data, it, isH265), isH265) }
        if (config.isEmpty()) return null
        return data.copyOfRange(config.first().start, config.last().end)
    }

    /** Splits an H.264 config blob into (SPS, PPS) for csd-0 / csd-1. */
    fun splitAvcConfig(config: ByteArray): Pair<ByteArray, ByteArray> {
        val nals = split(config)
        val sps = nals.filter { type(config, it, false) == 7 }
        val pps = nals.filter { type(config, it, false) == 8 }
        fun join(list: List<Nal>) = list.fold(ByteArray(0)) { acc, nal -> acc + config.copyOfRange(nal.start, nal.end) }
        return join(sps) to join(pps)
    }
}
