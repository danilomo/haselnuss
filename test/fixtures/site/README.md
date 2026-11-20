The site fixture set (TASK-264; doc-12, "Left to the builder"): two libraries
named by `libraries.yaml` in this folder, small enough to build in a test, and
covering what the library server has to handle. `examples/` is not used for
this, so it can stay as it is.

| Book | What it is for |
| --- | --- |
| `bible:blivre/*` | A generated shelf: `bible/blivre/shelf.clj` turns its shared `:data` file `blivre.json` into one book per entry (`genesis`, `mateus`, `joao`, `romanos`, `1-corintios`). Runs only because the entry says `scripts: true`. |
| `reformed:catecismo-heidelberg` | A copy of the first Lord's Day of the Heidelberg catechism that both `!quote`s Bible passages and `@{...}`-links them: references across libraries, into generated books, with locators. |
| `reformed:estudos/queda` | A book on a shelf below the library root. It only links to Genesis, which nothing quotes, so its edges there are link-only. One of those links is a single passage of about 3,000 characters (`genesis#3:1-24`). It also `!quote`s the catechism's question 1, which brings that answer's own Bible quotes with it, so it is a quote of a quote. |
| `reformed:catkins` | A folder book (`catkins/book.hdoc`) with an image beside it (`catkins.png`), and inline and display maths. |
| `reformed:dangling` | A book with one warning: a link to a Bible book the library does not have (`unknown-book`), a dangling reference. |
| `reformed:notas` | Two books sharing one canonical id, `notas.hdoc` beside `notas/book.hdoc` (`duplicate-slug`). |

`haselnuss.site-fixtures-test` runs `--check` over this folder and asserts
that the dangling reference and the duplicate id are the only problems it
reports. Adding a book here changes that test's book count; adding a problem
means adding it there.

The Bible text is a subset of the Bíblia Livre (BLIVRE), licence CC BY 3.0
Brasil; see `bible/README.md` for the credit and what was changed.
