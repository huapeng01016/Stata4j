# Stata4j API Documentation

## Overview

Stata4j provides a simple and efficient way to read Stata dataset files (.dta) in Java applications.

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

The library throws two types of checked exceptions:

- `IOException` - For I/O errors during file reading. A truncated file raises `EOFException`.
- `StataFormatException` - For invalid or unsupported Stata file formats

Both should be caught and handled appropriately in your application.

## Thread Safety

StataReader instances are **not thread-safe**. Each thread should create its own StataReader instance.

## Performance Considerations

- The entire dataset is loaded into memory when `read()` is called
- For large datasets, ensure sufficient heap memory is available

## Limitations

- Does not support writing Stata files
- Does not support Stata file formats older than 113 (Stata 7 and earlier) or format 116
- Datasets with more than 2,147,483,647 observations are not supported
- Characteristics (`char`) are skipped, so an alias variable's target frame and variable are not reported
- Alias support in formats 120/121 assumes alias variables occupy no bytes in the data section (the published spec lists the type but not its width); it has been tested against synthesized files, not files saved by Stata
