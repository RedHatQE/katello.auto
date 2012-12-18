(ns katello.changesets
  (:require (katello [navigation :as nav]
                     [tasks :refer :all] 
                     [ui-common :as common]
                     [ui :as ui]
                     [sync-management :as sync]
                     [notifications :as notification :refer [check-for-success]])
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [slingshot.slingshot :refer [throw+ try+]]
            [test.assert :as assert]))

;; Locators

(sel/template-fns
 {add-content-item    "//a[@data-display_name='%s' and starts-with(@id,'add_remove_')]"
  content-category    "//div[@id='%s']"
  content-item-n      "//div[@id='list']//li[%s]//div[contains(@class,'simple_link')]/descendant::text()[(position()=0 or parent::span) and string-length(normalize-space(.))>0]"
  select-product      "//span[contains(.,'%s')]"
  status              "//span[.='%s']/..//span[@class='changeset_status']"
  list-item           "//div[starts-with(@id,'changeset_') and normalize-space(.)='%s']"})

;; Nav

(ui/deflocators
  {::products-category           (content-category "products")
   ::errata-category             (content-category "errata")
   ::packages-category           (content-category "packages")
   ::kickstart-trees-category    (content-category "kickstart trees")
   ::templates-category          (content-category "templates")
   ::promotion-eligible-home     "//div[@id='content_tree']//span[contains(@class,'home_img_inactive')]"
   ::review-for-promotion        "review_changeset"
   ::promote-to-next-environment "//div[@id='promote_changeset' and not(contains(@class,'disabled'))]"
   ::new                         "new"
   ::name-text                   "changeset_name"
   ::save                        "save_changeset_button"
   ::content                     "//div[contains(@class,'slider_two') and contains(@class,'has_content')]"
   ::type                        "changeset[action_type]"
   ::deletion                    "//div[@data-cs_type='deletion']"})

(nav/defpages (common/pages)
  [::page 
   [::named-environment-page [env-name next-env-name]
    (nav/select-environment-widget env-name {:next-env-name next-env-name :wait true})
    [::named-page [changeset-name deletion?] (do (when deletion?
                                                   (browser click ::deletion))
                                                 (browser click (list-item changeset-name)))]]])

;; Tasks

(defn create
  "Creates a changeset for promotion from env-name to next-env name 
  or for deletion from env-name."
  [env-name changeset-name & [{:keys [deletion? next-env-name]}]]
  (nav/go-to ::named-environment-page {:env-name env-name 
                                       :next-env-name next-env-name})
  (if deletion? (browser click ::deletion))
  (sel/->browser (click ::new)
                 (setText ::name-text changeset-name)
                 (click ::save))
  (check-for-success))


(defn add-content
  "Adds the given content to an existing changeset. The originating
   and target environments need to be specified to find to locate the
   changeset."
  ;; to-env is mandatory if promotion changeset
  ;; to-env not required if deletion changeset
  [changeset-name from-env content deletion & [{:keys [to-env]}]]
  (nav/go-to ::named-page {:env-name from-env
                           :next-env-name to-env
                           :changeset-name changeset-name
                           :deletion? deletion})
  (doseq [category (keys content)]
    (let [data (content category)
          grouped-data (group-by :product-name data)]
      (cond 
       (some #{category} [:repos :packages])
       (do
         (doseq [[prod-item repos] grouped-data]
           (let [add-items (map :name repos)] 
             (sel/->browser (click ::products-category)  
                            (click (select-product prod-item))
                            (click (keyword (str "select-" (name category)))))
             (doseq [add-item add-items ] 
               (browser click (add-content-item add-item))))))
       
       (= category :errata)
       (do
         (browser click ::errata-category)
         (browser click ::select-errata-all)
         (doseq [add-item (map :name data )]
           (browser click (add-content-item add-item))))
       
       :else
       (do
         (browser click ::products-category)
         (doseq [item data]  
           (browser click (add-content-item item)))))
      ;; sleep to wait for browser->server comms to update changeset
      ;; can't navigate away until that's done
      (sel/->browser (sleep 5000)
                     (click ::promotion-eligible-home)))))

(defn promote-or-delete
  "Promotes the given changeset to its target environment and could also Delete  
   content from an environment. An optional timeout-ms key will specify how long to  
   wait for the promotion or deletion to complete successfully."
  [changeset-name {:keys [deletion? from-env to-env timeout-ms]}]
  (let [nav-to-cs (fn [] (nav/go-to ::named-page
                                   {:env-name from-env
                                    :next-env-name to-env
                                    :changeset-name changeset-name
                                    :deletion? deletion?}))]
    (nav-to-cs)
    (locking #'promotion-deletion-lock
      (browser click ::review-for-promotion)
      ;;for the submission
      (sel/loop-with-timeout (* 10 60 1000) []
        (when-not (try+ (browser click ::promote-to-next-environment)
                        (check-for-success)
                        (catch [:type ::notification/promotion-already-in-progress] _
                          (nav-to-cs)))
          (Thread/sleep 30000)
          (recur)))
      ;;for confirmation
      (sel/loop-with-timeout (or timeout-ms (* 20 60 1000)) [current-status ""]
        (case current-status
          "Applied" current-status
          "Apply Failed" (throw+ {:type :promotion-failed
                                  :changeset changeset-name
                                  :from-env from-env
                                  :to-env to-env})
          (do (Thread/sleep 2000)
              (recur (browser getText (status changeset-name))))))
      ;;wait for async success notif
      (check-for-success {:timeout-ms (* 20 60 1000)}))))

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
                                  :deletion? deletion})))

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
                   (try (browser getText elem)
                        (catch Exception e nil)))]
    (->> (map retrieve elems) (take-while identity) set)))

(defn environment-content
  "Returns the content that is available to promote, in the given environment."
  [env-name]
  (nav/go-to ::named-environment-page {:env-name env-name
                                       :next-env-name nil})
  (let [categories [:products :templates]]
    (zipmap categories
            (doall (for [category categories]
                     (do
                       (browser click (-> category name (str "-category") keyword))
                       (browser sleep 2000) 
                       (let [result (extract-content)]
                         (browser click ::promotion-eligible-home)
                         result)))))))

(defn ^{:TODO "finish me"} change-set-content [env]
  (nav/go-to ::named-environment-page {:env-name env}))

(defn enviroment-has-content?
  "If all the content is present in the given environment, returns true."
  [env content]
  (nav/go-to ::named-environment-page {:env-name env :next-env-name ""})
  (every? true?
          (flatten
           (for [category (keys content)]
             (do (browser click (-> category name (str "-category") keyword))
                 (for [item (content category)]
                   (try (do (browser isVisible
                                     (add-content-item item))
                            true)
                        (catch Exception e false))))))))
