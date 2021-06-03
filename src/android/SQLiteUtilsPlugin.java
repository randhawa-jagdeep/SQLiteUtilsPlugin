

package org.apache.cordova.sqliteUtilsPlugin;

import android.util.Log;

import java.io.File;

import java.lang.IllegalArgumentException;

import java.util.Map;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class SQLiteUtilsPlugin extends CordovaPlugin {

  
    private Map<String, DBRunner> dbMap= new ConcurrentHashMap<String, DBRunner>();


    /**
     * Executes the request and returns PluginResult.
     *
     * @param actionAsString The action to execute.
     * @param args   JSONArry of arguments for the plugin.
     * @param cbc    Callback context from Cordova API
     * @return       Whether the action was valid.
     */
    @Override
    public boolean execute(String actionAsString, JSONArray args, CallbackContext cbc) {

        Action action;
        try {
            action = Action.valueOf(actionAsString);
        } catch (IllegalArgumentException e) {
            // shouldn't ever happen
            Log.e(SQLiteUtilsPlugin.class.getSimpleName(), "unexpected error", e);
            return false;
        }

        try {
            return executeAndPossiblyThrow(action, args, cbc);
        } catch (JSONException e) {
            // TODO: signal JSON problem to JS
            Log.e(SQLiteUtilsPlugin.class.getSimpleName(), "unexpected error", e);
            return false;
        }
    }

    private boolean executeAndPossiblyThrow(Action action, JSONArray args, CallbackContext cbc)
            throws JSONException {

        boolean status = true;
        JSONObject obj;
        String echo_value;
        String dbname;

        switch (action) {
            case echoStringValue:
                obj = args.getJSONObject(0);
                echo_value = obj.getString("value");
                cbc.success(echo_value);
                break;

            case open:
                obj = args.getJSONObject(0);
                dbname = obj.getString("name");
                // open database and start reading its queue
                this.startDatabase(dbname, obj, cbc);
                break;

            case close:
                obj = args.getJSONObject(0);
                dbname = obj.getString("path");
                // put request in the q to close the db
                this.closeDatabase(dbname, cbc);
                break;

            case delete:
                obj = args.getJSONObject(0);
                dbname = obj.getString("path");

                deleteDatabase(dbname, cbc);

                break;

            case executeSqlBatch:
            case backgroundExecuteSqlBatch:
                JSONObject allargs = args.getJSONObject(0);
                JSONObject dbargs = allargs.getJSONObject("dbargs");
                dbname = dbargs.getString("dbname");
                JSONArray txargs = allargs.getJSONArray("executes");

                if (txargs.isNull(0)) {
                    cbc.error("INTERNAL PLUGIN ERROR: missing executes list");
                } else {
                    int len = txargs.length();
                    String[] queries = new String[len];
                    JSONArray[] jsonparams = new JSONArray[len];

                    for (int i = 0; i < len; i++) {
                        JSONObject a = txargs.getJSONObject(i);
                        queries[i] = a.getString("sql");
                        jsonparams[i] = a.getJSONArray("params");
                    }

                    // put db query in the queue to be executed in the db thread:
                    DBQuery q = new DBQuery(queries, jsonparams, cbc);
                    DBRunner r = dbMap.get(dbname);
                    if (r != null) {
                        try {
                            r.q.put(q);
                        } catch(Exception e) {
                            Log.e(SQLiteUtilsPlugin.class.getSimpleName(), "couldn't add to queue", e);
                            cbc.error("INTERNAL PLUGIN ERROR: couldn't add to queue");
                        }
                    } else {
                        cbc.error("INTERNAL PLUGIN ERROR: database not open");
                    }
                }
                break;
        }

        return status;
    }

    /**
     * Clean up and close all open databases.
     */
    @Override
    public void onDestroy() {
        while (!dbMap.isEmpty()) {
            String dbname = dbMap.keySet().iterator().next();

            this.closeDatabaseNow(dbname);

            DBRunner r = dbMap.get(dbname);
            try {
                // stop the db runner thread:
                r.q.put(new DBQuery());
            } catch(Exception e) {
                Log.e(SQLiteUtilsPlugin.class.getSimpleName(), "INTERNAL PLUGIN CLEANUP ERROR: could not stop db thread due to exception", e);
            }
            dbMap.remove(dbname);
        }
    }

    // --------------------------------------------------------------------------
    // LOCAL METHODS
    // --------------------------------------------------------------------------

    private void startDatabase(String dbname, JSONObject options, CallbackContext cbc) {
        DBRunner r = dbMap.get(dbname);

        if (r != null) {
            // NO LONGER EXPECTED due to BUG 666 workaround solution:
            cbc.error("INTERNAL ERROR: database already open for db name: " + dbname);
        } else {
            r = new DBRunner(dbname, options, cbc);
            dbMap.put(dbname, r);
            this.cordova.getThreadPool().execute(r);
        }
    }
    /**
     * Open a database.
     *
     * @param dbname   The name of the database file
     */
    private DBHelper openDatabase(String dbname, CallbackContext cbc, boolean old_impl) throws Exception {
        try {
            // ASSUMPTION: no db (connection/handle) is already stored in the map
            // [should be true according to the code in DBRunner.run()]

            File dbfile = this.cordova.getActivity().getDatabasePath(dbname);

            if (!dbfile.exists()) {
                dbfile.getParentFile().mkdirs();
            }

            Log.v("info", "Open sqlite db: " + dbfile.getAbsolutePath());

            DBHelper mydb = old_impl ? new DBHelper() : new DBHelper();
            mydb.open(dbfile);

            if (cbc != null) // XXX Android locking/closing BUG workaround
                cbc.success();

            return mydb;
        } catch (Exception e) {
            if (cbc != null) // XXX Android locking/closing BUG workaround
                cbc.error("can't open database " + e);
            throw e;
        }
    }

    /**
     * Close a database (in another thread).
     *
     * @param dbname   The name of the database file
     */
    private void closeDatabase(String dbname, CallbackContext cbc) {
        DBRunner r = dbMap.get(dbname);
        if (r != null) {
            try {
                r.q.put(new DBQuery(false, cbc));
            } catch(Exception e) {
                if (cbc != null) {
                    cbc.error("couldn't close database" + e);
                }
                Log.e(SQLiteUtilsPlugin.class.getSimpleName(), "couldn't close database", e);
            }
        } else {
            if (cbc != null) {
                cbc.success();
            }
        }
    }

    /**
     * Close a database (in the current thread).
     *
     * @param dbname   The name of the database file
     */
    private void closeDatabaseNow(String dbname) {
        DBRunner r = dbMap.get(dbname);

        if (r != null) {
            DBHelper mydb = r.mydb;

            if (mydb != null)
                mydb.closeDatabaseNow();
        }
    }

    private void deleteDatabase(String dbname, CallbackContext cbc) {
        DBRunner r = dbMap.get(dbname);
        if (r != null) {
            try {
                r.q.put(new DBQuery(true, cbc));
            } catch(Exception e) {
                if (cbc != null) {
                    cbc.error("couldn't close database" + e);
                }
                Log.e(SQLiteUtilsPlugin.class.getSimpleName(), "couldn't close database", e);
            }
        } else {
            boolean deleteResult = this.deleteDatabaseNow(dbname);
            if (deleteResult) {
                cbc.success();
            } else {
                cbc.error("couldn't delete database");
            }
        }
    }

    /**
     * Delete a database.
     *
     * @param dbname   The name of the database file
     *
     * @return true if successful or false if an exception was encountered
     */
    private boolean deleteDatabaseNow(String dbname) {
        File dbfile = this.cordova.getActivity().getDatabasePath(dbname);

        try {
            return cordova.getActivity().deleteDatabase(dbfile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(SQLiteUtilsPlugin.class.getSimpleName(), "couldn't delete database", e);
            return false;
        }
    }

    private class DBRunner implements Runnable {
        final String dbname;
        private boolean oldImpl;
        private boolean bugWorkaround;

        final BlockingQueue<DBQuery> q;
        final CallbackContext openCbc;

        DBHelper mydb;

        DBRunner(final String dbname, JSONObject options, CallbackContext cbc) {
            this.dbname = dbname;
            this.oldImpl = options.has("androidOldDatabaseImplementation");
            Log.v(SQLiteUtilsPlugin.class.getSimpleName(), "Android db implementation: built-in android.database.sqlite package");
            this.bugWorkaround = this.oldImpl && options.has("androidBugWorkaround");
            if (this.bugWorkaround)
                Log.v(SQLiteUtilsPlugin.class.getSimpleName(), "Android db closing/locking workaround applied");

            this.q = new LinkedBlockingQueue<DBQuery>();
            this.openCbc = cbc;
        }

        public void run() {
            try {
                this.mydb = openDatabase(dbname, this.openCbc, this.oldImpl);
            } catch (Exception e) {
                Log.e(SQLiteUtilsPlugin.class.getSimpleName(), "unexpected error, stopping db thread", e);
                dbMap.remove(dbname);
                return;
            }

            DBQuery dbq = null;

            try {
                dbq = q.take();

                while (!dbq.stop) {
                    mydb.executeSqlBatch(dbq.queries, dbq.jsonparams, dbq.cbc);

                    if (this.bugWorkaround && dbq.queries.length == 1 && dbq.queries[0] == "COMMIT")
                        mydb.bugWorkaround();

                    dbq = q.take();
                }
            } catch (Exception e) {
                Log.e(SQLiteUtilsPlugin.class.getSimpleName(), "unexpected error", e);
            }

            if (dbq != null && dbq.close) {
                try {
                    closeDatabaseNow(dbname);

                    dbMap.remove(dbname); // (should) remove ourself

                    if (!dbq.delete) {
                        dbq.cbc.success();
                    } else {
                        try {
                            boolean deleteResult = deleteDatabaseNow(dbname);
                            if (deleteResult) {
                                dbq.cbc.success();
                            } else {
                                dbq.cbc.error("couldn't delete database");
                            }
                        } catch (Exception e) {
                            Log.e(SQLiteUtilsPlugin.class.getSimpleName(), "couldn't delete database", e);
                            dbq.cbc.error("couldn't delete database: " + e);
                        }
                    }
                } catch (Exception e) {
                    Log.e(SQLiteUtilsPlugin.class.getSimpleName(), "couldn't close database", e);
                    if (dbq.cbc != null) {
                        dbq.cbc.error("couldn't close database: " + e);
                    }
                }
            }
        }
    }

    private final class DBQuery {
        // XXX TODO replace with DBRunner action enum:
        final boolean stop;
        final boolean close;
        final boolean delete;
        final String[] queries;
        final JSONArray[] jsonparams;
        final CallbackContext cbc;

        DBQuery(String[] myqueries, JSONArray[] params, CallbackContext c) {
            this.stop = false;
            this.close = false;
            this.delete = false;
            this.queries = myqueries;
            this.jsonparams = params;
            this.cbc = c;
        }

        DBQuery(boolean delete, CallbackContext cbc) {
            this.stop = true;
            this.close = true;
            this.delete = delete;
            this.queries = null;
            this.jsonparams = null;
            this.cbc = cbc;
        }

        // signal the DBRunner thread to stop:
        DBQuery() {
            this.stop = true;
            this.close = false;
            this.delete = false;
            this.queries = null;
            this.jsonparams = null;
            this.cbc = null;
        }
    }

    private static enum Action {
        echoStringValue,
        open,
        close,
        delete,
        executeSqlBatch,
        backgroundExecuteSqlBatch,
    }
}