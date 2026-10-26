package org.yanoproject.x.appchain.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Locale;

/**
 * Questions with defaults, and the {@code Next:} line every command ends with. Without a terminal (or with
 * {@code --yes}) every question takes its default, and a question without one fails with a usage hint.
 */
final class Terminal {
    private final BufferedReader in;
    private final PrintStream out;
    private final boolean interactive;
    private final boolean showNext;

    Terminal(BufferedReader in, PrintStream out, boolean interactive) {
        this(in, out, interactive, true);
    }

    /** {@code showNext} is false when another command runs this one as a step and gives its own Next line. */
    Terminal(BufferedReader in, PrintStream out, boolean interactive, boolean showNext) {
        this.in = in;
        this.out = out;
        this.interactive = interactive;
        this.showNext = showNext;
    }

    boolean interactive() {
        return interactive;
    }

    PrintStream out() {
        return out;
    }

    void say(String text) {
        out.println(text);
    }

    void blank() {
        out.println();
    }

    void ok(String text) {
        out.println("✓ " + text);
    }

    void warn(String text) {
        out.println("! " + text);
    }

    void next(String... lines) {
        if (!showNext) return;
        out.println();
        out.println("Next: " + lines[0]);
        for (int index = 1; index < lines.length; index++) out.println("      " + lines[index]);
    }

    /** Asks for a value; {@code flag} names the option a script uses instead. */
    String ask(String question, String fallback, String flag) {
        if (!interactive) {
            if (fallback != null) return fallback;
            throw CliException.usage(question + " (pass " + flag + ")");
        }
        while (true) {
            out.print(question + (fallback == null ? ": " : " [" + fallback + "]: "));
            out.flush();
            String line = readLine();
            if (line == null) {
                if (fallback != null) return fallback;
                throw CliException.usage(question + " (pass " + flag + ")");
            }
            line = line.strip();
            if (!line.isEmpty()) return line;
            if (fallback != null) return fallback;
        }
    }

    /** Numbered choice; returns the chosen option. */
    String choose(String question, List<String> options, List<String> descriptions, String fallback, String flag) {
        if (!interactive) {
            if (fallback != null) return fallback;
            throw CliException.usage(question + " (pass " + flag + ")");
        }
        out.println(question);
        for (int index = 0; index < options.size(); index++) {
            String description = descriptions == null ? "" : "  " + descriptions.get(index);
            out.printf(Locale.ROOT, "  %2d) %s%s%n", index + 1, options.get(index), description);
        }
        while (true) {
            String answer = ask("Choose 1-" + options.size() + " or a name", fallback, flag);
            if (options.contains(answer)) return answer;
            try {
                int index = Integer.parseInt(answer) - 1;
                if (index >= 0 && index < options.size()) return options.get(index);
            } catch (NumberFormatException ignored) {
                // Not a number; ask again.
            }
            out.println("Please choose one of the listed options.");
        }
    }

    boolean confirm(String question, boolean fallback) {
        if (!interactive) return fallback;
        String answer = ask(question + (fallback ? " (Y/n)" : " (y/N)"), fallback ? "y" : "n", "--yes");
        return answer.toLowerCase(Locale.ROOT).startsWith("y");
    }

    private String readLine() {
        try {
            return in.readLine();
        } catch (IOException error) {
            return null;
        }
    }
}
