(ns katello.changesets
  (:require [katello.locators :as locators]
            [com.redhat.qe.auto.selenium.selenium
              :refer  [browser ->browser loop-with-timeout]]
            [slingshot.slingshot :refer [throw+ try+]]
            [test.assert :as assert]
            [katello.tasks :refer :all] 
            [katello.ui-tasks :refer :all] 
            [katello.sync-management :as sync]
            [katello.notifications :refer [check-for-success]]))

;;
;; Changesets
;;


(defn create
  "Creates a changeset for promotion from env-name to next-env name 
  or for deletion from env-name."
  [env-name changeset-name & [{:keys [deletion? next-env-name]}]]
  (navigate :named-environment-changesets-page {:env-name env-name 
                                                :next-env-name next-env-name})
  (if deletion? (browser click :select-deletion-changeset))
    (->browser (click :new-changeset)
               (setText :changeset-name-text changeset-name)
               (click :save-changeset))
  (check-for-success))
   

(defn add-content
  "Adds the given content to an existing changeset. The originating
   and target environments need to be specified to find to locate the
   changeset."
  ;; to-env is mandatory if promotion changeset
  ;; to-env not required if deletion changeset
  [changeset-name from-env content deletion & [{:keys [to-env]}]]
  (navigate :named-changeset-page {:env-name from-env
                                   :next-env-name to-env
                                   :changeset-name changeset-name
                                   :changeset-type (if deletion "deletion" "promotion")})
  (doseq [category (keys content)]
    (let [data (content category)
          grouped-data (group-by :product-name data)]
      (cond 
        (some #{category} [:repos :packages])
        (do
          (doseq [[prod-item repos] grouped-data]
            (let [add-items (map :name repos)] 
              (->browser 
                (click :products-category)  
                (click (locators/select-product prod-item))
                (click (keyword (str "select-" (name category)))))
              (doseq [add-item add-items ] 
                (browser click (locators/promotion-add-content-item add-item))))))
        
        (= category :errata)
        (do
          (browser click :errata-category)
          (browser click :select-errata-all)
          (doseq [add-item (map :name data )]
            (browser click (locators/promotion-add-content-item add-item))))
            
        :else
        (do
          (doseq [item data]  
            (->browser 
              (click :products-category)
              (click (locators/promotion-add-content-item item))))))
      ;; sleep to wait for browser->server comms to update changeset
      ;; can't navigate away until that's done

      (browser sleep 5000)
      (browser click :promotion-eligible-home))))

(defn promote-or-delete
  "Promotes the given changeset to its target environment and could also Delete  
   content from an environment. An optional timeout-ms key will specify how long to  
   wait for the promotion or deletion to complete successfully."
  [changeset-name {:keys [deletion? from-env to-env timeout-ms]}]
  (let [nav-to-cs (fn [] (navigate :named-changeset-page
                                  {:env-name from-env
                                   :next-env-name to-env
                                   :changeset-name changeset-name
                                   :changeset-type (if deletion? "deletion" "promotion")}))]
    (nav-to-cs)
    (locking #'promotion-deletion-lock
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
      (check-for-success {:timeout-ms 180000}))))

(defn promote-delete-content
  "Promotes the given content from one environment to another 
   Example content: {:products ['Product1' 'Product2']} "
  [from-env to-env deletion content]
  (let [changeset (uniqueify "changeset")]
    (create from-env changeset {:deletion? deletion
                                :next-env-name to-env})
    (add-content changeset from-env content deletion {:to-env to-env})
    (promote-or-delete changeset {:from-env from-env
                                  :to-env to-env
                                  :deletion? deletion
                                  :timeout-ms 300000})))

(defn sync-and-promote [products from-env to-env]
  (let [all-prods (map :name products)
        all-repos (apply concat (map :repos products))
        sync-results (sync/perform-sync all-repos {:timeout 600000})]
        (assert/is (every? (fn [[_ res]] (sync/success? res))
                             sync-results))
        (promote-delete-content from-env to-env false {:products all-prods})))
