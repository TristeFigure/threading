# threading

A Clojure library that provides a small set of threading arrows, the kind of arrows you need on an impulse.

## Usage

```clojure
[threading "0.1.3"]
```

```clojure
(ns my-ns
  (:require [threading.core :refer :all]))
```

## [API doc](https://tristefigure.github.io/threading/index.html)

## Arrows

**All of the arrows below come with both a `->` variant and a `->>` variant.**

**All of their arguments are forms in which the threading form will be injected, either at the beginning or the end, depending on which variant is used.**

As such each argument of these threading arrows can be considered as a **threading slot**.

### The anti-threading arrow

As a threading equivalent to `constantly`, and as a way to return a value from a *threading slot* that does not depend on the threaded value, use the `<-` *anti-threading* arrow:

Consider:
```clojure
(-> 123
    (when-not-> (or-> string? (<- *number-accepted?*))
      (throw (IllegalArgumentException. "Not a string"))))
```

If you happen to be in the context of a `->>`-like threading arrow, use `<<-` to antithread a value.
```clojure
(when-not-> (or->> (re-matches #"[a-z]+") (<<- *number-accepted?*)
  ...))
```

### Conditionnals

`if->`, `when->`, `if-not->`, `when-not->`

Contrary to their Clojure counterparts (`if`, `when`, etc ...), these threading arrows return the threaded value rather than `nil` when the predicate fails.

Consider:
```clojure
(defn xxx [x]
  (-> x
      (when-> string? (str "_abc"))
      vector))

(xxx "bird")
;; => ["bird_abc"]

(xxx 123)
;; => [123] (rather than [nil])
```

### Boolean operators

`and->`, `or->`

Like their Clojure counterparts, these threading arrows will return early and can be used to control the execution flow just like Clojure's `and` & `or`.

Consider:
```clojure
(and (string? "abc") (vector "abc"))
;; => ["abc"]
(and-> "abc" string? vector)
;; => ["abc"]

(and 123 (string? 123) (println 123))
;; => false
(and-> 123 string? println)
;; => false
```

### Control flow

`tap->` gets is inspiration from Ruby's [`tap`](https://apidock.com/ruby/Object/tap)

> **`tap()`** public
> 
> Yields x to the block, and then returns x. The primary purpose of this
> method is to “tap into” a method chain, in order to perform operations on
> intermediate results within the chain.

In Clojure, this gives:
```clojure
(-> 123
    (tap-> (println "-> the initial value"))
    inc
    (tap->> (println "New value is :")))
;; 123 -> the initial value
;; New value is: 124
;; => 124
```

#### `tap`

The `tap` macro will work in a similar way:
```clojure
(tap (inc 1) (println "Status: done"))
;; Status: done
;; => 2
```

Although it is not a threading arrow strictly speaking it will thread the tapped value into any threading form that happens to be at the first level in its body.

Consider:
```clojure
(tap (inc 1)
     (println "Status: done")
     (->> (println "Result:")))
;; Status: done
;; Result: 2
;; => 2
```

### Debugging

`pp->` and `pp->>` will execute each form, threading them together, and will display at each step a debug line, then return the value from the last expr.
 
```clojure
(pp->> {:a 1 :b 2}
       (merge {:c 3})
       (map (fn [[k n]] [k (inc n)]))
       (into {}))
;; ->>                               : {:a 1, :b 2}
;;     (merge {:c 3})                : {:c 3, :a 1, :b 2}
;;     (map (fn [[k n]] [k (inc n)])): ([:c 4] [:a 2] [:b 3])
;;     (into {})                     : {:c 4, :a 2, :b 3}

;; => {:c 4, :a 2, :b 3}
```

### More

#### `map->`

```clojure
(map-> [1 2 3] (+ 3))
;; => [4 5 6]
```

## Defining new arrows

The challenge lying in defining both the `->` and `->>` variants, observe the actual definition of `tap`:

```clojure
(defthreading tap
  "Threads the expr through the forms then returns the value of
  the initial expr."
  [-> "Threads like `->`."
   ->> "Threads like `->>`."]
  [expr & forms]
  `(let [result# ~expr]
     (~&threading-variant result# ~@forms) ;; &threading-variant here is
     result#))                             ;; successively '-> then '->>
```


## TODO

-  `cond->`, maybe `merge->`, etc... Contributions are welcome if they are driven by *an impluse*.
-  `pp->` is a bit weird at times.



Copyright © 2018 TristeFigure

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
