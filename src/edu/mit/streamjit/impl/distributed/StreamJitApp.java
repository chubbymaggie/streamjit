package edu.mit.streamjit.impl.distributed;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import edu.mit.streamjit.api.StreamCompilationFailedException;
import edu.mit.streamjit.api.Worker;
import edu.mit.streamjit.impl.blob.BlobFactory;
import edu.mit.streamjit.impl.blob.Buffer;
import edu.mit.streamjit.impl.blob.Blob.Token;
import edu.mit.streamjit.impl.common.AbstractDrainer.BlobGraph;
import edu.mit.streamjit.impl.common.Configuration.IntParameter;
import edu.mit.streamjit.impl.common.Configuration.PartitionParameter;
import edu.mit.streamjit.impl.common.Configuration;
import edu.mit.streamjit.impl.common.MessageConstraint;
import edu.mit.streamjit.impl.common.Workers;
import edu.mit.streamjit.impl.distributed.common.GlobalConstants;
import edu.mit.streamjit.impl.distributed.common.Utils;
import edu.mit.streamjit.impl.distributed.runtimer.Controller;
import edu.mit.streamjit.impl.distributed.runtimer.OnlineTuner;
import edu.mit.streamjit.impl.interp.Interpreter;

/**
 * This class contains all information about the current streamJit application
 * including {@link BlobGraph}, current {@link Configuration}, TokenMachineMap,
 * and etc. Three main classes, {@link DistributedStreamCompiler},
 * {@link Controller} and {@link OnlineTuner} will be using this class of their
 * functional purpose.
 * 
 * @author Sumanan sumanan@mit.edu
 * @since Oct 8, 2013
 */
public class StreamJitApp {

	/**
	 * Since this is final, lets make public
	 */
	public final String topLevelClass;

	public final Worker<?, ?> source1;

	public final Worker<?, ?> sink1;

	public final String jarFilePath;

	private BlobGraph blobGraph1;

	public Map<Integer, List<Set<Worker<?, ?>>>> partitionsMachineMap1;

	private ImmutableMap<Token, Buffer> bufferMap;

	private List<MessageConstraint> constraints;

	/**
	 * Keeps track of assigned machine Ids of each blob. This information is
	 * need for draining. TODO: If possible use a better solution.
	 */
	public Map<Token, Integer> blobtoMachineMap;

	/**
	 * blobConfiguration contains decision variables that are tuned by
	 * opentuner. Specifically, a {@link Configuration} that is generated by a
	 * {@link BlobFactory#getDefaultConfiguration(java.util.Set)}.
	 */
	public Configuration blobConfiguration = null;

	public StreamJitApp(String topLevelClass, Worker<?, ?> source,
			Worker<?, ?> sink) {
		this.topLevelClass = topLevelClass;
		this.source1 = source;
		this.sink1 = sink;
		this.jarFilePath = this.getClass().getProtectionDomain()
				.getCodeSource().getLocation().getPath();

	}

	public BlobGraph getBlobGraph() {
		return blobGraph1;
	}

	/**
	 * Builds partitionsMachineMap and {@link BlobGraph} from the
	 * {@link Configuration}, and verifies for any cycles among blobs. If it is
	 * a valid configuration, (i.e., no cycles among the blobs), then this
	 * objects member variables {@link StreamJitApp#blobConfiguration},
	 * {@link StreamJitApp#blobGraph1} and
	 * {@link StreamJitApp#partitionsMachineMap1} will be assigned according to
	 * the new configuration, no changes otherwise.
	 * 
	 * @param config
	 * @throws StreamCompilationFailedException
	 *             if any cycles found among blobs.
	 */
	public void newConfiguration(Configuration config) {

		Map<Integer, List<Set<Worker<?, ?>>>> partitionsMachineMap = getMachineWorkerMap(
				config, this.source1);
		varifyConfiguration(partitionsMachineMap);
		this.blobConfiguration = config;
	}

	/**
	 * Builds {@link BlobGraph} from the partitionsMachineMap, and verifies for
	 * any cycles among blobs. If it is a valid partitionsMachineMap, (i.e., no
	 * cycles among the blobs), then this objects member variables
	 * {@link StreamJitApp#blobGraph1} and
	 * {@link StreamJitApp#partitionsMachineMap1} will be assigned according to
	 * the new configuration, no changes otherwise.
	 * 
	 * @param partitionsMachineMap
	 * 
	 * @throws StreamCompilationFailedException
	 *             if any cycles found among blobs.
	 */
	public void newPartitionMap(
			Map<Integer, List<Set<Worker<?, ?>>>> partitionsMachineMap) {
		varifyConfiguration(partitionsMachineMap);
	}

	/**
	 * Builds {@link BlobGraph} from the partitionsMachineMap, and verifies for
	 * any cycles among blobs. If it is a valid partitionsMachineMap, (i.e., no
	 * cycles among the blobs), then this objects member variables
	 * {@link StreamJitApp#blobGraph1} and
	 * {@link StreamJitApp#partitionsMachineMap1} will be assigned according to
	 * the new configuration, no changes otherwise.
	 * 
	 * @param partitionsMachineMap
	 * 
	 * @throws StreamCompilationFailedException
	 *             if any cycles found among blobs.
	 */
	private void varifyConfiguration(
			Map<Integer, List<Set<Worker<?, ?>>>> partitionsMachineMap) {
		List<Set<Worker<?, ?>>> partitionList = new ArrayList<>();
		for (List<Set<Worker<?, ?>>> lst : partitionsMachineMap.values()) {
			partitionList.addAll(lst);
		}

		BlobGraph bg = null;
		try {
			bg = new BlobGraph(partitionList);
		} catch (StreamCompilationFailedException ex) {
			System.err.print("Cycles found in the worker->blob assignment");
			for (int machine : partitionsMachineMap.keySet()) {
				System.err.print("\nMachine - " + machine);
				for (Set<Worker<?, ?>> blobworkers : partitionsMachineMap
						.get(machine)) {
					System.err.print("\n\tBlob worker set : ");
					for (Worker<?, ?> w : blobworkers) {
						System.err.print(Workers.getIdentifier(w) + " ");
					}
				}
			}
			System.err.println();
			throw ex;
		}
		this.blobGraph1 = bg;
		this.partitionsMachineMap1 = partitionsMachineMap;
	}

