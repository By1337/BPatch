package dev.by1337.bpatch.internal;

import dev.by1337.bpatch.Compression;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Shared format primitives used to verify and write reconstructed JARs. */
public final class ArchiveIO {
    private ArchiveIO() {
    }

    /** Writes reproducibly, replacing the destination only after a successful write. */
    public static void write(Path target, Map<String, byte[]> entries, Compression compression) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), ".bpatch-", ".tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                zip.setLevel(6);
                for (var item : new TreeMap<>(entries).entrySet()) {
                    validateName(item.getKey());
                    if (item.getKey().endsWith("/")) {
                        throw new IOException("Expected a file entry: " + item.getKey());
                    }
                    ZipEntry entry = new ZipEntry(item.getKey());
                    entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                    entry.setMethod(compression.method());
                    if (compression == Compression.STORED) {
                        CRC32 crc = new CRC32();
                        crc.update(item.getValue());
                        entry.setSize(item.getValue().length);
                        entry.setCompressedSize(item.getValue().length);
                        entry.setCrc(crc.getValue());
                    }
                    zip.putNextEntry(entry);
                    zip.write(item.getValue());
                    zip.closeEntry();
                }
            }
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** SHA-256 of sorted, length-prefixed UTF-8 paths and file contents. */
    public static String contentHash(Map<String, byte[]> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (var item : new TreeMap<>(entries).entrySet()) {
                byte[] name = item.getKey().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(8).putLong(name.length).array());
                digest.update(name);
                digest.update(ByteBuffer.allocate(8).putLong(item.getValue().length).array());
                digest.update(item.getValue());
            }
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest.digest()) {
                result.append(Character.forDigit((b >>> 4) & 15, 16));
                result.append(Character.forDigit(b & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static void validateName(String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":")) {
            throw new IOException("Invalid ZIP entry name: " + name);
        }
        for (String part : name.split("/", -1)) {
            if (part.equals("..") || part.equals(".")) {
                throw new IOException("Invalid ZIP entry name: " + name);
            }
        }
    }
}
