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

- `void read()` - Reads and parses the entire Stata dataset. Must be called before accessing data.

##### Metadata Access

- `String getFormat()` - Returns the Stata file format version (e.g., "115", "117", "118")
- `int getNumVars()` - Returns the number of variables in the dataset
- `int getNumObs()` - Returns the number of observations in the dataset
- `String getDatasetLabel()` - Returns the dataset label
- `String getTimestamp()` - Returns the dataset timestamp
- `List<String> getVarNames()` - Returns an unmodifiable list of variable names
- `List<String> getVarLabels()` - Returns an unmodifiable list of variable labels
- `List<String> getFmtList()` - Returns an unmodifiable list of variable formats
- `List<StataVarType> getVarTypes()` - Returns an unmodifiable list of variable types

##### Data Access

- `List<Map<String, Object>> getData()` - Returns all observations as an unmodifiable list of maps
- `Map<String, Object> getObservation(int index)` - Returns a specific observation by index (0-based)

##### Resource Management

- `void close()` - Closes the underlying input stream. StataReader implements AutoCloseable, so it can be used in try-with-resources statements.

### StataVarType

Enum representing Stata variable types.

#### Numeric Types

- `BYTE` (code 251) - 1-byte integer
- `INT` (code 252) - 2-byte integer
- `LONG` (code 253) - 4-byte integer
- `FLOAT` (code 254) - 4-byte floating point
- `DOUBLE` (code 255) - 8-byte floating point

#### String Types

- `STR1` through `STR244` - Fixed-length string types (codes 1-244)

#### Methods

- `int getCode()` - Returns the numeric code for this type
- `boolean isNumeric()` - Returns true if this is a numeric type
- `boolean isString()` - Returns true if this is a string type
- `int getStringLength()` - Returns the string length (only for string types)
- `static StataVarType fromCode(int code)` - Returns the type for a given code

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

### Accessing Specific Observations

```java
try (StataReader reader = new StataReader("data.dta")) {
    reader.read();
    
    // Get first observation
    Map<String, Object> firstObs = reader.getObservation(0);
    System.out.println("First observation: " + firstObs);
    
    // Access specific variable value
    Object value = firstObs.get("variableName");
}
```

## Supported File Formats

- **Format 115** - Stata 12
- **Format 117** - Stata 13-14  
- **Format 118** - Stata 15-17

## Data Type Mapping

| Stata Type | Java Type | Notes |
|------------|-----------|-------|
| byte | Byte | Missing value (127) represented as null |
| int | Short | Missing value (32767) represented as null |
| long | Integer | Missing value (2147483647) represented as null |
| float | Float | Missing value (NaN) represented as null |
| double | Double | Missing value (NaN) represented as null |
| str1-str244 | String | Null-terminated, trimmed |

## Error Handling

The library throws two types of checked exceptions:

- `IOException` - For I/O errors during file reading
- `StataFormatException` - For invalid or unsupported Stata file formats

Both should be caught and handled appropriately in your application.

## Thread Safety

StataReader instances are **not thread-safe**. Each thread should create its own StataReader instance.

## Performance Considerations

- The entire dataset is loaded into memory when `read()` is called
- For large datasets, ensure sufficient heap memory is available
- Consider using the `getObservation(int index)` method to access individual observations rather than loading all data at once

## Limitations

- Value labels are currently not parsed
- Does not support writing Stata files
- Does not support Stata file formats older than 115 (Stata 11 and earlier)
- Does not support format 119 and later (Stata 18+) yet
