Fixture Bible library for haselnuss.library.catechism-test (TASK-159), the
libraries milestone's acceptance test: `test/fixtures/catechism/
catecismo-domingo-1.hdoc` quotes its proof texts from here with `!quote`.

`nt/` holds `shelf.clj`, so it is a generated shelf. The script turns
`nt/almeida.json` -- a list of `{"slug", "title", "verses"}`, each verse a
`[chapter, verse, text]` triple -- into one book per entry, `bible:nt/joao`,
`bible:nt/romanos` and so on, whose locators are `C`, `C:V` or `C:V-W`
(`!quote bible:joao#8:34-36`). It runs only because the library's entry in
`test/fixtures/catechism/libraries.yaml` says `scripts: true`.

## The text: source and licence

`almeida.json` is a sample, not a Bible: the 36 verses (13 New Testament
books) that the first Lord's Day of the catechism cites, about 5 KB.

The verses were taken verbatim from `examples/catecismo-heidelberg.hdoc`,
which copied them by hand from bible-api.com's `almeida` translation.

**Their licence is unverified. Do not treat this text as public domain.**
bible-api.com served that translation from seven1m/open-bibles, and on
2026-11-06 open-bibles removed it (PR #109, issue #82) because nobody had
established its exact revision or the permission to publish it.
bible-api.com no longer serves it. The research in that issue points to a
copyrighted 20th-century revision circulated as "Almeida Atualizada" (the
spelling `iniqüidade` alone shows it is not Almeida's own text or the 1911
edition). It is also not the Almeida Corrigida Fiel or the Almeida Revista e
Corrigida, but that does not make it free to use.

Which text the fixture should carry instead is an open decision on TASK-159.
Until it is made, do not extend this sample. The upstream source is gone,
and the text's status is the problem in the first place.

The test compares the two copies: the copied version is cut out of the
example at build time, so a verse that drifts in either place fails it.
