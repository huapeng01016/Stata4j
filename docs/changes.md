# Changes (September 2026)

## Filtering observations

`StataReader.filterObservations(String expression)` reads only the observations that match a filter, such as `age >= 18 & (state == "CA" | state == "NY") & income < .`.

- **Syntax:** comparisons `variable op constant` (`<`, `<=`, `>`, `>=`, `==`, `!=`), combined with `&`, `|`, `!` and parentheses. `!` binds tightest, then `&`, then `|`, as in Stata. The request was for `a | b`, `a & b` and `!a` over simple comparisons; full nesting with parentheses and `!=` were added because they cost little and avoid awkward rewrites.
- **Missing values follow Stata** (chosen over SQL-style "missing never matches"): missing is greater than every number, and ordered `. < .a < ... < .z`. So `x > 5` matches missing `x`, `x < .` means non-missing, and `x == .` matches only system missing.
  - The reader returns every missing value as `null`, which loses `.a` versus `.z`. So the filter compares on the raw stored code, mapped to a sort key that keeps Stata's order (`DtaFilter.missingKey`).
- **String variables** (`str#`, `strL`): `==`/`!=` with an exact, case-sensitive match.
- **Combining:** works with `selectVariables` (filter variables needn't be selected) and `selectObservations` (the filter is applied within the range).
- **New `getObservationIndex(i)`** gives each result's position in the file, so filtered rows can be traced back.
- **Errors:** syntax errors are reported when the filter is set, with a 1-based position. Unknown variables, type mismatches, string `<`/`>` and alias variables are reported by `read()`.
- **Efficiency:**
  - Rows that fail the filter are dropped as they're read, and only the columns that are selected or filtered on are decoded.
  - Strings in rejected rows aren't loaded.
  - A filter on a strL variable has to wait for `<strls>`, so in that case every observation in the range is kept until then.
- **Code:** `DtaFilter` (package-private) parses the expression and compiles it into a predicate over per-row input slots. `readData` now returns a `DataPass` holding the kept rows, their file indexes, and the deferred inputs for strL filters.
- **Tests:** `DtaFilterTest` (29, on syntax and precedence) and `StataReaderFilterTest` (28, on behavior), bringing the suite to 133.
  - Stata missing-value rules are checked on the legacy file that stores `.a`/`.z`, in both byte orders.
  - 17 expressions run against a 300-row dataset, compared with the same rules written as Java predicates.
  - A strL filter is tested where the matching string is stored with another observation.

## Reading part of a dataset

`StataReader` can now read a subset of variables and/or a range of observations instead of the whole dataset.

- **API:** `selectVariables(String...)` / `selectVariables(Collection)` and `selectObservations(long from, long to)`, called before `read()`. `getNumVars()`/`getNumObs()` count what was read. The new `getTotalNumVars()`/`getTotalNumObs()` count what is in the file.
- **Variables** come back in the order requested. The metadata lists are narrowed to match, and an unknown name makes `read()` throw `IllegalArgumentException`.
- **Ranges** are 0-based and `[from, to)`, clamped to the dataset.
- **Rows outside the range** are skipped by byte count, not decoded. Every row has the same width, so the reader seeks straight past them. Reading the last 10 rows of a 4.1 MB file reads about 20 KB from the stream.
- **Unselected variables** are skipped without being decoded or stored. They are still read from the stream, because they are interleaved with the selected ones in each row.
- **strLs:** only the contents that selected cells refer to are loaded; other GSOs are skipped. The set of references is collected while reading, because a cell may refer to a strL first defined by another observation (pandas and Stata do this for repeated values).
- **Behavior change:** a dataset with more than 2,147,483,647 observations used to be rejected outright. It can now be read in ranges; a full read of one fails with a message pointing at `selectObservations`.
- **Tests:** `StataReaderSubsetTest` (15 tests) checks that, for every kind of test file and many selections, a subset read equals the same projection of a full read.

## StataWriter

Added `StataWriter`, which writes `.dta` files in **format 119, 120 or 121**, big- or little-endian (MSF or LSF). See [writer.md](writer.md) for the design and verification.

- **API:** declare variables (`addVariable`, `setVariableLabel`, `setFormat`, `setValueLabel`), define value labels (`defineValueLabel`), add observations (`addObservation` by position or by name), then call `write()`. `close()` doesn't write.
- **Types:** byte, int, long, float, double, `str1`–`str2045`, and strL (text `String` or binary `byte[]`).
- **Validation:** values are checked when added. Out-of-range numbers, strings too long for their `str#` width in UTF-8 bytes, invalid or reserved names, and alias variables are rejected with `IllegalArgumentException`.
- **`<map>`:** filled in using two passes, one to measure section offsets and one to write, so any `OutputStream` works.
- **Verification:** files in all six format/byte-order combinations were read back correctly by pandas, apart from big-endian strL, which pandas can't read. That is checked byte by byte against the spec's example instead.
- **Supporting changes:** missing-value codes moved into the shared `DtaMissing` (used by both reader and writer); `DtaOutput` added as the write-side counterpart of `DtaInput`; package-private `StataVarType.toTaggedCode()` added.
- **Tests:** 36 in `StataWriterTest`, bringing the suite to 61.

Not supported by the writer: formats before 119, alias variables, extended missing values `.a`–`.z`, characteristics and sort order.

## Reader rework

### Summary

Stata4j had two unrelated `.dta` readers, `StataReader` and `StataDatasetReader`, which came from separate Copilot pull requests. Neither could read files saved by Stata, and the build failed. They have been replaced by a single `StataReader` that reads formats 113–115 and 117–121 in either byte order. It is tested against real files written by pandas, plus files built byte by byte for cases pandas can't produce.

The version stays at 0.1.0. Some public APIs changed; see [Breaking changes](#breaking-changes).

### Problems in the previous code

#### `StataReader`
- It read the format version as three ASCII characters (`"115"`). In formats 113–115 the version is a single byte (`0x73` for 115).
- It treated byte-order flag `0x01` as little-endian. In the dta spec `0x01` is HILO (big-endian) and `0x02` is LOHI (little-endian).
- It accepted versions `"117"` and `"118"` but parsed them with the old binary layout. Files in those formats start with `<stata_dta>`, so they always failed.
- It read the end of the expansion fields as 1 zero byte. The terminator is 5 bytes: a type byte of 0 and a 4-byte length of 0.
- Its only data test passed because the test built its file with the same mistakes.

#### `StataDatasetReader`
- It read the byte order from the wrong place in tagged files (it's `<byteorder>MSF|LSF</byteorder>`).
- It assumed every string variable is 244 bytes wide.
- It mapped type codes loosely: 1–244 and unknown codes all became `BYTE`, and strL wasn't handled.
- It worked out the observation count from the size of the data section instead of reading `<N>`.
- It always reported format 117.
- Its test used JUnit 4 (`org.junit.Test`), which isn't on the classpath, so **`mvn test` failed to compile**.

#### Both
- Missing values were detected as 127 / 32767 / 2147483647 / NaN. Stata's actual missing codes start at 101 (byte), 32741 (int), 2147483621 (long), 2^127 (float) and 2^1023 (double), and run up through `.a`–`.z`. Values like `.a` were therefore returned as numbers.
- Value labels were never read.

#### Documentation
- The docs contradicted each other on supported formats (115/117/118 in one place, 117/118/119 in another) and on the Java version (8 vs 17). They also named a `stata4j-1.0.0.jar` that the build doesn't produce.

### What was done

#### One reader
- `StataReader` is the only reader. `StataDatasetReader`, `StataDatasetExample` and `StataDatasetReaderTest` were deleted.
- `read()` looks at the first byte. `<` means a tagged file (117–121), which is parsed strictly in order: each expected tag is checked, not searched for. Otherwise the byte is taken as a release number (113–115) and the file is parsed with the old binary layout.
- Everything that changes between releases (field widths, header sizes, character set) lives in one table, `DtaLayout`. Byte-order-aware reads are in `DtaInput`. Both are package-private.
- A truncated file raises `EOFException`. A malformed or unsupported file raises `StataFormatException` with the offending tag or release in the message.

#### Format and data support
- Formats **113, 114, 115, 117, 118, 119, 120, 121**, big- or little-endian.
- `str1`–`str2045`, `strL` (text and binary), and the `alias` type added in format 120.
- All missing values (`.`, `.a`–`.z`) return `null`.
- Value labels are parsed.
- Text is decoded as Windows-1252 for formats up to 117 and as UTF-8 from 118 on.

See [dta-format.md](dta-format.md) for details. Formats 120/121 rest on one assumption documented there.

#### New API
- `StataReader.getValue(int obs, int var)` and `getValue(int obs, String name)`, carried over from the deleted reader.
- `StataReader.getValueLabelNames()` and `getValueLabels()`.
- `StataReader.getByteOrder()`.
- `StataVarType.str(int)`, `STRL`, `ALIAS`, `isStrL()`, `isAlias()` and `getByteWidth()`.

#### Build and tooling
- Added the Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, pinned to Maven 3.9.16), so building needs only a JDK.
- `pom.xml` now uses `maven.compiler.release=17` instead of `source`/`target`. This removes a warning when building on newer JDKs and checks the code against the Java 17 API.
- Added `junit-jupiter-params` (test scope) for parameterized tests.
- Added `/bin/` (the Eclipse build copy) to `.gitignore`.

#### Documentation
- Rewrote README.md, API.md and GETTING_STARTED.md so they agree with the code.
- Added `CLAUDE.md` (guidance for AI coding assistants) and this `docs/` folder.

### Breaking changes

| Before | After | Why |
|---|---|---|
| `StataVarType` was an enum with `STR1`…`STR244` | Immutable class; use `StataVarType.str(n)` | Formats 117+ allow widths up to 2045, plus strL and alias |
| `StataVarType.getCode()`, `StataVarType.fromCode(int)` | Removed (package-private `fromLegacyCode` / `fromTaggedCode`) | The same code means different types in different formats: 251 is `byte` in 113–115 but `str251` in 117+ |
| String values were trimmed | Strings are cut at the first NUL only | Leading or trailing spaces are real data |
| `getData()` rows could be modified | Rows are unmodifiable maps | Consistent with the other getters, which were already unmodifiable |
| `read()` could be called again | A second `read()` throws `IllegalStateException` | The stream has already been consumed |
| Missing values were detected only at the top code / NaN | Every missing code, including `.a`–`.z`, returns `null` | Correctness |
| `StataDatasetReader` | Removed; use `StataReader` | Duplicate implementation |

#### Migrating from `StataDatasetReader`

| `StataDatasetReader` | `StataReader` |
|---|---|
| `new StataDatasetReader(path)` then `read()` | `try (StataReader r = new StataReader(path)) { r.read(); … }` |
| `getNumberOfVariables()` / `getNumberOfObservations()` | `getNumVars()` / `getNumObs()` |
| `getVariableNames()` / `getVariableLabels()` | `getVarNames()` / `getVarLabels()` |
| `getVariableTypes()` returning `VariableType` | `getVarTypes()` returning `StataVarType` |
| `getFormatVersion()` (int) | `getFormat()` (String, e.g. `"118"`) |
| `getData()` returning `Object[][]` | `getData()` returning `List<Map<String,Object>>`, or `getValue(obs, var)` |
| `getValue(obs, var)` / `getValue(obs, name)` | Same signatures |
| `printSummary()` / `printData(n)` | Removed; see `example/StataReaderExample` |

#### Migrating `StataVarType` usage

```java
// before
if (type == StataVarType.STR10) { … }
int width = type.getStringLength();

// after
if (type.equals(StataVarType.str(10))) { … }
if (type.isString() && !type.isStrL()) { int width = type.getStringLength(); }
```

### Known limitations and open items

- **Formats 120/121 are unverified against Stata output.** The spec gives the alias type code but not its width in the data section. The reader assumes 0 bytes. A small file with an alias variable, saved by Stata 18 or later, would confirm it or show it's wrong; see [dta-format.md](dta-format.md#formats-120-and-121-alias-variables).
- Characteristics are skipped, so an alias variable's target frame and variable aren't reported.
- Formats older than 113 and the unreleased format 116 are not supported.
- Datasets with more than 2,147,483,647 observations are rejected.
- The whole dataset is loaded into memory by `read()`.
