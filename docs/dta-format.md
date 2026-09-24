# How Stata4j parses .dta files

These notes describe the format as `StataReader` implements it. `StataWriter` writes the same tagged layout for 119–121; its choices are in [writer.md](writer.md). The authoritative references are Stata's own specifications: [dta](https://www.stata.com/help.cgi?dta) (118), [dta_119](https://www.stata.com/help.cgi?dta_119), [dta_120](https://www.stata.com/help.cgi?dta_120), [dta_121](https://www.stata.com/help.cgi?dta_121) and [dta_117](https://www.stata.com/help.cgi?dta_117) (117 and earlier).

## Supported releases

| Release | Written by | Family | Text encoding |
|---|---|---|---|
| 113 | Stata 8 | legacy | Windows-1252 |
| 114 | Stata 10 | legacy | Windows-1252 |
| 115 | Stata 12 | legacy | Windows-1252 |
| 117 | Stata 13 | tagged | Windows-1252 |
| 118 | Stata 14+ | tagged | UTF-8 |
| 119 | Stata 15+, more than 32,767 variables | tagged | UTF-8 |
| 120 | Stata 18+, with alias variables | tagged | UTF-8 |
| 121 | Stata 18+, alias variables and more than 32,767 variables | tagged | UTF-8 |

116 was never released. Releases before 113 are rejected with `StataFormatException`.

## Detecting the family

`read()` peeks at the first byte:
- `<` means a **tagged** file (`<stata_dta>…`).
- Anything else is read as a **legacy** release byte. If it isn't 113, 114 or 115, the file is rejected.

## Per-release widths (`DtaLayout`)

Everything that changes between releases is in `DtaLayout.forRelease`. To support a new release, add a row there rather than hard-coding widths in the reader.

| Field (bytes) | 113 | 114/115 | 117 | 118/120 | 119/121 |
|---|---|---|---|---|---|
| variable name | 33 | 33 | 33 | 129 | 129 |
| display format | 12 | 49 | 49 | 57 | 57 |
| value-label name | 33 | 33 | 33 | 129 | 129 |
| variable label | 81 | 81 | 81 | 321 | 321 |
| K (variable count) | 2 | 2 | 2 | 2 | 4 |
| N (observation count) | 4 | 4 | 4 | 8 | 8 |
| dataset-label length prefix | — (fixed 81) | — (fixed 81) | 1 | 2 | 2 |
| sortlist entry | 2 | 2 | 2 | 2 | 4 |
| strL cell: v / o | — | — | 4 / 4 | 2 / 6 | 3 / 5 |
| GSO record: v / o | — | — | 4 / 4 | 4 / 8 | 4 / 8 |

All of these were checked against files written by pandas for 114, 117, 118 and 119.

## Legacy layout (113–115)

In order, with integers in the file's byte order:

1. Header:
   - release (1 byte)
   - byte order (`0x01` = big-endian, `0x02` = little-endian)
   - filetype (1 byte)
   - unused (1 byte)
   - K (u16), N (u32)
   - dataset label (81 bytes), timestamp (18 bytes)
