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

- `void read()` - Reads and parses the entire Stata dataset. Must be called before accessing data, and may be called only once per reader (a second call throws `IllegalStateException`).

##### Metadata Access

- `String getFormat()` - Returns the Stata file format release (e.g. "115", "117", "118"), or `null` before `read()`
- `ByteOrder getByteOrder()` - Returns the byte order the file was written in
- `int getNumVars()` - Returns the number of variables in the dataset
- `int getNumObs()` - Returns the number of observations in the dataset
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

##### Resource Management

- `void close()` - Closes the underlying input stream. StataReader implements AutoCloseable, so it can be used in try-with-resources statements.

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

- The entire dataset is loaded into memory when `read()` is called
- `StataWriter` keeps every observation in memory until `write()`, which writes the file in two passes: one to measure section offsets, then the real one
- For large datasets, ensure sufficient heap memory is available

## Limitations

- Writes only formats 119, 120 and 121, and can't write alias variables, characteristics, a sort order, or extended missing values (`.a`-`.z`)
- Does not support Stata file formats older than 113 (Stata 7 and earlier) or format 116
- Datasets with more than 2,147,483,647 observations are not supported
- Characteristics (`char`) are skipped, so an alias variable's target frame and variable are not reported
- Alias support in formats 120/121 assumes alias variables occupy no bytes in the data section (the published spec lists the type but not its width); it has been tested against synthesized files, not files saved by Stata
