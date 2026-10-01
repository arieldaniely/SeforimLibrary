package io.github.kdroidfilter.seforimlibrary.search

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.math.sqrt

class Int8VectorsTest {
    @Test
    fun symmetricQuantizationMatchesPythonRoundingAndPreservesScale() {
        val source = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)
        val expected = byteArrayOf(-127, -63, 0, 64, 127)
        assertContentEquals(expected, Int8Vectors.quantize(source))
        assertContentEquals(expected, Int8Vectors.quantize(source.map { it * 0.25f }.toFloatArray()))
    }

    @Test
    fun rejectsInvalidCosineVectors() {
        for (source in listOf(floatArrayOf(), floatArrayOf(0f), floatArrayOf(Float.NaN),
            floatArrayOf(Float.POSITIVE_INFINITY))) {
            assertFailsWith<IllegalArgumentException> { Int8Vectors.quantize(source) }
        }
    }

    @Test
    fun keepsRepresentativeVectorDirection() {
        val source = FloatArray(256) { kotlin.math.sin(it.toDouble()).toFloat() }
        val quantized = Int8Vectors.quantize(source)
        val dot = source.indices.sumOf { source[it].toDouble() * quantized[it] }
        val norm = sqrt(source.sumOf { it.toDouble() * it } * quantized.sumOf { it.toDouble() * it })
        assertTrue(dot / norm > 0.9999)
    }
}
