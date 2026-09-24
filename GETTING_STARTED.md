# Getting Started with Stata4j

## Quick Start

Stata4j is a Java library for reading Stata dataset (.dta) files. This guide will help you get started quickly.

## Prerequisites

- Java 17 or higher
- No Maven install is needed; the repository includes the Maven Wrapper (`mvnw` / `mvnw.cmd`)

## Installation

### Building from Source

1. Clone the repository:
```bash
git clone https://github.com/huapeng01016/Stata4j.git
cd Stata4j
```

2. Build the project:
```bash
./mvnw clean package
```

On Windows (cmd or PowerShell), use `mvnw.cmd clean package`.

This will create `stata4j-0.1.0.jar` in the `target/` directory.

## Basic Usage

### 1. Create a Simple Java Program

Create a file named `ReadStata.java`:

```java
import io.github.huapeng01016.stata4j.StataFormatException;
import io.github.huapeng01016.stata4j.StataReader;
import java.io.IOException;
import java.util.Map;

public class ReadStata {
    public static void main(String[] args) {
        try (StataReader reader = new StataReader("mydata.dta")) {
            reader.read();
            
            System.out.println("Format: " + reader.getFormat());
            System.out.println(reader.getNumObs() + " observations, " + reader.getNumVars() + " variables");
            
            // Print first 10 observations
            int rows = Math.min(10, reader.getNumObs());
            for (int i = 0; i < rows; i++) {
                Map<String, Object> obs = reader.getObservation(i);
                System.out.println(obs);
            }
        } catch (IOException | StataFormatException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
}
```

### 2. Compile and Run

```bash
# Compile
javac -cp stata4j-0.1.0.jar ReadStata.java

# Run
java -cp .:stata4j-0.1.0.jar ReadStata
```

On Windows:
```cmd
javac -cp stata4j-0.1.0.jar ReadStata.java
java -cp .;stata4j-0.1.0.jar ReadStata
```

## Working with Data

### Accessing Dataset Information

```java
try (StataReader reader = new StataReader("data.dta")) {
    reader.read();

    System.out.println("Dataset: " + reader.getNumObs() + " observations, "
        + reader.getNumVars() + " variables");

    for (String name : reader.getVarNames()) {
        System.out.println("Variable: " + name);
    }
}
```

### Reading Specific Values

```java
// Read value by indices (observation 0, variable 0)
Object value = reader.getValue(0, 0);

// Read value by variable name
Object age = reader.getValue(0, "age");
System.out.println("Age of first person: " + age);
```

Missing values (`.`, `.a` to `.z`) are returned as `null`.

### Check Variable Types

```java
List<StataVarType> types = reader.getVarTypes();
List<String> names = reader.getVarNames();

for (int i = 0; i < names.size(); i++) {
    System.out.println(names.get(i) + " is type " + types.get(i)); // e.g. "name is type str20"
}
```

### Export to CSV (Simple Example)

```java
import java.io.PrintWriter;

try (StataReader reader = new StataReader("data.dta");
     PrintWriter writer = new PrintWriter("output.csv")) {
    reader.read();

    // Write header
    writer.println(String.join(",", reader.getVarNames()));

    // Write data (missing values as empty cells)
    for (Map<String, Object> obs : reader.getData()) {
        StringBuilder row = new StringBuilder();
        for (Object v : obs.values()) {
            if (row.length() > 0) row.append(",");
            row.append(v == null ? "" : v);
        }
        writer.println(row);
    }
}
```

## Supported Stata Formats

Stata4j supports formats 113-115 (Stata 8-12) and 117-121 (Stata 13 and later), in either byte order. See [API.md](API.md) for the full type mapping.

## Need Help?

- Check [API.md](API.md) for the full API reference
- Report issues on GitHub: https://github.com/huapeng01016/Stata4j/issues
- Review the tests for more examples: `src/test/java/io/github/huapeng01016/stata4j/StataReaderTest.java`

## Example Program

`src/main/java/io/github/huapeng01016/stata4j/example/StataReaderExample.java` is a command-line tool that prints a file's metadata, value labels and first observations:

```bash
java -cp target/stata4j-0.1.0.jar io.github.huapeng01016.stata4j.example.StataReaderExample mydata.dta
```
