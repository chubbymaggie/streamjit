package edu.mit.streamjit.tuner;

import static com.google.common.base.Preconditions.checkNotNull;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.google.common.base.Splitter;

import edu.mit.streamjit.api.CompiledStream;
import edu.mit.streamjit.api.Input;
import edu.mit.streamjit.api.OneToOneElement;
import edu.mit.streamjit.api.Output;
import edu.mit.streamjit.api.StreamCompiler;
import edu.mit.streamjit.impl.common.Configuration;
import edu.mit.streamjit.impl.common.Configuration.IntParameter;
import edu.mit.streamjit.impl.common.Configuration.Parameter;
import edu.mit.streamjit.impl.common.Configuration.SwitchParameter;
import edu.mit.streamjit.impl.compiler.CompilerStreamCompiler;
import edu.mit.streamjit.impl.distributed.DistributedStreamCompiler;
import edu.mit.streamjit.test.Benchmark;
import edu.mit.streamjit.test.Benchmark.Dataset;
import edu.mit.streamjit.test.Benchmarker;
import edu.mit.streamjit.test.Datasets;
import edu.mit.streamjit.tuner.ConfigGenerator.sqliteAdapter;

/**
 * {@link RunApp} reads configuration, streamJit's app name and location
 * information from streamjit.db based on the passed arguments, runs the
 * streamJit app and update the database with the execution time. StreamJit's
 * opentuner Python script calls this to run the streamJit application.
 *
 * @author Sumanan sumanan@mit.edu
 * @since Sep 10, 2013
 *
 */
public class RunApp {

	/**
	 * @param args
	 *            args[0] - program name. args[1] - tune round.
	 * @throws SQLException
	 */
	public static void main(String[] args) throws SQLException {
		if (args.length < 2)
			throw new IllegalArgumentException(
					"Not enough arguments to run the app");

		String benchmarkName = args[0];
		int round = Integer.parseInt(args[1]);

		System.out.println(String.format("JAVA Executing: %s Round - %d",
				benchmarkName, round));

		String dbPath = "streamjit.db";

		sqliteAdapter sqlite;
		try {
			sqlite = new sqliteAdapter();
		} catch (ClassNotFoundException e) {
			System.err
					.println("Sqlite3 database not found...couldn't update the database with the configutaion.");
			e.printStackTrace();
			return;
		}
		sqlite.connectDB(dbPath);

		ResultSet result = sqlite.executeQuery(String.format(
				"SELECT * FROM apps WHERE name='%s'", benchmarkName));

		String confgString = result.getString("configuration");

		String sjDbPath = "sj" + benchmarkName + ".db";
		sqliteAdapter sjDb;
		try {
			sjDb = new sqliteAdapter();
		} catch (ClassNotFoundException e1) {
			// Actually this exception will not occur. If Sqlite3 did not
			// exists then it would have exit at previous return point.
			System.err
					.println("Sqlite3 database not found...couldn't update the database with the configutaion.");
			e1.printStackTrace();
			return;
		}
		sjDb.connectDB(sjDbPath);

		ResultSet result1 = sjDb.executeQuery(String.format(
				"SELECT * FROM results WHERE Round=%d", round));

		String pyDict = result1.getString("SJConfig");

		Configuration config = Configuration
				.fromJson(getConfigurationString(confgString));

		Configuration cfg2 = rebuildConfiguration(pyDict, config);

		Benchmark app = Benchmarker.getBenchmarkByName(benchmarkName);
		double time = 0;
		MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
		try {
			time = runApp(app, cfg2);
		} catch (InterruptedException e) {
			e.printStackTrace();
			time = -1;
		} catch (Exception e) {
			e.printStackTrace();
			time = -2;
		} catch (OutOfMemoryError er) {
			System.out.println("******OutOfMemoryError******");
			MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
			int MEGABYTE = 1024 * 1024;
			long maxMemory = heapUsage.getMax() / MEGABYTE;
			long usedMemory = heapUsage.getUsed() / MEGABYTE;
			System.out.println("Memory Use :" + usedMemory + "M/" + maxMemory
					+ "M");
			time = -3;
		}

		System.out.println(String.format("RunApp : Execution time - %f", time));

		String qry = String.format(
				"UPDATE results SET Exectime=%f WHERE Round=%d", time, round);
		sjDb.executeUpdate(qry);
	}

