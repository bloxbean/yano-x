package org.yanoproject.x.devtools;

import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingIrV1.CommandTarget;
import org.yanoproject.x.composite.contracts.BindingIrV1.EffectTarget;

/** Deterministic Graphviz rendering of committed binding metadata, without activating runtime plugins. */
public final class BindingGraph {
    private BindingGraph() { }

    /**
     * Renders components, ordered binding edges, and separate effect sinks as UTF-8-compatible DOT text.
     * Conditions remain a label count, not an assertion that an edge will execute for every event.
     * Names are quoted and escaped; authored identifiers cannot inject Graphviz statements.
     *
     * @param ir structurally valid committed binding document
     * @return stable DOT ending in one newline
     */
    public static String dot(BindingIrV1 ir) {
        StringBuilder graph = new StringBuilder("digraph bindings {\n  rankdir=LR;\n");
        for (var component : ir.components()) {
            graph.append("  ").append(quote(component.id())).append(" [label=")
                    .append(quote(component.id() + "\n" + component.machineId())).append("];\n");
        }
        for (var binding : ir.bindings()) {
            String target;
            String command;
            if (binding.target() instanceof CommandTarget value) {
                target = value.component();
                command = value.command();
            } else {
                EffectTarget effect = (EffectTarget) binding.target();
                target = "effect:" + binding.id();
                command = effect.gate() + "/" + effect.resultPolicy();
                graph.append("  ").append(quote(target)).append(" [shape=box,label=")
                        .append(quote(effect.effectType())).append("];\n");
            }
            graph.append("  ").append(quote(binding.sourceComponent())).append(" -> ").append(quote(target))
                    .append(" [label=").append(quote(binding.id() + "\n" + binding.eventId() + " → " + command
                            + "\nconditions=" + binding.conditions().size())).append("];\n");
        }
        return graph.append("}\n").toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }
}
