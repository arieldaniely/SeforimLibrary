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
}
