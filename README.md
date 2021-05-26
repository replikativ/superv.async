# superv.async

<p align="center">
<a href="https://clojurians.slack.com/archives/CB7GJAN0L"><img src="https://img.shields.io/badge/clojurians%20slack-join%20channel-blueviolet"/></a>
<a href="https://clojars.org/io.replikativ/superv.async"> <img src="https://img.shields.io/clojars/v/io.replikativ/superv.async.svg" /></a>
<a href="https://circleci.com/gh/replikativ/superv.async"><img src="https://circleci.com/gh/replikativ/superv.async.svg?style=shield"/></a>
<a href="https://github.com/replikativ/superv.async/tree/development"><img src="https://img.shields.io/github/last-commit/replikativ/superv.async/development"/></a>
<a href="https://versions.deps.co/replikativ/superv.async" title="Dependencies Status"><img src="https://versions.deps.co/replikativ/superv.async/status.svg" /></a>
</p>

*Let it crash.* The Erlang approach to build reliable systems.

Errors happen, and proper error handling cannot be bolted on top of subsystems.
This library draws
on [the Erlang philosophy](http://erlang.org/download/armstrong_thesis_2003.pdf)
of error handling to
help
[building robust distributed systems that deal with errors by default](https://whilo.github.io/articles/16/error-handling1).
A detailed description of Erlang supervisors can also be
found [here](http://learnyousomeerlang.com/supervisors).

This is a Clojure(Script) library that
extends [core.async](https://github.com/clojure/core.async) with error handling
and includes a number of convenience functions and macros. This library is a
fork of [full.async](https://github.com/fullcontact/full.monty). The original
attempt to merge this work with it
failed
[due to the limitations of a dynamic binding approach](https://github.com/fullcontact/full.async).
The fork became reasonable, because full.async mostly deals with convenience
functions, but it is not as radically focused on proper error handling. Since
the error handling cannot happen through a transparent dynamic binding, some
convenience is lost in `superv.async` as you need to carry around the supervisor
lexically. If binding support comes to ClojureScript for asynchronous contexts
the projects might merge again. The binding approach also has a performance
penalty though. Since the boundary of both error handling libraries is the
exception mechanism and core.async channels, they still compose, but supervision
will not compose with `full.async` contexts.


## Usage

Add this to your project.
[![Clojars Project](http://clojars.org/io.replikativ/superv.async/latest-version.svg)](http://clojars.org/io.replikativ/superv.async)

### Exception Handling

Exception handling is an area for which `core.async` doesn't have support out of
the box. If something within a `go` block throws an exception, it will be logged
in the thread pool or logged to the cljs console, but the block will simply
return `nil`, hiding any information about the actual exception. You could wrap
the body of each `go` block within an exception handler but that's not very
convenient. `superv.async` does this internally for you and provides a set of
helper functions and macros for dealing with exceptions in a simple and robust
manner:

* `go-try`: equivalent of `go` but catches any exception thrown and returns via
the resulting channel
* `<?`, `alts?`, `<??`: equivalents of `<!`, `alts!` and `<!!` but if the value
is an exception, it will get rethrown. This also ensures a proper stacktrace
between async contexts.

### Supervision

`go-try` and the `?` expressions work well when you have a sequential execution
of goroutines because you can just rethrow the exceptions on the higher levels
of the call-stack. This fails for concurrent goroutines like go-loops or
background tasks, which are often the long-term purpose of the introduction of
asynchronous programming in the first place. To handle these concurrent
situations we have introduced an Erlang inspired supervision concept.

Two requirements for robust systems in Erlang are:

1. All exceptions in distributed processes are caught in a unifying supervision context
2. All concurrently acquired resources are freed on an exception

We decided to form a supervision context in which exceptions thrown in
concurrent parts get reported to a supervisor:

```clojure
(require '[superv.async :refer [S go-try restarting-supervisor on-abort]])

(let [try-fn (fn [S] (go-try S (throw (ex-info "stale" {}))))
      start-fn (fn [rs] ;; will be called again on retries
                 (go-try rs
                   (on-abort rs ;; you must ensure the freeing of resources for proper restarts
                     "do cleanup here")
                   (try-fn rs) ;; triggers restart after stale-timeout
                   42))]
  (<?? S (restarting-supervisor start-fn :retries 3 :stale-timeout 1000)))
```

The restarting supervisor tracks all nested goroutines and waits until all are
finished and have hence freed all resources (through `on-abort` cleanup
routines) before it tries to restart or finally returns itself either the result
or the exception. This allows composition of supervised contexts. There is a
default simple-supervisor `S` in the `superv.async` namespace for convenience
during development. We recommend to carry it as `S` along in your call stack.
While `go-try`, `thread-try` and `go-loop-try` propagate errors to their
supervisor, their errors, if they run concurrently, will only be caught after
they become stale. For the cases of concurrent go-routines `go-super`,
`go-super-loop` and `thread-super` exist. They will immediately report
exceptions to the supervisor and not propagate them through the call stack,
yielding an immediate restart or failure if necessary and shortening the restart
cycle.

To really free resources you just use the typical stack-based try-catch-finally
mechanism. To support this, channel operations `<?`, `>?`, `alt?`, `put?`...
trigger an "abort" exception when the supervisor detects an exception somewhere.
This at least eventually guarantees the termination of all blocking contexts,
since we cannot use preemption on errors like the Erlang VM does. In cases where
you have long blocking IO without core.async, you can insert some dummy blocking
operation to speed up restarts.

The supervisor tracks all thrown exceptions, so whenever they are not taken off
some channel and become stale, it will timeout and restart, depending on the
exception type it is tracking.

## Sequences & Collections

Channels by themselves are quite similar to sequences however converting between
them may sometimes be cumbersome. `superv.async` provides a set of convenience
methods derived from `full.async` for this:

* `<<!`, `<<?`: takes all items from the channel and returns them as a collection.
Must be used within a `go` block.
* `<<!!`, `<<??`: takes all items from the channel and returns as a lazy
sequence. Returns immediately.
* `<!*`, `<?*`, `<!!*`, `<??*` takes one item from each input channel and
returns them in a single collection. Results have the same ordering as input
channels.

* `go-for` is an adapted for-comprehension with channels instead of
lazy-seqs. It allows complex control flow across function boundaries
where a single transduction context would be not enough. For example:

```clojure
(let [query-fn #(go (* % 2))
      goroutine #(go [%1 %2])]
     (<<?? S (go-for S [a [1 2 3]
                        :let [b (<? S (query-fn a))]
                        :when (> b 5)]
                      (<? S (goroutine a b)))))
```

Note that channel operations are side-effects, so this is best used to
realize read-only operations like queries. Nested exceptions are
propagated according to go-try semantics.

Without `go-for` you needed to form some boilerplate around function
boundaries like this, which is much less succinct:

```clojure
(<<?? (->> [1 2 3]
           (map #(go [% (<? (query-fn %))]))
           async/merge
           (async/into [])
           (filter #(> (second %) 5))
           (map (apply goroutine))
           async/merge))
```

## Parallel Processing

`pmap>>` lets you apply a function to channel's output in parallel,
returning a new channel with results.

## Conventions

For readability of code, `superv.async` follows these conventions from
`full.async`:
* Async operators that throw exceptions use `?` in place of `!`, for example
throwing counterpart of `<!` is `<?`.
* Functions that return channel that will contain zero or one value (typically
result of `go` blocks) are sufixed with `>`. Similarly operators that expect
zero/single value channel as input are prefixed with `<` (for example `<?`).
* Functions that return channel that will contain zero to many values are
sufixed with `>>`. Similarly operators that expect zero to many value channel as
input are prefixed with `<<` (for example `<<?`).

## Change Log

See [CHANGES](CHANGES.md).


## TODO

- add spec signatures


## License

Copyright (C) 2015-2016 Christian Weilbach, 2016 FullContact. Distributed under the Eclipse Public License, the same as Clojure.
