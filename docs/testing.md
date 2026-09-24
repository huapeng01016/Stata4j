# Testing

## Running

```bash
./mvnw test                                          # everything (mvnw.cmd on Windows cmd/PowerShell)
./mvnw test -Dtest=StataReaderTest                   # one class
./mvnw test -Dtest=StataReaderTest#readsAliasFixture # one method, all its parameterized cases
```

There are 25 tests: 20 in `StataReaderTest` (counting each parameterized case) and 5 in `StataVarTypeTest`.

## Test sources

| File | Role |
|---|---|
| `StataReaderTest` | Reads every fixture and asserts metadata, types, each value, missing values, strL, value labels and accessors; also covers the error paths |
| `StataVarTypeTest` | Type-code mapping for both families, width limits, predicates, equality and `toString` |
| `LegacyDtaBuilder` | Builds 113/115 files byte by byte, in either byte order |
| `src/test/resources/fixtures/*.dta` | Checked-in fixture files |
| `src/test/resources/fixtures/make_fixtures.py` | Regenerates the fixtures (needs pandas) |

## Fixtures

Real files are preferred to hand-built ones. The old test built its own file and shared the reader's mistakes, so it passed while the reader couldn't read anything Stata writes.

### Written by pandas

`make_fixtures.py` writes the same three-row DataFrame in each format:

| File | Format | Byte order | Notes |
|---|---|---|---|
| `v114.dta` | 114 | little | no str#>244 or strL (the format can't hold them) |
| `v117.dta` | 117 | little | `longs` is str300, `notes` is strL |
| `v118.dta` | 118 | little | also a UTF-8 value (`"Zoë"`) |
| `v119.dta` | 119 | little | 4-byte K, 4-byte sortlist entries, 3/5 strL split |
| `v118_big.dta` | 118 | **big** | `notes` is a plain str10, not strL (see below) |

Columns (plus `longs` and `notes` in 117+):
- `b`, `i`, `l`: int8, int16, int32
- `f`, `d`: float32, float64
- `s`: a string
- `grade`: categorical, which becomes value label `grade` = {0: low, 1: high}

Row 3 is missing in every numeric column. The script reads each file back with `pandas.read_stata` as a sanity check.

**Why `v118_big.dta` has no strL:** pandas 2.2 writes big-endian strL cells in the wrong layout (see [dta-format.md](dta-format.md#strl)). Big-endian parsing is still covered by that file's other columns and by the big-endian legacy case. Big-endian strL isn't covered.

### Synthesized 120/121

pandas can't write 120/121. `write_alias()` in `make_fixtures.py` builds `v120_alias.dta` and `v121_alias.dta` from pandas 118/119 output:
1. Write columns `id` (byte), `al` (byte), `name` (str3) and `notes` (strL).
2. Change the release to 120 or 121, and the type code of `al` to 65525.
3. Delete `al`'s byte from every row in `<data>`.
4. Move the `<map>` offsets for the sections after `<data>` back by the number of bytes removed.

These files encode the assumption that alias variables take 0 bytes in `<data>`. They show the reader handles that layout, including a strL column after the alias, but they aren't evidence of what Stata writes. Replace them with files saved by Stata 18+ when possible.

### Built in code: `LegacyDtaBuilder`

This covers what pandas can't write. It builds 113 and 115 files and is exercised as 113 LE, 115 LE and 115 BE. Each file includes:
- one expansion-field (characteristic) record, which the reader must skip, followed by the 5-byte terminator
- the largest non-missing values: byte 100, int 32740, long 2147483620
- extended missing values: `.a` and `.z`, float `.a`, double `.z`
- a Windows-1252 character (`ë`)
- a str8 value that fills the field with no NUL terminator
- a value-label set `gradelbl`

## Error-path coverage

| Test | Input | Expected |
|---|---|---|
| `rejectsNonStataInput` | `"999"` | `StataFormatException` |
| `rejectsUnsupportedLegacyRelease` | legacy release byte 112 | `StataFormatException` naming 112 |
| `rejectsUnsupportedTaggedRelease` | `<release>110</release>` | `StataFormatException` naming 110 |
| `rejectsMisplacedTag` | `<varnames>` corrupted | `StataFormatException` naming `<varnames>` |
| `rejectsAliasBeforeFormat120` | type 65525 in a 118 file | `StataFormatException` |
| `truncatedFilesThrowEof` | half a 118 file, 200 bytes of a legacy file, empty input | `EOFException` |
| `readOnlyOnce` | second `read()` | `IllegalStateException` |
| `accessorsValidateArguments` | out-of-range indexes, unknown name, modifying a row | IOOBE / IAE / UOE |
| `missingFileThrows` | nonexistent path | `FileNotFoundException` |

## Gaps

- No fixture saved by Stata itself. Fixtures come from pandas, a synthesis step, or the builder.
- Formats 120/121 are checked only against synthesized files (see above).
- Big-endian strL isn't covered.
- Binary strL (GSO type 129) isn't covered, because pandas only writes text strLs.
- Format 114 comes from pandas, but 113 and 115 come only from `LegacyDtaBuilder`.

## Updating fixtures

1. Edit `make_fixtures.py`, then run `python src/test/resources/fixtures/make_fixtures.py` from the repo root.
2. Update the expected values in `StataReaderTest` to match. The frame in the script is the source of truth.
3. Commit the regenerated `.dta` files. The timestamps inside them change on every run, so expect binary diffs.
