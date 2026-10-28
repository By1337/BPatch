package dev.by1337.bpatch.maven;

import dev.by1337.bpatch.ResourceFilter;

import java.util.ArrayList;
import java.util.List;

public abstract class ResourceOptions {
    public List<String> includes = new ArrayList<>();
    public List<String> excludes = new ArrayList<>();
    public boolean excludeMetaInf;

    public ResourceFilter filter() {
        List<String> effective = new ArrayList<>(excludes);
        if (excludeMetaInf) {
            effective.add("META-INF/**");
        }
        return new ResourceFilter(includes, effective);
    }
}
