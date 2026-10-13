#!/usr/bin/env bash
# Validates the ADR-031.4 frozen vectors and negative cases with the pinned cddl-cli 0.10.5.
# Usage: CDDL_BIN=<cddl-cli 0.10.5> validate_freeze.sh <schema.cddl> <vectors.properties> [pre-ADR vectors]
set -uo pipefail

C=${CDDL_BIN:-cddl}
CDDL=${1:?schema}; P=${2:?vectors}; OLD=${3:-}
W=$(mktemp -d); fails=0
$C --ci compile-cddl --cddl "$CDDL" >/dev/null || { echo "schema does not compile"; exit 1; }
# The pinned validator cannot evaluate regular-expression map keys; validate with plain text keys.
sed -e 's/binding-name => binding-scalar/tstr => binding-scalar/' -e 's/rule-parameter-name => binding-scalar/tstr => binding-scalar/' "$CDDL" > "$W/schema.cddl"
check() { { printf 'binding-vector-root = %s\n\n' "$2"; cat "$W/schema.cddl"; } > "$W/$1.cddl"; printf '%s' "$3" | xxd -r -p > "$W/$1.cbor"
 if $C --ci validate --cddl "$W/$1.cddl" --cbor "$W/$1.cbor" >"$W/$1.log" 2>&1; then r=PASS; else r=FAIL; fi
 [ "$r" = "$4" ] || fails=$((fails+1)); echo "$r (want $4) $1"; }
for n in $(grep -v '^#' "$P" | grep -v cddl-root | cut -d= -f1); do
  check "$n" "$(grep "^$n.cddl-root=" "$P" | cut -d= -f2)" "$(grep "^$n=" "$P" | cut -d= -f2)" PASS; done
if [ -n "$OLD" ]; then
  # Amended structures in their pre-ADR-031.4 layout must fail.
  # receipt.derived-denied is checked through its failed step below: cddl-cli 0.10.5 validates only
  # the first element of a keyed repetition, and that receipt's failure is in its second step.
  for n in ir.admission receipt.rule-denied receipt.rule-error receipt.rule-input receipt.fact-denied \
           receipt.rule-capacity receipt.fact-input; do
    check "pre-031.4.$n" "$(grep "^$n.cddl-root=" "$OLD" | cut -d= -f2)" "$(grep "^$n=" "$OLD" | cut -d= -f2)" FAIL; done
  # Structures ADR-031.4 did not amend keep their exact bytes.
  for n in ir.forward receipt.accepted receipt.rejected expression.threshold expression.fact-role; do
    if [ "$(grep "^$n=" "$OLD" | cut -d= -f2)" = "$(grep "^$n=" "$P" | cut -d= -f2)" ]; then echo "SAME (want SAME) $n";
    else echo "DIFF (want SAME) $n"; fails=$((fails+1)); fi; done
