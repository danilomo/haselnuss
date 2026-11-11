Bible library of `examples/catecismo-heidelberg.hdoc` (TASK-159; text replaced
in TASK-212). The example quotes every proof text from here with `!quote`
(`!quote bible:romanos#14:8`), and `haselnuss.library.catechism-test` builds it.

`blivre/` holds `shelf.clj`, so it is a generated shelf. The script turns
`blivre/blivre.json` -- a list of `{"slug", "title", "verses"}`, each verse a
`[chapter, verse, text]` triple -- into one book per entry, `bible:blivre/joao`,
`bible:blivre/romanos` and so on, whose locators are `C`, `C:V` or `C:V-W`. It
runs only because the library's entry in `../libraries.yaml` says
`scripts: true`.

## The text: Bíblia Livre (BLIVRE), licence CC BY 3.0 Brasil

| | |
| --- | --- |
| Work | Bíblia Livre (BLIVRE), a Portuguese translation of the Scriptures |
| Version used | the **Textus Receptus** New Testament (`textos/f4/tr`; the Old Testament is identical in the `n4` variant), release **2025.1.0** |
| Upstream | https://github.com/blivre/BibliaLivre, commit `a315a15e9f4d01883b62206fe441d57762f126b3` ("Versão 2025.1.0.", 2025-01-12) |
| Project page | https://sites.google.com/site/biblialivre/ |
| Authors | Diego Renato dos Santos (chief editor, all books), Mario Sérgio de Souza Figueira, Marco Antonio Teles da Costa (`Contribuidores.md`) |
| Licence | Creative Commons Atribuição 3.0 Brasil (CC BY 3.0 BR), https://creativecommons.org/licenses/by/3.0/br/ |
| Licence evidence | upstream `README.md` ("A Bíblia Livre tem uso livre, porém a menção da obra de forma adequada é obrigatória ... em conformidade com a Licença Creative Commons Atribuição 3.0 Brasil") and upstream `LICENCA.md` (the full licence text), both at the commit above |

The licence is **CC BY 3.0 Brasil**, not 4.0: that is what the upstream
states, and this library follows it. The licence permits copying, adapting
and redistributing, including commercially, as long as the credit below is
kept and changes are indicated.

Upstream's `progresso revisao.txt` says the translation was prepared from
sources it describes as all in the public domain (Almeida 1819, the Almeida
Revista e Corrigida of 1911, the English Revised Version 1885, Louis Segond
1910, Reina Valera 1909, the World English Bible, and others). That is the
upstream's own statement and this library does not rely on it: the licence
that matters here is the one the authors grant on their translation.

### Attribution (the credit upstream asks for)

