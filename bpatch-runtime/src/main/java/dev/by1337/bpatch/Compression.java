package dev.by1337.bpatch;

import java.util.zip.ZipEntry;

public enum Compression {
    STORED(ZipEntry.STORED),
    DEFLATED(ZipEntry.DEFLATED);

    private final int method;

    Compression(int method) {
        this.method = method;
    }

    public int method() {
        return method;
    }
}
