package org.example.workersafety_server.exception;

public final class DatabaseErrors {
    private DatabaseErrors() { }
    public static boolean isBusy(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) {
                int code = sql.getErrorCode() & 0xff;
                if (code == 5 || code == 6) return true;
            }
        }
        return false;
    }
}
