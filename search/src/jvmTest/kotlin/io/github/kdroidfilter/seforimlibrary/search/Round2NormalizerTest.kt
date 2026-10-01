package io.github.kdroidfilter.seforimlibrary.search

import kotlin.test.Test
import kotlin.test.assertEquals

class Round2NormalizerTest {
    @Test
    fun matchesTrainingNormalizerExamples() {
        assertEquals("שלום עולם", Round2Normalizer.clean("<p>שָׁלוֹם&nbsp;עולם</p>"))
        assertEquals("ר' יוחנן - אמר: & שלום", Round2Normalizer.clean("ר׳ יוחנן — אמר: test &amp; שלום"))
        assertEquals("אבג שלום!!", Round2Normalizer.clean("אָבַג  שלום!!!"))
        assertEquals("מלך ם סופית", Round2Normalizer.clean("מלך ם סופית"))
    }
}
