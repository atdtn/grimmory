# Russian Providers — Author and ISBN Search Fallbacks

**Date:** 2026-11-12
**Status:** Implemented

## Problem

Users often have an author with a patronymic ("Галина Васильевна Герасимова")
while catalogue records contain only first name and surname ("Галина
Герасимова"). Fantasy Worlds returned zero for the full name because its search
treats every word as required. The same class of issue applies to LitRes and
Litnet author searches.

Books also carry ISBN in different forms. Fantasy Worlds stores
`978-5-9922-3373-5`, while a user or another metadata source may provide
`9785992233735`. Filters must compare normalized ISBNs, not raw strings.

## Decisions

- Added `MetadataSearchUtils` with:
  - `authorVariants`: full name, first + last (drops patronymic), surname,
    first name, and first + middle for reversed name orders.
  - `isbnVariants`: raw and digits-only form (with `X` normalized).
- **Fantasy Worlds** tries a cascade of queries until something matches:
  `title + author`, `title`, author variants, ISBN variants. Results are
  filtered by normalized ISBN, then by title, then by author variants. This
  fixes both the patronymic case and the ISBN format mismatch when a title is
  available.
- **LitRes** tries ISBN variants, then `title + author`, then `title`, then
  author variants.
- **Litnet** already searches by title when a title is present (its search does
  not tolerate combined terms); for author-only searches it now tries author
  variants. Author-based ranking also uses variants.
- ISBN-only searches against Fantasy Worlds still cannot succeed: the site's
  search index contains titles/authors/tags, not ISBNs. The provider therefore
  uses ISBN as a normalized filter whenever a title or author is supplied. This
  limitation is source-level, not a parser bug.
