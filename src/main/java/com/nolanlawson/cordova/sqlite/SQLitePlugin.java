package com.nolanlawson.cordova.sqlite;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Author: Nolan Lawson License: Apache 2
 */
public class SQLitePlugin extends CordovaPlugin {

	private static final boolean DEBUG_MODE = false;

	private static final String TAG = SQLitePlugin.class.getSimpleName();

	private static final Object[][] EMPTY_ROWS = new Object[][] {};
	private static final String[] EMPTY_COLUMNS = new String[] {};
	private static final SQLitePLuginResult EMPTY_RESULT = new SQLitePLuginResult(EMPTY_ROWS, EMPTY_COLUMNS, 0, 0,
			null);

	private static final Map<String, java.sql.Connection> DATABASES = new HashMap<String, java.sql.Connection>();

	private Thread backgroundHandler;

	@Override
	public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
		debug("execute(%s)", action);
		this.run(args, callbackContext);
		return true;
	}

	private void run(final JSONArray args, final CallbackContext callbackContext) {
//		backgroundHandler = new Thread(new Runnable() {
//			@Override
//			public void run() {
//				
//			}
//		});
//		backgroundHandler.start();
		try {
			runInBackground(args, callbackContext);
		} catch (Throwable e) {
			e.printStackTrace(); // should never happen
			callbackContext.error(e.getMessage());
		}
		
	}

	private void runInBackground(JSONArray args, CallbackContext callbackContext) throws JSONException {
		SQLitePLuginResult[] pluginResults = execInBackgroundAndReturnResults(args);
		callbackContext.success(pluginResultsToString(pluginResults));
	}

	private SQLitePLuginResult[] execInBackgroundAndReturnResults(JSONArray args) throws JSONException {

		String dbName = args.getString(0);
		JSONArray queries = args.getJSONArray(1);
		boolean readOnly = args.getBoolean(2);
		int numQueries = queries.length();
		SQLitePLuginResult[] results = new SQLitePLuginResult[numQueries];
		java.sql.Connection db = getDatabase(dbName);

		for (int i = 0; i < numQueries; i++) {
			JSONArray sqlQuery = queries.getJSONArray(i);
			String sql = sqlQuery.getString(0);
			String[] bindArgs = jsonArrayToStringArray(sqlQuery.getJSONArray(1));
			try {
				if (isSelect(sql)) {
					results[i] = doSelectInBackgroundAndPossiblyThrow(sql, bindArgs, db);
				} else { // update/insert/delete
					if (readOnly) {
						results[i] = new SQLitePLuginResult(EMPTY_ROWS, EMPTY_COLUMNS, 0, 0, new ReadOnlyException());
					} else {
						results[i] = doUpdateInBackgroundAndPossiblyThrow(sql, bindArgs, db);
					}
				}
			} catch (Throwable e) {
				if (DEBUG_MODE) {
					e.printStackTrace();
				}
				results[i] = new SQLitePLuginResult(EMPTY_ROWS, EMPTY_COLUMNS, 0, 0, e);
			}
		}
		return results;
	}

	// do a update/delete/insert operation
	private SQLitePLuginResult doUpdateInBackgroundAndPossiblyThrow(String sql, String[] bindArgs,
			java.sql.Connection db) {
		debug("\"run\" query: %s", sql);
		java.sql.PreparedStatement statement = null;
		try {
			statement = db.prepareStatement(sql);
			debug("compiled statement");
			if (bindArgs != null) {
				bindAllArguments(statement, bindArgs);
			}
			debug("bound args");
			if (isInsert(sql)) {
				debug("type: insert");
				long insertId = statement.executeUpdate();
				int rowsAffected = insertId >= 0 ? 1 : 0;
				return new SQLitePLuginResult(EMPTY_ROWS, EMPTY_COLUMNS, rowsAffected, insertId, null);
			} else if (isDelete(sql) || isUpdate(sql)) {
				debug("type: update/delete");
				int rowsAffected = statement.executeUpdate();
				return new SQLitePLuginResult(EMPTY_ROWS, EMPTY_COLUMNS, rowsAffected, 0, null);
			} else {
				// in this case, we don't need rowsAffected or insertId, so we can have a slight
				// perf boost by just executing the query
				debug("type: drop/create/etc.");
				statement.execute();
				return EMPTY_RESULT;
			}
		} catch (java.sql.SQLException e) {
			throw new RuntimeException(e);
		} finally {
			if (statement != null) {
				try {
					statement.close();
				} catch (SQLException e) {
				}
			}
		}
	}

	// do a select operation
	private SQLitePLuginResult doSelectInBackgroundAndPossiblyThrow(String sql, String[] bindArgs,
			java.sql.Connection db) {
		debug("\"all\" query: %s", sql);
		java.sql.ResultSet cursor = null;
		try {
			java.sql.PreparedStatement prepareStatement = db.prepareStatement(sql);

			for (int j = 0; j < bindArgs.length; j++) {
				prepareStatement.setString(j + 1, bindArgs[j]);
			}
			cursor = prepareStatement.executeQuery();

			if (!(cursor != null && cursor.next())) {
				return EMPTY_RESULT;
			}

			java.sql.ResultSetMetaData rsmd = cursor.getMetaData();
			int numColumns = rsmd.getColumnCount();
			java.util.List<Object[]> rows = new java.util.ArrayList<>();
			String[] columnNames = new String[numColumns];

			do {
				java.util.List<Object> values = new java.util.ArrayList<>();
				for (int i = 0; i < numColumns; ++i) {
					columnNames[i] = rsmd.getColumnLabel(i + 1);
					values.add(getValueFromCursor(cursor, i + 1));
				}
				rows.add(values.toArray());
			} while (cursor.next());
			return new SQLitePLuginResult(rows.toArray(new Object[0][0]), columnNames, 0, 0, null);
		} catch (java.sql.SQLException e) {
			throw new RuntimeException(e);
		} finally {
			if (cursor != null) {
				try {
					cursor.close();
				} catch (SQLException e) {
				}
			}
		}
	}

	private Object getValueFromCursor(java.sql.ResultSet cursor, int index) {
		try {
			return cursor.getObject(index);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	private java.sql.Connection getDatabase(String name) {
		try {
			Class.forName("org.sqlite.JDBC");
			java.sql.Connection database = DATABASES.get(name);

			if (database == null) {
				database = DriverManager.getConnection("jdbc:sqlite:src/" + name + ".db");
				database.setAutoCommit(false);
				DATABASES.put(name, database);
			}
			return database;
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	private static void debug(String line, Object... format) {
		if (DEBUG_MODE) {
			System.out.println(String.format(line, format));
		}
	}

	private static String pluginResultsToString(SQLitePLuginResult[] results) throws JSONException {
		// Instead of converting to a json array, we convert directly to a string
		// because the perf ends up being better, since Cordova will just stringify
		// the JSONArray anyway.
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < results.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			appendPluginResult(results[i], sb);
		}
		return sb.append(']').toString();
	}

	private static void appendPluginResult(SQLitePLuginResult result, StringBuilder sb) throws JSONException {
		sb.append('[');
		if (result.error == null) {
			sb.append("null");
		} else {
			sb.append(JSONObject.quote(result.error.getMessage()));
		}
		sb.append(',').append(JSONObject.numberToString(result.insertId)).append(',')
				.append(JSONObject.numberToString(result.rowsAffected)).append(',');

		// column names
		sb.append('[');
		for (int i = 0; i < result.columns.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(JSONObject.quote(result.columns[i]));
		}
		sb.append("],[");
		// rows
		for (int i = 0; i < result.rows.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			Object[] values = result.rows[i];
			// row content
			sb.append('[');
			for (int j = 0; j < values.length; j++) {
				if (j > 0) {
					sb.append(',');
				}
				Object value = values[j];
				if (value == null) {
					sb.append("null");
				} else if (value instanceof String) {
					sb.append(JSONObject.quote((String) value));
				} else if (value instanceof Boolean) {
					sb.append(value.toString());
				} else {
					sb.append(JSONObject.numberToString((Number) value));
				}
			}
			sb.append(']');
		}
		sb.append("]]");

		debug("returning json: %s", sb);
	}

	private static boolean isSelect(String str) {
		return startsWithCaseInsensitive(str, "select");
	}

	private static boolean isInsert(String str) {
		return startsWithCaseInsensitive(str, "insert");
	}

	private static boolean isUpdate(String str) {
		return startsWithCaseInsensitive(str, "update");
	}

	private static boolean isDelete(String str) {
		return startsWithCaseInsensitive(str, "delete");
	}

	// identify an "insert"/"select" query more efficiently than with a Pattern
	private static boolean startsWithCaseInsensitive(String str, String substr) {
		int i = -1;
		int len = str.length();
		while (++i < len) {
			char ch = str.charAt(i);
			if (!Character.isWhitespace(ch)) {
				break;
			}
		}

		int j = -1;
		int substrLen = substr.length();
		while (++j < substrLen) {
			if (j + i >= len) {
				return false;
			}
			char ch = str.charAt(j + i);
			if (Character.toLowerCase(ch) != substr.charAt(j)) {
				return false;
			}
		}
		return true;
	}

	private static String[] jsonArrayToStringArray(JSONArray jsonArray) throws JSONException {
		int len = jsonArray.length();
		String[] res = new String[len];
		for (int i = 0; i < len; i++) {
			
			res[i] = !jsonArray.isNull(i) ? toString(jsonArray.get(i)) : null;
		}
		return res;
	}

	private static String toString(Object object) {
		return object + "";
	}

	private static class SQLitePLuginResult {
		public final Object[][] rows;
		public final String[] columns;
		public final int rowsAffected;
		public final long insertId;
		public final Throwable error;

		public SQLitePLuginResult(Object[][] rows, String[] columns, int rowsAffected, long insertId, Throwable error) {
			this.rows = rows;
			this.columns = columns;
			this.rowsAffected = rowsAffected;
			this.insertId = insertId;
			this.error = error;
		}
	}

	private static class ReadOnlyException extends Exception {
		public ReadOnlyException() {
			super("could not prepare statement (23 not authorized)");
		}
	}

	private static void bindAllArguments(java.sql.PreparedStatement statement, String[] bindArgs) {
		try {
			for (int i = 0; i < bindArgs.length; i++) {
				if (bindArgs[i] == null) {
					statement.setString(i + 1, null);
				} else {
					statement.setString(i + 1, bindArgs[i]);
				}
			}
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}
}