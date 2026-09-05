# Changelog

## Unreleased
- export a clj-kondo configuration. The macros take a supervisor as their
  first argument, which clj-kondo cannot interpret on its own: `go-loop-try`,
  `go-loop-try-`, `go-loop-super` and `go-for` put it where the binding vector
  is expected, so every loop binding was reported as an unresolved symbol and
  every `recur` as the wrong arity. Hooks rewrite those calls into the forms
  the macros expand to, and the `[S & body]` macros are declared `:lint-as do`.
  A consumer picks this up with `clj-kondo --copy-configs`, which lets a
  project with these macros run lint as a build gate.

## 0.2.8
- fix too many pending takes on abort-ch
- add reduce<

## 0.2.6
- fix alts? https://github.com/replikativ/superv.async/issues/2
- bump core.async

## 0.2.5
- bump core.async

## 0.2.2
- import debounce>> from full.async
- move to core.test from midje (partial port of new full.async tests)
- unify namespaces
- start work towards clojure.spec
- CircleCI
