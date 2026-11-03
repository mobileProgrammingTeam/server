package org.example.workersafety_server.exception;

public final class DatabaseErrors {
    private DatabaseErrors() { }
    public static boolean isBusy(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) {
                String state = sql.getSQLState();
                if ("55P03".equals(state) || "40001".equals(state) || "40P01".equals(state)) return true;
            }
        }
        return false;
    }
}
