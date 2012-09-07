(ns katello.testrunner
  (:require clojure.pprint
            [katello.tests.suite :as suite]
            [bugzilla.checker :refer [open-bz-bugs]]
            test.tree.debug
            katello.conf
            [clojure.pprint :refer [pprint]]
   )
  (:use seesaw.core
        seesaw.chooser)
  (:import [javax.swing.tree DefaultMutableTreeNode]
           [javax.swing JTree]
           [javax.swing.tree TreePath]
           [java.text SimpleDateFormat]
           [java.lang.System])
  )

(def win-width 900)
(def win-height 600)
(def win-title "Katello Test Runner")

(def test-map suite/katello-tests)
(def test-tree-root (DefaultMutableTreeNode. "Test Tree")) 
(def test-tree (JTree. test-tree-root)) 
(def output-tree-root (DefaultMutableTreeNode. "Test Results")) 
(def output-tree (JTree. output-tree-root))
(def test-output (text :multi-line? true :wrap-lines? true))

(def is-running? (atom false))
(def test-results-ref (atom nil))
(def in-repl? true)
(def need-save? false)


(defn add-test-groups
  [test-group tree-node]

  (let [group-name (first (:groups test-group))
        new-node (DefaultMutableTreeNode. (str (:name test-group)))]

    (.add tree-node new-node)

    (when (contains? test-group :more)
      (.setUserObject new-node (first (:groups test-group)))
      (doseq [child-group (:more test-group)]
        (add-test-groups child-group new-node)))
    )
  )

(defn get-test-entry-from-path
  [test-map path path-idx]
  (let [next-path-idx (inc path-idx) 
        cur-node (.getPathComponent path path-idx) 
        child-node (.getPathComponent path (inc path-idx))
        index (.getIndex cur-node child-node)
        child-test-map (nth (:more test-map) (.getIndex cur-node child-node))]
    (if (<= (dec (.getPathCount path)) next-path-idx)
      child-test-map
      (get-test-entry-from-path child-test-map path next-path-idx))) 
  )

(defn selected-item-changed
  [test-info event-info]
  (let [path (.getPath event-info)
        sel-test (get-test-entry-from-path test-map path 1)]
    (text! test-info (str "Groups: " (:groups sel-test) "\n" 
                          "Parameters: " (:parameters sel-test) "\n"
                          "Blockers: " (str (:blockers sel-test)) "\n"
                          "Steps: " (str (:steps sel-test))))
    )
  )

(defn keyword-to-string [keywrd]
  (when keywrd (name keywrd))
  )

(defn get-date-string [d]
  (when (not (nil? d))
    (.format (SimpleDateFormat. "MM/dd/yyyy hh:mm:ss") d))
  )

(defn add-output-node [parent-node key-str val-str]
  (.add parent-node 
        (DefaultMutableTreeNode. (str key-str ": " (keyword-to-string val-str))))
  )

(defn refresh-test-output [watch-key watch-ref old-state new-state]

  (.removeAllChildren output-tree-root)

  (doseq [report-key (keys new-state)] 
    (let [report-val (get new-state report-key) 
          report-root (DefaultMutableTreeNode. (:name report-key))
          report      (:report report-val)]
      (.add output-tree-root report-root) 
      (add-output-node report-root "Start Time" (get-date-string (:start-time report)))
      (add-output-node report-root "End Time" (get-date-string (:end-time report)))
      (add-output-node report-root "Parameters" (:parameters report))
      (add-output-node report-root "Return Value" (:returned report))
      (add-output-node report-root "Status" (:status report-val))
      (add-output-node report-root "Promise" (:promise report-val))
      (.expandPath output-tree (TreePath. (.getPath report-root)))
      )
    )

  (try (.updateUI output-tree) (catch Exception e nil))
  (def need-save? true)
  )

