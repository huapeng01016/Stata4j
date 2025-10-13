# Stata4j
Java library for Stata data management

## Overview

Stata4j is a Java library that enables reading and working with Stata dataset files (.dta format). It supports Stata format versions 117, 118, and 119, corresponding to Stata 13 and later versions.

## Features

- Read Stata .dta dataset files
- Access variable names, types, and labels
- Retrieve observation data
- Support for various Stata data types (byte, int, long, float, double, string)
- Simple and intuitive API

## Building

The project uses Maven for build management:

```bash
mvn clean compile
```

To run tests:

```bash
mvn test
```

To create a JAR package:

```bash
mvn package
```

## Usage

### Basic Example

```java
import io.github.huapeng01016.stata4j.StataDatasetReader;

public class Example {
    public static void main(String[] args) throws Exception {
        // Create a reader for your Stata dataset
        StataDatasetReader reader = new StataDatasetReader("data.dta");
        
        // Read the dataset
        reader.read();
        
        // Print dataset summary
        reader.printSummary();
        
        // Print first 10 observations
        reader.printData(10);
    }
}
```

### Advanced Usage

```java
import io.github.huapeng01016.stata4j.StataDatasetReader;
import java.util.List;

public class AdvancedExample {
    public static void main(String[] args) throws Exception {
        StataDatasetReader reader = new StataDatasetReader("data.dta");
        reader.read();
        
        // Get dataset information
        int numVars = reader.getNumberOfVariables();
        int numObs = reader.getNumberOfObservations();
        System.out.println("Dataset has " + numObs + " observations and " + numVars + " variables");
        
        // Get variable names
        List<String> varNames = reader.getVariableNames();
        System.out.println("Variables: " + varNames);
        
        // Get variable types
        List<StataDatasetReader.VariableType> varTypes = reader.getVariableTypes();
        
        // Access specific values
        Object value = reader.getValue(0, 0);  // First observation, first variable
        System.out.println("Value at [0,0]: " + value);
        
        // Access by variable name
        Object valueByName = reader.getValue(0, "varname");
        System.out.println("Value for variable 'varname' in first observation: " + valueByName);
        
        // Get all data
        Object[][] allData = reader.getData();
    }
}
```

### Running the Example Program

After building the project, you can run the example program:

```bash
java -cp target/stata4j-1.0.0.jar io.github.huapeng01016.stata4j.StataDatasetExample <path-to-dta-file>
```

## API Reference

### StataDatasetReader Class

#### Constructor
- `StataDatasetReader(String filePath)` - Creates a new reader for the specified Stata dataset file

#### Methods
- `void read()` - Reads the dataset file and loads all data into memory
- `int getNumberOfVariables()` - Returns the number of variables in the dataset
- `int getNumberOfObservations()` - Returns the number of observations in the dataset
- `List<String> getVariableNames()` - Returns a list of variable names
- `List<VariableType> getVariableTypes()` - Returns a list of variable types
- `List<String> getVariableLabels()` - Returns a list of variable labels
- `Object[][] getData()` - Returns all data as a 2D array [observations][variables]
- `Object getValue(int observation, int variable)` - Gets a specific value by indices
- `Object getValue(int observation, String variableName)` - Gets a specific value by observation index and variable name
- `void printSummary()` - Prints a summary of the dataset
- `void printData(int numRows)` - Prints the first n rows of data

### Variable Types

The library supports the following Stata variable types:
- `BYTE` - 1-byte integer
- `INT` - 2-byte integer
- `LONG` - 4-byte integer
- `FLOAT` - 4-byte floating point
- `DOUBLE` - 8-byte floating point
- `STRING` - Variable-length string

## Requirements

- Java 8 or higher
- Maven 3.x (for building)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please feel free to submit issues or pull requests.

## Author

Hua Peng
