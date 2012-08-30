(ns katello.changesets
  (:require [katello.locators :as locators]
            [com.redhat.qe.auto.selenium.selenium
              :refer  [browser ->browser loop-with-timeout]]
            [slingshot.slingshot :refer [throw+ try+]]
            [tools.verify :refer [verify-that]]
            [katello.tasks :refer :all] 
            [katello.ui-tasks :refer :all] 
            [katello.sync-management :as sync]
            [katello.notifications :refer [check-for-success]]))

;;
;; Changesets
;;

(defn create-changeset
  "Creates a changeset for promotion from env-name to next-env name."
  [env-name next-env-name changeset-name]
  (navigate :named-environment-changesets-page {:env-name env-name
                                                :next-env-name next-env-name})
  (->browser (click :new-changeset)
             (setText :changeset-name-text changeset-name)
             (click :save-changeset))
  (check-for-success))

(defn add-to-changeset
  "Adds the given content to an existing changeset. The originating
   and target environments need to be specified to find to locate the
   changeset."
  [changeset-name from-env to-env content]
  (navigate :named-changeset-page {:env-name from-env
                                              :next-env-name to-env
                                              :changeset-name changeset-name})
  (doseq [category (keys content)]
    (browser click (-> category name (str "-category") keyword))
    (doseq [item (content category)]
      (browser click (locators/promotion-add-content-item item)))
    (browser sleep 5000)))  ;;sleep to wait for browser->server comms to update changeset
;;can't navigate away until that's done

(defn promote-changeset
  "Promotes the given changeset to its target environment. An optional
   timeout-ms key will specify how long to wait for the promotion to
   complete successfully."
  [changeset-name {:keys [from-env to-env timeout-ms]}]
  (let [nav-to-cs (fn [] (navigate :named-changeset-page
                                  {:env-name from-env
                                   :next-env-name to-env
                                   :changeset-name changeset-name}))]
    (nav-to-cs)
    (locking #'promotion-lock
      (browser click :review-for-promotion)
      ;;for the submission
      (loop-with-timeout 600000 []
        (when-not (try+ (browser click :promote-to-next-environment)
                        (check-for-success)
                        (catch [:type ::promotion-already-in-progress] _
                          (nav-to-cs)))
          (Thread/sleep 30000)
          (recur)))
      ;;for confirmation
      (loop-with-timeout (or timeout-ms 120000) [status ""]
        (case status
          "Applied" status
          "Apply Failed" (throw+ {:type :promotion-failed
                                      :changeset changeset-name
                                      :from-env from-env
                                      :to-env to-env})
          (do (Thread/sleep 2000)
              (recur (browser getText
                              (locators/changeset-status changeset-name))))))
      ;;wait for async success notif
      (check-for-success {:timeout-ms 180000 :refresh? true}))))

(defn promote-content
  "Promotes the given content from one environment to anot Example
  content:
     {:products ['Product1' 'Product2']} "
  [from-env to-env content]
  (let [changeset (uniqueify "changeset")]
    (create-changeset from-env to-env changeset)
    (add-to-changeset changeset from-env to-env content)
    (promote-changeset changeset {:from-env from-env
                                  :to-env to-env
                                  :timeout-ms 300000})))

(defn sync-and-promote [products from-env to-env]
  (let [all-prods (map :name products)
        all-repos (apply concat (map :repos products))
        sync-results (sync/perform-sync all-repos {:timeout 600000})]
        (verify-that (every? (fn [[_ res]] (sync/success? res))
                             sync-results))
        (promote-content from-env to-env {:products all-prods})))
