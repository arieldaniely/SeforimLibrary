package io.github.kdroidfilter.seforimlibrary.bookimporter

import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ImportExecutionTest {
    @Test
    fun unwrapInvocationTargetReturnsNestedCause() {
        val root = IllegalStateException("boom")
        val wrapped = InvocationTargetException(InvocationTargetException(root))

        val actual = wrapped.unwrapInvocationTarget()

        assertSame(root, actual)
    }

    @Test
    fun describeForUiUsesFallbackWhenMessageMissing() {
        val error = IllegalArgumentException("   ")

        val description = error.describeForUi()

        assertEquals("IllegalArgumentException (no message provided)", description)
    }

    @Test
    fun describeForUiAddsActionableHintForMissingHttpClientClass() {
        val error = NoClassDefFoundError("java/net/http/HttpClient")

        val description = error.describeForUi()

        assertEquals(
            "חסר מודול java.net.http (HttpClient). כנראה שה-runtime שנארז לא כולל java.net.http. הפתרון: לכלול את המודול ב-jlink/jpackage או להשתמש ב-Java 11+ עם runtime מתאים.",
            description
        )
    }

}
