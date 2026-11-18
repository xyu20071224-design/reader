import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.security.*;
import java.security.cert.Certificate;

/** A Tinker patch is a signed JAR, not an installable APK with AndroidManifest.xml. */
public final class VerifyHotfixSignature {
    public static void main(String[] args) throws Exception {
        int count = 0;
        try (JarFile jar = new JarFile(args[0], true)) {
            Enumeration<JarEntry> entries = jar.entries();
            byte[] buffer = new byte[65536];
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory() || e.getName().startsWith("META-INF/")) continue;
                try (InputStream in = jar.getInputStream(e)) { while (in.read(buffer) != -1) {} }
                Certificate[] certificates = e.getCertificates();
                boolean matches = false;
                if (certificates != null) for (Certificate cert : certificates) {
                    String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
                    if (args[1].equals(hex)) matches = true;
                }
                if (!matches) throw new SecurityException("Unsigned or wrong-certificate patch entry: "+e.getName());
                count++;
            }
        }
        if (count == 0) throw new SecurityException("Empty patch");
        System.out.println("Verified all "+count+" payload entries; signer certificate SHA-256: "+args[1]);
    }
}
