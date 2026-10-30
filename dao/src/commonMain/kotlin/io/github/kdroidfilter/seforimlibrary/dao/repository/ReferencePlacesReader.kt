package io.github.kdroidfilter.seforimlibrary.dao.repository

import io.github.kdroidfilter.seforimlibrary.core.models.ConnectionType
import io.github.kdroidfilter.seforimlibrary.core.models.Line
import io.github.kdroidfilter.seforimlibrary.core.models.LinkLoadLevel
import io.github.kdroidfilter.seforimlibrary.core.models.LinkTypeClassification
import io.github.kdroidfilter.seforimlibrary.db.SeforimDb

/** A referenced passage with the book metadata needed to group it, retaining its original selected line. */
data class LineReference(
    val connectionType: ConnectionType,
    val fromLineId: Long,
    val line: Line,
    val bookTitle: String,
    val categoryId: Long,
    val bookOrderIndex: Int,
    val isDeclaredBase: Boolean = false,
    val isBaseBook: Boolean = false,
)

internal class ReferencePlacesReader(
    private val database: SeforimDb,
    private val inversePartitions: (Collection<Long>, () -> List<LineReference>) -> List<LineReference>,
) {
    fun references(
        lineIds: Collection<Long>,
        level: LinkLoadLevel,
    ): List<LineReference> {
        if (lineIds.isEmpty()) return emptyList()
        val forwardTypes =
            setOf(ConnectionType.REFERENCE, ConnectionType.OTHER, ConnectionType.MESORAT_HASHAS, ConnectionType.EIN_MISHPAT) +
                LinkTypeClassification.forwardMentionTypes(level)
        val inverseTypes =
            setOf(ConnectionType.REFERENCE, ConnectionType.OTHER, ConnectionType.MESORAT_HASHAS) +
                LinkTypeClassification.inverseMentionTypes(level)
        val forward =
            database.referencePlacesQueries
                .selectForwardPlaces(
                    lineIds,
                    forwardTypes.map { it.name },
                    ::reference,
                ).executeAsList()
        val inverse =
            inversePartitions(lineIds) {
                database.referencePlacesQueries.selectInversePlaces(lineIds, inverseTypes.map { it.name }, ::reference).executeAsList()
            }
        val selected = lineIds.toSet()
        return (forward + inverse).filter { it.line.id !in selected }.distinctBy { it.connectionType to it.line.id }
    }

    fun sources(
        lineIds: Collection<Long>,
        level: LinkLoadLevel,
    ): List<LineReference> {
        if (lineIds.isEmpty()) return emptyList()
        val inverse =
            inversePartitions(lineIds) {
                database.referencePlacesQueries
                    .selectInversePlaces(
                        lineIds,
                        LinkTypeClassification.inverseSourceTypes(level).map { it.name },
                        ::reference,
                    ).executeAsList()
            }
        val forward =
            database.referencePlacesQueries
                .selectForwardPlaces(
                    lineIds,
                    LinkTypeClassification.forwardSourceTypes(level).map { it.name },
                    ::reference,
                ).executeAsList()
        return (inverse + forward)
            .sortedWith(
                compareByDescending<LineReference> { it.isDeclaredBase }
                    .thenByDescending { it.isBaseBook }
                    .thenBy { it.bookOrderIndex }
                    .thenBy { it.line.lineIndex },
            ).distinctBy { it.line.id }
            .map { it.copy(connectionType = ConnectionType.SOURCE) }
    }

    private fun reference(
        connectionType: String,
        fromLineId: Long,
        lineId: Long,
        bookId: Long,
        lineIndex: Long,
        heRef: String?,
        content: String,
        bookTitle: String,
        categoryId: Long,
        bookOrderIndex: Long,
        isDeclaredBase: Long,
        isBaseBook: Long,
    ): LineReference =
        LineReference(
            ConnectionType.fromString(connectionType),
            fromLineId,
            Line(id = lineId, bookId = bookId, lineIndex = lineIndex.toInt(), content = content, heRef = heRef),
            bookTitle,
            categoryId,
            bookOrderIndex.toInt(),
            isDeclaredBase != 0L,
            isBaseBook != 0L,
        )
}
