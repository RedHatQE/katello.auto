(ns katello.changesets
  (:require (katello [navigation :as nav]
                     [tasks :refer :all] 
                     [ui-common :as ui] 
                     [sync-management :as sync]
                     [notifications :refer [check-for-success]])
            [com.redhat.qe.auto.selenium.selenium :as sel]
            [slingshot.slingshot :refer [throw+ try+]]
            [test.assert :as assert]))

;; Locators

(sel/template-fns
 {add-content-item    "//a[@data-display_name='%s' and contains(.,'Add')]"
  content-category    "//div[@id='%s']"
  content-item-n      "//div[@id='list']//li[%s]//div[contains(@class,'simple_link')]/descendant::text()[(position()=0 or parent::span) and string-length(normalize-space(.))>0]"
  remove-content-item "//a[@data-display_name='%s' and contains(.,'Remove')]"
  select-product      "//span[contains(.,'%s')]"
  changeset-status    "//span[.='%s']/..//span[@class='changeset_status']"
  })

(swap! ui/uimap merge
       {:products-category           (content-category "products")
        :expand-path                 "path-collapsed"
        :errata-category             (content-category "errata")
        :packages-category           (content-category "packages")
        :kickstart-trees-category    (content-category "kickstart trees")
        :templates-category          (content-category "templates")
        :promotion-eligible-home     "//div[@id='content_tree']//span[contains(@class,'home_img_inactive')]"

        :review-for-promotion        "review_changeset"
        :promote-to-next-environment "//div[@id='promote_changeset' and not(contains(@class,'disabled'))]"
        :promotion-empty-list        "//div[@id='left_accordion']//ul[contains(.,'available for promotion')]"
        :new-changeset               "//a[contains(.,'New Changeset')]"
        :changeset-name-text         "changeset[name]"
        :save-changeset              "save_changeset_button"
        :changeset-content           "//div[contains(@class,'slider_two') and contains(@class,'has_content')]"
        :changeset-type              "changeset[action_type]"
        :select-deletion-changeset   "//div[@data-cs_type='deletion']"
        :select-repos                "//div[contains(@class,'simple_link') and contains(.,'Repositories')]"
        :select-packages             "//div[contains(@class,'simple_link') and contains(.,'Packages')]"
        :select-errata               "//div[contains(@class,'simple_link') and contains(.,'Errata')]"
        :select-errata-all           "//div[contains(@class,'simple_link') and contains(.,'All')]"})

(nav/graft-page-tree
 :content-tab
 [:changeset-promotions-tab [] (sel/browser mouseOver :changeset-management)
  [:changesets-page [] (sel/browser clickAndWait :changesets)
   [:named-environment-changesets-page [env-name next-env-name]
    (nav/select-environment-widget env-name {:next-env-name next-env-name :wait true})
    [:named-changeset-page [changeset-name changeset-type]
     (do
       (when (= changeset-type "deletion")
         (sel/browser click :select-deletion-changeset))
       (sel/browser click (ui/changeset changeset-name)))]]]
  [:changeset-promotion-history-page [] (sel/browser clickAndWait :changeset-history)]])

;; Tasks

(defn create
  "Creates a changeset for promotion from env-name to next-env name 
  or for deletion from env-name."
  [env-name changeset-name & [{:keys [deletion? next-env-name]}]]
  (nav/go-to :named-environment-changesets-page {:env-name env-name 
                                                 :next-env-name next-env-name})
  (if deletion? (sel/browser click :select-deletion-changeset))
  (sel/->browser (click :new-changeset)
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
  (nav/go-to :named-changeset-page {:env-name from-env
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
             (sel/->browser (click :products-category)  
                            (click (select-product prod-item))
                            (click (keyword (str "select-" (name category)))))
             (doseq [add-item add-items ] 
               (sel/browser click (add-content-item add-item))))))
       
       (= category :errata)
       (do
         (sel/browser click :errata-category)
         (sel/browser click :select-errata-all)
         (doseq [add-item (map :name data )]
           (sel/browser click (add-content-item add-item))))
       
       :else
       (do
         (doseq [item data]  
           (sel/->browser 
            (click :products-category)
            (click (add-content-item item))))))
      ;; sleep to wait for browser->server comms to update changeset
      ;; can't navigate away until that's done

      (sel/->browser (sleep 5000)
                     (click :promotion-eligible-home)))))

(defn promote-or-delete
  "Promotes the given changeset to its target environment and could also Delete  
   content from an environment. An optional timeout-ms key will specify how long to  
   wait for the promotion or deletion to complete successfully."
  [changeset-name {:keys [deletion? from-env to-env timeout-ms]}]
  (let [nav-to-cs (fn [] (nav/go-to :named-changeset-page
                                   {:env-name from-env
                                    :next-env-name to-env
                                    :changeset-name changeset-name
                                    :changeset-type (if deletion? "deletion" "promotion")}))]
    (nav-to-cs)
    (locking #'promotion-deletion-lock
      (sel/browser click :review-for-promotion)
      ;;for the submission
      (sel/loop-with-timeout 600000 []
        (when-not (try+ (sel/browser click :promote-to-next-environment)
                        (check-for-success)
                        (catch [:type ::promotion-already-in-progress] _
                          (nav-to-cs)))
          (Thread/sleep 30000)
          (recur)))
      ;;for confirmation
      (sel/loop-with-timeout (or timeout-ms 120000) [status ""]
        (case status
          "Applied" status
          "Apply Failed" (throw+ {:type :promotion-failed
                                  :changeset changeset-name
                                  :from-env from-env
                                  :to-env to-env})
          (do (Thread/sleep 2000)
              (recur (sel/browser getText
                                  (changeset-status changeset-name))))))
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

(defn- extract-content []
  (let [elems (for [index (iterate inc 1)]
                (content-item-n (str index)))
        retrieve (fn [elem]
                   (try (sel/browser getText elem)
                        (catch Exception e nil)))]
    (->> (map retrieve elems) (take-while identity) set)))

(defn environment-content
  "Returns the content that is available to promote, in the given environment."
  [env-name]
  (nav/go-to :named-environment-changesets-page {:env-name env-name
                                                 :next-env-name nil})
  (let [categories [:products :templates]]
    (zipmap categories
            (doall (for [category categories]
                     (do
                       (sel/browser click (-> category name (str "-category") keyword))
                       (sel/browser sleep 2000) 
                       (let [result (extract-content)]
                         (sel/browser click :promotion-eligible-home)
                         result)))))))

(defn ^{:TODO "finish me"} change-set-content [env]
  (nav/go-to :named-environment-changesets-page {:env-name env}))

(defn enviroment-has-content?
  "If all the content is present in the given environment, returns true."
  [env content]
  (nav/go-to :named-environment-changesets-page {:env-name env :next-env-name ""})
  (every? true?
          (flatten
           (for [category (keys content)]
             (do (sel/browser click (-> category name (str "-category") keyword))
                 (for [item (content category)]
                   (try (do (sel/browser isVisible
                                         (add-content-item item))
                            true)
                        (catch Exception e false))))))))