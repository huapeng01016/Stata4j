# Getting Started with Stata4j

## Quick Start

Stata4j is a Java library for reading Stata dataset (.dta) files. This guide will help you get started quickly.

## Prerequisites

- Java 8 or higher
- Maven 3.x (for building from source)

## Installation

### Building from Source

1. Clone the repository:
```bash
git clone https://github.com/huapeng01016/Stata4j.git
cd Stata4j
```

2. Build the project:
```bash
mvn clean package
```

This will create `stata4j-1.0.0.jar` in the `target/` directory.

## Basic Usage

### 1. Create a Simple Java Program

Create a file named `ReadStata.java`:

```java
import io.github.huapeng01016.stata4j.StataDatasetReader;
import java.io.IOException;

public class ReadStata {
    public static void main(String[] args) {
        try {
            // Create a reader
            StataDatasetReader reader = new StataDatasetReader("mydata.dta");
            
            // Read the dataset
            reader.read();
            
            // Print summary
            reader.printSummary();
            
            // Print first 10 observations
            System.out.println("\nData:");
            reader.printData(10);
            
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
```

### 2. Compile and Run

```bash
# Compile
javac -cp stata4j-1.0.0.jar ReadStata.java

# Run
java -cp .:stata4j-1.0.0.jar ReadStata
```

On Windows:
```cmd
javac -cp stata4j-1.0.0.jar ReadStata.java
java -cp .;stata4j-1.0.0.jar ReadStata
```

## Working with Data

### Accessing Dataset Information

```java
StataDatasetReader reader = new StataDatasetReader("data.dta");
reader.read();

// Get basic information
int numVars = reader.getNumberOfVariables();
int numObs = reader.getNumberOfObservations();
System.out.println("Dataset: " + numObs + " observations, " + numVars + " variables");

// Get variable names
List<String> varNames = reader.getVariableNames();
for (String name : varNames) {
    System.out.println("Variable: " + name);
}
```

### Reading Specific Values

```java
// Read value by indices (observation 0, variable 0)
Object value = reader.getValue(0, 0);

// Read value by variable name
Object valueByName = reader.getValue(0, "age");
System.out.println("Age of first person: " + valueByName);
```

### Getting All Data

```java
// Get all data as a 2D array
Object[][] allData = reader.getData();

// Process the data
for (int i = 0; i < reader.getNumberOfObservations(); i++) {
    for (int j = 0; j < reader.getNumberOfVariables(); j++) {
        System.out.print(allData[i][j] + "\t");
    }
    System.out.println();
}
```

## Supported Stata Formats

Stata4j currently supports:
- Stata 13 format (version 117)
- Stata 14 format (version 118)
- Stata 15+ format (version 119)

## Variable Types

The library handles the following Stata variable types:
- `BYTE` - 1-byte integer
- `INT` - 2-byte integer  
- `LONG` - 4-byte integer
- `FLOAT` - 4-byte floating point
- `DOUBLE` - 8-byte floating point
- `STRING` - Variable-length string

## Common Tasks

### Check Variable Types

```java
List<StataDatasetReader.VariableType> types = reader.getVariableTypes();
List<String> names = reader.getVariableNames();

for (int i = 0; i < names.size(); i++) {
    System.out.println(names.get(i) + " is type " + types.get(i));
}
```

### Export to CSV (Simple Example)

```java
import java.io.PrintWriter;

StataDatasetReader reader = new StataDatasetReader("data.dta");
reader.read();

try (PrintWriter writer = new PrintWriter("output.csv")) {
    // Write header
    writer.println(String.join(",", reader.getVariableNames()));
    
    // Write data
    Object[][] data = reader.getData();
    for (int i = 0; i < reader.getNumberOfObservations(); i++) {
        StringBuilder row = new StringBuilder();
        for (int j = 0; j < reader.getNumberOfVariables(); j++) {
            if (j > 0) row.append(",");
            row.append(data[i][j]);
        }
        writer.println(row);
    }
}
```

## Need Help?

- Check the main [README.md](README.md) for API documentation
- Report issues on GitHub: https://github.com/huapeng01016/Stata4j/issues
- Review the test file for more examples: `src/test/java/io/github/huapeng01016/stata4j/StataDatasetReaderTest.java`

## Example Programs

The repository includes example programs in `src/main/java/io/github/huapeng01016/stata4j/`:

- `StataDatasetExample.java` - Command-line tool to read and display Stata files

Run the example:
```bash
java -cp target/stata4j-1.0.0.jar io.github.huapeng01016.stata4j.StataDatasetExample mydata.dta
```
