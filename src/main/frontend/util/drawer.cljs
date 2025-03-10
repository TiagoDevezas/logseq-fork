(ns frontend.util.drawer
  (:require [clojure.string :as string]
            [frontend.util :as util]
            [frontend.util.property :as property]
            [frontend.format.mldoc :as mldoc]))

(defn drawer-start
  [typ]
  (util/format ":%s:" (string/upper-case typ)))

(defonce drawer-end ":END:")

(defonce logbook-start ":LOGBOOK:")

(defn build-drawer-str
  ([typ]
   (build-drawer-str typ nil))
  ([typ value]
   (if value
     (string/join "\n" [(drawer-start typ) value drawer-end])
     (string/join "\n" [(drawer-start typ) drawer-end]))))

(defn get-drawer-ast
  [format content typ]
  (let [ast (mldoc/->edn content (mldoc/default-config format))
        typ-drawer (ffirst (filter (fn [x]
                                     (mldoc/typ-drawer? x typ)) ast))]
    typ-drawer))

(defn insert-drawer
  [format content typ value]
  (when (string? content)
    (try
      (let [ast (mldoc/->edn content (mldoc/default-config format))
            has-properties? (some (fn [x] (mldoc/properties? x)) ast)
            has-typ-drawer? (some (fn [x] (mldoc/typ-drawer? x typ)) ast)
            lines (string/split-lines content)
            title (first lines)
            body (string/join "\n" (rest lines))
            start-idx (.indexOf lines (drawer-start typ))
            end-idx (let [[before after] (split-at start-idx lines)]
                      (+ (count before) (.indexOf after drawer-end)))
            result  (cond
                      (not has-typ-drawer?)
                      (let [drawer (build-drawer-str typ value)]
                        (if has-properties?
                          (cond
                            (= :org format)
                            (let [prop-start-idx (.indexOf lines property/properties-start)
                                  prop-end-idx (.indexOf lines property/properties-end)
                                  properties (subvec lines prop-start-idx (inc prop-end-idx))
                                  after (subvec lines (inc prop-end-idx))]
                              (string/join "\n" (concat [title] properties [drawer] after)))

                            :else
                            (let [properties-count (count (second (first (second ast))))
                                  before (subvec lines 0 (inc properties-count))
                                  after (rest lines)]
                              (string/join "\n" (concat before [drawer] after))))
                          (str title "\n" drawer "\n" body)))

                      (and has-typ-drawer?
                           (>= start-idx 0) (> end-idx 0) (> end-idx start-idx))
                      (let [before (subvec lines 0 start-idx)
                            middle (conj
                                    (subvec lines (inc start-idx) end-idx)
                                    value)
                            after (subvec lines (inc end-idx))
                            lines (concat before [(drawer-start typ)] middle [drawer-end] after)]
                        (string/join "\n" lines))
                      :else
                      content)]
        (string/trimr result))
      (catch js/Error e
        (js/console.error e)
        content))))

(defn contains-logbook?
  [content]
  (and (util/safe-re-find (re-pattern (str "(?i)" logbook-start)) content)
       (util/safe-re-find (re-pattern (str "(?i)" drawer-end)) content)))

;; TODO: DRY
(defn remove-logbook
  [content]
  (if (contains-logbook? content)
    (let [lines (string/split-lines content)
          [title-lines body] (split-with (fn [l]
                                           (not (string/starts-with? (string/upper-case (string/triml l)) ":LOGBOOK:")))
                                         lines)
          body (drop-while (fn [l]
                             (let [l' (string/lower-case (string/trim l))]
                               (or
                                (not (string/starts-with? l' ":end:"))
                                (string/blank? l))))
                           body)
          body (if (and (seq body)
                        (string/starts-with? (string/lower-case (string/triml (first body))) ":end:"))
                 (let [line (string/replace (first body) #"(?i):end:\s?" "")]
                   (if (string/blank? line)
                     (rest body)
                     (cons line (rest body))))
                 body)]
      (->> (concat title-lines body)
           (string/join "\n")))
    content))

(defn get-logbook
  [body]
  (-> (filter (fn [v] (and (vector? v)
                          (= (first v) "Drawer")
                          (= (second v) "logbook"))) body)
      first))

(defn with-logbook
  [block content]
  (let [new-clocks (last (get-drawer-ast (:block/format block) content "logbook"))
        body (:block/body block)
        logbook (get-logbook body)]
    (if logbook
      (let [content (remove-logbook content)
            clocks (->> (concat new-clocks (last logbook))
                        (distinct))
            clocks (->> (map string/trim clocks)
                        (remove string/blank?))
            logbook (->> (concat [":LOGBOOK:"] clocks [":END:"])
                         (string/join "\n"))
            lines (string/split-lines content)]
        (if (:block/title block)
          (str (first lines)
               "\n"
               logbook
               "\n"
               (string/join "\n" (rest lines)))
          content))
      content)))
