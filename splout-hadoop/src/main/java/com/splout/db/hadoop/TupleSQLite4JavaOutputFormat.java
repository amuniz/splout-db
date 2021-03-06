package com.splout.db.hadoop;

/*
 * #%L
 * Splout SQL Hadoop library
 * %%
 * Copyright (C) 2012 Datasalt Systems S.L.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;
import com.datasalt.pangool.io.ITuple;
import com.datasalt.pangool.io.Schema.Field;
import com.splout.db.common.HeartBeater;
import com.splout.db.hadoop.SQLiteOutputFormat.SQLRecordWriter;
import com.splout.db.hadoop.TableSpec.FieldIndex;

/**
 * An OutputFormat that accepts Pangool's Tuples and writes to a sqlite4Java SQLite file. The Tuples that are written to
 * it must conform to a particular schema: having a "_partition" integer field (which will then create a file named
 * "partition".db) and be a {@link NullableTuple} so that nulls are accepted as normal SQL values.
 * <p>
 * The different schemas that will be given to this OutputFormat are defined in the constructor by providing a
 * {@link TableSpec}. These TableSpec also contains information such as pre-SQL or post-SQL statements but most notably
 * contain a Schema so that a CREATE TABLE can be derived automatically from it. Note that the Schema provided to 
 * TableSpec doesn't need to contain a "_partition" field or be nullable.
 */