	/**
	 * Reads the configuration and returns a map of nodeID to list of workers
	 * set which are assigned to the node. value of the returned map is list of
	 * worker set where each worker set is individual blob.
	 * 
	 * @param config
	 * @param workerset
	 * @return map of nodeID to list of workers set which are assigned to the
	 *         node. value is list of worker set where each set is individual
	 *         blob.
	 */
	private Map<Integer, List<Set<Worker<?, ?>>>> getMachineWorkerMap(
			Configuration config, Worker<?, ?> source) {

		ImmutableSet<Worker<?, ?>> workerset = Workers
				.getAllWorkersInGraph(source);

		Map<Integer, Set<Worker<?, ?>>> partition = new HashMap<>();
		for (Worker<?, ?> w : workerset) {
			IntParameter w2m = config.getParameter(String.format(
					"worker%dtomachine", Workers.getIdentifier(w)),
					IntParameter.class);
			int machine = w2m.getValue();

			if (!partition.containsKey(machine)) {
				Set<Worker<?, ?>> set = new HashSet<>();
				partition.put(machine, set);
			}
			partition.get(machine).add(w);
		}

		Map<Integer, List<Set<Worker<?, ?>>>> machineWorkerMap = new HashMap<>();
		for (int machine : partition.keySet()) {
			machineWorkerMap.put(machine, getBlobs(partition.get(machine)));
		}
		return machineWorkerMap;
	}

	/**
	 * Goes through all the workers assigned to a machine, find the workers
	 * which are interconnected and group them as a blob workers. i.e., Group
	 * the workers such that each group can be executed as a blob.
	 * <p>
	 * TODO: If any dynamic edges exists then should create interpreter blob.
	 * 
	 * @param workerset
	 * @return list of workers set which contains interconnected workers. Each
	 *         worker set in the list is supposed to run in an individual blob.
	 */
	private List<Set<Worker<?, ?>>> getBlobs(Set<Worker<?, ?>> workerset) {
		List<Set<Worker<?, ?>>> ret = new ArrayList<Set<Worker<?, ?>>>();
		while (!workerset.isEmpty()) {
			Deque<Worker<?, ?>> queue = new ArrayDeque<>();
			Set<Worker<?, ?>> blobworkers = new HashSet<>();
			Worker<?, ?> w = workerset.iterator().next();
			blobworkers.add(w);
			workerset.remove(w);
			queue.offer(w);
			while (!queue.isEmpty()) {
				Worker<?, ?> wrkr = queue.poll();
				for (Worker<?, ?> succ : Workers.getSuccessors(wrkr)) {
					if (workerset.contains(succ)) {
						blobworkers.add(succ);
						workerset.remove(succ);
						queue.offer(succ);
					}
				}

				for (Worker<?, ?> pred : Workers.getPredecessors(wrkr)) {
					if (workerset.contains(pred)) {
						blobworkers.add(pred);
						workerset.remove(pred);
						queue.offer(pred);
					}
				}
			}
			ret.add(blobworkers);
		}
		return ret;
	}

	public ImmutableMap<Token, Buffer> getBufferMap() {
		return bufferMap;
	}

	public void setBufferMap(ImmutableMap<Token, Buffer> bufferMap) {
		this.bufferMap = bufferMap;
	}

	public List<MessageConstraint> getConstraints() {
		return constraints;
	}

	public void setConstraints(List<MessageConstraint> constraints) {
		this.constraints = constraints;
	}

	public Configuration getStaticConfiguration() {
		Configuration.Builder builder = Configuration.builder();
		builder.putExtraData(GlobalConstants.JARFILE_PATH, jarFilePath)
				.putExtraData(GlobalConstants.TOPLEVEL_WORKER_NAME,
						topLevelClass);
		return builder.build();
	}

	public Configuration getDynamicConfiguration() {
		Configuration.Builder builder = Configuration.builder();

		Map<Integer, Integer> coresPerMachine = new HashMap<>();
		for (Entry<Integer, List<Set<Worker<?, ?>>>> machine : partitionsMachineMap1
				.entrySet()) {
			coresPerMachine.put(machine.getKey(), machine.getValue().size());
		}

		PartitionParameter.Builder partParam = PartitionParameter.builder(
				GlobalConstants.PARTITION, coresPerMachine);

		BlobFactory factory = new Interpreter.InterpreterBlobFactory();
		partParam.addBlobFactory(factory);

		blobtoMachineMap = new HashMap<>();

		for (Integer machineID : partitionsMachineMap1.keySet()) {
			List<Set<Worker<?, ?>>> blobList = partitionsMachineMap1
					.get(machineID);
			for (Set<Worker<?, ?>> blobWorkers : blobList) {
				// TODO: One core per blob. Need to change this.
				partParam.addBlob(machineID, 1, factory, blobWorkers);

				// TODO: Temp fix to build.
				Token t = Utils.getblobID(blobWorkers);
				blobtoMachineMap.put(t, machineID);
			}
		}

		builder.addParameter(partParam.build());
		if (this.blobConfiguration != null)
			builder.addSubconfiguration("blobConfigs", this.blobConfiguration);
		return builder.build();
	}
}
