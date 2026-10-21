package net.coderbot.iris.gl.program;

/** Installs deterministic program ownership context for cross-package tests that do not create GL contexts. */
public final class RetainedProgramsTestSupport {
    private static final ProgramDeleter NO_OP_DELETER = program -> {};

    private RetainedProgramsTestSupport() {
    }

    /** Replaces the production context reader with the existing deterministic test provider. */
    public static void installNoNativeContext() {
        RetainedPrograms.discardAll();
        RetainedPrograms.setDeleterForTesting(NO_OP_DELETER);
        RetainedPrograms.setContextProviderForTesting(new TestProgramContextProvider());
    }

    /** Restores the production context reader and leaves no programs in the static pool. */
    public static void restoreProductionProvider() {
        RetainedPrograms.discardAll();
        RetainedPrograms.setDeleterForTesting(null);
        RetainedPrograms.setContextProviderForTesting(null);
    }
}
