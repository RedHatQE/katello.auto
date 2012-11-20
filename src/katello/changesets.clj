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


(defn create
  "Creates a changeset for promotion from env-name to next-env name."
  [env-name next-env-name changeset-name]
  (navigate :named-environment-changesets-page {:env-name env-name 
                                                :next-env-name next-env-name})
  (->browser (click :new-changeset)
             (setText :changeset-name-text changeset-name)
             (click :save-changeset))
  (check-for-success))

(defn create-deletion-changeset
  "Creates a deletion changeset which helps in deletion of Products, repos, 
   packages, errata from an environment"
  [env-name changeset-name changeset-type]
  (navigate :named-environment-changesets-page {:env-name env-name
                                                :next-env-name nil})
  (->browser (click :new-changeset)
             (setText :changeset-name-text changeset-name))
  (let [envs (extract-list locators/fetch-environments-in-org)]
    (if-not (some #{env-name} (list (last envs) (first envs))) 
      (fill-ajax-form {:changeset-type changeset-type}
                       :save-changeset)  
      (browser click :save-changeset)))
  (check-for-success))

(defn add-content
  "Adds the given content to an existing changeset. The originating
   and target environments need to be specified to find to locate the
   changeset."
  ;; to-env is mandatory if promotion changeset
  ;; to-env not required if deletion changeset
  [changeset-name changeset-type from-env content & to-env]
  (navigate :named-changeset-page {:env-name from-env
                                   :next-env-name (first to-env)
                                   :changeset-name changeset-name
                                   :changeset-type changeset-type})
  (doseq [category (keys content)]
    (browser click (-> category name (str "-category") keyword))
    (if (= changeset-type "promotion") 
      (doseq [item (content category)]
        (browser click (locators/promotion-add-content-item item)))
      (browser click (locators/promotion-add-content-item (first (content category)))))
      (browser sleep 5000)))  ;;sleep to wait for browser->server comms to update changeset
;;can't navigate away until that's done


(defn promote-or-delete
  "Promotes the given changeset to its target environment and could also Delete  
   content from an environment. An optional timeout-ms key will specify how long to  
   wait for the promotion or deletion to complete successfully."
  [changeset-name changeset-type {:keys [from-env to-env timeout-ms]}]
  (let [nav-to-cs (fn [] (navigate :named-changeset-page
                                  {:env-name from-env
                                   :next-env-name to-env
                                   :changeset-name changeset-name
                                   :changeset-type changeset-type}))]
    (nav-to-cs)
    (locking #'promotion-deletion-lock
      (browser click :review-for-promotion)
      ;;for the submission
      (loop-with-timeout 600000 []
        (when-not (try+ (browser click :promote-to-next-environment)
                        (check-for-success)
                        (catch [:type ::promotion-already-in-progress] _
                          (nav-to-cs))
                        (catch [:type ::repository-already-promoted] _ 
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
      (check-for-success {:timeout-ms 180000}))))

(defn promote-content
  "Promotes the given content from one environment to another 
   Example content: {:products ['Product1' 'Product2']} "
  [from-env to-env content]
  (let [changeset (uniqueify "changeset")
        changeset-type "promotion"]
    (create from-env to-env changeset)
    (add-content changeset changeset-type from-env content to-env)
    (promote-or-delete changeset changeset-type {:from-env from-env
                                                 :to-env to-env
                                                 :timeout-ms 300000})))

(defn delete-content
  "Deletes a given content from an environment"
  [from-env content]
  (let [changeset (uniqueify "del_changeset")
        changeset-type "deletion"]
    (create-deletion-changeset from-env changeset changeset-type)
    (add-content changeset changeset-type from-env content)
    (promote-or-delete changeset changeset-type {:from-env from-env
                                                 :timeout-ms 300000})))

(defn sync-and-promote [products from-env to-env]
  (let [all-prods (map :name products)
        all-repos (apply concat (map :repos products))
        sync-results (sync/perform-sync all-repos {:timeout 600000})]
        (verify-that (every? (fn [[_ res]] (sync/success? res))
                             sync-results))
        (promote-content from-env to-env {:products all-prods})))
