package edu.mit.streamjit.impl.distributed.node;

import edu.mit.streamjit.impl.distributed.common.Error.ErrorProcessor;

/**
 * {@link ErrorProcessor} at {@link StreamNode} side.
 * @author Sumanan sumanan@mit.edu
 * @since May 27, 2013
 */
public class SNErrorProcessorImpl implements ErrorProcessor {

	@Override
	public void processFILE_NOT_FOUND() {
		throw new IllegalArgumentException(
				"FILE_NOT_FOUND error should be informed to Controller");
	}

	@Override
	public void processWORKER_NOT_FOUND() {
		throw new IllegalArgumentException(
				"WORKER_NOT_FOUND error should be informed to Controller");
	}

	@Override
	public void processBLOB_NOT_FOUND() {
		throw new IllegalArgumentException(
				"BLOB_NOT_FOUND error should be informed to Controller");
	}
}