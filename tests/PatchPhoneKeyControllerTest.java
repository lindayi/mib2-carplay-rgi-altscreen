import java.io.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class PatchPhoneKeyControllerTest {
    public static void main(String[] args) throws Exception {
        byte[] bytes;
        try (JarFile jar = new JarFile(args[0]); InputStream in = jar.getInputStream(jar.getJarEntry(PatchPhoneKeyController.ENTRY))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) != -1;) out.write(buffer, 0, n);
            bytes = out.toByteArray();
        }
        byte[] woven = PatchPhoneKeyController.patch(bytes);
        ClassNode c = new ClassNode();
        new ClassReader(woven).accept(c, 0);
        if (c.version != 48) throw new AssertionError("Wrong class target");
        int hooks = 0, writes = 0;
        for (MethodNode m : c.methods) for (AbstractInsnNode n : m.instructions) {
            if (n instanceof MethodInsnNode && ((MethodInsnNode)n).owner.equals(
                    "com/luka/carplay/telephony/StockPhoneKeyFilter")) {
                if (!m.name.equals("access$900")) throw new AssertionError("Wrong interception point");
                hooks++;
            }
            if (n instanceof FieldInsnNode && ((FieldInsnNode)n).name.equals("phoneCallController")
                    && n.getOpcode() == Opcodes.PUTFIELD) {
                if (!m.name.equals("access$902")) throw new AssertionError("Unexpected OEM writer");
                writes++;
            }
        }
        if (hooks != 1 || writes != 1) throw new AssertionError("Extra hook or controller writer");
        bytes[bytes.length - 1] ^= 1;
        try {
            PatchPhoneKeyController.patch(bytes);
            throw new AssertionError("Unrecognized stock accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            PatchPhoneKeyController.patch(woven);
            throw new AssertionError("Double weave accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("PHONE_KEY_WEAVE_GUARDS=PASS");
    }
}
