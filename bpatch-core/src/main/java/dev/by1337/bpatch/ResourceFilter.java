package dev.by1337.bpatch;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Case-sensitive, slash-separated globs: *, **, ?, and directory patterns ending in /. */
public final class ResourceFilter implements Predicate<String> {
    private final List<Pattern> includes;
    private final List<Pattern> excludes;

    public ResourceFilter(List<String> includes, List<String> excludes) {
        this.includes = compile(includes);
        this.excludes = compile(excludes);
    }

    public static ResourceFilter all() {
        return new ResourceFilter(List.of(), List.of());
    }

    @Override
    public boolean test(String name) {
        return (includes.isEmpty() || includes.stream().anyMatch(p -> p.matcher(name).matches()))
                && excludes.stream().noneMatch(p -> p.matcher(name).matches());
    }

    private static List<Pattern> compile(List<String> patterns) {
        return patterns.stream().map(ResourceFilter::compile).toList();
    }

    private static Pattern compile(String glob) {
        if (glob == null || glob.isBlank() || glob.startsWith("/") || glob.contains("\\")) {
            throw new IllegalArgumentException("Invalid resource glob: " + glob);
        }
        if (glob.endsWith("/")) {
            glob += "**";
        }
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        regex.append("(?:.*/)?");
                        i++;
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.append('$').toString());
    }
}
