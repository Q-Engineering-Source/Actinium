package com.dhj.actinium.architecture;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Bytecode view of the compiled modules, used by the architecture tests to reason about references
 * between classes instead of scanning source text.
 *
 * <p>Paths are resolved against the {@code actinium.projectRoot} system property that the root test
 * task sets, so the scan does not depend on the test working directory.</p>
 */
final class ArchScan {

    /** Modules whose compiled output the architecture tests inspect. */
    enum Dir {
        MAIN("build/classes/java/main"),
        GLSM("glsm/build/classes/java/main"),
        SHADER("shader/build/classes/java/main"),
        CELERITAS_COMMON("celeritas-common/build/classes/java/main"),
        GTNHLIB("GTNHLib/build/classes/java/main"),
        SDL_GPU("sdl-gpu/build/classes/java/main");

        final String path;

        Dir(String path) {
            this.path = path;
        }
    }

    enum Kind { FIELD, CALL }

    record Access(String method, Kind kind, String owner, String name, String desc) {

        String target() {
            return owner + "." + name + (kind == Kind.FIELD ? " " : "") + desc;
        }
    }

    record ClassConstant(String method, String type) {
    }

    record Member(String name, String desc) {
    }

    /**
     * @param references every class mentioned by the class file, including annotations, field and
     *                   method descriptors, generic signatures and instruction operands
     */
    record ScannedClass(Dir dir, String name, Set<String> annotations, Set<String> references,
                        List<Member> methods, List<Access> accesses, List<ClassConstant> classConstants) {
    }

    private static final List<ScannedClass> CLASSES = scan();
    private static final Map<String, ScannedClass> BY_NAME = index(CLASSES);

    private ArchScan() {
    }

    static List<ScannedClass> in(Dir... dirs) {
        final List<Dir> wanted = Arrays.asList(dirs);
        final List<ScannedClass> selected = new ArrayList<>();
        for (ScannedClass scanned : CLASSES) {
            if (wanted.contains(scanned.dir())) selected.add(scanned);
        }
        return selected;
    }

    static ScannedClass find(String name) {
        return BY_NAME.get(name);
    }

    static ScannedClass get(String name) {
        final ScannedClass scanned = BY_NAME.get(name);
        if (scanned == null) throw new IllegalStateException(name + " was not scanned; the scan paths are likely wrong");
        return scanned;
    }

    private static Map<String, ScannedClass> index(List<ScannedClass> classes) {
        final Map<String, ScannedClass> byName = new HashMap<>();
        for (ScannedClass scanned : classes) {
            byName.put(scanned.name(), scanned);
        }
        return byName;
    }

    private static List<ScannedClass> scan() {
        final String projectRoot = System.getProperty("actinium.projectRoot");
        if (projectRoot == null) {
            throw new IllegalStateException("Missing system property actinium.projectRoot; run the architecture tests through the root test task");
        }
        final List<ScannedClass> classes = new ArrayList<>();
        for (Dir dir : Dir.values()) {
            final Path root = Paths.get(projectRoot, dir.path);
            if (!Files.isDirectory(root)) {
                throw new IllegalStateException(root + " is not a directory; the scanned module was not compiled");
            }
            final int before = classes.size();
            try (Stream<Path> files = Files.walk(root)) {
                for (final Iterator<Path> it = files.iterator(); it.hasNext(); ) {
                    final Path file = it.next();
                    if (file.toString().endsWith(".class")) classes.add(read(dir, Files.readAllBytes(file)));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (classes.size() == before) throw new IllegalStateException(root + " yielded no classes");
        }
        return classes;
    }

    private static ScannedClass read(Dir dir, byte[] bytes) {
        final Set<String> references = new HashSet<>();
        final ClassNode node = new ClassNode();
        final Remapper recorder = new Remapper() {
            @Override
            public String map(String internalName) {
                references.add(className(internalName));
                return internalName;
            }
        };
        new ClassReader(bytes).accept(new ClassRemapper(node, recorder), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        final String name = className(node.name);
        references.remove(name);

        final Set<String> annotations = new HashSet<>();
        if (node.visibleAnnotations != null) {
            for (AnnotationNode annotation : node.visibleAnnotations) {
                annotations.add(Type.getType(annotation.desc).getClassName());
            }
        }

        final List<Member> methods = new ArrayList<>();
        final List<Access> accesses = new ArrayList<>();
        final List<ClassConstant> classConstants = new ArrayList<>();
        for (MethodNode method : node.methods) {
            methods.add(new Member(method.name, method.desc));
            final String origin = method.name + method.desc;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field) {
                    accesses.add(new Access(origin, Kind.FIELD, className(field.owner), field.name, field.desc));
                } else if (insn instanceof MethodInsnNode call) {
                    accesses.add(new Access(origin, Kind.CALL, className(call.owner), call.name, call.desc));
                } else if (insn instanceof InvokeDynamicInsnNode dynamic) {
                    for (Object argument : dynamic.bsmArgs) {
                        if (argument instanceof Handle handle) {
                            accesses.add(new Access(origin, handle.getTag() <= Opcodes.H_PUTSTATIC ? Kind.FIELD : Kind.CALL,
                                className(handle.getOwner()), handle.getName(), handle.getDesc()));
                        }
                    }
                } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type
                    && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY)) {
                    classConstants.add(new ClassConstant(origin, type.getClassName()));
                }
            }
        }
        return new ScannedClass(dir, name, Collections.unmodifiableSet(annotations),
            Collections.unmodifiableSet(references), Collections.unmodifiableList(methods),
            Collections.unmodifiableList(accesses), Collections.unmodifiableList(classConstants));
    }

    private static String className(String internalName) {
        return Type.getObjectType(internalName).getClassName();
    }
}
