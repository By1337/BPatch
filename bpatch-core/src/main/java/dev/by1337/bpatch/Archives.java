package dev.by1337.bpatch;

import dev.by1337.bpatch.internal.ArchiveIO;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class Archives {
    private Archives() {
    }

    public static Map<String, byte[]> read(Path path, Predicate<String> filter) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        var names = new HashSet<String>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                ArchiveIO.validateName(entry.getName());
                if (!names.add(entry.getName())) {
                    throw new IOException("Duplicate ZIP entry: " + entry.getName());
                }
                if (!entry.isDirectory() && filter.test(entry.getName())) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        entries.put(entry.getName(), input.readAllBytes());
                    }
                }
            }
        }
        return entries;
    }

    /** Consumes and closes the input stream. Directory entries are omitted. */
    public static Map<String, byte[]> read(InputStream input, Predicate<String> filter) throws IOException {
        Path temporary = Files.createTempFile("bpatch-input-", ".jar");
        try (input) {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            return read(temporary, filter);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void repack(Path source, Path target, Compression compression,
        Predicate<String> filter) throws IOException {
        if (source.toRealPath().equals(target.toAbsolutePath().normalize())
                || (Files.exists(target) && Files.isSameFile(source, target))) {
            throw new IOException("Source and destination must differ: " + source);
        }
        write(target, read(source, filter), compression);
    }

    public static void write(Path target, Map<String, byte[]> entries, Compression compression) throws IOException {
        ArchiveIO.write(target, entries, compression);
    }

    public static String contentHash(Map<String, byte[]> entries) {
        return ArchiveIO.contentHash(entries);
    }
}
