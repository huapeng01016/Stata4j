package io.github.huapeng01016.stata4j;

import java.io.IOException;

/**
 * Example usage of StataDatasetReader
 */
public class StataDatasetExample {
    
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java StataDatasetExample <path-to-dta-file>");
            System.out.println("\nExample:");
            System.out.println("  java StataDatasetExample data.dta");
            return;
        }
        
        String filePath = args[0];
        
        try {
            // Create reader instance
            StataDatasetReader reader = new StataDatasetReader(filePath);
            
            // Read the dataset
            System.out.println("Reading Stata dataset: " + filePath);
            reader.read();
            
            // Print summary
            reader.printSummary();
            
            // Print first 10 observations
            System.out.println("\nFirst observations:");
            reader.printData(10);
            
            // Access specific values
            if (reader.getNumberOfObservations() > 0 && reader.getNumberOfVariables() > 0) {
                System.out.println("\nExample - accessing first observation, first variable:");
                System.out.println("Value: " + reader.getValue(0, 0));
            }
            
        } catch (IOException e) {
            System.err.println("Error reading Stata file: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
