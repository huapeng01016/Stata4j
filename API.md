# Stata4j API Documentation

## Overview

Stata4j provides a simple and efficient way to read and write Stata dataset files (.dta) in Java applications.

## Core Classes

### StataReader

The main class for reading Stata dataset files.

#### Constructors

- `StataReader(File file)` - Creates a reader from a File object
- `StataReader(String filePath)` - Creates a reader from a file path
- `StataReader(InputStream inputStream)` - Creates a reader from an input stream

#### Methods

##### Reading Data

- `void read()` - Reads and parses the Stata dataset, or the selected part of it. Must be called before accessing data, and may be called only once per reader (a second call throws `IllegalStateException`). Throws `IllegalArgumentException` if a selected variable isn't in the file.

##### Selecting Part of a Dataset

Call these before `read()`; afterwards they throw `IllegalStateException`. Both return the reader, so they can be chained.

- `StataReader selectVariables(String... names)` / `selectVariables(Collection<String> names)` - Reads only these variables, **in the order given**. The metadata getters (`getVarNames`, `getVarTypes`, `getVarLabels`, `getFmtList`, `getValueLabelNames`) then cover only these variables, and each observation map has only these keys. A repeated name throws `IllegalArgumentException`. An empty selection reads no variables, but still counts the observations.
- `StataReader selectObservations(long from, long to)` - Reads only observations `from` (inclusive) to `to` (exclusive), 0-based. The range is clamped to the dataset, so `selectObservations(0, 1000)` on a 500-observation file reads 500, and a range past the end reads none. After reading, index 0 of `getObservation`/`getValue`/`getData` is observation `from` of the file. Throws `IllegalArgumentException` if `from < 0` or `to < from`.

