# Promesa CLJD Runtime Workspace

This `dart/` directory is a runtime workspace used for ClojureDart compilation and test execution for the Promesa library.
It is not a sample application.

## Scope

- Focus CLJD support on `promesa.core` plus promise abstractions (`promesa.impl`, minimal `promesa.exec` support).
- CSP modules are out of scope unless explicitly requested.

## Commands

Run commands from this directory:

```bash
cd dart
```

Initialize/regenerate Dart runtime files:

```bash
clojure -M:cljd init
```

Compile CLJD:

```bash
clojure -M:cljd compile
```

Run CLJD tests (core support, CSP excluded):

```bash
clojure -M:cljd:cljd-test test
```

Clean CLJD build artifacts:

```bash
clojure -M:cljd clean
```

Full reset/rebuild:

```bash
clojure -M:cljd clean
clojure -M:cljd init
clojure -M:cljd compile
clojure -M:cljd:cljd-test test
```

## Notes

- CLJD configuration lives in `dart/deps.edn`.
- Generated artifacts are expected under `dart/` (for example `pubspec.yaml`, `pubspec.lock`, `.clojuredart`, `.dart_tool`).
- Keep `{:no-dynamic true}` on CLJD-targeted namespaces (`.cljc`/`.cljd`); treat dynamic warnings during CLJD compile as blockers.
