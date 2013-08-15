package edu.mit.streamjit.impl.distributed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableMap;

import edu.mit.streamjit.api.CompiledStream;
import edu.mit.streamjit.api.OneToOneElement;
import edu.mit.streamjit.api.Portal;
import edu.mit.streamjit.api.StreamCompiler;
import edu.mit.streamjit.api.Worker;
import edu.mit.streamjit.impl.blob.Blob.Token;
import edu.mit.streamjit.impl.blob.Buffer;
import edu.mit.streamjit.impl.blob.ConcurrentArrayBuffer;
import edu.mit.streamjit.impl.common.BlobGraph;
import edu.mit.streamjit.impl.common.ConnectWorkersVisitor;
import edu.mit.streamjit.impl.common.MessageConstraint;
import edu.mit.streamjit.impl.common.Portals;
import edu.mit.streamjit.impl.common.VerifyStreamGraph;
import edu.mit.streamjit.impl.common.BlobGraph.AbstractDrainer;
import edu.mit.streamjit.impl.concurrent.ConcurrentStreamCompiler;
import edu.mit.streamjit.impl.distributed.node.StreamNode;
import edu.mit.streamjit.impl.distributed.runtimer.CommunicationManager.CommunicationType;
import edu.mit.streamjit.impl.distributed.runtimer.Controller;
import edu.mit.streamjit.impl.distributed.runtimer.DistributedDrainer;
import edu.mit.streamjit.impl.interp.AbstractCompiledStream;
import edu.mit.streamjit.partitioner.HorizontalPartitioner;
import edu.mit.streamjit.partitioner.Partitioner;

/**
 * TODO: Now it executes all blobs in a single thread. Need to implement
 * Distributed concurrent blobs soon. TODO: {@link DistributedStreamCompiler}
 * must work with 1 {@link StreamNode} as well. In that case, it should behave
 * like a {@link ConcurrentStreamCompiler}.
 * 
 * @author Sumanan sumanan@mit.edu
 * @since May 6, 2013
 */
public class DistributedStreamCompiler implements StreamCompiler {

	/**
	 * Total number of nodes including controller node.
	 */
	int noOfnodes;

	/**
	 * @param noOfnodes
	 *            : Total number of nodes the stream application intended to run
	 *            - including controller node. If it is 1 then it means the
	 *            whole stream application is supposed to run on controller.
	 */
	public DistributedStreamCompiler(int noOfnodes) {
		if (noOfnodes < 1)
			throw new IllegalArgumentException("noOfnodes must be 1 or greater");
		this.noOfnodes = noOfnodes;
	}

	/**
	 * Run the whole application on the controller node.
	 */
	public DistributedStreamCompiler() {
		this(1);
	}