- `StataReader filterObservations(String expression)` - Reads only the observations matching the filter; see [Filtering Observations](#filtering-observations). A syntax error throws `IllegalArgumentException` immediately, with its position. An unknown variable or a type mismatch throws `IllegalArgumentException` from `read()`.

The three can be combined. The filter is applied within the range, and it may use variables that aren't selected.

`getValueLabels()` still returns every value-label set in the file.

##### Metadata Access

- `String getFormat()` - Returns the Stata file format release (e.g. "115", "117", "118"), or `null` before `read()`
- `ByteOrder getByteOrder()` - Returns the byte order the file was written in
- `int getNumVars()` - Returns the number of variables read (all, or the selected ones)
- `int getNumObs()` - Returns the number of observations read (all, or those in the selected range)
- `int getTotalNumVars()` - Returns the number of variables in the file, whatever the selection
- `long getTotalNumObs()` - Returns the number of observations in the file, whatever the selection. It's a `long` because a file can hold more than `Integer.MAX_VALUE` observations; such a file must be read in ranges.
- `String getDatasetLabel()` - Returns the dataset label
- `String getTimestamp()` - Returns the dataset timestamp
- `List<String> getVarNames()` - Returns an unmodifiable list of variable names
- `List<String> getVarLabels()` - Returns an unmodifiable list of variable labels
- `List<String> getFmtList()` - Returns an unmodifiable list of variable display formats
- `List<StataVarType> getVarTypes()` - Returns an unmodifiable list of variable types
- `List<String> getValueLabelNames()` - Returns the value-label name attached to each variable (`""` if none)
- `Map<String, Map<Integer, String>> getValueLabels()` - Returns every value-label set in the file, keyed by label name, each mapping a value to its label

##### Data Access

- `List<Map<String, Object>> getData()` - Returns all observations as an unmodifiable list of unmodifiable maps, keyed by variable name in dataset order
- `Map<String, Object> getObservation(int index)` - Returns a specific observation by index (0-based)
- `Object getValue(int obs, int var)` - Returns one value by observation and variable index (both 0-based)
- `Object getValue(int obs, String varName)` - Returns one value by observation index and variable name
- `long getObservationIndex(int obs)` - Returns the 0-based position in the file of observation `obs` as read. This tells you which file observations a filter matched. Without a range or filter it equals `obs`.

##### Resource Management

- `void close()` - Closes the underlying input stream. StataReader implements AutoCloseable, so it can be used in try-with-resources statements.

### Filtering Observations

`filterObservations` takes a boolean expression over the dataset's variables:

```
expr       := and ('|' and)*
and        := unary ('&' unary)*
unary      := '!' unary | '(' expr ')' | comparison
comparison := variable op constant
op         := <  <=  >  >=  ==  !=
constant   := number | . | .a ... .z | "string"
```

`!` binds tightest, then `&`, then `|`, as in Stata: `a | b & c` means `a | (b & c)`, and `!a & b` means `(!a) & b`. The variable always comes first (`age > 18`, not `18 < age`).

**Numeric variables** compare with a number (`18`, `-2.5`, `1e6`, `.5`) or a missing value (`.`, `.a`–`.z`), using **Stata's rules**:
- Missing values are greater than every number, and ordered `. < .a < .b < ... < .z`.
- So `x > 5` and `x >= 5` are true when `x` is missing. `x < 5`, `x <= 5` and `x == 5` are false.
- `x < .` keeps only non-missing values: the usual Stata idiom, often combined as `x > 5 & x < .`.
- `x == .` matches only the system missing value `.`, not `.a`–`.z`; use `x >= .` for any missing value.
- The stored value is compared exactly. A `float` variable holding 0.1 is not `== 0.1`, because 0.1 can't be stored exactly as a float; this is the same as in Stata. Compare with a range instead.

These rules use the exact missing code stored in the file, even though the values returned by `getData()` show every missing value as `null`.

**String variables** (`str#` and `strL`) support only `==` and `!=` with a double-quoted literal, compared exactly: case-sensitive, and with no trimming. Inside the literal, `\"` is a quote and `\\` a backslash. A binary strL never equals a literal.

Examples:

```java
reader.filterObservations("age >= 18 & age < 65");
reader.filterObservations("state == \"CA\" | state == \"NY\"");
reader.filterObservations("!(income < .)");                  // income is missing
reader.filterObservations("score > 90 & score < . & name != \"\"");
```

A filter that uses a strL variable is checked after the strL contents are read, which comes after all the data. So until then the reader keeps every observation in the range, not just the matching ones. Filters on other variable types are applied as each observation is read.

### StataWriter

Writes a Stata dataset in format 119, 120 or 121, in either byte order. The dataset is built in memory and written by `write()`.

#### Constructors

- `StataWriter(OutputStream out, int format, ByteOrder byteOrder)`
- `StataWriter(File file, int format, ByteOrder byteOrder)` - The file is created or truncated. The arguments are checked first, so an invalid format doesn't create the file.
- `StataWriter(String filePath, int format, ByteOrder byteOrder)`

`format` must be 119, 120 or 121 (`StataWriter.SUPPORTED_FORMATS`); otherwise `IllegalArgumentException`. `byteOrder` is `ByteOrder.BIG_ENDIAN` (written as MSF) or `ByteOrder.LITTLE_ENDIAN` (LSF).

#### Methods

All setters return the writer, so calls can be chained.

##### Dataset metadata

- `setDatasetLabel(String label)` - At most 80 characters
- `setTimestamp(LocalDateTime timestamp)` - Defaults to the time of `write()`; `null` records no timestamp

##### Variables

Add every variable before the first observation; afterwards `addVariable` throws `IllegalStateException`.

- `addVariable(String name, StataVarType type)` / `addVariable(String name, StataVarType type, String label)` - Names are 1-32 characters: a letter or `_`, then letters, digits or `_`. Stata's reserved words (`int`, `_n`, `str10`, ...) are rejected, as are duplicates. `StataVarType.ALIAS` can't be written. Format 120 allows at most 32,767 variables; use 121 for more.
- `setVariableLabel(String var, String label)` - At most 80 characters
- `setFormat(String var, String format)` - A Stata display format such as `%9.2f` or `%td`. Defaults: `%8.0g` (byte, int), `%12.0g` (long), `%9.0g` (float), `%10.0g` (double), `%#s` for `str#` (at least `%9s`), `%9s` for strL.
- `setValueLabel(String var, String labelName)` - Attaches a value-label set to a numeric variable
- `defineValueLabel(String labelName, Map<Integer, String> labels)` - Defines or replaces a value-label set. Labels may be up to 32,000 characters.

##### Observations

- `addObservation(Object... values)` - One value per variable, in the order the variables were added
- `addObservation(Map<String, ?> values)` - Values by variable name. Absent variables are written as missing (numbers) or `""` (strings). An unknown name throws `IllegalArgumentException`.

Each value is checked when it's added, and an invalid one throws `IllegalArgumentException` naming the observation and variable:

| Variable type | Accepted values | Written as missing |
|---|---|---|
| byte | integral `Number` from -127 to 100 | `null`, NaN |
| int | integral `Number` from -32767 to 32740 | `null`, NaN |
| long | integral `Number` from -2147483647 to 2147483620 | `null`, NaN |
| float | `Number` with magnitude below 2^127 | `null`, NaN |
| double | `Number` with magnitude below 2^1023 | `null`, NaN |
| str# | `String` of at most `#` UTF-8 bytes, no NUL | `null` is written as `""` |
| strL | `String` (text, no NUL) or `byte[]` (binary) | `null` is written as `""` |

Integer types accept any integral `Number`, including `7.0` and `BigInteger`. Values outside the range are rejected, never clipped.

##### Writing

- `void write()` - Writes the file and flushes the stream. It can be called only once. **Required:** `close()` doesn't write.
- `void close()` - Closes the underlying stream
- `getFormat()`, `getByteOrder()`, `getNumVars()`, `getNumObs()`

### StataVarType

Immutable value class representing a Stata storage type. Instances compare with `equals`, so `StataVarType.str(10).equals(StataVarType.str(10))` is true.

#### Constants and Factory

- `BYTE`, `INT`, `LONG`, `FLOAT`, `DOUBLE` - Numeric types (1, 2, 4, 4 and 8 bytes)
- `STRL` - Long string (`strL`, formats 117+)
- `ALIAS` - Alias variable (formats 120/121), a reference to a variable in another frame
- `static StataVarType str(int width)` - Fixed-width string `str1` to `str2045`. Formats 113-115 only allow widths up to 244.

#### Methods

- `boolean isNumeric()` - True for the numeric types
- `boolean isString()` - True for `str#` and `strL`
- `boolean isStrL()` - True for `strL`
- `boolean isAlias()` - True for alias variables
- `int getStringLength()` - Returns the width of a `str#` type; throws `IllegalStateException` for other types
- `int getByteWidth()` - Returns the number of bytes a value occupies in the data section (8 for a `strL` reference, 0 for an alias)
- `String toString()` - Returns Stata's name for the type, e.g. `double`, `str10`, `strL`, `alias`

### StataFormatException

Exception thrown when an invalid Stata file format is encountered.

#### Constructors

- `StataFormatException(String message)` - Creates exception with a message
- `StataFormatException(String message, Throwable cause)` - Creates exception with a message and cause

## Usage Examples

### Basic Reading

```java
try (StataReader reader = new StataReader("data.dta")) {
    reader.read();
    
    System.out.println("Variables: " + reader.getNumVars());
    System.out.println("Observations: " + reader.getNumObs());
    
    List<Map<String, Object>> data = reader.getData();
    for (Map<String, Object> obs : data) {
        System.out.println(obs);
    }
} catch (IOException | StataFormatException e) {
    e.printStackTrace();
}
```

### Accessing Variable Metadata

```java
try (StataReader reader = new StataReader("data.dta")) {
    reader.read();
    
    List<String> varNames = reader.getVarNames();
    List<StataVarType> varTypes = reader.getVarTypes();
    List<String> varLabels = reader.getVarLabels();
    
    for (int i = 0; i < varNames.size(); i++) {
        System.out.printf("Variable: %s, Type: %s, Label: %s%n",
            varNames.get(i), varTypes.get(i), varLabels.get(i));
    }
}
```

### Accessing Specific Values

```java
try (StataReader reader = new StataReader("data.dta")) {
    reader.read();
    
    // Get first observation
    Map<String, Object> firstObs = reader.getObservation(0);
    System.out.println("First observation: " + firstObs);
    
    // Access a specific value
    Object age = reader.getValue(0, "age");
}
```

### Writing a Dataset

```java
try (StataWriter writer = new StataWriter("out.dta", 121, ByteOrder.BIG_ENDIAN)) {
    writer.addVariable("id", StataVarType.LONG)
          .addVariable("score", StataVarType.DOUBLE, "Test score")
          .addVariable("notes", StataVarType.STRL);
    writer.setFormat("score", "%9.2f");
    writer.addObservation(1, 87.5, "passed");
    writer.addObservation(Map.of("id", 2));   // score missing, notes ""
    writer.write();
}
```

### Copying a Dataset to Another Format

```java
try (StataReader in = new StataReader("old.dta");
     StataWriter out = new StataWriter("new.dta", 119, ByteOrder.LITTLE_ENDIAN)) {
    in.read();
    out.setDatasetLabel(in.getDatasetLabel());
    for (int i = 0; i < in.getNumVars(); i++) {
        String name = in.getVarNames().get(i);
        out.addVariable(name, in.getVarTypes().get(i), in.getVarLabels().get(i));
        out.setFormat(name, in.getFmtList().get(i));
        if (!in.getValueLabelNames().get(i).isEmpty()) {
            out.setValueLabel(name, in.getValueLabelNames().get(i));
        }
    }
    in.getValueLabels().forEach(out::defineValueLabel);
    for (Map<String, Object> obs : in.getData()) {
        out.addObservation(obs);
    }
    out.write();
}
```

Extended missing values (`.a`-`.z`) are read as `null` and so are written back as `.`.

### Reading Part of a Dataset

```java
try (StataReader reader = new StataReader("big.dta")) {
    reader.selectVariables("id", "income").selectObservations(1_000, 2_000);
    reader.read();
    for (Map<String, Object> obs : reader.getData()) {
        System.out.println(obs);   // {id=..., income=...}
    }
}
```

### Filtering and Finding the Matching Observations

```java
try (StataReader reader = new StataReader("survey.dta")) {
    reader.selectVariables("id", "income")
          .filterObservations("age >= 18 & (state == \"CA\" | state == \"NY\") & income < .");
    reader.read();
    for (int i = 0; i < reader.getNumObs(); i++) {
        System.out.println("file observation " + reader.getObservationIndex(i) + ": " + reader.getObservation(i));
    }
}
```

`age` and `state` are used by the filter without being selected, so the results only contain `id` and `income`.

### Processing a Large File in Chunks

A reader reads once, so open a new reader for each chunk:

```java
long total;
try (StataReader probe = new StataReader("big.dta")) {
    probe.selectVariables().read();   // no variables: the data is skipped, not decoded
    total = probe.getTotalNumObs();
}
for (long from = 0; from < total; from += 100_000) {
    try (StataReader chunk = new StataReader("big.dta")) {
        chunk.selectObservations(from, from + 100_000).read();
        process(chunk.getData());
    }
}
```

### Applying Value Labels

```java
try (StataReader reader = new StataReader("data.dta")) {
    reader.read();
    
    int var = reader.getVarNames().indexOf("grade");
    Map<Integer, String> labels = reader.getValueLabels().get(reader.getValueLabelNames().get(var));
    Number code = (Number) reader.getValue(0, var);
    String label = (labels == null || code == null) ? null : labels.get(code.intValue());
}
```

## Supported File Formats

- **Formats 113, 114, 115** - Stata 8-12 (legacy binary layout)
- **Format 117** - Stata 13
- **Format 118** - Stata 14 and later
- **Format 119** - Stata 15 and later, datasets with more than 32,767 variables
- **Formats 120, 121** - Stata 18 and later, datasets with alias variables (121: more than 32,767 variables)

Text is decoded as Windows-1252 for formats 113-117 and UTF-8 for formats 118-121.

## Data Type Mapping

| Stata Type | Java Type | Notes |
|------------|-----------|-------|
| byte | Byte | Values ≥ 101 (`.`, `.a`-`.z`) returned as null |
| int | Short | Values ≥ 32741 returned as null |
| long | Integer | Values ≥ 2147483621 returned as null |
| float | Float | Values ≥ 2^127 returned as null |
| double | Double | Values ≥ 2^1023 returned as null |
| str1-str2045 | String | Read up to the first NUL byte; not trimmed |
| strL | String, or byte[] for binary strL | |
| alias | null | No data is stored in the file; the variable refers to another frame |

Missing values are not distinguished from one another: `.`, `.a` and `.z` all come back as `null`.

## Error Handling

`StataWriter` reports invalid arguments and values immediately with `IllegalArgumentException`, and misuse (adding a variable after observations, calling `write()` twice) with `IllegalStateException`. When reading, the library throws two types of checked exceptions:

- `IOException` - For I/O errors during file reading. A truncated file raises `EOFException`.
- `StataFormatException` - For invalid or unsupported Stata file formats

Both should be caught and handled appropriately in your application.

## Thread Safety

StataReader and StataWriter instances are **not thread-safe**. Each thread should create its own instance.

## Performance Considerations

- `read()` loads the whole dataset into memory, or only the selected part if `selectVariables`/`selectObservations` were used
- When a range is selected, rows outside it are skipped without being read, which is a seek on a `FileInputStream`. Unselected variables are skipped without being decoded or stored, but because they sit inside each row they are still read from the stream. Only the strL contents that selected cells refer to are loaded.
- `StataWriter` keeps every observation in memory until `write()`, which writes the file in two passes: one to measure section offsets, then the real one
- For large datasets, ensure sufficient heap memory is available

## Limitations

- Writes only formats 119, 120 and 121, and can't write alias variables, characteristics, a sort order, or extended missing values (`.a`-`.z`)
- Does not support Stata file formats older than 113 (Stata 7 and earlier) or format 116
- At most 2,147,483,647 observations can be read at once; read a larger dataset in ranges with `selectObservations`, or with a filter that matches fewer
- Filters compare a variable with a constant only: no arithmetic, no comparing two variables, and string variables support only `==`/`!=`
- Characteristics (`char`) are skipped, so an alias variable's target frame and variable are not reported
- Alias support in formats 120/121 assumes alias variables occupy no bytes in the data section (the published spec lists the type but not its width); it has been tested against synthesized files, not files saved by Stata
