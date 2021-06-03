package org.apache.cordova.sqliteUtilsPlugin;


public interface SQLiteConnectionFactory {
    SQLiteConnection newSQLiteConnection(String s, int i) throws java.sql.SQLException;
}