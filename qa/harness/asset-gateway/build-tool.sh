#!/bin/bash
# Compiles GatewayDevnet into OUT (default ./gw-tool) and writes the launcher OUT/gw. Run from anywhere:
#   OUT=<dir> qa/harness/asset-gateway/build-tool.sh
set -eu
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
OUT=${OUT:-$PWD/gw-tool}
mkdir -p "$OUT/classes"
(cd "$REPO" && ./gradlew -q -I "$HERE/classpath.gradle" -DcpOut="$OUT/cp.txt" \
  :ledgers:asset-payments:ledger:gatewayDevnetClasspath)
javac -d "$OUT/classes" -cp "$(cat "$OUT/cp.txt")" "$HERE/GatewayDevnet.java"
printf '#!/bin/bash\nexec java -cp "%s/classes:$(cat %s/cp.txt)" GatewayDevnet "$@"\n' "$OUT" "$OUT" > "$OUT/gw"
chmod +x "$OUT/gw"
echo "GW=$OUT/gw"
