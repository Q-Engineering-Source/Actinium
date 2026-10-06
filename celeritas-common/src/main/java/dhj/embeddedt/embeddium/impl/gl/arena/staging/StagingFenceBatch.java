package dhj.embeddedt.embeddium.impl.gl.arena.staging;

/** Accounts for copied ring-buffer bytes until their enclosing upload batch can be fenced. */
public interface StagingFenceBatch {
    /** Opens a scope; nested upload scopes must not fence their parent's copies early. */
    void begin();

    /** Closes a scope without releasing any ring-buffer memory. */
    void end();

    /** Records bytes whose GPU copy commands have been issued. */
    void addTransferredBytes(int bytes);

    /** Returns bytes to protect with a fence, or zero while a scope remains open. */
    int takeFenceBytes();
}
