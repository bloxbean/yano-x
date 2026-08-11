# Yano core host boundary

Yano X consumes the Yano app-chain host but does not own its built-in runtime
features. OrderedLog, consensus, finality, anchoring, the webhook executor and
sink, the operations API, and the console UI remain in
[bloxbean/yano](https://github.com/bloxbean/yano).

The matching Yano release documentation is authoritative for those features.
This repository documents and tests the JVM plugins, products, examples, and
tooling layered on that host.