fi
neg() { check "$1" "$2" "$(python3 -c "import cbor2; print(cbor2.dumps($3, canonical=True).hex())")" "${4:-FAIL}"; }
# ADR-031.3 cases, amended where the layout changed.
neg bad.binding-facts-scope binding-boolean-expression '[1,3,[1,5,"roles"]]'
neg bad.binding-command-scope binding-boolean-expression '[1,3,[1,1,"amount"]]'
neg bad.rule-event-scope rule-boolean-expression '[1,3,[1,0,"amount"]]'
neg bad.rule-event-source rule-field-source '[0,0,"amount"]'
neg bad.binding-param-source binding-field-source '[0,2,"maxAmount"]'
neg bad.textset-result binding-expression-v1 '[1,4,[1,5,"roles"]]'
neg bad.deny-lowercase rule-denial '["r",0,"lower",None]'
neg bad.denial-clause-minus1 rule-failure '["r",-1,"DENY",None]'
neg bad.error-with-code rule-error-or-input '["r",0,"DENY",None]'
neg bad.limits-12 binding-limits '[8,32,4096,65536,2,8,65536,128,16,65536,1048576,33554432]'
neg bad.limits-rules-17 binding-limits '[8,32,4096,65536,2,8,65536,128,16,65536,1048576,33554432,17]'
neg bad.step-10 binding-step '[0,0,None,"a",bytes(32),[],[],"REJECTED","X",False]'
neg bad.component-6 binding-component '["a","b","t",{},0,1]'
neg bad.clause-9 rule-failure '["r",9,None,None]'
neg bad.param-type-5 rule-parameter '["p",5,None]'
neg bad.rule-no-clauses admission-rule '["r","D",None,[],[],[]]'
neg bad.context-timestamp binding-boolean-expression '[1,3,[2,"eq",[[1,4,"timestamp"],[0,0]]]]'
neg bad.binding-in binding-boolean-expression '[1,3,[2,"in",[[1,0,"a"],[1,0,"b"]]]]'
neg bad.zero-step-receipt binding-receipt-v1 '[1,bytes(32),1,"ACCEPTED",None,"",[]]'
neg bad.context-source-unknown binding-field-source '[0,4,"clock"]'
# ADR-031.4 cases.
neg bad.pre-031.4-derived-step binding-steps '[[1,1,"order-to-approval","approvals",bytes([0x11])*32,[],[],[0,["minimum-quorum",0,"QUORUM_TOO_LOW"]],"REJECTED","ADMISSION_RULE_DENIED",False]]'
neg bad.rule-without-reads admission-rule '["r","D",None,[],[[2,[1,3,[0,True]]]]]'
neg bad.failure-3 rule-failure '["r",0,"DENY"]'
neg bad.write-index-128 rule-denial '["r",0,"D",128]'
neg bad.input-with-index rule-error-or-input '["r",-1,None,0]'
neg bad.binding-read-scope binding-boolean-expression '[1,3,[1,6,"x","present"]]'
neg bad.binding-quantifier binding-boolean-expression '[1,3,[3,0,[0,True]]]'
neg bad.binding-element binding-boolean-expression '[1,3,[1,7,"op"]]'
neg bad.element-outside-quantifier rule-boolean-expression '[1,3,[1,7,"op"]]'
neg bad.nested-quantifier rule-boolean-expression '[1,3,[3,0,[3,1,[0,True]]]]'
neg bad.quantifier-kind-2 rule-boolean-expression '[1,3,[3,2,[0,True]]]'
neg bad.read-without-field rule-boolean-expression '[1,3,[1,6,"x"]]'
neg bad.read-value-wrong-tag rule-boolean-expression '[1,3,[1,6,"x","val","f"]]'
neg bad.read-key-reads rule-read '["x","c","",[0,6,"y"]]'
neg bad.read-key-element rule-read '["x","c","",[0,7,"op"]]'
neg bad.read-namespace rule-read '["x","c","!!",[1,"k"]]'
neg bad.lookup-key-reads rule-lookup-clause '[1,"c",[0,6,"y"],[0]]'
neg bad.lookup-expect-reads rule-lookup-clause '[1,"c",[1,"k"],[3,6,"y"]]'
neg bad.binding-mapping-reads binding-source '[0,6,"y"]'
neg bad.read-key-reads-expr rule-read '["x","c","",[3,[1,2,[1,6,"y","f"]]]]'
neg bad.read-key-quantifier rule-read '["x","c","",[3,[1,3,[3,0,[1,7,"op"]]]]]'
neg bad.lookup-key-reads-expr rule-lookup-clause '[1,"c",[3,[1,2,[1,6,"y","f"]]],[0]]'
neg bad.lookup-key-quantifier rule-lookup-clause '[1,"c",[3,[1,3,[3,0,[1,7,"op"]]]],[0]]'
neg bad.read-3 rule-read '["x","c",""]'
neg bad.read-5 rule-read '["x","c","",[1,"k"],1]'
neg bad.quantifier-2 rule-boolean-expression '[1,3,[3,0]]'
neg bad.quantifier-4 rule-boolean-expression '[1,3,[3,0,[0,True],[0,True]]]'
neg bad.starts-with-3 rule-boolean-expression '[1,3,[2,"startsWith",[[0,"a"],[0,"b"],[0,"c"]]]]'
neg bad.size-2 rule-boolean-expression '[1,3,[2,"ge",[[2,"size",[[0,"a"],[0,"b"]]],[0,1]]]]'
neg bad.failure-after-16 rule-trace '[16,["r",0,"D",None]]'
neg bad.standalone-nested-quantifier binding-expression-v1 '[1,3,[3,0,[3,1,[0,True]]]]'
neg ok.binding-starts-with binding-boolean-expression '[1,3,[2,"startsWith",[[1,0,"a"],[0,"b"]]]]' PASS
neg ok.binding-size binding-boolean-expression '[1,3,[2,"ge",[[2,"size",[[1,0,"a"]]],[0,1]]]]' PASS
neg ok.element-read-in-body rule-boolean-expression '[1,3,[3,1,[2,"eq",[[1,7,"key"],[1,6,"x","key"]]]]]' PASS
rm -rf "$W"; echo "unexpected outcomes: $fails"; exit $fails
