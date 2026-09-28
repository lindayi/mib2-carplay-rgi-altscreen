import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.Arrays;

public final class JavaLogRotationTest {
    public static void main(String[] args) throws Exception {
        if(!Files.exists(Paths.get("/.dockerenv")))throw new AssertionError("Docker only");
        Path log=Paths.get("/tmp/carplay_java.log"),old=Paths.get("/tmp/carplay_java.log.1");
        if(Files.exists(log) || Files.exists(old))throw new AssertionError("fixture log paths occupied");
        try {
            byte[] oversized=new byte[700000];Arrays.fill(oversized,(byte)'x');
            Files.write(log,oversized);
            if(log.toFile().renameTo(new File("/tmp/rename-probe")))throw new AssertionError("QNX rename guard is not active");
            Method rotate=com.luka.carplay.framework.Log.class.getDeclaredMethod("rotateFiles",File.class);
            rotate.setAccessible(true);rotate.invoke(null,log.toFile());
            if(Files.size(log)!=0 || Files.size(old)!=524288)throw new AssertionError("rotation not bounded without rename");
            Files.write(log,"second rotation\n".getBytes("UTF-8"));rotate.invoke(null,log.toFile());
            if(!new String(Files.readAllBytes(old),"UTF-8").equals("second rotation\n"))
                throw new AssertionError("second rotation archive not replaced");
            Files.delete(old);Files.createDirectory(old);
            Files.write(log,oversized);rotate.invoke(null,log.toFile());
            if(Files.size(log)>2048 || !new String(Files.readAllBytes(log),"UTF-8").contains("ARCHIVE_FAILED"))
                throw new AssertionError("archive failure not bounded and visible");
        } finally {Files.deleteIfExists(log);Files.deleteIfExists(old);}
        System.out.println("JavaLogRotationTest: QNX ENOSYS rename, bounded copy/truncate and repeat rotation PASS");
    }
}
