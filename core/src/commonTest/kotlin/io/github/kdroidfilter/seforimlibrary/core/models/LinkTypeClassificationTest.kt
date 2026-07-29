package io.github.kdroidfilter.seforimlibrary.core.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkTypeClassificationTest {
    @Test
    fun `minimal level matches the historical reader`() {
        assertEquals(
            setOf(
                ConnectionType.COMMENTARY,
                ConnectionType.SUPER_COMMENTARY,
                ConnectionType.TARGUM,
                ConnectionType.MIDRASH,
                ConnectionType.PARSHANUT,
                ConnectionType.DIBUR_HAMATCHIL,
                ConnectionType.EIN_MISHPAT,
            ),
            LinkTypeClassification.inverseSourceTypes(LinkLoadLevel.MINIMAL),
        )
        assertTrue(LinkTypeClassification.forwardSourceTypes(LinkLoadLevel.MINIMAL).isEmpty())
        assertTrue(LinkTypeClassification.inverseMentionTypes(LinkLoadLevel.MINIMAL).isEmpty())
        assertTrue(LinkTypeClassification.forwardMentionTypes(LinkLoadLevel.MINIMAL).isEmpty())
        assertEquals(
            setOf(ConnectionType.COMMENTARY),
            LinkTypeClassification.commentaryTypes(LinkLoadLevel.MINIMAL),
        )
        assertEquals(setOf(ConnectionType.TARGUM), LinkTypeClassification.targumTypes)
    }

    @Test
    fun `focused and extended levels add study links progressively`() {
        val focusedSources = LinkTypeClassification.forwardSourceTypes(LinkLoadLevel.FOCUSED)
        assertTrue(ConnectionType.REFERENCE in focusedSources)
        assertTrue(ConnectionType.QUOTATION in focusedSources)
        assertTrue(ConnectionType.MESORAT_HASHAS in focusedSources)
        assertTrue(ConnectionType.MISHNAH_IN_TALMUD in focusedSources)
        assertFalse(ConnectionType.RELATED in focusedSources)
        assertEquals(
            setOf(ConnectionType.EIN_MISHPAT),
            LinkTypeClassification.forwardMentionTypes(LinkLoadLevel.FOCUSED),
        )

        val extendedSources = LinkTypeClassification.forwardSourceTypes(LinkLoadLevel.EXTENDED)
        assertTrue(extendedSources.containsAll(focusedSources))
        assertTrue(ConnectionType.RELATED in extendedSources)
        assertTrue(ConnectionType.ALLUSION in extendedSources)
        assertTrue(ConnectionType.LAW in extendedSources)
        assertFalse(ConnectionType.OTHER in extendedSources)
        assertTrue(
            LinkTypeClassification.forwardMentionTypes(LinkLoadLevel.EXTENDED).containsAll(
                setOf(
                    ConnectionType.EIN_MISHPAT,
                    ConnectionType.MIDRASH,
                    ConnectionType.PARSHANUT,
                    ConnectionType.DIBUR_HAMATCHIL,
                ),
            ),
        )
    }

    @Test
    fun `maximal level exposes every persisted type without duplicating dedicated panes`() {
        val displayed =
            LinkTypeClassification.commentaryTypes(LinkLoadLevel.MAXIMAL) +
                LinkTypeClassification.targumTypes +
                LinkTypeClassification.inverseSourceTypes(LinkLoadLevel.MAXIMAL) +
                LinkTypeClassification.forwardSourceTypes(LinkLoadLevel.MAXIMAL) +
                LinkTypeClassification.inverseMentionTypes(LinkLoadLevel.MAXIMAL) +
                LinkTypeClassification.forwardMentionTypes(LinkLoadLevel.MAXIMAL)
        val persisted = ConnectionType.entries.toSet() - LinkTypeClassification.virtualTypes

        assertEquals(persisted, displayed)
        assertEquals(
            setOf(
                ConnectionType.COMMENTARY,
                ConnectionType.SUPER_COMMENTARY,
                ConnectionType.ELUCIDATION,
                ConnectionType.EXPLICATION,
            ),
            LinkTypeClassification.commentaryTypes(LinkLoadLevel.MAXIMAL),
        )
        assertTrue(
            LinkTypeClassification.forwardMentionTypes(LinkLoadLevel.MAXIMAL)
                .intersect(LinkTypeClassification.commentaryTypes(LinkLoadLevel.MAXIMAL))
                .isEmpty(),
        )
        assertFalse(ConnectionType.TARGUM in LinkTypeClassification.forwardMentionTypes(LinkLoadLevel.MAXIMAL))
    }

    @Test
    fun `raw aliases map to their stored types`() {
        assertEquals(ConnectionType.QUOTATION, ConnectionType.fromString("quotation auto tanakh"))
        assertEquals(ConnectionType.EIN_MISHPAT, ConnectionType.fromString("ein mishpat / ner mitsvah"))
        assertEquals(ConnectionType.RELATED, ConnectionType.fromString("related passage"))
        assertEquals(ConnectionType.SIFREI_MITZVOT, ConnectionType.fromString("sifrei mitsvot"))
        assertEquals(ConnectionType.OTHER, ConnectionType.fromString("footnotes"))
    }
}
