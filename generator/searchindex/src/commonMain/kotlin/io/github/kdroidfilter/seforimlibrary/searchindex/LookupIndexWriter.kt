package io.github.kdroidfilter.seforimlibrary.searchindex

interface LookupIndexWriter : AutoCloseable {
    /** Removes the book and TOC lookup documents belonging to [bookId]. */
    fun deleteBookById(bookId: Long) { /* default no-op */ }

    fun addBook(
        bookId: Long,
        categoryId: Long,
        displayTitle: String,
        terms: Collection<String>,
        isBaseBook: Boolean = false,
        orderIndex: Int? = null
    )

    fun addToc(
        tocId: Long,
        bookId: Long,
        categoryId: Long,
        bookTitle: String,
        text: String,
        level: Int
    )

    fun commit()
}
