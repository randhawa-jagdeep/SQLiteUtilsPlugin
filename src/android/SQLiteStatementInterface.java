package org.apache.cordova.sqliteUtilsPlugin;

public interface SQLiteStatementInterface {
    void bindDouble(int i, double v) throws java.sql.SQLException;

    void bindInteger(int i, int i1) throws java.sql.SQLException;

    void bindLong(int i, long l) throws java.sql.SQLException;

    void bindNull(int i) throws java.sql.SQLException;

    void bindTextNativeString(int i, java.lang.String s) throws java.sql.SQLException;

    boolean step() throws java.sql.SQLException;

    int getColumnCount() throws java.sql.SQLException;

    java.lang.String getColumnName(int i) throws java.sql.SQLException;

    double getColumnDouble(int i) throws java.sql.SQLException;

    int getColumnInteger(int i) throws java.sql.SQLException;

    long getColumnLong(int i) throws java.sql.SQLException;

    java.lang.String getColumnTextNativeString(int i) throws java.sql.SQLException;

    int getColumnType(int i) throws java.sql.SQLException;

    void dispose() throws java.sql.SQLException;
}