@SuppressWarnings("serial")
public class TupleSQLite4JavaOutputFormat extends FileOutputFormat<ITuple, NullWritable> implements
    Serializable {

	public final static String PARTITION_TUPLE_FIELD = "_partition";

	public static Log LOG = LogFactory.getLog(TupleSQLite4JavaOutputFormat.class);
	private int batchSize;

	/**
	 * Exception that is thrown if the Output Format cannot be instantiated because the specified parameters are
	 * inconsistent or invalid. The reason is the message of the Exception, and it may optionally wrap another Exception.
	 */
	public static class TupleSQLiteOutputFormatException extends Exception {

		public TupleSQLiteOutputFormatException(String cause) {
			super(cause);
		}

		public TupleSQLiteOutputFormatException(String cause, Exception e) {
			super(cause, e);
		}
	}

	// Given a {@link TableSpec}, returns the appropriated SQL CREATE TABLE...
	private static String getCreateTable(TableSpec tableSpec) throws TupleSQLiteOutputFormatException {
		String createTable = "CREATE TABLE " + tableSpec.getSchema().getName() + " (";
		for(Field field : tableSpec.getSchema().getFields()) {
			if(field.getName().equals(PARTITION_TUPLE_FIELD)) {
				continue;
			}
			if(field.getName().equals(NullableSchema.NULLS_FIELD)) {
				continue;
			}
			createTable += field.getName() + " ";
			switch(field.getType()) {
			/*
			 * This mapping is done after SQLite's documentation. For instance, SQLite doesn't have Booleans (have to be
			 * INTEGERs). It doesn't have LONGS either.
			 */
			case INT:
				createTable += "INTEGER, ";
				break;
			case LONG:
				createTable += "INTEGER, ";
				break;
			case DOUBLE:
				createTable += "REAL, ";
				break;
			case FLOAT:
				createTable += "REAL, ";
				break;
			case STRING:
				createTable += "TEXT, ";
				break;
			case BOOLEAN:
				createTable += "INTEGER, ";
				break;
			default:
				throw new TupleSQLiteOutputFormatException("Unsupported field type: " + field.getType());
			}
		}
		createTable = createTable.substring(0, createTable.length() - 2);
		return createTable += ");";
	}

	// Get all the CREATE TABLE... for a list of {@link TableSpec}
	protected static String[] getCreateTables(TableSpec... tableSpecs)
	    throws TupleSQLiteOutputFormatException {
    List<String> createTables = new ArrayList<String>();
    // First the initSQL provided by user
    for(TableSpec tableSpec : tableSpecs) {
      if(tableSpec.getInitialSQL() != null) {
        createTables.addAll(Arrays.asList(tableSpec.getInitialSQL()));
      }
    }
    // CREATE TABLE statements
		for(TableSpec tableSpec : tableSpecs) {
			createTables.add(getCreateTable(tableSpec));
		}
    // Add user preInsertsSQL if exists just after the CREATE TABLE's
		for(TableSpec tableSpec : tableSpecs) {
			if(tableSpec.getPreInsertsSQL() != null) {
				createTables.addAll(Arrays.asList(tableSpec.getPreInsertsSQL()));
			}
		}
		return createTables.toArray(new String[0]);
	}

	// Get a list of CREATE INDEX... Statements for a {@link TableSpec} list.
	protected static String[] getCreateIndexes(TableSpec... tableSpecs)
	    throws TupleSQLiteOutputFormatException {
		List<String> createIndexes = new ArrayList<String>();
    // Add user postInsertsSQL if exists just before the CREATE INDEX statements
    for(TableSpec tableSpec : tableSpecs) {
      if(tableSpec.getPostInsertsSQL() != null) {
        createIndexes.addAll(Arrays.asList(tableSpec.getPostInsertsSQL()));
      }
    }
    for(TableSpec tableSpec : tableSpecs) {
			for(FieldIndex index : tableSpec.getIndexes()) {
				for(Field field : index.getIndexFields()) {
					if(!tableSpec.getSchema().getFields().contains(field)) {
						throw new TupleSQLiteOutputFormatException("Field to index (" + index
						    + ") not contained in input schema (" + tableSpec.getSchema() + ")");
					}
				}
				// The following code is able to create indexes for one field or for multiple fields
				String createIndex = "CREATE INDEX idx_" + tableSpec.getSchema().getName() + "_";
				for(Field field : index.getIndexFields()) {
					createIndex += field.getName();
				}
				createIndex += " ON " + tableSpec.getSchema().getName() + "(";
				for(Field field : index.getIndexFields()) {
					createIndex += field.getName() + ", ";
				}
				createIndex = createIndex.substring(0, createIndex.length() - 2) + ");";
				createIndexes.add(createIndex);
			}
		}
    // Add user finalSQL if exists just after the CREATE INDEX statements
		for(TableSpec tableSpec : tableSpecs) {
			if(tableSpec.getFinalSQL() != null) {
				createIndexes.addAll(Arrays.asList(tableSpec.getFinalSQL()));
			}
		}
		return createIndexes.toArray(new String[0]);
	}

	private String[] preSQL, postSQL;
	private static AtomicLong FILE_SEQUENCE = new AtomicLong(0);

	/**
	 * This OutputFormat receives a list of {@link TableSpec}. These are the different tables that will be created. They
	 * will be identified by Pangool Tuples. The batch size is the number of SQL statements to execute before a COMMIT.
	 */
	public TupleSQLite4JavaOutputFormat(int batchSize, TableSpec... dbSpec)
	    throws TupleSQLiteOutputFormatException {
		// Generate create tables and create index statements
		preSQL = getCreateTables(dbSpec);
		postSQL = getCreateIndexes(dbSpec);
		this.batchSize = batchSize;
	}

	@Override
	public RecordWriter<ITuple, NullWritable> getRecordWriter(TaskAttemptContext context)
	    throws IOException, InterruptedException {
		return new TupleSQLRecordWriter(context);
	}

	/**
	 * A RecordWriter that accepts an Int(Partition), a Tuple and delegates to a {@link SQLRecordWriter} converting the
	 * Tuple into SQL and assigning the partition that comes in the Key.
	 */
	public class TupleSQLRecordWriter extends RecordWriter<ITuple, NullWritable> {

		// Temporary and permanent Paths for properly writing Hadoop output files
		private Map<Integer, Path> permPool = new HashMap<Integer, Path>();
		private Map<Integer, Path> tempPool = new HashMap<Integer, Path>();

		private HeartBeater heartBeater;

		// Map of prepared statements per Schema and per Partition
		Map<Integer, Map<String, SQLiteStatement>> stCache = new HashMap<Integer, Map<String, SQLiteStatement>>();
		Map<Integer, SQLiteConnection> connCache = new HashMap<Integer, SQLiteConnection>();

		long records = 0;
		private FileSystem fs;
		private Configuration conf;
		private TaskAttemptContext context;

		public TupleSQLRecordWriter(TaskAttemptContext context) {
			this.context = context;
			heartBeater = new HeartBeater(context);
			heartBeater.needHeartBeat();
			conf = context.getConfiguration();
		}

		// This method is called one time per each partition
		private void initSql(int partition) throws IOException {

			if(!FileSystem.get(conf).equals(FileSystem.getLocal(conf))) {
				// This is a trick for not having to use the DistributedCache:
				// "The child-jvm always has its current working directory added to the java.library.path and LD_LIBRARY_PATH"
				// (from http://hadoop.apache.org/docs/mapreduce/r0.22.0/mapred_tutorial.html#Task+Execution+%26+Environment)
				// So we bundle the native libs in the JAR and copy them to the working directory
				String[] mapRedLocalDirs = conf.get("mapred.local.dir").split(",");
				for(String mapRedLocaLDir : mapRedLocalDirs) {
					LOG.info("Mapred local dir: " + mapRedLocaLDir);
					File[] nativeLibs = new File(mapRedLocaLDir + "/../jars").listFiles();
					LOG.info("Examining: " + (mapRedLocaLDir + "/../jars"));
					if(nativeLibs != null) {
						for(File nativeLib : nativeLibs) {
							if((nativeLib + "").contains("sqlite")) {
								FileUtils.copyFile(nativeLib, new File(".", nativeLib.getName()));
							}
						}
						LOG.info("Found native libraries in : " + Arrays.toString(nativeLibs)
						    + ", copied to task work directory.");
						break;
					}
				}
			}

			Path outPath = FileOutputFormat.getOutputPath(context);
			fs = outPath.getFileSystem(conf);
			Path perm = new Path(FileOutputFormat.getOutputPath(context), partition + ".db");
			Path temp = conf.getLocalPath("mapred.local.dir",
			    partition + "." + FILE_SEQUENCE.incrementAndGet());
			fs.delete(perm, true); // delete old, if any
			fs.delete(temp, true); // delete old, if any
			Path local = fs.startLocalOutput(perm, temp);
			//
			try {
				permPool.put(partition, perm);
				tempPool.put(partition, temp);
				LOG.info("Initializing SQL connection [" + partition + "]");
				SQLiteConnection conn = new SQLiteConnection(new File(local.toString()));
				// Change the default temp_store_directory, otherwise we may run out of disk space as it will go to /var/tmp
				// In EMR the big disks are at /mnt
				// It suffices to set it to . as it is the tasks' work directory
				// Warning: this pragma is deprecated and may be removed in further versions, however there is no choice
				// other than recompiling SQLite or modifying the environment.
				conn.open(true);
				conn.exec("PRAGMA temp_store_directory = '" + new File(".").getAbsolutePath() + "'");
				SQLiteStatement st = conn.prepare("PRAGMA temp_store_directory");
				st.step();
				LOG.info("Changed temp_store_directory to: " + st.columnString(0));
        // journal_mode=OFF speeds up insertions
        conn.exec("PRAGMA journal_mode=OFF");
        /* page_size is one of of the most important parameters for speed up indexation.
         * SQLite performs a merge sort for sorting data before inserting it in an index.
         * The buffer SQLites uses for sorting has a size equals to
         * page_size * SQLITE_DEFAULT_TEMP_CACHE_SIZE. Unfortunately, SQLITE_DEFAULT_TEMP_CACHE_SIZE
         * is a compilation parameter. That is then fixed to the sqlite4java library used. We have
         * recompiled that library to increase SQLITE_DEFAULT_TEMP_CACHE_SIZE (up to 32000 at
         * the point of writing this lines), so, at runtime the unique way to change the buffer size
         * used for sorting is change the page_size. page_size must be changed BEFORE CREATE
         * STATEMENTS, otherwise it won't have effect. page_size should be a multiple of
         * the sector size (1024 on linux) in order to be efficient.
         **/
        conn.exec("PRAGMA page_size=8192;");
        connCache.put(partition, conn);
				// Init transaction
				for(String sql : preSQL) {
					LOG.info("Executing: " + sql);
					conn.exec(sql);
				}
				conn.exec("BEGIN");
				Map<String, SQLiteStatement> stMap = new HashMap<String, SQLiteStatement>();
				stCache.put(partition, stMap);
			} catch(SQLiteException e) {
				throw new IOException(e);
			}
		}

		@Override
		public void write(ITuple tuple, NullWritable ignore) throws IOException, InterruptedException {
			int partition = (Integer) tuple.get(PARTITION_TUPLE_FIELD);

			Object nulls = null;
			try {
				nulls = tuple.get(NullableSchema.NULLS_FIELD);
			} catch(IllegalArgumentException e) {
				throw new RuntimeException("Expected a NullableTuple but received a normal Tuple instead.");
			}

			try {
				/*
				 * Key performance trick: Cache PreparedStatements when possible. We will have one PreparedStatement per each
				 * different Tuple Schema (table).
				 */
				Map<String, SQLiteStatement> stMap = stCache.get(partition);
				if(stMap == null) {
					initSql(partition);
					stMap = stCache.get(partition);
				}

				SQLiteStatement pS = stMap.get(tuple.getSchema().getName());
				if(pS == null) {
					SQLiteConnection conn = connCache.get(partition);
					// Create a PreparedStatement according to the received Tuple
					String preparedStatement = "INSERT INTO " + tuple.getSchema().getName() + " VALUES (";
					// NOTE: tuple.getSchema().getFields().size() - 2 : quick way of skipping "_nulls" and "_partition" fields here
					for(int i = 0; i < tuple.getSchema().getFields().size() - 2; i++) {
						preparedStatement += "?, ";
					}
					preparedStatement = preparedStatement.substring(0, preparedStatement.length() - 2) + ");";
					pS = conn.prepare(preparedStatement);
					stMap.put(tuple.getSchema().getName(), pS);
				}

				int count = 1;
				for(Field field : tuple.getSchema().getFields()) {
					if(field.getName().equals(NullableSchema.NULLS_FIELD)) {
						continue;
					}
					if(field.getName().equals(PARTITION_TUPLE_FIELD)) {
						continue;
					}

					boolean isNull = false;
					if(nulls instanceof ByteBuffer) {
						ByteBuffer bB = ((ByteBuffer) nulls);
						isNull = NullableTuple.isNull(count - 1, bB.array(), bB.position());
					} else if(nulls instanceof byte[]) {
						isNull = NullableTuple.isNull(count - 1, (byte[]) nulls, 0);
					}

					if(isNull) {
						pS.bindNull(count);
					} else {
						switch(field.getType()) {

						case INT:
							pS.bind(count, (Integer) tuple.get(count - 1));
							break;
						case LONG:
							pS.bind(count, (Long) tuple.get(count - 1));
							break;
						case DOUBLE:
							pS.bind(count, (Double) tuple.get(count - 1));
							break;
						case FLOAT:
							pS.bind(count, (Float) tuple.get(count - 1));
							break;
						case STRING:
							pS.bind(count, tuple.get(count - 1).toString());
							break;
						case BOOLEAN: // Remember: In SQLite there are no booleans
							pS.bind(count, ((Boolean) tuple.get(count - 1)) == true ? 1 : 0);
						default:
							break;
						}
					}
					count++;
				}
				pS.step();
				pS.reset();

				records++;
				if(records == batchSize) {
					SQLiteConnection conn = connCache.get(partition);
					conn.exec("COMMIT");
					conn.exec("BEGIN");
					records = 0;
				}
			} catch(SQLiteException e) {
				throw new IOException(e);
			}
		}

		@Override
		public void close(TaskAttemptContext ctx) throws IOException, InterruptedException {
			try {
				if(ctx != null) {
					heartBeater.setProgress(ctx);
				}
				for(Map.Entry<Integer, SQLiteConnection> entry : connCache.entrySet()) {
					LOG.info("Closing SQL connection [" + entry.getKey() + "]");
					//
					entry.getValue().exec("COMMIT");
					if(postSQL != null) {
						LOG.info("Executing end SQL statements.");
						for(String sql : postSQL) {
							LOG.info("Executing: " + sql);
							entry.getValue().exec(sql);
						}
					}
					entry.getValue().dispose();
					// Hadoop - completeLocalOutput()
					fs.completeLocalOutput(permPool.get(entry.getKey()), tempPool.get(entry.getKey()));
				}
			} catch(SQLiteException e) {
				throw new IOException(e);
			} finally { // in any case, destroy the HeartBeater
				heartBeater.cancelHeartBeat();
			}
		}
	}
}
