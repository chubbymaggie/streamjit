/*
 * Copyright (c) 2013-2014 Massachusetts Institute of Technology
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package edu.mit.streamjit.api;

/**
 * Thrown when a StreamCompiler fails to compile a valid, supported stream
 * graph.  For example, the stream graph may have many rate mismatches, causing
 * the steady-state schedule or buffering requirements to be too large.
 * @author Jeffrey Bosboom <jbosboom@csail.mit.edu>
 * @since 8/12/2013
 */
public final class StreamCompilationFailedException extends RuntimeException {
	private static final long serialVersionUID = 1L;
	public StreamCompilationFailedException() {
	}
	public StreamCompilationFailedException(String message) {
		super(message);
	}
	public StreamCompilationFailedException(String message, Throwable cause) {
		super(message, cause);
	}
	public StreamCompilationFailedException(Throwable cause) {
		super(cause);
	}
}
