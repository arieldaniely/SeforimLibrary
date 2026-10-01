package io.github.kdroidfilter.seforimlibrary.search

import kotlin.math.abs
import kotlin.math.floor

/** Symmetric per-vector quantization; cosine similarity does not need the discarded scale. */
object Int8Vectors {
    const val ENCODING = "int8-maxabs-v1"
    const val DIMENSION = 256
    const val FILE_MAGIC = "ZYVECI8\n"

    fun quantize(vector: FloatArray): ByteArray {
        require(vector.isNotEmpty() && vector.all { it.isFinite() }) { "Invalid semantic vector" }
        val maximum = vector.maxOf { abs(it) }
        require(maximum > 0f) { "Zero semantic vector" }
        return ByteArray(vector.size) { index ->
            floor((vector[index] / maximum * 127f + 0.5f).toDouble())
                .toInt().coerceIn(-127, 127).toByte()
        }
    }
}
