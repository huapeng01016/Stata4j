# StataWriter

`StataWriter` writes `.dta` files in format **119, 120 or 121**, big- or little-endian. For usage, see [README](../README.md#writing-a-stata-dataset) and [API.md](../API.md#statawriter). This page covers the design, how the output was verified, and the limitations.

## Why these formats

| Format | Readable by | Notes |
|---|---|---|
| 119 | Stata 15+ | Same as 118, but with 4-byte K and sortlist entries, and a 3/5 strL split |
| 120 | Stata 18+ | Same layout as 118; K is 2 bytes, so at most 32,767 variables |
| 121 | Stata 18+ | Same layout as 119 |

Formats 120/121 exist for datasets with alias variables. The writer can't write alias variables (see [Limitations](#limitations)), so a 120/121 file from Stata4j has the 118/119 layout with a different release number. All three share one code path, and the differences come from `DtaLayout`, the same per-release table the reader uses.

## Design

### Build in memory, then write

Variables, value labels and observations are collected first, and `write()` produces the whole file. There are two reasons:
- The header records N (the observation count) before the data.
- strL contents go in `<strls>`, *after* `<data>`, which only holds (v,o) references.

A single-pass streaming writer would need to know N in advance and keep the strLs aside anyway.

### Two passes for `<map>`

`<map>` near the top of the file holds the byte offsets of 14 later sections. Stata's spec suggests writing zeros, then seeking back to fill them in, but a Java `OutputStream` can't seek. So `write()` runs the same routine twice:
1. It writes to `OutputStream.nullOutputStream()` through a byte-counting `DtaOutput`, recording each section's offset.
2. It writes to the real stream, putting those offsets into `<map>`.

The output doesn't depend on any state that changes between the passes. For example, the timestamp is resolved once, before the first pass. The second pass also records offsets, and `write()` checks they match the first pass, so a mismatch fails loudly rather than producing a corrupt map. This works for any `OutputStream`, including sockets and `ByteArrayOutputStream`, and needs no second copy of the file in memory.

### Validation happens when data is added

Each value is converted and checked in `addObservation`, not in `write()`. A bad value is reported next to the code that supplied it, and the error names the observation and variable. Rules:
- **Numbers** must be within Stata's non-missing range for the type (byte -127 to 100, int -32767 to 32740, long -2147483647 to 2147483620, float/double magnitude below 2^127/2^1023). Values are never clipped. Integer types accept any integral `Number`.
- **`null` and NaN** are written as the system missing value `.`.
- **Strings** are encoded as UTF-8 and must fit the `str#` width in bytes, not characters. They can't contain NUL, because a reader would cut the value off there. Binary content goes in a strL as `byte[]`.
- **Names** (variable and value-label): 1–32 characters, a letter or `_` followed by letters, digits or `_`, not a Stata reserved word, and unique.

### strL

- Each non-empty strL cell gets its own GSO with (v,o) = (variable number, observation number), both 1-based. That's the "usual case" in the spec (5.11.1). Equal strings are **not** deduplicated; the spec doesn't require it.
- An empty string, or `null`, is written as (0,0), which the spec defines as "".
- GSOs are written in the order their references appear in `<data>`: by observation, then by variable (spec 5.11.2).
- Text strLs are written as GSO type 130 (ASCII/UTF-8) with a NUL terminator counted in `len`. `byte[]` values are written as type 129 (binary) with no terminator.

### Value labels

Each set is sorted by value before writing, because the spec requires `val[]` in ascending order. Label texts are stored in the same order, each followed by NUL.

### Other fields

| Field | Written as |
|---|---|
| Timestamp | `dd Mon yyyy HH:mm` in English (e.g. `04 Jul 2032 04:23`), always 17 characters, or empty if `setTimestamp(null)` |
| Sortlist | All zeros (not sorted) |
| Characteristics | Empty `<characteristics></characteristics>` |
| Display formats | Stata's defaults unless set: `%8.0g` byte/int, `%12.0g` long, `%9.0g` float, `%10.0g` double, `%#s` str# (at least `%9s`), `%9s` strL |
| Text encoding | UTF-8 throughout |

## Verification

The writer was checked against a reader other than Stata4j's own, because a writer and reader from the same author can share a misunderstanding. That is how the original reader's test passed while the reader couldn't read Stata files.

1. **pandas 2.2.3** read files written in all six format/byte-order combinations. pandas knows only 118/119, so 120/121 were checked by changing the release number in a copy (valid because, without aliases, their layout is exactly 118/119).
   - Every value matched: including the edge-of-range values that must *not* become missing, null/NaN as missing, UTF-8 text (`Zoë`, `日本語`), str300, and strL.
   - Value labels, variable labels, display formats, dataset label and timestamp all matched.
   - Every `<map>` entry pointed exactly at its section, and entry 14 equaled the file length.
2. **Big-endian strL** can't be checked with pandas, because pandas mishandles big-endian strL cells (see [dta-format.md](dta-format.md#strl)). Instead:
   - each big-endian file was compared with its little-endian twin after zeroing the strL cells, and they matched
   - `StataWriterTest.strlCellLayoutFollowsSpec` asserts the exact bytes of a strL cell for each layout. The 118/120 big-endian cell is `0008 000000000001` for (v=8, o=1), the same shape as the spec's own MSF example `0005 00000000000001`.
3. **Round trips** through `StataReader` for every format and byte order, plus a copy of a pandas-written 118 file into 121 big-endian that reads back identical.

**Not verified:** opening the files in Stata itself. Stata wasn't available.

## Limitations

- **Formats 119–121 only.** Adding 118 would take one entry in `SUPPORTED_FORMATS`, since its layout is already in `DtaLayout`. Older formats would need the legacy writer paths.
- **No alias variables.** The spec gives the type code, but not how alias values are stored or where the link to the other frame lives (see [dta-format.md](dta-format.md#formats-120-and-121-alias-variables)). Writing a file Stata might reject was judged worse than refusing.
- **No extended missing values.** `.a`–`.z` can't be written; `null` is always `.`. Since the reader returns all missing values as `null`, copying a dataset turns `.a`–`.z` into `.`.
- **No characteristics or sort order.**
- **Memory.** The whole dataset is held in memory until `write()`. Each non-empty strL is written as its own GSO, so repeated long strings take repeated space.
- **At most 2,147,483,647 observations** (a Java list).
