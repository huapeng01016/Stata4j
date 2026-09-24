# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Stata4j is a small, dependency-free Java library for reading and writing Stata `.dta` files. Maven project (`io.github.huapeng01016:stata4j:0.1.0`), Java 17 (`maven.compiler.release`). JUnit 5 (api/params/engine) is the only test dependency.

## Commands

Use the Maven Wrapper (downloads Maven 3.9 on first run; `mvn` is not assumed to be installed). On Windows PowerShell/cmd use `mvnw.cmd`; in Git Bash use `./mvnw`.

```bash
./mvnw clean package                                  # build + test, produces target/stata4j-0.1.0.jar
./mvnw test                                           # all tests
./mvnw test -Dtest=StataReaderTest                    # one test class
./mvnw test -Dtest=StataReaderTest#readsLegacyFile    # one test method (all its parameterized cases)
python src/test/resources/fixtures/make_fixtures.py   # regenerate pandas fixtures (needs pandas)
java -cp target/classes io.github.huapeng01016.stata4j.example.StataReaderExample <file.dta>
```

## Architecture

Package `io.github.huapeng01016.stata4j`:

- **`StataReader`**: the only public reader. `read()` peeks at the first byte and takes one of two paths. `'<'` means a **tagged** file (117–121, `<stata_dta>…`), parsed strictly by position with `expect(tag)`; there's no tag scanning. Anything else is treated as a **legacy** release byte (113/114/115), a fixed binary layout with a 1-byte release, byte order `0x01`=big/`0x02`=little, and expansion fields terminated by 5 zero bytes. Both paths share `readData`/`readValue` and the value-label table parser. `readData` implements `selectVariables`/`selectObservations`. Rows have a fixed width, so it skips unselected rows and columns by byte count, merging adjacent skips into one `pending` count. It returns rows in the requested column order and collects the strL references it saw. `finish()` then narrows the per-variable metadata lists to the selected columns. `totalVars`/`totalObs` are the file's counts; `numVars`/`numObs` are what was read.
- **`StataWriter`** writes formats 119/120/121 in either byte order, driven by the same `DtaLayout` rows. It collects variables and rows in memory, checks each value in `addObservation` (the error names the observation and variable), and writes on `write()`, not `close()`. `<map>` holds offsets of later sections and a stream can't seek back, so `write()` runs the same `writeFile` routine twice: first into a counting null sink to get the offsets, then for real, and it checks the two passes agree. Anything that feeds the output (e.g. the timestamp) must be fixed before pass 1. Alias variables are rejected. strLs get one GSO each at (v,o) = (var, obs), 1-based.
- **`DtaMissing`** holds the missing-value codes and valid ranges shared by reader and writer. **`DtaOutput`** is the write-side counterpart of `DtaInput` and tracks the byte position.
- **`DtaLayout`** (package-private record) holds every width that changes by release: varname/format/label widths, K/N byte counts, sortlist entry size, strL (v,o) split, GSO `o` width, and the charset (windows-1252 up to 117, UTF-8 from 118). **To support a new release, add a row to `DtaLayout.forRelease`.** Don't hard-code widths in the reader.
- **`DtaInput`** (package-private) does byte-order-aware reads (`uN(n)` reads any 1–8-byte unsigned value) and `nextIs`/`peek` via `mark`/`reset`. Truncated input surfaces as `EOFException`.
- **`StataVarType`** is a value class, not an enum: constants for numerics and `STRL`, plus `str(width)` for widths 1–2045, and `ALIAS` (formats 120/121 only). Type codes differ by format, so use `fromLegacyCode` (1–244 str, 251–255 numeric) or `fromTaggedCode` (1–2045 str, 32768 strL, 65525 alias, 65526–65530 double…byte). The same number means different things in each: 251 is `byte` in legacy files and `str251` in tagged ones.

Semantics to keep consistent:
- Values come back as boxed Byte/Short/Integer/Float/Double/String. Every Stata missing value (`.`, `.a`–`.z`, i.e. at or above 101 / 32741 / 2147483621 / 2^127 / 2^1023) becomes `null`. Strings are cut at the first NUL and not trimmed.
- Formats 120/121 are 118/119 plus alias variables. Alias cells are **assumed to take 0 bytes** in `<data>` and read as `null`. The dta_120 spec lists the type code without a width, and no public reader (pandas, ReadStat) supports 120/121. If that assumption is wrong, the `</data>` check throws. `v120_alias.dta`/`v121_alias.dta` are synthesized from pandas 118/119 output (`write_alias` in `make_fixtures.py`), so they share the assumption. Replace them with files saved by Stata 18+ when available.
- strL cells are read as a `StrLRef(v,o)` placeholder and swapped for the `<strls>` GSO content once that section has been read. Only GSOs referenced by cells that were read get loaded, and a reference may point outside the selected range (cross-linked strLs). `(0,0)` means an empty string. In the data section, v comes first and then o, each in the file's byte order; the split is 4/4 for 117, 2/6 for 118 and 3/5 for 119.

## Tests

- `StataReaderSubsetTest` checks that for every kind of test file and many (variables, range) selections, a subset read equals the same projection of a full read. Add new test files to its `files()` source.
- `StataWriterTest` round-trips through `StataReader`, and also checks things the reader can't catch: `<map>` offsets read straight from the bytes, and the exact hex of strL cells from the spec's example. The writer was checked by hand against pandas (docs/writer.md); that check isn't automated.
- `StataReaderTest` has a parameterized test over the pandas-written fixtures in `src/test/resources/fixtures/`, and the expected values there must match `make_fixtures.py`. It also has one over `LegacyDtaBuilder`, a byte-level writer for 113/115 in either byte order. That builder covers what pandas can't write: formats 113 and 115, extended missing values, and windows-1252 text.
- pandas 2.2 writes big-endian strL cells incorrectly (as a single u64), against the dta spec. So `v118_big.dta` has no strL column. Don't "fix" the reader to match pandas on this.

## Repo notes

- `bin/` is Eclipse output: a copy of the whole project with its own `src/` and `pom.xml`. Don't edit or search it as if it were source. It is gitignored along with the other local Eclipse files (`.classpath`, `.project`, `.settings/`).
- README.md (overview) and API.md (reference) document the public API. GETTING_STARTED.md is a tutorial. `docs/` has developer notes: `changes.md` (history and migration), `writer.md` (writer design and verification), `dta-format.md` (per-release layouts and assumptions) and `testing.md` (fixtures and coverage gaps). Update them when public behavior or format handling changes.
