(load "bootstrap")

(ns katello.testrunner
  (:require clojure.pprint
            [katello.tests.suite :as suite]
            [bugzilla.checker :refer [open-bz-bugs]]
   )
  (:use seesaw.core)
  (:import [javax.swing.tree DefaultMutableTreeNode]
           [javax.swing JTree])
  )

(def win-width 900)
(def win-height 600)
(def win-title "Katello Test Runner")

(def running (agent false))
(def test-map suite/katello-tests)
(def test-output (text :editable? false :multi-line? true))
(def root-node (DefaultMutableTreeNode. "Test Tree")) 
(def test-tree (JTree. root-node)) 

(def test-results-ref (atom nil))


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
                          "Blockers: " (:blockers sel-test) "\n"
                          "Steps: " (:steps sel-test)))
    )
  )

(defn update-test-output []
  (while (@running)
    (text! test-output (str @test-results-ref))
    (Thread/sleep 500))
  )

(defn run-test-click [test-tree]
  (let [sel-path (.getSelectionPath test-tree)
        sel-test (get-test-entry-from-path test-map sel-path 1)] 
      (send running not)
      (future 
        (do (user/debug sel-test test-results-ref)
            (send running not)))
      (future (update-test-output))
    )
  )

(defn mouse-pressed
  [e]

  (when (and (not @running) (= (.getButton e) 3)) 
    (let [x (.getX e)
          y (.getY e)]
      (with-widgets [(menu-item :text "Run Test" :id :menu-item1 :listen [:action (fn [sender] (run-test-click test-tree))]) 
                     (menu-item :text "Item 2" :id :menu-item2) 
                     (popup :items [menu-item1 menu-item2] :id :popup-menu)]
        (->> (.getPathForLocation test-tree x y) (.setSelectionPath test-tree))
        (.show popup-menu test-tree x y)))) 
  )

(defn -main [& args]

  (invoke-later
    (let [tree-scroll-pane (scrollable test-tree :hscroll :as-needed :vscroll :always)
          test-info (text :editable? false :multi-line? true :rows 5)
          info-scroll-pane (scrollable test-info :hscroll :as-needed :vscroll :as-needed)
          left-pane (top-bottom-split tree-scroll-pane info-scroll-pane :divider-location (/ (* win-height 3) 4))
          right-pane (scrollable test-output :hscroll :as-needed :vscroll :always)]

      (with-widgets [(left-right-split left-pane right-pane :id :main-panel :divider-location (/ win-width 3))]

        (listen test-tree 
          :selection #(selected-item-changed test-info %))
        (listen test-tree
          :mouse-pressed #(mouse-pressed %))

        (add-test-groups test-map root-node)
        (doseq [node-index [0 1]]  (.expandRow test-tree node-index))

        (-> (frame :title win-title
                   :content main-panel
                   :size [win-width :by win-height]
                   :on-close :exit)
            show!)
        )
      )
  ))