> Todas as Escrituras em português citadas são da Bíblia Livre (BLIVRE),
> Copyright © Diego Santos, Mario Sérgio, e Marco Teles,
> http://sites.google.com/site/biblialivre/ - fevereiro de 2018. Licença
> Creative Commons Atribuição 3.0 Brasil
> (http://creativecommons.org/licenses/by/3.0/br/). Reprodução permitida
> desde que devidamente mencionados fonte e autores.

`examples/catecismo-heidelberg.hdoc` repeats this credit, with the version
used, in its "Sobre esta edição" section, so it travels with the HTML and the
PDF. The same text is quoted in each build, and each quoted verse names its
source in the output (`data-source="bible:blivre/romanos#14:8"` in HTML, a
source line in LaTeX).

## What the library contains, and what was changed

`blivre.json` is a **subset**, not a Bible: the 1,363 verses (47 of the 66
books, the ones the catechism cites) that `examples/catecismo-heidelberg.hdoc`
cites, about 190 KB.
Five proof texts are whole chapters (Genesis 3, Isaiah 53, John 17, Luke 22,
Galatians 6) and are held whole. Everything else is held verse by verse, so a
bare chapter locator (`bible:joao#8`) answers only the verses held of that
chapter, which is why the script cannot reject a partial chapter; `C:V` and
`C:V-W` are rejected unless every verse they name is held.

Changes made to the text (a derivative work, indicated as CC BY asks):

- only the verses above were kept;
- the footnotes (`\fn ... \*fn`, 2,500 of them in the full text), the
  cross-reference notes (`\ref`), the psalm titles (`\psalm-title`) and the
  markers for words the translators added (`\added ... \*added`, which
  upstream sets in italics) were dropped, leaving plain verse text, with the
  stray space a dropped footnote leaves before punctuation removed;
- line breaks inside a verse were joined into one line;
- book titles are the names the catechism example uses (`Miquéias`, not
  upstream's `Miqueias`), and the slugs are their ASCII forms
  (`bible:blivre/1-corintios`, `bible:blivre/jo` for Job).

Five verses needed a correction, because upstream's generated `tr` files have
defects there (the conversion script, `conversores/inclusao_condicional.js`,
drops or leaks lines that carry an `{n4}`-style marker, and a few typos sit in
the sources themselves). Each was fixed by hand:

- Mateus 5:44: the stray tail ` perseguem”. Comparar com Lucas 6:27,28`
  (an N4 footnote that leaked into the generated `tr` file; `geral` keeps it
  on `{n4}`-marked lines) was removed;
- Atos 2:38: `pecados; e recebereis o dom do Espírito Santo.` was restored
  after `para perdão dos` (the `geral` line carried an `{n4: vossos}` marker
  and the script dropped it whole; ` vossos` is the N4 reading and not
  restored);
- Romanos 5:1: the leaked footnote text ` RP, TR, NA e SBL: temos` was removed;
- Romanos 16:20: the stray `}` after `Jesus` was removed (a typo present in
  upstream's `geral` source as well as in `tr`);
- Jeremias 17:5: the stray `)` after `humana,` was removed (a footnote's
  closing parenthesis left in upstream's text; the text now reads
  `humana, e seu coração`).

One further class of change: where a dropped footnote left a space before
punctuation or a closing quote, or after an opening quote or parenthesis
(`sangue , também`, `“ Simão`), the space was removed. That touches exactly
these 92 verses (Jeremias 17:5 and Mateus 5:44 are in both lists):
Jó 38:4; Salmos 119:105, 119:137; Isaías 42:2; Jeremias 17:5; Mateus 3:11,
5:27, 5:44, 6:15, 9:12, 18:15, 18:35, 19:9, 19:17, 22:39, 26:26, 26:52, 27:24,
28:19; Marcos 12:31, 13:33, 14:22; Lucas 1:78, 22:30, 22:31, 22:45, 22:62,
22:68, 23:15; João 1:18, 3:13, 17:11, 17:12, 20:22; Atos 2:30, 3:22, 4:24,
9:31, 17:24, 17:26; Romanos 1:16, 4:19, 5:3, 6:6, 7:24, 8:1, 8:23, 10:17,
12:19; 1 Coríntios 6:20, 10:7, 13:3, 13:5; Gálatas 4:7, 6:15, 6:17; Efésios
1:14, 1:17, 1:18, 2:6, 4:6, 5:30, 6:12; Filipenses 1:23, 3:13; Colossenses
2:11, 3:5, 3:6, 3:8, 3:22; 1 Timóteo 4:13, 5:16; 2 Timóteo 2:12; Hebreus 2:14,
7:21, 10:12, 10:30, 11:6; Tiago 5:12; 1 Pedro 1:23, 2:12, 3:18, 4:14, 5:5;
2 Pedro 1:10; 1 João 1:7, 2:23, 2:27; Apocalipse 1:8, 5:9, 19:10, 22:9.

Apart from the changes above the text is verbatim. Parentheses that remain
unbalanced inside one verse (Romanos 1:3-4, Hebreus 7:21) are upstream's:
the parenthetical runs across verses. Chapter and verse numbering is
upstream's, which is the English (Protestant) versification: a psalm's title
is not a numbered verse, so `Salmos 3:1` is "Ah SENHOR, como têm se
multiplicado meus adversários!". Of the 1,425 references of the example,
1,423 are present in this edition. Two are not present in its versification
(João 15:35 and 1 Samuel 24:23; the latter may belong to a Hebrew
versification, in which 1 Samuel 24 has one more verse than in the English).
The example keeps them as "não localizada" notes rather than quoting.

## History

This library replaces a sample of the "almeida" translation that
bible-api.com served, which the example and the TASK-159 fixture had used.
That text's licence could not be verified: its upstream, seven1m/open-bibles,
removed it on 2026-11-06 (PR #109, issue #82), and the research there points
to a copyrighted 20th-century revision. None of it is in the repository any
longer, and nothing here depends on it.

## Updating or extending

`blivre.json` was produced from the upstream `textos/f4/tr/*.txt` files at the
commit above by applying exactly the changes listed; to extend it, take more
verses from those files the same way and keep this README's commit hash,
change list and verse counts in step. A verse from any other text needs its
own licence evidence recorded here first.