	@Override
	public <I, O> CompiledStream<I, O> compile(OneToOneElement<I, O> stream) {

		ConnectWorkersVisitor primitiveConnector = new ConnectWorkersVisitor();
		stream.visit(primitiveConnector);
		Worker<I, ?> source = (Worker<I, ?>) primitiveConnector.getSource();
		Worker<?, O> sink = (Worker<?, O>) primitiveConnector.getSink();

		// TODO: Need to analyze the capacity of the buffers.
		Buffer head = new ConcurrentArrayBuffer(1000);
		Buffer tail = new ConcurrentArrayBuffer(1000);

		ImmutableMap.Builder<Token, Buffer> bufferMapBuilder = ImmutableMap
				.<Token, Buffer> builder();

		bufferMapBuilder.put(Token.createOverallInputToken(source), head);
		bufferMapBuilder.put(Token.createOverallOutputToken(sink), tail);

		VerifyStreamGraph verifier = new VerifyStreamGraph();
		stream.visit(verifier);

		Map<CommunicationType, Integer> conTypeCount = new HashMap<>();
		conTypeCount.put(CommunicationType.LOCAL, 1);
		conTypeCount.put(CommunicationType.TCP, this.noOfnodes - 1);
		Controller controller = new Controller();
		controller.connect(conTypeCount);

		Map<Integer, Integer> coreCounts = controller.getCoreCount();

		// As we are just running small benchmark applications, lets utilize
		// just a single core per node. TODO: When running big
		// real world applications utilize all available cores. For that simply
		// comment the following lines.
		for (Integer key : coreCounts.keySet()) {
			coreCounts.put(key, 1);
		}

		int totalCores = 0;
		for (int coreCount : coreCounts.values())
			totalCores += coreCount;

		// TODO: Need to map the machines and the partitions precisely. Now just
		// it is mapped based on the list order.
		Partitioner<I, O> horzPartitioner = new HorizontalPartitioner<>();
		List<Set<Worker<?, ?>>> partitionList = horzPartitioner
				.partitionEqually(stream, source, sink, totalCores);
		Map<Integer, List<Set<Worker<?, ?>>>> partitionsMachineMap = mapPartitionstoMachines(
				partitionList, coreCounts);

		BlobGraph bg = new BlobGraph(partitionList);

		// TODO: Copied form DebugStreamCompiler. Need to be verified for this
		// context.
		List<MessageConstraint> constraints = MessageConstraint
				.findConstraints(source);
		Set<Portal<?>> portals = new HashSet<>();
		for (MessageConstraint mc : constraints)
			portals.add(mc.getPortal());
		for (Portal<?> portal : portals)
			Portals.setConstraints(portal, constraints);

		controller
				.setPartition(partitionsMachineMap,
						stream.getClass().getName(), constraints, source, sink,
						bufferMapBuilder.build());

		return new DistributedCompiledStream<>(head, tail, bg, controller);
	}

	// TODO: Need to do precise mapping. For the moment just mapping in order.
	private Map<Integer, List<Set<Worker<?, ?>>>> mapPartitionstoMachines(
			List<Set<Worker<?, ?>>> partitionList,
			Map<Integer, Integer> coreCountMap) {

		Map<Integer, List<Set<Worker<?, ?>>>> partitionsMachineMap = new HashMap<Integer, List<Set<Worker<?, ?>>>>();
		for (Integer machineID : coreCountMap.keySet()) {
			partitionsMachineMap.put(
					machineID,
					new ArrayList<Set<Worker<?, ?>>>(coreCountMap
							.get(machineID)));
		}

		int index = 0;
		for (Integer machineID : partitionsMachineMap.keySet()) {
			if (!(index < partitionList.size()))
				break;
			for (int i = 0; i < coreCountMap.get(machineID); i++) {
				if (!(index < partitionList.size()))
					break;
				partitionsMachineMap.get(machineID).add(
						partitionList.get(index++));
			}
		}

		// In case we received more partitions than available cores. Assign the
		// remaining partitions in round robin fashion. This case
		// shouldn't happen if the partitioning is correctly done based on the
		// available core count. This code is added just to ensure
		// the correctness of the program and to avoid the bugs.
		while (index < partitionList.size()) {
			for (Integer machineID : partitionsMachineMap.keySet()) {
				if (!(index < partitionList.size()))
					break;
				partitionsMachineMap.get(machineID).add(
						partitionList.get(index++));
			}
		}
		return partitionsMachineMap;
	}

	private static class DistributedCompiledStream<I, O> extends
			AbstractCompiledStream<I, O> {

		Controller controller;
		AbstractDrainer drainer;

		public DistributedCompiledStream(Buffer head, Buffer tail,
				BlobGraph blobGraph, Controller controller) {
			super(head, tail);
			this.controller = controller;
			this.drainer = new DistributedDrainer(blobGraph, false, controller);
			this.controller.start();
		}

		@Override
		public boolean isDrained() {
			return drainer.isDrained();
		}

		@Override
		protected void doDrain() {
			drainer.startDraining();
		}
	}
}