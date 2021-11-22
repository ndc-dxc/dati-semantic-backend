package it.teamdigitale.ndc.harvester.exception;

public class InvalidAssetException extends RuntimeException {
    public InvalidAssetException(String message) {
        super(message);
    }

    public InvalidAssetException(String message, Throwable cause) {
        super(message, cause);
    }
}
