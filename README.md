# Stata4j
Java library for Stata data management

## Features

- Read Stata dataset files (.dta)
- Support for Stata file formats 115, 117, and 118 (Stata 12-17)
- Handle both little-endian and big-endian byte orders
- Support for all Stata variable types (numeric and string)
- Clean API with automatic resource management

## Installation

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.huapeng01016</groupId>
    <artifactId>stata4j</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Usage

### Reading a Stata Dataset

```java
import io.github.huapeng01016.stata4j.StataReader;
import java.util.List;
import java.util.Map;

// Read a Stata file
try (StataReader reader = new StataReader("data.dta")) {
    reader.read();
    
    // Get metadata
    System.out.println("Format: " + reader.getFormat());
    System.out.println("Variables: " + reader.getNumVars());
    System.out.println("Observations: " + reader.getNumObs());
    System.out.println("Variable names: " + reader.getVarNames());
    
    // Access data
    List<Map<String, Object>> data = reader.getData();
    for (Map<String, Object> observation : data) {
        System.out.println(observation);
    }
    
    // Access specific observation
    Map<String, Object> firstObs = reader.getObservation(0);
    System.out.println("First observation: " + firstObs);
}
```

### Available Methods

- `read()` - Read and parse the Stata dataset
- `getFormat()` - Get the Stata file format version
- `getNumVars()` - Get the number of variables
- `getNumObs()` - Get the number of observations
- `getDatasetLabel()` - Get the dataset label
- `getTimestamp()` - Get the timestamp
- `getVarNames()` - Get the list of variable names
- `getVarLabels()` - Get the list of variable labels
- `getVarTypes()` - Get the list of variable types
- `getData()` - Get all observations as a list of maps
- `getObservation(int index)` - Get a specific observation by index

## Supported Formats

Stata4j supports the following Stata file formats:

- **Format 115** - Stata 12
- **Format 117** - Stata 13-14
- **Format 118** - Stata 15-17

## Variable Types

The library supports all Stata variable types:

- **Numeric types**: byte, int, long, float, double
- **String types**: str1 through str244

## Building from Source

```bash
mvn clean install
```

## Running Tests

```bash
mvn test
```

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
