package edu.mit.streamjit.impl.concurrent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import sun.awt.windows.ThemeReader;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.sun.istack.internal.Nullable;

import edu.mit.streamjit.api.IllegalStreamGraphException;
import edu.mit.streamjit.api.Rate;
import edu.mit.streamjit.api.Worker;
import edu.mit.streamjit.impl.blob.Blob;
import edu.mit.streamjit.impl.blob.Blob.Token;
import edu.mit.streamjit.impl.common.Configuration;
import edu.mit.streamjit.impl.common.IOInfo;
import edu.mit.streamjit.impl.common.MessageConstraint;
import edu.mit.streamjit.impl.common.Workers;
import edu.mit.streamjit.impl.interp.Channel;
import edu.mit.streamjit.impl.interp.Interpreter;

/**
 * {@link ConcurrentBlob} interprets a section(partition) of a stream graph. For the moment, {@link ConcurrentBlob} is single threaded
 * and maxNumCores that is provided when making a {@link Blob} has no effect. As {@link ConcurrentBlob} is single threaded, few codes
 * are copied from {@link Interpreter} class. But in future, when we response to maxNumCores this will change.
 * 
 * @author Sumanan sumanan@mit.edu
 * @since Apr 10, 2013
 */
public class ConcurrentBlob implements Blob {

	ImmutableSet<Worker<?, ?>> workers, blobSinks;
	Configuration config;
	int maxNumCores;

	/**
	 * Set this flag to false to stop the normal stream execution and to trigger the draining.
	 */
	private volatile boolean infinityRunFlag = true;

	/**
	 * Absolute input and output {@link Channel}s of a {@link Blob}
	 */
	private final ImmutableMap<Token, Channel<?>> inputChannels, outputChannels;

	/**
	 * Maps workers to all constraints of which they are recipients.
	 */
	private final Map<Worker<?, ?>, List<MessageConstraint>> constraintsForRecipient = new IdentityHashMap<>();

	/**
	 * When running normally, null. After drain() has been called, contains the callback we should execute.
	 */
	private final AtomicReference<Runnable> callbackContainer = new AtomicReference<>();

	/**
	 * @param workers
	 *            : set of workers assigned to this blob.
	 * @param config
	 * @param maxNumCores
	 *            : As {@link ConcurrentBlob} is single threaded for the moment, maxNumCores has no effect.
	 */
	public ConcurrentBlob(Set<Worker<?, ?>> workers, Configuration config, int maxNumCores, Iterable<MessageConstraint> constraintsIter) {
		this.workers = ImmutableSet.copyOf(workers);
		this.config = config;
		this.maxNumCores = maxNumCores;
		blobSinks = findSinks(this.workers);

		// TODO: Copied from Interpreter class. Verify the validity.
		for (MessageConstraint mc : constraintsIter)
			if (this.workers.contains(mc.getSender()) != this.workers.contains(mc.getRecipient()))
				throw new IllegalArgumentException("Constraint crosses interpreter boundary: " + mc);
		for (MessageConstraint constraint : constraintsIter) {
			Worker<?, ?> recipient = constraint.getRecipient();
			List<MessageConstraint> constraintList = constraintsForRecipient.get(recipient);
			if (constraintList == null) {
				constraintList = new ArrayList<>();
				constraintsForRecipient.put(recipient, constraintList);
			}
			constraintList.add(constraint);
		}

		ImmutableMap.Builder<Token, Channel<?>> inputChannelsBuilder = ImmutableMap.builder();
		ImmutableMap.Builder<Token, Channel<?>> outputChannelsBuilder = ImmutableMap.builder();
		for (IOInfo info : IOInfo.create(workers))
			(info.isInput() ? inputChannelsBuilder : outputChannelsBuilder).put(info.token(), info.channel());
		this.inputChannels = inputChannelsBuilder.build();
		this.outputChannels = outputChannelsBuilder.build();
	}

	@Override
	public ImmutableSet<Worker<?, ?>> getWorkers() {
		return workers;
	}

	@Override
	public ImmutableMap<Token, Channel<?>> getInputChannels() {
		return inputChannels;
	}

	@Override
	public ImmutableMap<Token, Channel<?>> getOutputChannels() {
		return outputChannels;
	}

	@Override
	public int getCoreCount() {
		return 1;
	}

	@Override
	public Runnable getCoreCode(int core) {
		return new Runnable() {
			@Override
			public void run() {
				while (infinityRunFlag) {
					interpret();
				}
				myDrain();
			}

		};
	}

	/**
	 * Drain the this Blob and pass the call back to next blob.
	 */
	private void myDrain() {
		assert this.callbackContainer.get() != null : "Illegal call. Call back is not set";
		// TODO: implement all draining code here...
		this.callbackContainer.get().run();
	}

	@Override
	public void drain(Runnable callback) {
		if (callback == null) {
			throw new IllegalArgumentException("NULL callback is passed.");
		}

		// Set the callback; the core code will run it after its next interpret().
		if (!this.callbackContainer.compareAndSet(null, callback))
			throw new IllegalStateException("drain() called multiple times");

		this.infinityRunFlag = false;
	}

