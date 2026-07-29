package io.github.kdroidfilter.seforimlibrary.dao.repository

/**
 * Optional driver capability for attached personal libraries.
 *
 * Inverse link lookups can match rows in both the immutable main database and the
 * personal database. Executing those lookups through generic UNION views makes
 * SQLite materialize expensive multi-view JOIN plans. A capable driver runs the
 * same indexed query once per physical link partition; the repository then merges
 * the small result sets in memory.
 */
interface LinkPartitionQueryDriver {
    fun <T> queryEachLinkPartition(query: () -> T): List<T>

    /**
     * Runs an inverse-link query only against partitions that can contain one of
     * [targetLineIds]. Implementations may use an exact side index to avoid touching
     * an attached overlay when none of the requested lines are targeted there.
     */
    fun <T> queryEachLinkPartitionForTargetLines(
        targetLineIds: Collection<Long>,
        query: () -> T,
    ): List<T> = queryEachLinkPartition(query)

    /** True when an attached partition contains links targeting [bookId]. */
    fun hasAdditionalLinksTargetingBook(bookId: Long): Boolean = false
}
