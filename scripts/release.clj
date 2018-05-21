(defdeps
  [[org.clojars.tristefigure/shuriken "0.14.1"]
   [threading "0.1.5"]])

(require '[clojure.java.shell :as shell]
         '[threading.core :refer :all]
         '[shuriken.core :refer [lines words tabulate]])

(defn sh [& args]
  (println args)
  (println (format "$> %s" (apply str (interpose " " args))))
  (let [{:keys [err exit out]} (apply shell/sh args)]
    (when (or (not (zero? exit)) (not (empty? err)))
      (throw (ex-info (format "Shell error: %s\n%s" exit (tabulate err "  "))
                      {:type :shell-error
                       :args args :err err :exit exit :out out})))
    (println out)
    (newline)))

(defn shs [s]
  (-> (lines s)
      (map-> (-> words (->> (apply sh))))
      doall))

(defn current-version []
  (-> "project.clj" slurp read-string (nth 2)))

(defn project-name []
  (-> "project.clj" slurp read-string (nth 1)))

(defn stash []
  (shs "git stash"))

(defn unstash []
  (shs "git stash pop"))

(defn version-tag []
  (shs (format "git tag -a %s" (current-version)))
  (shs "git push --tags"))

(defn clojars []
  (shs "lein deploy clojars"))

(defn documentation []
  (shs (format "rm -rf target/doc && mkdir target/doc
                git clone git@github.com:%s/%s.git target/doc
                cd target/doc
                git symbolic-ref HEAD refs/heads/gh-pages
                rm .git/index
                git clean -fdx
                cd ../.."
                "TristeFigure" (project-name)))
  (shs "lein codox")
  (shs (format "cd target/doc
                git checkout gh-pages # To be sure you're on the right branch
                git add .
                git commit -am \"Documentation for version %s.\"
                git push -fu origin gh-pages
                cd ../.."
               (current-version))))


(stash)
(clojars)
(version-tag)
(documentation)
(unstash)

(System/exit 0)