2. Type list: K × 1 byte (see [Type codes](#type-codes)).
3. Variable names, sortlist ((K+1) × 2 bytes, skipped), display formats, value-label names, variable labels.
4. Expansion fields: records of (type u8, length u32, contents). The list ends with type 0 and length 0, **5 bytes in total**. The contents are skipped.
5. Data: N rows of K values.
6. Value labels, repeated until end of file: length (u32), name (33 bytes), 3 padding bytes, then the [label table](#value-labels).

## Tagged layout (117–121)

The file is parsed strictly in order. `DtaInput.expect` checks each tag exactly where it should be, so a mismatch fails immediately and names the expected tag. Nothing is found by scanning.

```
<stata_dta>
  <header>
    <release>NNN</release>
    <byteorder>MSF|LSF</byteorder>
    <K>…</K> <N>…</N>
    <label>len + text</label>
    <timestamp>len(0 or 17) + text</timestamp>
  </header>
  <map>14 × u64</map>                       skipped
  <variable_types>K × u16</variable_types>
  <varnames> <sortlist> <formats> <value_label_names> <variable_labels>
  <characteristics>(<ch>u32 len + bytes</ch>)*</characteristics>    skipped
  <data>N rows</data>
  <strls>(GSO …)*</strls>
  <value_labels>(<lbl>len, name, 3 pad, table</lbl>)*</value_labels>
</stata_dta>
```

## Type codes

`StataVarType.fromLegacyCode` and `fromTaggedCode` translate the codes. **The same number means different things in the two families**, which is why `StataVarType` doesn't expose a single public code.

| Type | Legacy code | Tagged code | Bytes in `<data>` | Java value |
|---|---|---|---|---|
| str1–str244 | 1–244 | 1–244 | width | `String` |
| str245–str2045 | — | 245–2045 | width | `String` |
| strL | — | 32768 | 8 (a (v,o) reference) | `String` or `byte[]` |
| alias | — | 65525 (120/121 only) | 0 (assumed) | always `null` |
| double | 255 | 65526 | 8 | `Double` |
| float | 254 | 65527 | 4 | `Float` |
| long | 253 | 65528 | 4 | `Integer` |
| int | 252 | 65529 | 2 | `Short` |
| byte | 251 | 65530 | 1 | `Byte` |

Fixed-width strings are read up to the first NUL, or the full width if there's no NUL. They are not trimmed.

## Missing values

Stata stores `.` and `.a`–`.z` as the largest values of each type. `StataReader` returns `null` for anything at or above the `.` code:

| Type | `.` | `.a` … `.z` | Largest non-missing |
|---|---|---|---|
| byte | 101 | 102 … 127 | 100 |
| int | 32741 | 32742 … 32767 | 32740 |
| long | 2147483621 | … 2147483647 | 2147483620 |
| float | 2^127 (`0x7f000000`) | above | just under 2^127 |
| double | 2^1023 (`0x7fe0000000000000`) | above | just under 2^1023 |

NaN (which isn't valid in a `.dta` file) is also returned as `null`. The reader doesn't tell `.` apart from `.a`–`.z`.

## strL

- In `<data>`, a strL cell is 8 bytes: **v then o**, each in the file's byte order. The split is 4/4 (117), 2/6 (118/120) or 3/5 (119/121).
  - The dta spec's big-endian example: `0005 00000000000001` means (v=5, o=1).
- (0,0) means an empty string.
- Each cell is first stored as a placeholder (`StrLRef`). After `<strls>` has been read, the placeholders are replaced with the real contents. A reference with no matching GSO record raises `StataFormatException`.
- GSO record: `GSO`, v (u32), o (u32 in 117, u64 from 118), t (u8), length (u32), contents.
  - t = 130 is text: decoded with the file's charset, up to the NUL terminator.
  - t = 129 is binary: returned as `byte[]`.

**Known pandas bug:** pandas 2.2 writes big-endian strL cells as one u64 (`o << 16 | v`) instead of v followed by o, and can't read those files back itself. Stata4j follows the spec, so it won't read strLs from pandas-written big-endian files. The test fixtures avoid that case; see [testing.md](testing.md).

## Value labels

Every value-label set has the same table layout, with integers in the file's byte order:

```
n (i32)  txtlen (i32)  off[n] (i32 each)  val[n] (i32 each)  txt[txtlen]
```

Label *i* is the NUL-terminated text starting at `txt[off[i]]`, and it labels the value `val[i]`. The result is `getValueLabels()`: label-set name → (value → label). `getValueLabelNames()` gives the label-set name attached to each variable, or `""` if it has none. Labels are not applied to the data automatically. Offsets outside `txt`, or counts that don't fit the table, raise `StataFormatException`.

## Formats 120 and 121 (alias variables)

Stata 18 added **alias variables**: a variable that points to a variable in another frame. A dataset with an alias variable is saved as 120, or as 121 if it has more than 32,767 variables. Otherwise the layouts are the same as 118 and 119, and `DtaLayout` reuses those rows.

What the spec (dta_120) says:
- type code **65525** means "alias: reference to variable in another frame"

What it doesn't say, and what Stata4j assumes:
- **The width of an alias value in `<data>`.** Stata4j assumes 0 bytes, since the values live in the other frame. Alias variables still appear in `getVarNames()`/`getVarTypes()` (as `StataVarType.ALIAS`), and every value is `null`.
- **Where the link to the target frame and variable is stored.** Probably in characteristics, which Stata4j skips, so the target isn't reported.

Two protections:
- If the 0-byte assumption is wrong, each row is read with the wrong width, so the `</data>` check fails with a `StataFormatException`. No shifted data is returned.
- Type code 65525 in a file older than 120 is rejected.

No open-source reader supported 120/121 as of September 2026 (neither pandas, including its main branch, nor ReadStat), so there was nothing to compare against. The 120/121 test files are synthesized (see [testing.md](testing.md)) and are based on the same assumption. **Open item:** check against a file saved by Stata 18 or later that contains an alias variable, then replace the synthesized fixtures with it.
