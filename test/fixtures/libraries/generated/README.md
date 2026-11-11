Fixture library for haselnuss.library.generated-test (TASK-158): a
filesystem library mixing a generated shelf with an ordinary book.

`scripture/` holds `shelf.clj`, so it is a generated shelf: its books
(`joao`, `romanos`, `hebreus`) come from the script, which the host feeds
`biblia.json` and `books.edn` -- a few verses of the Bíblia Livre (BLIVRE),
copied from `examples/libraries/bible/blivre/blivre.json`. Nothing else in
`scripture/` is scanned for books. `commentary/salmos-notas.hdoc` is an
ordinary single-file book. The script runs only when the library's entry
says `scripts: true`.

The verse text is the Bíblia Livre (BLIVRE), Copyright (c) Diego Santos,
Mario Sérgio e Marco Teles, https://sites.google.com/site/biblialivre/,
licensed CC BY 3.0 Brasil (https://creativecommons.org/licenses/by/3.0/br/).
Edition, licence evidence and the changes made to the text are in
`examples/libraries/bible/README.md`.
