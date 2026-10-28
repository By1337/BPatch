package dev.by1337.bpatch;

import dev.by1337.bpatch.internal.ArchiveIO;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** An indexed ZIP containing a full base and whole-file patches; no runtime dependencies. */
public final class PatchArchive implements Closeable {
    private static final String INDEX = "index.properties";
    private static final String REMOVED = ".removed";

    public record Version(String id, String parent, String contentSha256) {
    }

    private final ZipFile zip;
    private final Path source;
    private final Path temporary;
    private final List<Version> versions;
    private final Map<String, Integer> ordinals;

    private PatchArchive(Path path, Path temporary) throws IOException {
        this.temporary = temporary;
        this.source = path.toRealPath();
        this.zip = new ZipFile(path.toFile());
        try {
            var names = new HashSet<String>();
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                ArchiveIO.validateName(entry.getName());
                if (!names.add(entry.getName())) {
                    throw new IOException("Duplicate ZIP entry: " + entry.getName());
                }
            }
            Properties index = new Properties();
            ZipEntry entry = zip.getEntry(INDEX);
            if (entry == null || entry.isDirectory()) {
                throw new IOException("Missing BPatch index");
            }
            try (InputStream input = zip.getInputStream(entry)) {
                index.load(input);
            }
            if (!"1".equals(index.getProperty("format"))) {
                throw new IOException("Unsupported BPatch format: " + index.getProperty("format"));
            }
            int count = Integer.parseInt(index.getProperty("version.count"));
            if (count < 1 || count > index.size() / 3) {
                throw new IOException("Invalid version count: " + count);
            }
            List<Version> parsed = new ArrayList<>();
            Map<String, Integer> positions = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String key = "version." + i + ".";
                String id = index.getProperty(key + "id");
                if (id == null || !id.matches("[A-Za-z0-9_.+\\-]+") || positions.containsKey(id)) {
                    throw new IOException("Invalid or duplicate version ID: " + id);
                }
                int parent = Integer.parseInt(index.getProperty(key + "parent"));
                if ((i == 0 && parent != -1) || (i > 0 && (parent < 0 || parent >= i))) {
                    throw new IOException("Invalid parent for version: " + id);
                }
                String hash = index.getProperty(key + "sha256");
                if (hash == null || !hash.matches("[0-9a-f]{64}")) {
                    throw new IOException("Invalid content hash for version: " + id);
                }
                parsed.add(new Version(id, parent == -1 ? null : parsed.get(parent).id(), hash));
                positions.put(id, i);
            }
            versions = List.copyOf(parsed);
            ordinals = Collections.unmodifiableMap(positions);
        } catch (IOException | RuntimeException e) {
            zip.close();
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Invalid BPatch index", e);
        }
    }

    public static PatchArchive open(Path path) throws IOException {
        return new PatchArchive(path, null);
    }

    /** Spools the resource to a temporary file for random access. Consumes and closes input. */
    public static PatchArchive open(InputStream input) throws IOException {
        Path temporary = Files.createTempFile("bpatch-", ".zip");
        try {
            try (input) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            return new PatchArchive(temporary, temporary);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
    }

    /** Reads only archive metadata, without reconstructing any version. */
    public List<Version> versions() {
        return versions;
    }

    public void unpack(String id, Path target) throws IOException {
        unpack(id, target, Compression.DEFLATED);
    }

    /** Reconstructs and verifies the requested version, then atomically replaces target. */
    public void unpack(String id, Path target, Compression compression) throws IOException {
        if (source.equals(target.toAbsolutePath().normalize())
                || (Files.exists(target) && Files.isSameFile(source, target))) {
            throw new IOException("Unpacked JAR must not overwrite the BPatch archive");
        }
        ArchiveIO.write(target, readVersion(id), compression);
    }

    public Map<String, byte[]> readVersion(String id) throws IOException {
        Integer ordinal = ordinals.get(id);
        if (ordinal == null) {
            throw new IOException("Unknown BPatch version: " + id);
        }
        List<Integer> chain = new ArrayList<>();
        for (Integer current = ordinal; current != null;) {
            chain.add(current);
            String parent = versions.get(current).parent();
            current = parent == null ? null : ordinals.get(parent);
        }
        Collections.reverse(chain);
        Map<String, byte[]> files = new TreeMap<>();
        for (int current : chain) {
            String prefix = prefix(current);
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
                    continue;
                }
                String name = entry.getName().substring(prefix.length());
                ArchiveIO.validateName(name);
                byte[] data;
                try (InputStream input = zip.getInputStream(entry)) {
                    data = input.readAllBytes();
                }
                if (name.endsWith(REMOVED)) {
                    String removed = name.substring(0, name.length() - REMOVED.length());
                    if (current == 0 || data.length != 0 || files.remove(removed) == null) {
                        throw new IOException("Invalid removal marker: " + entry.getName());
                    }
                } else {
                    files.put(name, data);
                }
            }
            if (!ArchiveIO.contentHash(files).equals(versions.get(current).contentSha256())) {
                throw new IOException("Content hash mismatch for version: " + versions.get(current).id());
            }
        }
        return files;
    }

    private static String prefix(int ordinal) {
        return "versions/" + ordinal + "/";
    }

    @Override
    public void close() throws IOException {
        try {
            zip.close();
        } finally {
            if (temporary != null) {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
