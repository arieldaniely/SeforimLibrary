package io.github.kdroidfilter.seforimlibrary.core.models

import kotlinx.serialization.Serializable

/**
 * Link between two texts (commentary, reference, etc.)
 *
 * Storage convention: links are persisted in a single canonical direction
 * `source → target` (base book → dependant book for dependant relations, and
 * citing text → cited text for citation relations). The virtual `SOURCE` and
 * `MENTION` views combine the appropriate direction for both families at read
 * time. Neither virtual type ever appears as a stored row.
 *
 * @property id The unique identifier of the link
 * @property sourceBookId The identifier of the source book
 * @property targetBookId The identifier of the target book
 * @property sourceLineId The identifier of the source line
 * @property targetLineId The identifier of the target line
 * @property targetLineIndex The 0-based index of the target line within its book.
 *           Denormalized from `line.lineIndex` so that commentaries can be ordered
 *           by their natural position in the target book without an extra JOIN.
 * @property connectionType The type of connection between the texts
 */
@Serializable
data class Link(
    val id: Long = 0,
    val sourceBookId: Long,
    val targetBookId: Long,
    val sourceLineId: Long,
    val targetLineId: Long,
    val targetLineIndex: Int,
    val connectionType: ConnectionType,
    /**
     * `true` when the orientation of this link was determined by an explicit
     * Sefaria-declared `base_text_titles` match (i.e. the target book's schema
     * declares the source book as its base text). `false` for orientations
     * inferred via density chaining, isBaseBook/priorityRank fallback, or
     * unoriented types.
     *
     * Used by the SOURCE virtual view's ORDER BY to surface Sefaria-declared
     * bases above lateral citations in books that cite Tanakh extensively
     * while having a smaller declared base (e.g. Nachalat Avot on Pirkei Avot
     * where Mishnah Avot has 93 links but Tehillim citations dominate at 371).
     */
    val isDeclaredBase: Boolean = false,
)

/**
 * Types of connections between texts.
 *
 * [SOURCE] and [MENTION] are virtual repository views; all other values are persisted types.
 * Sources combine inverse dependant links with forward citations. Mentions combine
 * forward dependant links with inverse citations.
 */
@Serializable
enum class ConnectionType {
    COMMENTARY,
    SUPER_COMMENTARY,
    TARGUM,
    REFERENCE,

    /** Virtual: never stored. Bases and forward REFERENCE/OTHER citations used by this text. */
    SOURCE,

    /** Virtual: never stored. Inbound REFERENCE/OTHER links where another text mentions this one. */
    MENTION,

    MIDRASH,
    QUOTATION,
    MESORAT_HASHAS,
    EIN_MISHPAT,
    DIBUR_HAMATCHIL,
    PARSHANUT,
    MISHNAH_IN_TALMUD,
    RELATED,
    ELUCIDATION,
    EXPLICATION,
    LINKER,
    ALLUSION,
    LITURGY,
    LAW,
    SUMMARY,
    SIFREI_MITZVOT,
    ESSAY,
    OTHER,
    ;

    companion object {
        /**
         * Creates a ConnectionType from a string value.
         *
         * Accepts Sefaria's `Conection Type` (sic) CSV values verbatim — case,
         * whitespace and underscore/space variations are normalized. Unknown
         * values fall back to [OTHER].
         */
        fun fromString(value: String): ConnectionType {
            val v = value.trim().lowercase().replace(' ', '_')
            return when (v) {
                "commentary" -> COMMENTARY
                "super_commentary", "supercommentary" -> SUPER_COMMENTARY
                "targum" -> TARGUM
                "reference" -> REFERENCE
                "source" -> SOURCE
                "mention" -> MENTION
                "midrash" -> MIDRASH
                "quotation", "quotation_auto", "quotation_auto_tanakh" -> QUOTATION
                "mesorat_hashas" -> MESORAT_HASHAS
                "ein_mishpat", "ein_mishpat_/_ner_mitsvah", "ein_mishpat_/_ner_mitzvah" -> EIN_MISHPAT
                "dibur_hamatchil" -> DIBUR_HAMATCHIL
                "parshanut" -> PARSHANUT
                "mishnah_in_talmud" -> MISHNAH_IN_TALMUD
                "related", "related_passage" -> RELATED
                "elucidation" -> ELUCIDATION
                "explication" -> EXPLICATION
                "linker" -> LINKER
                "allusion" -> ALLUSION
                "liturgy" -> LITURGY
                "law" -> LAW
                "summary" -> SUMMARY
                "sifrei_mitzvot", "sifrei_mitsvot" -> SIFREI_MITZVOT
                "essay" -> ESSAY
                "", "none" -> OTHER
                else -> OTHER
            }
        }
    }
}

