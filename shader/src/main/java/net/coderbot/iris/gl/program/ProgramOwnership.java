package net.coderbot.iris.gl.program;

import net.coderbot.iris.gl.GlResource;

import java.util.Objects;

/** Owns a linked program while its builder is still assembling the wrapper state. */
final class ProgramOwnership implements AutoCloseable {
	private final int program;
	private final long ownerShareGroupId;
	private GlResource wrapper;
	private boolean transferred;
	private boolean closed;

	ProgramOwnership(int program, long ownerShareGroupId) {
		if (program == 0) {
			throw new IllegalArgumentException("A program owner requires a nonzero GL name");
		}
		this.program = program;
		if (ownerShareGroupId <= 0L) {
			throw new IllegalArgumentException("A program owner requires a positive GL share-group id");
		}
		this.ownerShareGroupId = ownerShareGroupId;
	}

	int program() {
		if (closed || transferred) {
			throw new IllegalStateException("The builder no longer owns this program");
		}
		return program;
	}

	void setWrapper(GlResource wrapper) {
		if (closed || transferred || this.wrapper != null) {
			throw new IllegalStateException("A program wrapper can only be attached once while the builder owns it");
		}
		this.wrapper = Objects.requireNonNull(wrapper, "wrapper");
	}

	void transfer() {
		if (closed || transferred || wrapper == null) {
			throw new IllegalStateException("Only a completed program wrapper can take ownership");
		}
		transferred = true;
	}

	@Override
	public void close() {
		if (closed || transferred) {
			return;
		}
		closed = true;
		if (wrapper == null) {
			RetainedPrograms.deleteProgram(ownerShareGroupId, program);
		} else {
			// A complete wrapper may return its program to the retained pool; incomplete builders are deleted.
			wrapper.destroy();
		}
	}
}
