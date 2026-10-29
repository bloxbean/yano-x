package org.yanoproject.x.devtools;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CddlSchemaCompilerTest {

    @Test
    void compilesNamedRulesMapsRangesSizesChoicesAndOptionalFields() {
        String source = """
                root = {
                  id: short-text,
                  count: 1..10,
                  ? ok: bool,
                  state: "active" / "held"
                }
                short-text = tstr .size (1..8)
                """;

        CddlSchemaCompiler.Compilation compilation =
                CddlSchemaCompiler.compile(source);

        assertThat(compilation.authoringLanguage()).isEqualTo("cddl-yano-subset-v1");
        assertThat(compilation.irCatalog()).isEqualTo("yano-cbor-schema-ir-v1");
        assertThat(compilation.schema().accepts(hex(
                "a46269646161626f6bf565636f756e740565737461746566616374697665")))
                .isTrue();
        assertThat(compilation.schema().accepts(hex(
                "a36269646065636f756e74056573746174656468656c64"))).isFalse();
        assertThat(compilation.schema().accepts(hex(
                "a3626964616165636f756e740b6573746174656468656c64"))).isFalse();
    }

    @Test
    void compilesBoundedArrayOccurrencesWithoutBacktrackingAmbiguity() {
        CddlSchemaCompiler.Compilation compilation =
                CddlSchemaCompiler.compile("""
                        root = [? uint, 1*3 label]
                        label = tstr .size (1..4)
                        """);

        assertThat(compilation.schema().accepts(hex("816178"))).isTrue();
        assertThat(compilation.schema().accepts(hex("82016178"))).isTrue();
        assertThat(compilation.schema().accepts(hex("846161616261636164"))).isFalse();
    }

    @Test
    void canonicalIrDoesNotDependOnMapOrChoiceSourceOrder() {
        byte[] first = CddlSchemaCompiler.compile("""
                root = {alpha: uint, beta: tstr, state: "a" / "b"}
                """).definition();
        byte[] second = CddlSchemaCompiler.compile("""
                root = {state: "b" / "a", beta: tstr, alpha: uint}
                """).definition();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void rejectsRecursiveUnboundedAndHostDependentCddl() {
        assertThatThrownBy(() -> CddlSchemaCompiler.compile("""
                root = child
                child = root
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recursive");
        assertThatThrownBy(() -> CddlSchemaCompiler.compile(
                "root = [* tstr]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CddlSchemaCompiler.compile(
                "root = tstr .regexp \".*\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported CDDL control");
        assertThatThrownBy(() -> CddlSchemaCompiler.compile(
                "root = external<thing>"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresAnExplicitlyDeclaredRootAndCanonicalNumbers() {
        assertThatThrownBy(() -> CddlSchemaCompiler.compile(
                "record = uint", "root"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root rule");
        assertThatThrownBy(() -> CddlSchemaCompiler.compile(
                "root = uint .le 01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical decimal");
    }

    @Test
    void boundsSourceNestingBeforeRecursiveParsingAndMatchesBrowserDomainVectors() {
        assertThatThrownBy(() -> CddlSchemaCompiler.compile("root = " + "(".repeat(1000) + "uint"
                + ")".repeat(1000))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nesting");
        assertThatThrownBy(() -> CddlSchemaCompiler.compile("root = {\"" + "é".repeat(129) + "\": uint}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("256 UTF-8 bytes");
        assertThat(CddlSchemaCompiler.compile("root = 0..18446744073709551616").definition())
                .isEqualTo(hex("82018400026130743138343436373434303733373039353531363135"));
        assertThat(CddlSchemaCompiler.compile("root = -18446744073709551617..0").definition())
                .isEqualTo(hex("8201840002752d31383434363734343037333730393535313631366130"));
    }

    @Test
    void boundsExpandedNestingAcrossNamedRulesWithoutCountingAliases() {
        StringBuilder nested = new StringBuilder();
        StringBuilder aliases = new StringBuilder();
        for (int i = 0; i < 128; i++) {
            String name = i == 0 ? "root" : "r" + i;
            String next = i == 127 ? "uint" : "r" + (i + 1);
            nested.append(name).append(" = ").append("[".repeat(60)).append(next)
                    .append("]".repeat(60)).append('\n');
            aliases.append(name).append(" = ").append(next).append('\n');
        }
        assertThatThrownBy(() -> CddlSchemaCompiler.compile(nested.toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expansion exceeds the nesting");
        assertThat(CddlSchemaCompiler.compile(aliases.toString()).definition())
                .isEqualTo(CddlSchemaCompiler.compile("root = uint").definition());
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }
}
