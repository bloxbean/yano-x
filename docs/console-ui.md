# Console UI boundary

The console UI source and build remain in the Yano repository during the
current split. Yano X contributes JVM plugins, capability metadata, examples,
and product definitions that the console discovers through Yano's generic
plugin and domain API contracts.

This is a temporary source-ownership boundary, not a special integration API.
Yano X extensions must remain usable without the console UI.