/** Controls how many optional link relations are exposed by the reader. */
enum class LinkLoadLevel(val value: Int) {
    MINIMAL(1),
    FOCUSED(2),
    EXTENDED(3),
    MAXIMAL(4),
    ;

    companion object {
        fun fromValue(value: Int): LinkLoadLevel = entries.firstOrNull { it.value == value } ?: FOCUSED
    }
}

/**
 * Single source of truth for assigning persisted link types to reader panes.
 *
 * Persisted dependant relations use `base -> dependant`. Citation-like relations use
 * `citing text -> cited text`. Sources are therefore inverse dependant links plus
 * forward citation links, while mentions are forward dependant links plus inverse
 * citation links. Dedicated commentary/targum relations are excluded from mentions.
 */
object LinkTypeClassification {
    val virtualTypes: Set<ConnectionType> = setOf(ConnectionType.SOURCE, ConnectionType.MENTION)

    val legacyDependantTypes: Set<ConnectionType> =
        setOf(
            ConnectionType.COMMENTARY,
            ConnectionType.SUPER_COMMENTARY,
            ConnectionType.TARGUM,
            ConnectionType.MIDRASH,
            ConnectionType.PARSHANUT,
            ConnectionType.DIBUR_HAMATCHIL,
            ConnectionType.EIN_MISHPAT,
        )

    private val extendedDependantTypes: Set<ConnectionType> =
        legacyDependantTypes + ConnectionType.ELUCIDATION + ConnectionType.EXPLICATION

    private val citationMinimumLevel: Map<ConnectionType, LinkLoadLevel> =
        mapOf(
            ConnectionType.REFERENCE to LinkLoadLevel.FOCUSED,
            ConnectionType.QUOTATION to LinkLoadLevel.FOCUSED,
            ConnectionType.MESORAT_HASHAS to LinkLoadLevel.FOCUSED,
            ConnectionType.MISHNAH_IN_TALMUD to LinkLoadLevel.FOCUSED,
            ConnectionType.RELATED to LinkLoadLevel.EXTENDED,
            ConnectionType.ALLUSION to LinkLoadLevel.EXTENDED,
            ConnectionType.LAW to LinkLoadLevel.EXTENDED,
            ConnectionType.SIFREI_MITZVOT to LinkLoadLevel.EXTENDED,
            ConnectionType.LITURGY to LinkLoadLevel.EXTENDED,
            ConnectionType.SUMMARY to LinkLoadLevel.EXTENDED,
            ConnectionType.ESSAY to LinkLoadLevel.EXTENDED,
            ConnectionType.LINKER to LinkLoadLevel.MAXIMAL,
            ConnectionType.OTHER to LinkLoadLevel.MAXIMAL,
        )

    private val dependantMentionMinimumLevel: Map<ConnectionType, LinkLoadLevel> =
        mapOf(
            ConnectionType.EIN_MISHPAT to LinkLoadLevel.FOCUSED,
            ConnectionType.MIDRASH to LinkLoadLevel.EXTENDED,
            ConnectionType.PARSHANUT to LinkLoadLevel.EXTENDED,
            ConnectionType.DIBUR_HAMATCHIL to LinkLoadLevel.EXTENDED,
        )

    fun commentaryTypes(level: LinkLoadLevel): Set<ConnectionType> =
        if (level == LinkLoadLevel.MAXIMAL) {
            setOf(
                ConnectionType.COMMENTARY,
                ConnectionType.SUPER_COMMENTARY,
                ConnectionType.ELUCIDATION,
                ConnectionType.EXPLICATION,
            )
        } else {
            setOf(ConnectionType.COMMENTARY)
        }

    val targumTypes: Set<ConnectionType> = setOf(ConnectionType.TARGUM)

    fun inverseSourceTypes(level: LinkLoadLevel): Set<ConnectionType> =
        if (level == LinkLoadLevel.MAXIMAL) extendedDependantTypes else legacyDependantTypes

    fun forwardSourceTypes(level: LinkLoadLevel): Set<ConnectionType> =
        citationMinimumLevel.filterValues { it.value <= level.value }.keys

    fun inverseMentionTypes(level: LinkLoadLevel): Set<ConnectionType> = forwardSourceTypes(level)

    fun forwardMentionTypes(level: LinkLoadLevel): Set<ConnectionType> {
        val enabled = dependantMentionMinimumLevel.filterValues { it.value <= level.value }.keys.toMutableSet()
        if (level == LinkLoadLevel.MAXIMAL) {
            enabled += extendedDependantTypes
            enabled -= commentaryTypes(level)
            enabled -= targumTypes
        }
        return enabled
    }
}
