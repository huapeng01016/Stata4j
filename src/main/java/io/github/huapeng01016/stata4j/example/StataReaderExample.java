package io.github.huapeng01016.stata4j.example;

import io.github.huapeng01016.stata4j.StataReader;
import io.github.huapeng01016.stata4j.StataFormatException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Example demonstrating how to use the StataReader class.
 */
public class StataReaderExample {
    
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java StataReaderExample <path-to-dta-file>");
            System.exit(1);
        }
        
        String filePath = args[0];
        
        try (StataReader reader = new StataReader(filePath)) {
            // Read the dataset
            reader.read();
            
            // Print metadata
            System.out.println("Stata File Information");
            System.out.println("======================");
            System.out.println("Format: " + reader.getFormat());
            System.out.println("Dataset Label: " + reader.getDatasetLabel());
            System.out.println("Timestamp: " + reader.getTimestamp());
            System.out.println("Number of Variables: " + reader.getNumVars());
            System.out.println("Number of Observations: " + reader.getNumObs());
            System.out.println();
            
            // Print variable information
            System.out.println("Variables:");
            System.out.println("----------");
            List<String> varNames = reader.getVarNames();
            List<String> varLabels = reader.getVarLabels();
            List<String> fmtList = reader.getFmtList();
            
            for (int i = 0; i < varNames.size(); i++) {
                System.out.printf("%d. %s (%s) - %s - Format: %s%n", 
                    i + 1, 
                    varNames.get(i), 
                    reader.getVarTypes().get(i),
                    varLabels.get(i),
                    fmtList.get(i)
                );
            }
            System.out.println();
            
            // Print first few observations
            System.out.println("First 5 Observations:");
            System.out.println("---------------------");
            List<Map<String, Object>> data = reader.getData();
            int numToPrint = Math.min(5, data.size());
            
            for (int i = 0; i < numToPrint; i++) {
                System.out.printf("Observation %d: %s%n", i + 1, data.get(i));
            }
            
            if (data.size() > 5) {
                System.out.println("... (" + (data.size() - 5) + " more observations)");
            }
            
        } catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
            e.printStackTrace();
        } catch (StataFormatException e) {
            System.err.println("Invalid Stata file format: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
