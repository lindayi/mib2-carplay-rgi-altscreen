import java.io.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class NativeRouteStatePatchTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        byte[] input;
        try (JarFile jar = new JarFile(args[0]); InputStream in = jar.getInputStream(jar.getJarEntry(PatchNativeRouteState.ENTRY))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) != -1;) out.write(buffer, 0, n);
            input = out.toByteArray();
        }
        byte[] patched = PatchNativeRouteState.patch(input);
        ClassNode before = new ClassNode(), after = new ClassNode();
        new ClassReader(input).accept(before, 0); new ClassReader(patched).accept(after, 0);
        check(after.version == 48 && before.methods.size() == after.methods.size(), "version/method preservation");
        for (int i = 0; i < before.methods.size(); i++) {
            MethodNode a = before.methods.get(i), b = after.methods.get(i);
            check(a.name.equals(b.name) && a.desc.equals(b.desc) && a.access == b.access, "method metadata");
            int injected = a.name.equals("setRgActive") ? 4 : 0;
            check(b.instructions.size() == a.instructions.size() + injected, "unrelated code changed");
        }
        try { PatchNativeRouteState.patch(patched); throw new AssertionError("double weave accepted"); }
        catch (IllegalArgumentException expected) { }
        input[input.length - 1] ^= 1;
        try { PatchNativeRouteState.patch(input); throw new AssertionError("wrong input accepted"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("NativeRouteStatePatchTest PASS");
    }
}