(defn run-test-click [test-tree]
  ; TODO: Check need-save?
  (let [sel-path (.getSelectionPath test-tree)
        sel-test (get-test-entry-from-path test-map sel-path 1)] 
      (reset! is-running? true)
      (future 
        (do 
          (try (reset! test-results-ref 
                       (test.tree.debug/debug 
                         (with-meta sel-test {:watchers {:test-runner-watch refresh-test-output}}) 
                         (katello.conf/trace-list))) 
             (catch Exception e (println (str "Exception: " (str e)))))
            (reset! is-running? false)
            )
        )
    )
  )

(defn mouse-pressed [e]

  (when (= (.getButton e) 3)
    (let [x (.getX e)
          y (.getY e)]
      (with-widgets [(menu-item :text "Run Tests" 
                                :id :run-menu-item 
                                :listen [:action (fn [sender] (run-test-click test-tree))]) 
                     (menu-item :text "Terminate Run" 
                                :id :terminate-menu-item
                                :listen [:action (fn [sender] (test.tree/terminate-all-tests (second @test-results-ref)))])
                     (popup :items [run-menu-item terminate-menu-item] 
                            :id :popup-menu)]
        (config! run-menu-item :enabled? (not @is-running?))
        (config! terminate-menu-item :enabled? @is-running?)
        (->> (.getPathForLocation test-tree x y) (.setSelectionPath test-tree))
        (.show popup-menu test-tree x y)))) 
  )

(defn open-results [sender]
  ; TODO: Implementation
  )

(defn save-results [sender]
  (cond @is-running?  (alert "Wait for tests to complete before saving results.")
        (nil? @test-results-ref) (alert "No results available.")
        :else (let [filename (choose-file (to-root sender)
                                          :type :save
                                          :filters [["Text Files" ["txt"]]])]
                (when (not (nil? filename))
                  (binding [*out* (java.io.FileWriter. filename)] 
                           (prn (second @test-results-ref))
                           )
                  (def need-save? false)
                  )
                ))
  )

(defn start-gui [& args]

  ; Reset state
  (reset! test-results-ref nil)

  (let [tree-scroll-pane (scrollable test-tree :hscroll :as-needed :vscroll :always)
        test-info (text :editable? false :multi-line? true :rows 5)
        info-scroll-pane (scrollable test-info :hscroll :as-needed :vscroll :as-needed)
        left-pane (top-bottom-split tree-scroll-pane info-scroll-pane :divider-location (/ (* win-height 3) 4))
        right-pane (scrollable output-tree :hscroll :as-needed :vscroll :always)]

    (with-widgets [(left-right-split left-pane right-pane :id :main-panel :divider-location (/ win-width 3))
                   (menu-item :text "Open Results" 
                              :id :open-menu 
                              :listen [:action #(open-results %)])
                   (menu-item :text "Save Results" 
                              :id :save-menu 
                              :listen [:action #(save-results %)])
                   (menu-item :text "Exit" 
                              :id :exit-menu)
                   (menu      :text "File" 
                              :id :file-menu 
                              :items [open-menu save-menu (separator) exit-menu])
                   (menubar   :id :main-menu-bar 
                              :items [file-menu])
                   (frame     :id :main-frame
                              :title win-title
                              :menubar main-menu-bar
                              :content main-panel
                              :size [win-width :by win-height]
                              :on-close (if in-repl? :dispose :exit))]

      (listen exit-menu :mouse-pressed 
        (fn [sender] (if in-repl? (.dispose main-frame) (. System exit 0))))
      (listen test-tree 
        :selection #(selected-item-changed test-info %))
      (listen test-tree
        :mouse-pressed #(mouse-pressed %))

      (add-test-groups test-map test-tree-root)
      (doseq [node-index [0 1]]  (.expandRow test-tree node-index))

      (show! main-frame)
      )
    )
  )

(defn -main [& args]

  ;(add-watch test-results-ref nil #(refresh-test-output %1 %2 %3 %4))
  (invoke-later
    (load "/bootstrap")
    (def in-repl? false)
    (start-gui args)
  ))