	private static String getConfigurationString(String s) {
		String s1 = s.replaceAll("__class__", "ttttt");
		String s2 = s1.replaceAll("javaClassPath", "class");
		String s3 = s2.replaceAll("ttttt", "__class__");
		return s3;
	}

	private static double runApp(Benchmark app, Configuration cfg)
			throws InterruptedException {
		StreamCompiler sc;
		IntParameter p = cfg.getParameter("noOfMachines", IntParameter.class);
		if (p == null) {
			CompilerStreamCompiler csc = new CompilerStreamCompiler();
			csc.setConfig(cfg);
			sc = csc;
		} else {
			sc = new DistributedStreamCompiler(p.getValue(), cfg);
		}
		Dataset dataset = app.inputs().get(0);

		Input<Object> input = dataset.input();
		// Input<Object> input = Datasets.nCopies(10, dataset.input());
		// Output<Object> output = Output.blackHole();
		Output<Object> output = Output.<Object> toPrintStream(System.out);

		long startTime = System.nanoTime();
		run(sc, app.instantiate(), input, output);
		long endTime = System.nanoTime();
		double diff = (endTime - startTime) / 1e6;
		return diff;
	}

	private static void run(StreamCompiler compiler,
			OneToOneElement<Object, Object> streamGraph, Input<Object> input,
			Output<Object> output) {
		CompiledStream stream = compiler.compile(streamGraph, input, output);
		try {
			stream.awaitDrained();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Creates a new {@link Configuration} from the received python dictionary
	 * string. This is not a good way to do.
	 * <p>
	 * TODO: Need to add a method to {@link Configuration} so that the
	 * configuration object can be updated from the python dict string. Now we
	 * are destructing the old confg object and recreating a new one every time.
	 * Not a appreciatable way.
	 *
	 * @param pythonDict
	 *            Python dictionary string. Autotuner gives a dictionary of
	 *            features with trial values.
	 * @param config
	 *            Old configuration object.
	 * @return New configuration object with updated values from the pythonDict.
	 */
	private static Configuration rebuildConfiguration(String pythonDict,
			Configuration config) {
		// System.out.println(pythonDict);
		checkNotNull(pythonDict, "Received Python dictionary is null");
		pythonDict = pythonDict.replaceAll("u'", "");
		pythonDict = pythonDict.replaceAll("':", "");
		pythonDict = pythonDict.replaceAll("\\{", "");
		pythonDict = pythonDict.replaceAll("\\}", "");
		Splitter dictSplitter = Splitter.on(", ").omitEmptyStrings()
				.trimResults();
		Configuration.Builder builder = Configuration.builder();
		System.out.println("New parameter values from Opentuner...");
		for (String s : dictSplitter.split(pythonDict)) {
			String[] str = s.split(" ");
			if (str.length != 2)
				throw new AssertionError("Wrong python dictionary...");
			Parameter p = config.getParameter(str[0]);
			if (p == null)
				continue;
			if (p instanceof IntParameter) {
				IntParameter ip = (IntParameter) p;
				builder.addParameter(new IntParameter(ip.getName(),
						ip.getMin(), ip.getMax(), Integer.parseInt(str[1])));

			} else if (p instanceof SwitchParameter<?>) {
				SwitchParameter sp = (SwitchParameter) p;
				Class<?> type = sp.getGenericParameter();
				int val = Integer.parseInt(str[1]);
				SwitchParameter<?> sp1 = new SwitchParameter(sp.getName(),
						type, sp.getUniverse().get(val), sp.getUniverse());
				builder.addParameter(sp1);
			}
		}
		return builder.build();
	}
}
