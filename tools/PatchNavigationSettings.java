import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Weave only our native subclasses into the owner's verified settings factory.
 * The input/output OEM class stays in private inputs and ignored build output. */
public final class PatchNavigationSettings {
    static final String ENTRY="de/audi/tghu/navi/hmi/evohigh/NaviScreenBag8.class";
    static final String SHA="73ff64dfe504ab5d97f8cb6685657d54c2fa79aabb81a0afa92b1b565db400cd";
    static final String METHOD="mAPOPTNAVIGENERALSETTINGSMAIN";
    static final String MENU="de/esolutions/hmi/widgets/audi/evo/widgets/menu/MenuController";
    static final String SCREEN="de/esolutions/hmi/widgets/audi/evo/ScreenWidgetEVO";
    public static byte[] patch(byte[] input) throws Exception {
        StringBuilder digest=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(input))digest.append(String.format("%02x",b&255));
        if(!digest.toString().equals(SHA))throw new IllegalArgumentException("Unsupported Navigation Settings class SHA256: "+digest);
        ClassNode c=new ClassNode();
        new ClassReader(input).accept(c,0);
        if(c.version>48)throw new IllegalArgumentException("Unexpected stock class version");
        int targets=0,menuNew=0,menuInit=0,screenNew=0,screenInit=0;
        for(MethodNode m:c.methods)if(m.name.equals(METHOD)) {
            targets++;
            if(!m.desc.equals("(Lde/audi/tghu/navi/hmi/evohigh/NaviScreenFactory;I)Lde/audi/atip/hmi/view/Screen;"))
                throw new IllegalArgumentException("Settings factory signature changed");
            for(AbstractInsnNode n:m.instructions) {
                if(n instanceof TypeInsnNode && n.getOpcode()==Opcodes.NEW) {
                    TypeInsnNode t=(TypeInsnNode)n;
                    if(t.desc.equals(MENU) && ++menuNew==1)t.desc="com/luka/carplay/settings/CarplayMenuController";
                    else if(t.desc.equals(SCREEN)) {screenNew++;t.desc="com/luka/carplay/settings/CarplaySettingsScreen";}
                } else if(n instanceof MethodInsnNode) {
                    MethodInsnNode call=(MethodInsnNode)n;
                    if(call.name.equals("<init>") && call.owner.equals(MENU)) {
                        if(++menuInit==1)call.owner="com/luka/carplay/settings/CarplayMenuController";
                    } else if(call.name.equals("<init>") && call.owner.equals(SCREEN)) {
                        screenInit++;call.owner="com/luka/carplay/settings/CarplaySettingsScreen";
                    }
                }
            }
        }
        if(targets!=1 || menuNew!=2 || menuInit!=2 || screenNew!=1 || screenInit!=1)
            throw new IllegalArgumentException("Unexpected native settings construction");
        c.version=48;
        ClassWriter writer=new ClassWriter(0);c.accept(writer);
        return writer.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("stock.jar output-classes");
        byte[] input;
        try(JarFile jar=new JarFile(args[0]);InputStream in=jar.getInputStream(jar.getJarEntry(ENTRY))) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);
            input=out.toByteArray();
        }
        Path output=Paths.get(args[1],ENTRY);
        Files.createDirectories(output.getParent());
        Files.write(output,patch(input));
        System.out.println("Native MMI settings: guarded factory hook applied to screen 400102");
    }
}
