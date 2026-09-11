package io.github.kdroidfilter.seforimlibrary.core.models

import kotlinx.serialization.Serializable

/**
 * Represents a book author in the library
 *
 * @property id The unique identifier of the author
 * @property name The name of the author
 */
@Serializable
data class Author(
    val id: Long = 0,
    val name: String,
    val sefariaSlug: String? = null,
    val heBio: String? = null,
    val birthYear: Int? = null,
    val birthYearIsApprox: Boolean = false,
    val deathYear: Int? = null,
    val deathYearIsApprox: Boolean = false,
    val era: String? = null,
    val eraName: String? = null,
    val birthPlace: String? = null,
    val deathPlace: String? = null,
    val heWikiLink: String? = null,
    val heNliLink: String? = null,
)

/** A Hebrew alternative name by which an author is known in Sefaria. */
@Serializable
data class AuthorAlias(
    val authorId: Long,
    val name: String,
    val isPrimary: Boolean = false,
)

/** A relationship exposed by Sefaria's author Topic record. */
@Serializable
data class AuthorRelation(
    val authorId: Long,
    val targetSlug: String,
    val targetName: String? = null,
    val relationType: String,
    val relationTypeHe: String? = null,
    val isInverse: Boolean = false,
)

@Serializable
data class AuthorDetails(
    val author: Author,
    val aliases: List<AuthorAlias> = emptyList(),
    val relations: List<AuthorRelation> = emptyList(),
)
