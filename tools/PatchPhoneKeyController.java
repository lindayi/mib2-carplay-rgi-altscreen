import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Host-only exact-MU1316 weave; never write the OEM controller field at runtime. */
public final class PatchPhoneKeyController {
    static final String OWNER = "de/audi/app/terminalmode/dsi/DSISmartphoneManagerProxy";
    static final String ENTRY = OWNER + ".class";
    static final String PHONE = "Lde/audi/app/terminalmode/smartphone/IPhoneCallController;";
    static final String SHA = "97903a930d626d12028d5909702fe76100f3b876dfe0eaac3411e99f469ddfaf";

    public static byte[] patch(byte[] input) throws Exception {
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(input))
            hash.append(String.format("%02x", b & 255));
        if (!SHA.equals(hash.toString()))
            throw new IllegalArgumentException("Unsupported phone manager SHA256: " + hash);
        ClassNode c = new ClassNode();
        new ClassReader(input).accept(c, 0);
        if (!OWNER.equals(c.name) || c.version != 49)
            throw new IllegalArgumentException("Unexpected phone manager class/version");
        int targets = 0;
        for (MethodNode m : c.methods) if (m.name.equals("access$900")) {
            if (!m.desc.equals("(L" + OWNER + ";)" + PHONE) || (m.access & Opcodes.ACC_STATIC) == 0)
                throw new IllegalArgumentException("Unexpected phone getter signature");
            int step = 0;
            AbstractInsnNode result = null;
            for (AbstractInsnNode n : m.instructions) {
                if (n.getOpcode() < 0 || n.getOpcode() == Opcodes.NOP) continue;
                if (step == 0 && n instanceof VarInsnNode && n.getOpcode() == Opcodes.ALOAD
                        && ((VarInsnNode)n).var == 0) step++;
                else if (step == 1 && n instanceof FieldInsnNode) {
                    FieldInsnNode f = (FieldInsnNode)n;
                    if (n.getOpcode() != Opcodes.GETFIELD || !f.owner.equals(OWNER)
                            || !f.name.equals("phoneCallController") || !f.desc.equals(PHONE))
                        throw new IllegalArgumentException("Unexpected phone getter field");
                    step++;
                } else if (step == 2 && n.getOpcode() == Opcodes.ARETURN) { result = n; step++; }
                else throw new IllegalArgumentException("Unexpected phone getter instruction");
            }
            if (step != 3 || !m.tryCatchBlocks.isEmpty())
                throw new IllegalArgumentException("Unexpected phone getter shape");
            m.instructions.insertBefore(result, new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/luka/carplay/telephony/StockPhoneKeyFilter", "forController",
                "(" + PHONE + ")" + PHONE, false));
            targets++;
        }
        if (targets != 1) throw new IllegalArgumentException("Missing unique phone getter");
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
        System.out.println("PHONE_KEY_GETTER_PATCH=PASS");
    }
}
