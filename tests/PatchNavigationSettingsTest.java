import java.io.*;
import java.util.Arrays;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class PatchNavigationSettingsTest {
    static byte[] encode(ClassNode c){ClassWriter w=new ClassWriter(0);c.accept(w);return w.toByteArray();}
    public static void main(String[] args) throws Exception {
        byte[] original;
        try(JarFile jar=new JarFile(args[0]);InputStream in=jar.getInputStream(jar.getJarEntry(PatchNavigationSettings.ENTRY))) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];
            for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);original=out.toByteArray();
        }
        ClassNode before=new ClassNode(),after=new ClassNode();
        new ClassReader(original).accept(before,0);
        new ClassReader(PatchNavigationSettings.patch(original)).accept(after,0);
        if(after.version!=48)throw new AssertionError("target class version");
        int restored=0;
        for(MethodNode m:after.methods)for(AbstractInsnNode n:m.instructions) {
            if(n instanceof TypeInsnNode) {
                TypeInsnNode t=(TypeInsnNode)n;
                if(t.desc.equals("com/luka/carplay/settings/CarplayMenuController")){t.desc=PatchNavigationSettings.MENU;restored++;}
                if(t.desc.equals("com/luka/carplay/settings/CarplaySettingsScreen")){t.desc=PatchNavigationSettings.SCREEN;restored++;}
            }
            if(n instanceof MethodInsnNode) {
                MethodInsnNode c=(MethodInsnNode)n;
                if(c.owner.equals("com/luka/carplay/settings/CarplayMenuController")){c.owner=PatchNavigationSettings.MENU;restored++;}
                if(c.owner.equals("com/luka/carplay/settings/CarplaySettingsScreen")){c.owner=PatchNavigationSettings.SCREEN;restored++;}
            }
        }
        after.version=before.version;
        if(restored!=4 || !Arrays.equals(encode(before),encode(after)))throw new AssertionError("unrelated OEM code changed");
        byte[] bad=original.clone();bad[bad.length-1]^=1;
        try{PatchNavigationSettings.patch(bad);throw new AssertionError("hash guard not enforced");}
        catch(IllegalArgumentException expected){}
        System.out.println("PatchNavigationSettingsTest: exactly four constructor/type substitutions, all other OEM code unchanged, wrong firmware rejected PASS");
    }
}
