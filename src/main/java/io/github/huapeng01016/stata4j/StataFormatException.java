package io.github.huapeng01016.stata4j;

/**
 * Exception thrown when a Stata file format error is encountered.
 */
public class StataFormatException extends Exception {
    
    public StataFormatException(String message) {
        super(message);
    }
    
    public StataFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
