Fixture library for haselnuss.library.generated-test (TASK-158): a
filesystem library mixing a generated shelf with an ordinary book.

`scripture/` holds `shelf.clj`, so it is a generated shelf: its books
(`joao`, `romanos`, `hebreus`) come from the script, which the host feeds
`biblia.json` and `books.edn` -- a few Almeida verses, also copied by hand
into examples/catecismo-heidelberg.hdoc. Nothing else in `scripture/` is
scanned for books. `commentary/salmos-notas.hdoc` is an ordinary
single-file book. The script runs only when the library's entry says
`scripts: true`.
