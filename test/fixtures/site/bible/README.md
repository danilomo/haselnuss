Bible library of the site fixture set (TASK-264), adapted from
`examples/libraries/bible/` (TASK-159, TASK-212) and independent of it.

`blivre/` holds `shelf.clj`, so it is a generated shelf. The script turns
`blivre/blivre.json` -- a list of `{"slug", "title", "verses"}`, each verse a
`[chapter, verse, text]` triple -- into one book per entry
(`bible:blivre/romanos` and so on), whose locators are `C`, `C:V` or `C:V-W`.
It differs from the example's script in one place: each chapter's section id
is `sec:cap-N` rather than `cap:N`, so the generated books build without an
`unnumbered-section-id` warning and `--check` over the fixture set reports
only the problems the fixtures plant.

That change is visible in the output. `sec:` is a numbered kind, so each
chapter heading gets a section number counted from 1 over the chapters this
library holds: Romanos 3, 8 and 14 render as sections 1, 2 and 3, so the section
number does not match the chapter number. The pages therefore differ from the
example shelf's, whose `cap:N` chapters are unnumbered. Tests of headings,
anchors or heading text should expect `sec:cap-N` ids and these numbers.

A second difference (TASK-269): on a book's own page each verse number sits in
`<span id="vC-V" class="verse">`, and `:locate` answers that id as the
passage's `:anchor` (`sec:cap-C` for a whole-chapter locator), so a
`--build-site` link to `romanos#14:8` lands on the verse. A quoted passage's
verses carry no id, so a quote never repeats one.

## The text: Bíblia Livre (BLIVRE), licence CC BY 3.0 Brasil

The verses are the Bíblia Livre (BLIVRE), Textus Receptus, release 2025.1.0,
https://github.com/blivre/BibliaLivre, as prepared for
`examples/libraries/bible/blivre/blivre.json` (that folder's README lists the
upstream commit and the corrections made there).

> Todas as Escrituras em português citadas são da Bíblia Livre (BLIVRE),
> Copyright © Diego Santos, Mario Sérgio, e Marco Teles,
> http://sites.google.com/site/biblialivre/ - fevereiro de 2018. Licença
> Creative Commons Atribuição 3.0 Brasil
> (http://creativecommons.org/licenses/by/3.0/br/). Reprodução permitida
> desde que devidamente mencionados fonte e autores.

Changes made to the text, beyond those listed in the example library's
README: only the twelve verses the fixture catechism cites (Mateus 9:12,
10:29-30; João 10:27-30, 17:3; Romanos 3:10, 8:28, 14:8; 1 Coríntios 3:23) and
Gênesis 3, which `reformed:estudos/queda` links to as one passage of about
3,000 characters, were kept.
