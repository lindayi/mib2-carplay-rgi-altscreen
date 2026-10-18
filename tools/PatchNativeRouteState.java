import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Host-only, guarded MU1316 weave. Never deploy this tool or an opaque OEM jar.
 * Adapted from MHI2Q-CarPlay-AltScreen NativeRouteStateJarPatch (GPL-3.0),
 * Copyright (c) 2026 yuedizhibo. Uses public ASM and exact-stock identity guards. */
public final class PatchNativeRouteState {
    static final String OWNER = "de/audi/tghu/navi/app/command/DSIResponseContainer";
    static final String ENTRY = OWNER + ".class";
    static final String SHA = "941624b637d5c2d783aa2b08b2c2d448f5abc508bcd031c9514c6b261a9c1775";
    public static byte[] patch(byte[] input) throws Exception {
        StringBuilder digest = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(input))
            digest.append(String.format("%02x", b & 255));
        if (!SHA.equals(digest.toString()))
            throw new IllegalArgumentException("Unsupported DSIResponseContainer SHA256: " + digest);
        ClassNode c = new ClassNode();
        new ClassReader(input).accept(c, 0);
        if (!OWNER.equals(c.name) || c.version != 46)
            throw new IllegalArgumentException("Unexpected native route-state class/version");
        int targets = 0;
        for (MethodNode m : c.methods) if (m.name.equals("setRgActive")) {
            if (!m.desc.equals("(Z)V") || m.access != (Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNCHRONIZED))
                throw new IllegalArgumentException("Unexpected native route-state setter signature/access");
            int fields = 0, returns = 0;
            for (AbstractInsnNode n : m.instructions) {
                if (n instanceof FieldInsnNode) {
                    FieldInsnNode f = (FieldInsnNode)n;
                    if (n.getOpcode() != Opcodes.PUTFIELD || !f.owner.equals(OWNER)
                            || !f.name.equals("rgActive") || !f.desc.equals("Z"))
                        throw new IllegalArgumentException("Unexpected native route-state field");
                    fields++;
                } else if (n.getOpcode() == Opcodes.RETURN) returns++;
                else if (n.getOpcode() >= 0 && n.getOpcode() != Opcodes.ALOAD
                        && n.getOpcode() != Opcodes.ILOAD && n.getOpcode() != Opcodes.NOP)
                    throw new IllegalArgumentException("Unexpected native route-state setter instruction");
            }
            if (fields != 1 || returns != 1 || !m.tryCatchBlocks.isEmpty())
                throw new IllegalArgumentException("Unexpected native route-state setter shape");
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new VarInsnNode(Opcodes.ILOAD, 1));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/luka/carplay/rgd/NativeRouteStateOverlay", "nativeWrite", "(Ljava/lang/Object;Z)Z", false));
            hook.add(new VarInsnNode(Opcodes.ISTORE, 1));
            m.instructions.insert(hook);
            targets++;
        }
        if (targets != 1) throw new IllegalArgumentException("Missing unique native route-state setter");
        c.version = 48;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        c.accept(writer);
        return writer.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("stock.jar output-classes");
        byte[] input;
        try (JarFile jar = new JarFile(args[0]); InputStream in = jar.getInputStream(jar.getJarEntry(ENTRY))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) != -1;) out.write(buffer, 0, n);
            input = out.toByteArray();
        }
        Path output = Paths.get(args[1], ENTRY);
        Files.createDirectories(output.getParent());
        Files.write(output, patch(input));
        System.out.println("NATIVE_ROUTE_STATE_PATCH=PASS");
    }
}
