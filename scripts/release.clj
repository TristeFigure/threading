(defdeps
  [[org.clojars.tristefigure/shuriken "0.14.1"]
   [threading "0.1.5"]
   [jline "2.14.5"]])

(require '[clojure.java.shell :as shell]
         '[threading.core :refer :all]
         '[shuriken.core :refer [lines words tabulate]])

(import java.util.Scanner)
(import jline.console.ConsoleReader)

(defn sh [& args]
  (println (format "$> %s"
                   (apply str (take-while string? (interpose " " args)))))
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

(defn version-tag []
  (shs (format "git tag -a %s" (current-version)))
  (shs "git push --tags"))

(defn read-clojars-password []
  (.readLine (ConsoleReader.) "Clojars password:", \*))

(defn clojars []
  (shell/with-sh-env {"CLOJARS_USERNAME" "TristeFigure"
                      "CLOJARS_PASSWORD" (read-clojars-password)}
    (shs "lein deploy clojars"))
  #_(sh "expect" "-c"
      (str "\""
           "spawn lein deploy clojars; "
           "expect -re \\\"Username:\\\"; "
           "send \\\"TristeFigure\\n\\\"; "
           "expect -re \\\"Password:\\\"; "
           "send \\\"" (read-clojars-password) "\\n\\\"; "
           "expect eof"
           "\"")))

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


; (clojars)
(version-tag)
(documentation)

(System/exit 0)

expect -c "spawn lein deploy clojars; expect -re \"Username:\"; send \"TristeFigure\n\"; expect -re \"Password:\"; send \"az\n\"; expect eof;"
expect -c "spawn lein deploy clojars; expect -re \"Username:\"; send \"TristeFigure\n\"; expect -re \"Password:\"; send \"azerty\n\";"
