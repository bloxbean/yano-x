package org.yanoproject.x.appchain.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code --name value}, {@code --flag} and positional arguments, checked against what a verb accepts. */
final class Args {
    private final Map<String, List<String>> options = new LinkedHashMap<>();
    private final List<String> positionals = new ArrayList<>();

    Args(String[] arguments, Set<String> valued, Set<String> flags) {
        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];
            if (argument.startsWith("--")) {
                String name = argument.substring(2);
                String inline = null;
                int equals = name.indexOf('=');
                if (equals > 0) {
                    inline = name.substring(equals + 1);
                    name = name.substring(0, equals);
                }
                if (flags.contains(name) && inline == null) {
                    options.computeIfAbsent(name, key -> new ArrayList<>()).add("true");
                } else if (valued.contains(name)) {
                    String value = inline;
                    if (value == null) {
                        if (index + 1 >= arguments.length) throw CliException.usage("--" + name + " needs a value");
                        value = arguments[++index];
                    }
                    options.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
                } else {
                    throw CliException.usage("Unknown option --" + name);
                }
            } else {
                positionals.add(argument);
            }
        }
    }

    boolean flag(String name) {
        return options.containsKey(name);
    }

    String value(String name) {
        List<String> values = options.get(name);
        return values == null ? null : values.getLast();
    }

    String value(String name, String fallback) {
        String value = value(name);
        return value == null ? fallback : value;
    }

    List<String> values(String name) {
        return options.getOrDefault(name, List.of());
    }

    Integer integer(String name) {
        String value = value(name);
        if (value == null) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw CliException.usage("--" + name + " must be a number");
        }
    }

    Path path(String name, Path fallback) {
        String value = value(name);
        return value == null ? fallback : Path.of(value);
    }

    List<String> positionals() {
        return positionals;
    }

    String positional(int index) {
        return index < positionals.size() ? positionals.get(index) : null;
    }

    void maxPositionals(int count, String usage) {
        if (positionals.size() > count) throw CliException.usage("Too many arguments. Usage: " + usage);
    }
}
