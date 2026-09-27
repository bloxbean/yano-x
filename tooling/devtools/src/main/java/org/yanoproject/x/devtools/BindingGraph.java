package org.yanoproject.x.devtools;

import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingIrV1.CommandTarget;
import org.yanoproject.x.composite.contracts.BindingIrV1.EffectTarget;

import java.util.Locale;
import java.util.Map;

/** Deterministic Graphviz rendering of committed binding metadata, without activating runtime plugins. */
public final class BindingGraph {
    private BindingGraph() { }

    /**
     * Renders components, ordered binding edges, and separate effect sinks as UTF-8-compatible DOT text.
     * Conditions remain a label count, not an assertion that an edge will execute for every event. A component
     * with admission rules (ADR-031.3) gets one guard node listing them in evaluation order: the admission slot,
     * then the verified-fact slot, each with its command selector and deny code.
     * Names are quoted and escaped; authored identifiers cannot inject Graphviz statements.
     *
     * @param ir structurally valid committed binding document
     * @return stable DOT ending in one newline
     */
    public static String dot(BindingIrV1 ir) {
        return dot(ir, Map.of());
    }

    /**
     * Renders as {@link #dot(BindingIrV1)}, placing each rule in the slot the profile resolved. A rule that reads
     * write coverage is a fact rule by its kernel's declaration (ADR-031.4 §1.6), which the IR alone cannot show;
     * {@code resolved} holds the {@code declarative-event-bindings} manifest attributes
     * ({@code admission.<component>.<nn>.slot}). Without an attribute the IR's own classification is used.
     *
     * @param ir structurally valid committed binding document
     * @param resolved manifest attributes of the validated profile, or empty
     * @return stable DOT ending in one newline
     */
    public static String dot(BindingIrV1 ir, Map<String, String> resolved) {
        StringBuilder graph = new StringBuilder("digraph bindings {\n  rankdir=LR;\n");
        for (var component : ir.components()) {
            graph.append("  ").append(quote(component.id())).append(" [label=")
                    .append(quote(component.id() + "\n" + component.machineId())).append("];\n");
        }
        for (var component : ir.components()) {
            if (component.admission().isEmpty()) continue;
            StringBuilder label = new StringBuilder("admission rules");
            for (boolean factSlot : new boolean[]{false, true}) {
                for (int index = 0; index < component.admission().size(); index++) {
                    var rule = ir.rule(component.admission().get(index).rule());
                    if (rule == null) continue;
                    String attribute = resolved.get(String.format(Locale.ROOT, "admission.%s.%02d.slot",
                            component.id(), index));
                    String slot = attribute != null ? attribute : rule.readsFacts() ? "fact" : "admission";
                    if (slot.equals("fact") != factSlot) continue;
                    // Without the profile a rule over writes may read coverage, which only its kernel declares.
                    boolean unresolved = attribute == null && !rule.readsFacts() && rule.readsWrites();
                    label.append("\n").append(rule.id()).append(rule.command() == null ? "" : " on " + rule.command())
                            .append(" → ").append(rule.denyCode()).append(factSlot ? " (facts)" : "")
                            .append(unresolved ? " (writes; slot resolved by the kernel)" : "");
                    for (var read : rule.reads()) {
                        label.append("\n  reads ").append(read.name()).append(" ← ").append(read.component())
                                .append(read.namespace().isEmpty() ? "" : "/" + read.namespace());
                    }
                }
            }
            String guard = "guard:" + component.id();
            graph.append("  ").append(quote(guard)).append(" [shape=octagon,label=").append(quote(label.toString()))
                    .append("];\n  ").append(quote(guard)).append(" -> ").append(quote(component.id()))
                    .append(" [style=dashed,arrowhead=tee];\n");
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
