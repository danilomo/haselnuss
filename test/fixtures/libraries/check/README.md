Fixture library for haselnuss.check-test (TASK-242, `--check`). Together with
the other fixture libraries, named as `../libraries.yaml` names them, it plants
one of every problem `--check` reports that they do not already plant:

- `links.hdoc` -- one dangling reference of each kind (an unknown library, an
  ambiguous book, an unknown label, a bad label range, a locator on a book that
  interprets none, a locator the book rejects, a library that failed to load),
  a stale shelf path (info) and a dangling cross-reference;
- `quotes/cycle-a` and `quotes/cycle-b` quote each other: a quote cycle;
- `quotes/deep-01` ... `quotes/deep-10` quote each other in a chain one deeper
  than quotes may nest: `deep-09`'s quote is too deep;
- `gen/broken/shelf.clj` fails (script-failed) and `gen/odd/shelf.clj` lists a
  book entry that is invalid (invalid-generated-book).

This file is not a book: only `*.hdoc` files are.
