Fixture library for haselnuss.library.passage-test (TASK-153): passage
resolution against a filesystem book. `catechisms/heidelberg` is a book
folder whose sections come in through `!include` (one of which is missing,
for the `:unreadable-include` warning) and whose bare `@ursinus1563` is a
citation only when the book's own `refs.json` is its parse vocabulary.
`creeds/nicene` declares a bibliography that does not exist. This file is
not a book: only `*.hdoc` files are.

`creeds/quoting` (TASK-154, haselnuss.library.quote-test) quotes from
inside a book: one `!quote` that resolves and one, on line 9, naming a book
the library does not have, whose warning must name that file and line.
