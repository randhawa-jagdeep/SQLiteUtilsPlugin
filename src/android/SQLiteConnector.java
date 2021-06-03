package org.apache.cordova.sqliteUtilsPlugin;

import java.sql.SQLException;

public class SQLiteConnector implements SQLiteConnectionFactory {
    static boolean isLibLoaded;

    public SQLiteConnector() { /* compiled code */ }


    @Override
    public SQLiteConnection newSQLiteConnection(String s, int i) throws SQLException {
        return null;
    }
}