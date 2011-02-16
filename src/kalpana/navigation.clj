(ns kalpana.navigation
  (:require [kalpana.locators :as locate])
  (:use [com.redhat.qe.auto.selenium.selenium :only [browser]]
        [clojure.zip :as zip]))

(defmacro page [name-kw fndef & links]
  (let [m {:page name-kw         
              :fn fndef}
        fn-args (second fndef)
        args  (if (empty? fn-args) {} {:req-args (vec (map keyword fn-args))})
        linkmap (if (nil? links) {} {:links (vec links)})]
    (merge m args linkmap)))

(def nav-tree
  (page :organizations-tab (fn [] (browser clickAndWait :organizations))
        (page :new-organization-page  (fn [] (browser clickAndWait :new-organization)))
        (page :named-organization-page  (fn [org-name] (browser clickAndWait (locate/org-link org-name)))
              (page :edit-organization-page  (fn [] (browser clickAndWait :edit-organization))))))

(def page-zip (zip/zipper #(contains? % :links)
                          #(:links %)
                          #(conj %1 {:links %2})
                          nav-tree))
