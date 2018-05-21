(ns threading.core
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [shuriken.core :refer [conform! adjust truncate lines join-lines
                                   format-code debug-print]]))

(s/def ::macro-variants
  (s/and vector?
         (s/* (s/cat
                :macro     symbol?
                :docstring (s/? string?)
                :opts      (s/? map?)))))

(s/def ::defthreading-args
  (s/cat
    :name-prefix (s/? symbol?)
    :doc-prefix  (s/? string?)
    :variants    ::macro-variants
    :bodies      :shuriken.spec/args+bodies))

;; TODO: document
(defmacro ^{:arglists [[name-prefix? doc-prefix? [(variant docstring? opts?)+]
                        [params*] body]
                       [name-prefix? doc-prefix? [(variant docstring? opts?)+]
                        ([params*] prepost-map? body)+]]}
  defthreading
  "Defines a threading arrow with variants.

  Each variant is defined by a sequence like `(name docstring? opts?)`.

  The name of each defined threading arrow will consist in the
  variant symbol (usually `->` and `->>`) prefixed by `name-prefix`
  if provided.
  Same goes for each arrow docstring: it will be prefixed with
  `doc-prefix` if provided.

  The `body` will have access to some local variables defined under
  the hood:

  - `&threading-variant` (mapped to the variant's name)
  - `&threading-opts`    (mapped to the variant's opts map if provided)

  Keep in mind `defthreading` defines macros: the body should
  return Clojure code, not runtime values.


  Example:

  Consider this definition of [[tap]] with a custom `->debug`
  threading variant:
  ```clojure

  (defmacro ->debug [& forms]
    `(-> ~@forms))

  (defthreading tap
    \"Some documentation.\"
    [-> \"Some sufix for the documentation.\"
     ->> \"Another documentation complement\"
     ->debug {:debug true}] ;; No documentation here
    [expr & forms]
    `(let [result# ~expr]
       (when (:debug ~&threading-opts)
         (println \"debug->:\" result#))
       (~&threading-variant result# ~@forms)
       result#))

  (tap->debug 1 (println \": perform some side-effect\"))
  ;; debug->: 1
  ;; 1 : perform some side-effect.
  ;; => 1
  ```
  "
  [& args]
  (let [{:keys [name-prefix doc-prefix variants bodies]}
        (conform! ::defthreading-args args)]
    (cons 'do (for [{:keys [macro docstring opts]} variants
                    :let [specific-name (symbol (str name-prefix macro))
                          specific-doc
                          (if (or doc-prefix docstring)
                            (clojure.string/trim
                              (str doc-prefix \newline
                                   (->> docstring
                                        lines
                                        (map #(-> (str/replace % #"^\s+" "")
                                                  (->> (str "  "))))
                                        join-lines))))
                          bodies (map (fn [{:keys [body] :as plan}]
                                        (->> body)
                                        (assoc plan :body
                                          `((let [~'&threading-variant '~macro
                                                  ~'&threading-opts '~opts]
                                              ~@body))))
                                      bodies)]]
                (do (->> {:def-macro  'defmacro
                          :name       specific-name
                          :doc-string specific-doc
                          :attr-map   nil ; TODO
                          :bodies     bodies}
                         (remove (fn [[k v]] (nil? v)))
                         (into {})
                         (s/unform :shuriken.spec/macro-definition)))))))

(defthreading tap
  "Threads the expr through the forms then returns the value of
  the initial expr."
  [->  "Threads like `->`."
   ->> "Threads like `->>`."]
  [expr & forms]
  `(let [result# ~expr]
     (~&threading-variant result# ~@forms)
     result#))

(defmacro tap
  "Evaluates expressions in order returning the value of the first.
  Will thread the first expr into any subsequent threading expr.

  (tap 123
       (println \"yo\")
       (some-> inc println))
  ; yo
  ; 124
  ; => 123"
  [expr & body]
  (let [result-sym (gensym "result-")]
    `(let [~result-sym ~expr]
       ~@(map (fn [x]
                (if (some->> x first str (re-matches #"^.*->>?$"))
                  `(-> ~result-sym ~x)
                  x))
              body)
       ~result-sym)))

(defmacro ^:no-doc literally [expr]
  `(let [expr# '~expr
         value# ~expr]
     [(and (not (instance? clojure.lang.Iterate value#))
           (= (str expr#) (str value#)))
      value#]))

(defn ^:no-doc pp->log* [variant max-label-length label result]
  (let []
    (debug-print
      (adjust :left max-label-length label)
      result)))

(def ^:dynamic *pp-lazy-max* 100)

(defthreading pp
  [->  "Like `->`, but prints a debug statement for `f` and each expr in `forms`."
   ->> "Like `->>`, but prints a debug statement for `f` and each expr in `forms`."]
  [f & forms]
  (let [max-expr-length (->> forms
                             (map #(->> % format-code lines first
                                        (str &threading-variant " ") count))
                             (apply max))
        log-sym (gensym "log")
        padding (apply str (-> &threading-variant (str " ") count (repeat " ")))]
    `(let [[literal?# result-f#] (literally ~f)
           label-f# (str ~(str &threading-variant)
                         (when-not literal?# (str " " '~f)))
           max-label-length# (max ~max-expr-length
                                  (min (count label-f#)
                                       24))
           ~log-sym (partial pp->log*
                             ~(str &threading-variant)
                             max-label-length#)]
       (~log-sym (truncate label-f# max-label-length#)
                 (if (instance? clojure.lang.Iterate result-f#)
                   (take *pp-lazy-max* result-f#)
                   result-f#))
       (~&threading-variant
         result-f#
         ~@(map (fn [expr]
                  `((fn [x#]
                      (let [result# (~&threading-variant x# ~expr)]
                        (~log-sym ~(str padding expr)
                                  (if (instance? clojure.lang.Iterate result#)
                                    (take *pp-lazy-max* result#)
                                    result#))
                        result#))))
                forms)))))

(defthreading if
  "Threads `expr` through `test` then `then` or `else`. If `else` is
  not provided, returns `expr` when `test` fails."
  [->  "Threads like `->`."
   ->> "Threads like `->>`."]
  ([expr test then]
   `(~(symbol (str 'if &threading-variant))
     ~expr ~test ~then identity))
  ([expr test then else]
   `(let [e# ~expr]
      (if (~&threading-variant e# ~test)
        (~&threading-variant e# ~then)
        (~&threading-variant e# ~else)))))

(defthreading if-not
  "Threads `expr` through `test` then `then` or `else`. If `else` is
  not provided, returns `expr` when `test` fails."
  [->  "Threads like `->`."
   ->> "Threads like `->>`."]
  ([expr test then]
   `(~(symbol (str 'if-not &threading-variant))
     ~expr ~test ~then identity))
  ([expr test then else]
   `(let [e# ~expr]
      (if-not (~&threading-variant e# ~test)
        (~&threading-variant e# ~then)
        (~&threading-variant e# ~else)))))

(defthreading when
  "Threads `expr` through `test` then the rest of the `forms` if it succeeds.
  Returns `expr` otherwise."
  [->  "Threads like `->`."
   ->> "Threads like `->>`."]
  [expr test & forms]
  `(let [e# ~expr]
     (if (~&threading-variant e# ~test)
       (~&threading-variant e# ~@forms)
       e#)))

(defthreading when-not
  "Threads `expr` through `test` then the rest of the `forms` if it fails.
  Returns `expr` otherwise."
  [->  "Threads like `->`."
   ->> "Threads like `->>`."]
  [expr test & forms]
  `(let [e# ~expr]
     (if-not (~&threading-variant e# ~test)
       (~&threading-variant e# ~@forms)
       e#)))

(defthreading and
  "Threads `expr` through each form halting on a `nil` or `false` result."
  [->  "Threads like `->`."
   ->> "Threads like `->>`."]
  [expr & forms]
  (let [v-sym (gensym "v-")]
    `(let [~v-sym ~expr]
       (and ~@(map (fn [e]
                     `(~&threading-variant ~v-sym ~e))
                   forms)))))

(defthreading or
  "Threads `expr` through each expr halting on a non `nil` or `false` result."
  [->  "Threads like `->`."
   ->> "Threads like `->>`."]
  [expr & forms]
  (let [v-sym (gensym "v-")]
    `(let [~v-sym ~expr]
       (or ~@(map (fn [e]
                     `(~&threading-variant ~v-sym ~e))
                   forms)))))

(defthreading not
  "Threads `expr` through the `forms` then returns the negated result."
  [->  "Threads like `->`."
   ->> "Threads like `->>`."]
  [expr & forms]
  `(not (~&threading-variant ~expr ~@forms)))

(defthreading map
  "For an `expr` that will yield a sequence, threads each of its values
  through the `forms`."
  [->  "Threads like `->`."
   ->> "Threads like `->>`."]
  [expr & forms]
  `(let [expr# ~expr]
     (map (fn [f#]
            (~&threading-variant f# ~@forms))
          expr#)))

(defthreading
  [<-
   "Used to provide arbitrary input and output values to forms threading in the
   style of `->`.
   The threaded form will be evaluated although its value will be
   discarded."

   <<-
   "Like [[<-]] but must be used in the context of a form threading in the
   style of `->>`."]
  [& body]
  (case &threading-variant
    <-  `(do ~@body)
    <<- `(do ~(last body) ~@(butlast body))))

(defthreading
  "A threading arrow fletching used to change the position at which the
  threaded form will be injected in the threading slots of the threading form."
  [>-  "Transitions from a thread-last to a thread-first threading style."
   >>- "Transitions from a thread-first to a thread-last threading style."]
  [first-expr last-expr]
  (case &threading-variant
    >-  (let [threaded-expr last-expr
              [verb & args] first-expr]
          `(~verb ~threaded-expr ~@args))
    >>- (let [threaded-expr first-expr
              [verb & args] last-expr]
          `(~verb ~@args ~threaded-expr))))

(defmacro >-> [expr threaded-expr]
  `(>- (-> ~expr) ~threaded-expr))

(defmacro >->> [expr threaded-expr]
  `(>- (->> ~expr) ~threaded-expr))

(defmacro >>-> [threaded-expr expr]
  `(>>- ~threaded-expr (-> ~expr)))

(defmacro >>->> [threaded-expr expr]
  `(>>- ~threaded-expr (->> ~expr)))
