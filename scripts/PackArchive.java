import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** JDK 17, streaming ZIP64 verification; never extracts archive paths to the filesystem. */
class PackArchive {
    static final long MANIFEST_LIMIT = 1024 * 1024;
    static final long SOURCE_LIMIT = 2L * 1024 * 1024 * 1024;
    static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    static String safeName(String name, boolean directory) throws IOException {
        String value = directory && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (value.isEmpty() || value.startsWith("/") || value.contains("\\") || value.matches("^[A-Za-z]:.*") ||
            value.chars().anyMatch(c -> c < 32 || c == 127) ||
            Arrays.stream(value.split("/", -1)).anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..")))
            throw new IOException("Unsafe ZIP path: " + name);
        return value;
    }
    static void add(ZipOutputStream out, String name, Path file) throws IOException {
        var entry = new ZipEntry(name); entry.setTime(0L); out.putNextEntry(entry);
        Files.copy(file, out); out.closeEntry();
    }
    static List<? extends ZipEntry> entries(ZipFile zip, int limit) throws IOException {
        var result = Collections.list(zip.entries());
        if (result.size() > limit) throw new IOException("Too many ZIP entries");
        Map<String, Boolean> names = new HashMap<>();
        for (var entry : result) {
            String name = safeName(entry.getName(), entry.isDirectory());
            if (names.putIfAbsent(name, entry.isDirectory()) != null) throw new IOException("Duplicate ZIP path: " + name);
        }
        for (var name : names.keySet()) {
            int slash = name.lastIndexOf('/');
            while (slash > 0) {
                String parent = name.substring(0, slash);
                if (Boolean.FALSE.equals(names.get(parent))) throw new IOException("ZIP file/directory collision: " + parent);
                slash = parent.lastIndexOf('/');
            }
        }
        return result;
    }
    static byte[] manifest(ZipFile zip) throws IOException {
        var e = zip.getEntry("manifest.json");
        if (e == null || e.isDirectory() || e.getSize() < 1 || e.getSize() > MANIFEST_LIMIT) throw new IOException("Missing or oversized manifest");
        try (var input = zip.getInputStream(e)) {
            byte[] bytes = input.readNBytes((int) MANIFEST_LIMIT + 1);
            CRC32 crc = new CRC32(); crc.update(bytes);
            if (bytes.length > MANIFEST_LIMIT || bytes.length != e.getSize() || crc.getValue() != e.getCrc()) throw new IOException("Invalid manifest size/CRC");
            return bytes;
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Missing operation");
        if (args[0].equals("dictionary")) {
            try (var out = new ZipOutputStream(Files.newOutputStream(Path.of(args[1]), StandardOpenOption.CREATE_NEW))) {
                add(out, "manifest.json", Path.of(args[4])); add(out, "dictionary/ecdict.sqlite", Path.of(args[2])); add(out, "LICENSE-ECDICT.txt", Path.of(args[3]));
            }
            return;
        }
        Path source = Path.of(args[1]);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) > SOURCE_LIMIT) throw new IOException("Invalid source archive");
        try (var zip = new ZipFile(source.toFile())) {
            if (args[0].equals("manifest")) {
                entries(zip, 200000);
                System.out.println(Base64.getEncoder().encodeToString(manifest(zip))); return;
            }
            if (args[0].equals("member")) {
                var entry = zip.getEntry(args[2]);
                if (entry == null || entry.isDirectory() || !safeName(entry.getName(), false).equals(args[2])) throw new IOException("Missing bundle member");
                try (var input = zip.getInputStream(entry); var output = Files.newOutputStream(Path.of(args[3]), StandardOpenOption.CREATE_NEW)) {
                    long n = 0; byte[] buf = new byte[65536]; int read;
                    while ((read = input.read(buf)) != -1) { n += read; if (n > SOURCE_LIMIT) throw new IOException("Bundle member too large"); output.write(buf, 0, read); }
                }
                return;
            }
            if (!args[0].equals("inspect")) throw new IllegalArgumentException("Unknown operation");
            int maxEntries = Integer.parseInt(args[2]); long maxBytes = Long.parseLong(args[3]);
            byte[] meta = manifest(zip);
            System.out.println(Base64.getEncoder().encodeToString(meta));
            long total = meta.length;
            for (var entry : entries(zip, maxEntries)) {
                if (entry.isDirectory() || entry.getName().equals("manifest.json")) continue;
                var digest = MessageDigest.getInstance("SHA-256"); var crc = new CRC32(); long size = 0;
                try (var input = zip.getInputStream(entry)) {
                    byte[] buf = new byte[65536]; int read;
                    while ((read = input.read(buf)) != -1) {
                        total += read; size += read;
                        if (total > maxBytes) throw new IOException("Uncompressed pack too large");
                        digest.update(buf, 0, read); crc.update(buf, 0, read);
                    }
                }
                if (size != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("ZIP size/CRC mismatch: " + entry.getName());
                System.out.println(b64(entry.getName()) + "\t" + size + "\t" + HexFormat.of().formatHex(digest.digest()));
            }
        }
    }
}
