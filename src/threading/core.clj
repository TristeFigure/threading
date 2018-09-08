(ns threading.core
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [shuriken.spec :refer [conform!]]
            [shuriken.string :refer [adjust truncate lines join-lines
                                     format-code]]
            [shuriken.debug :refer [debug-print]]
            [shuriken.associative :refer [map-keys map-vals]]))

(s/def ::threading-variant
  (s/cat
    :arrow     symbol?
    :docstring (s/? string?)
    :opts      (s/? map?)))

(s/def ::threading-variants
  (s/and vector?
         (s/* ::threading-variant)))

(s/def ::defthreading-args
  (s/cat
    :thread-name    (s/? symbol?)
    :arrow-position (s/? #{:prefix :suffix})
    :doc-prefix     (s/? string?)
    :variants       ::threading-variants
    :bodies         :shuriken.spec/args+bodies))

(defmacro ^{:arglists [[thread-name? arrow-position?
                        doc-prefix?
                        [(variant docstring? opts?)+]
                        [params*]
                        body*]
                       [thread-name? arrow-position?
                        doc-prefix?
                        [(variant docstring? opts?)+]
                        [params*]
                        ([params*] prepost-map? body)+]]}
  defthreading
  "Defines a threading arrow with variants.

  Each variant is defined by a sequence like `(name docstring? opts?)`.

  The name of each defined threading arrow will consist in the
  variant symbol (usually `->` and `->>`) prefixed by `thread-name`
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

  Remarks:

  - `name` is optional.
  - `name` must be followed by the `:prefix` `arrow-position` to
    define fletchings."
  [& args]
  (let [{:keys [thread-name arrow-position doc-prefix variants bodies]}
        (conform! ::defthreading-args args)]
    (cons 'do (for [{:keys [arrow docstring opts]} variants
                    :let [specific-name (-> (case (or arrow-position :suffix)
                                              :suffix (str thread-name arrow)
                                              :prefix (str arrow thread-name))
                                            symbol)
                          specific-doc
                          (if (or doc-prefix docstring)
                            (clojure.string/trim
                              (str doc-prefix
                                   (some->> docstring
                                            lines
                                            (map #(-> (str/replace % #"^\s+" "")
                                                      (->> (str "  "))))
                                            join-lines
                                            (str \newline)))))
                          bodies
                          (map (fn [{:keys [body] :as plan}]
                                 (assoc plan :body
                                   `((let [~'&threading-variant '~arrow
                                           ~'&threading-opts '~opts]
                                       ~@body))))
                               bodies)]]
                (do (->> {:op        'defmacro
                          :name       specific-name
                          :doc-string specific-doc
                          :attr-map   nil ; TODO
                          :bodies     bodies}
                         (remove (fn [[k v]] (nil? v)))
                         (into {})
                         (s/unform :shuriken.spec/defmacro-form)))))))

(defthreading
  "A threading fletching used to change the position at which the threaded
  form will be injected in the threading slots of the threading form."
  [>-  "Transitions from a thread-first to a thread-last threading style."
   >>- "Transitions from a thread-last to a thread-first threading style."]
  [first-expr second-expr]
  (case &threading-variant
    >-  (let [threaded-expr first-expr
              [verb & args] second-expr]
          `(~verb ~@args ~threaded-expr))
    >>- (let [threaded-expr second-expr
              [verb & args] first-expr]
          `(~verb ~threaded-expr ~@args))))

(defmacro >->
  "Shorthand for `(>- (-> ...)."
  [threaded-expr expr]
  `(>- ~threaded-expr (-> ~expr)))

(defmacro >->>
  "Shorthand for `(>- (->> ...)."
  [threaded-expr expr]
  `(>- ~threaded-expr (->> ~expr)))

(defmacro >>->
  "Shorthand for `(>>- (-> ...)."
  [expr threaded-expr]
  `(>>- (-> ~expr) ~threaded-expr))

(defmacro >>->>
  "Shorthand for `(>>- (->> ...)."
  [expr threaded-expr]
  `(>>- (->> ~expr) ~threaded-expr))

(defthreading
  "As an equivalent to `constantly`, the anti-threading arrow is used to
  return an arbitrary value from a threading slot.
  The threaded expression will be evaluated although its value will be
  discarded."
  [<-  "To be used in the context of a form threading in the style of `->`."
   <<- "To be used in the context of a form threading in the style of `->>`."]
  [first-expr second-expr]
  (case &threading-variant
    <-  `(do ~second-expr)
    <<- `(do ~first-expr)))

(defthreading tap
  "Threads `expr` through `forms` then returns the value of
  the initial expression."
  [->  "Threads like `->`."
   ->> "Threads like `->>`."]
  [expr & forms]
  `(let [result# ~expr]
     (~&threading-variant result# ~@forms)
     result#))

(defmacro tap
  "Evaluates expressions in order returning the value of the first.
  Will thread the first expression into any immediate threading
  subexpressions.

  ```clojure
  (tap 123
       (println \"yo\")
       (some-> inc println))
  ; yo
  ; 124
  ; => 123
  ```"
  [expr & body]
  (let [result-sym (gensym "result-")]
    `(let [~result-sym ~expr]
       ~@(map (fn [x]
                (if (some->> x first str (re-matches #"^.*->>?|-••?$"))
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

(defthreading mapv
  "For an `expr` that will yield a sequence, threads each of its values
  through the `forms`. Returns the transformed sequence as a vector."
  [->  "Threads like `->`."
   ->> "Threads like `->>`."]
  [expr & forms]
  `(let [expr# ~expr]
     (mapv (fn [f#]
            (~&threading-variant f# ~@forms))
          expr#)))

(defthreading mapcat
  "For an `expr` that will yield a sequence, threads each of its values
  through the `forms` then returns the individual results concatenated together
  as a seq."
  [->  "Threads like `->`."
   ->> "Threads like `->>`."]
  [expr & forms]
  `(let [expr# ~expr]
     (mapcat (fn [f#]
               (~&threading-variant f# ~@forms))
             expr#)))

(defthreading map-vals
  "For an `expr` that will yield an associative structure, threads each of its
  values through the `forms`."
  [->  "Threads like `->`."
   ->> "Threads like `->>`."]
  [expr & forms]
  `(let [expr# ~expr]
     (map-vals (fn [f#]
                 (~&threading-variant f# ~@forms))
               expr#)))

(defthreading map-keys
  "For an `expr` that will yield an associative structure, threads each of its
  keys through the `forms`."
  [->  "Threads like `->`."
   ->> "Threads like `->>`."]
  [expr & forms]
  `(let [expr# ~expr]
     (map-keys (fn [f#]
                 (~&threading-variant f# ~@forms))
               expr#)))

(defthreading juxt
  "Threads `expr` through each form individually, collecting the results in a
  lazy sequence."
  [->  "Threads like `->`."
   ->> "Threads like `->>`."]
  [expr & forms]
  (let [expr-sym (gensym "expr-")]
    `(let [~expr-sym ~expr]
       (lazy-cat
         ~@(map (fn [form]
                  `[(~&threading-variant ~expr-sym ~form)])
                forms)))))

(defthreading let
  "Threads `expr` through each bound expression then through each form in
  `body` like `let`."
  [->  "Threads like `->`."
   ->> "Threads like `->>`."]
  [expr bindings & body]
  (let [input-sym (gensym "input-")
        adapt (fn [expr] `(~&threading-variant ~input-sym ~expr))]
    `(as-> ~expr ~input-sym
       (let ~(->> bindings
                  (partition 2)
                  (>>- (map-vals-> adapt))
                  (apply concat)
                  vec)
         ~@(map adapt body)))))

(defthreading binding
  "Threads `expr` through each bound expression then through each form in
  `body` like `binding`."
  [->  "Threads like `->`."
   ->> "Threads like `->>`."]
  [expr bindings & body]
  (let [input-sym (gensym "input-")
        adapt (fn [expr] `(~&threading-variant ~input-sym ~expr))]
    `(as-> ~expr ~input-sym
       (binding ~(->> bindings
                      (partition 2)
                      (>>- (map-vals-> adapt))
                      (apply concat)
                      vec)
         ~@(map adapt body)))))

(def ^:private •-value-sym (gensym "•-value-"))

(defthreading
  "Stores `expr` on the stack then threads it to `form`.
  In `form`, any deep occurence of the `-•` arrow will thread this
  value stored on the stack to its inner expressions.

  Note that `-•` can only be used within the body of a `•-` form."
  [•-  "Must be used in the context of a thread-first arrow."
   ••- "Must be used in the context of a thread-last arrow."]
  [first-expr second-expr]
  (let [[expr form arrow] (case &threading-variant
                            •-  [first-expr  second-expr '->]
                            ••- [second-expr first-expr  '->>])]
    `(let [~•-value-sym ~expr]
       (~arrow ~•-value-sym ~form))))

(defthreading
  "See [[•-]]."
  [-•  "Threads like `->`."
   -•• "Threads like `->>`."]
  [& forms]
  (let [arrow (case &threading-variant
                -• '->
                -•• '->>)]
    `(~arrow ~•-value-sym ~@forms)))

(defthreading args :prefix
  "Fletching that uses the arrow in the threading form to thread the
  expression in all subforms of its subform. `form` must have only one subform.

  Fletching that moves the arrow in the threading form inside its subform's
  arguments. The threading arrow must have only one subform."
  [>-  "Will thread the expr into the subforms with `->`."
   >>- "Will thread the expr into the subforms with `->>`."]
  [first-expr second-expr]
  (let [[expr [arrow subform]] (case &threading-variant
                                 >-  [first-expr  second-expr]
                                 >>- [second-expr first-expr])
        [head & subform-subforms] subform
        v-sym (gensym "v-")]
    `(let [~v-sym ~expr]
       (~head ~@(map (fn [sub]
                       `(~arrow ~v-sym ~sub))
                     subform-subforms)))))
