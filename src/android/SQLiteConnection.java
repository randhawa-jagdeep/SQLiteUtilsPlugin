package org.apache.cordova.sqliteUtilsPlugin;

public interface SQLiteConnection {
    void dispose() throws java.sql.SQLException;

    SQLiteStatementInterface prepareStatement(java.lang.String s) throws java.sql.SQLException;

    long getLastInsertRowid() throws java.sql.SQLException;

    int getTotalChanges() throws java.sql.SQLException;
}