	private ImmutableSet<Worker<?, ?>> findSinks(Set<Worker<?, ?>> workers) {
		Set<Worker<?, ?>> bottommost = new HashSet<>();
		for (Worker<?, ?> w : workers) {
			if (!workers.containsAll(Workers.getAllSuccessors(w)) || Workers.getAllSuccessors(w).isEmpty())
				bottommost.add(w);
		}
		return ImmutableSet.copyOf(bottommost);
	}

	/**
	 * Interprets the stream graph section by running a pull schedule on the "bottom-most" workers in the section (firing predecessors
	 * as required if possible) until no more progress can be made. Returns true if any "bottom-most" workers were fired. Note that
	 * returning false does not mean no workers were fired -- some predecessors might have been fired, but others prevented the
	 * "bottom-most" workers from firing.
	 * 
	 * @return true iff progress was made
	 */
	public boolean interpret() {
		// Fire each sink once if possible, then repeat until we can't fire any
		// sinks.
		// System.out.println(Thread.currentThread().getName() + ": I am interpretting");
		boolean fired, everFired = false;
		do {
			fired = false;
			for (Worker<?, ?> sink : blobSinks)
				everFired |= fired |= pull(sink);
		} while (fired);
		return everFired;
	}

	/**
	 * Fires upstream filters just enough to allow worker to fire, or returns false if this is impossible.
	 * 
	 * This is an implementation of Figure 3-12 from Bill's thesis.
	 * 
	 * @param worker
	 *            the worker to fire
	 * @return true if the worker fired, false if it didn't
	 */
	private boolean pull(Worker<?, ?> worker) {
		// This stack holds all the unsatisfied workers we've encountered
		// while trying to fire the argument.
		Deque<Worker<?, ?>> stack = new ArrayDeque<>();
		stack.push(worker);
		recurse: while (!stack.isEmpty()) {
			Worker<?, ?> current = stack.element();
			assert workers.contains(current) : "Executing outside stream graph section";
			// If we're already trying to fire current, current depends on
			// itself, so throw. TODO: explain which constraints are bad?
			// We have to pop then push so contains can't just find the top
			// of the stack every time. (no indexOf(), annoying)
			stack.pop();
			if (stack.contains(current))
				throw new IllegalStreamGraphException("Unsatisfiable message constraints", current);
			stack.push(current);

			// Execute predecessors based on data dependencies.
			int channel = indexOfUnsatisfiedChannel(current);
			if (channel != -1) {
				if (!workers.contains(Iterables.get(Workers.getPredecessors(current), channel, null)))
					// We need data from a worker not in our stream graph section,
					// so we can't do anything.
					return false;
				// Otherwise, recursively fire the worker blocking us.
				stack.push(Workers.getPredecessors(current).get(channel));
				continue recurse;
			}

			List<MessageConstraint> constraints = constraintsForRecipient.get(current);
			if (constraints != null)
				// Execute predecessors based on message dependencies; that is,
				// execute any filter that might send a message to the current
				// worker for delivery just prior to its next firing, to ensure
				// that delivery cannot be missed.
				for (MessageConstraint constraint : constraintsForRecipient.get(current)) {
					Worker<?, ?> sender = constraint.getSender();
					long deliveryTime = constraint.getDeliveryTime(Workers.getExecutions(sender));
					// If deliveryTime == current.getExecutions() + 1, it's for
					// our next execution. (If it's <= current.getExecutions(),
					// we already missed it!)
					if (deliveryTime <= (Workers.getExecutions(sender) + 1)) {
						// We checked in our constructor that message constraints
						// do not cross the interpreter boundary. Assert that.
						assert workers.contains(sender);
						stack.push(sender);
						continue recurse;
					}
				}

			Workers.doWork(current);
			afterFire(current);
			stack.pop(); // return from the recursion
		}

		// Stack's empty: we fired the argument.
		return true;
	}

	/**
	 * Searches the given worker's input channels for one that requires more elements before the worker can fire, returning the index
	 * of the found channel or -1 if the worker can fire.
	 */
	private <I, O> int indexOfUnsatisfiedChannel(Worker<I, O> worker) {
		List<Channel<? extends I>> channels = Workers.getInputChannels(worker);
		List<Rate> peekRates = worker.getPeekRates();
		List<Rate> popRates = worker.getPopRates();
		for (int i = 0; i < channels.size(); ++i) {
			Rate peek = peekRates.get(i), pop = popRates.get(i);
			if (peek.max() == Rate.DYNAMIC || pop.max() == Rate.DYNAMIC)
				throw new UnsupportedOperationException("Unbounded input rates not yet supported");
			int required = Math.max(peek.max(), pop.max());
			if (channels.get(i).size() < required)
				return i;
		}
		return -1;
	}

	/**
	 * Called after the given worker is fired. Provided for the debug interpreter to check rate declarations.
	 * 
	 * @param worker
	 *            the worker that just fired
	 */
	protected void afterFire(Worker<?, ?> worker) {
	}
}
