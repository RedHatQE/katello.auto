(ns katello.changesets
  (:refer-clojure :exclude [remove])
  (:require [katello :as kt]
            (katello [navigation :as nav]
                     [tasks :as tasks :refer [uniqueify with-unique]]
                     [ui-common :as common]
                     [ui :as ui]
                     [rest :as rest]
                     [conf :as conf]
                     [organizations :as organization]
                     [sync-management :as sync]
                     [notifications :as notification :refer [check-for-success request-type?]])
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [loop-with-timeout browser]]
            [slingshot.slingshot :refer [throw+ try+]]
            [test.assert :as assert]
            [clojure.data :as data]
            [clojure.string :as string]
            [inflections.core :refer [pluralize]]))

;; Locators

(sel/template-fns
 {add-content-item    "//a[@data-display_name='%s' and starts-with(@id,'add_remove_') and contains(.,'Add')]"
  remove-content-item "//a[@data-display_name='%s' and starts-with(@id,'add_remove_') and contains(.,'Undo')]"
  content-category    "//div[@id='%s']"
  content-item-n      "//div[@id='list']//li[%s]//div[contains(@class,'simple_link')]/descendant::text()[(position()=0 or parent::span) and string-length(normalize-space(.))>0]"
  select-product      "//span[contains(.,'%s')]"
  select-types        "//div[contains(@class,'simple_link') and contains(.,'%s')]"
  status              "//span[.='%s']/..//span[@class='changeset_status']"
  list-item           "//div[starts-with(@id,'changeset_') and normalize-space(.)='%s']"})

(defn check-all [template coll]
  (doseq [item coll]
    (browser check (template item))))
;; Nav

(ui/deflocators
  {::products-category           (content-category "products")
   ::errata-category             (content-category "errata")
   ::kickstart-trees-category    (content-category "kickstart trees")
   ::templates-category          (content-category "templates")
   ::select-errata               (select-types "Errata")
   ::select-repos                (select-types "Repositories")
   ::select-packages             (select-types "Packages")
   ::select-errata-all           (select-types "All")
   ::promotion-eligible-home     "//div[@id='content_tree']//span[contains(@class,'home_img_inactive')]"
   ::review-for-promotion        "review_changeset"
   ::promote-to-next-environment "//div[@id='promote_changeset' and not(contains(@class,'disabled'))]"
   ::new                         "new"
   ::name-text                   "changeset_name"
   ::save                        "save_changeset_button"
   ::content                     "//div[contains(@class,'slider_two') and contains(@class,'has_content')]"
   ::type                        "changeset[action_type]"
   ::deletion                    "//div[@data-cs_type='deletion']"
   ::remove-changeset            "//span[contains(.,'Remove')]"
   ::ui-box-confirm              "//span[@class='ui-button-text' and contains(.,'Yes')]"})  
   
(nav/defpages (common/pages)
  [::page
   [::named-environment-page [env next-env]
    (nav/select-environment-widget env {:next-env next-env :wait true})
    [::named-page [changeset deletion?] (do (when deletion?
                                              (browser click ::deletion))
                                            (browser click (list-item (:name changeset))))]]])

;; Protocol

(defn go-to-item-in-product [category-loc item]
  (browser click ::products-category)
  (browser click (select-product (-> item :product :name)))
  (browser click category-loc))

(defprotocol Promotable
  "Interface for entities that are promotable"
  (go-to [x] "Navigates to entity from a changeset's environment content view"))

(extend-protocol Promotable
  katello.Product
  (go-to [prod]
    (browser click ::products-category))

  katello.Template
  (go-to [template]
    (browser click ::templates-category))
  
  katello.Repository
  (go-to [repo] (go-to-item-in-product ::select-repos repo))

  katello.Package
  (go-to [package] (go-to-item-in-product ::select-packages package))

  katello.Erratum
  (go-to [erratum]
    (if (:product erratum)
      (go-to-item-in-product ::select-errata erratum)
      (do (browser click ::errata-category)
          (browser click ::select-errata-all)))))

(defn- add-rm [loc ent]
  (go-to ent)
  (browser click (-> ent :name loc)))

(def ^{:doc "Adds ent to current changeset (assumes the ui is on that
             page already)"}
  add (partial add-rm add-content-item))

(def ^{:doc "Removes ent from current changeset (assumes the ui is on
             that page already)"}
  remove (partial add-rm remove-content-item))
;; Tasks

(defn create
  "Creates a changeset for promotion from env to next-env 
  or for deletion from env-name."
  [{:keys [name env deletion?]}]
  (nav/go-to ::named-environment-page {:org (kt/org env)
                                       :env (:prior env)
                                       :next-env env})
  (if deletion? (browser click ::deletion))
  (sel/->browser (click ::new)
                 (setText ::name-text name)
                 (click ::save))
  (check-for-success))


(defn update [{:keys [env name deletion?] :as changeset} new-changeset]
  (let [[to-remove to-add _] (data/diff changeset new-changeset)
        go-home (fn []
                  (browser sleep 5000)
                  (browser click ::promotion-eligible-home))]
    (nav/go-to changeset)
    (doseq [item (:content to-add)]
      (add item)
      (go-home))
    (doseq [item (:content to-remove)]
      (remove item)
      (go-home))))

(extend katello.Changeset
  ui/CRUD {:create create
           :update* update}

  rest/CRUD (let [id-url (partial rest/url-maker ["api/changesets/%s" [identity]])
                  env-url (partial rest/url-maker [["api/organizations/%s/environments/%s/changesets"
                                                    [(comp :org :env) :env]]])
                  ent-to-api-name #(-> % class .getName (string/split #"\.") last string/lower-case pluralize)
                  ent-to-api-req-field #(-> % class .getName (string/split #"\.") last string/lower-case (str "_id")) 
                  content-add-url (fn [cs additem]
                                    (rest/api-url (format "api/changesets/%s/%s"
                                                          (rest/get-id cs)
                                                          (ent-to-api-name additem))))
                  content-delete-url (fn [cs remitem]
                                       (rest/api-url (format "api/changesets/%s/%s/%s"
                                                             (rest/get-id cs)
                                                             (ent-to-api-name remitem)
                                                             (rest/get-id remitem))))]
              {:id rest/id-field
               :query (partial rest/query-by-name env-url)
               :read (partial rest/read-impl id-url)
               :create (fn [{:keys [name deletion?] :as cs}]
                         (rest/http-post (env-url cs)
                                         {:body {:changeset {:name name
                                                             :type (if deletion? "DELETION" "PROMOTION")}}}))
               :update* (fn [cs updated] ; updates content only for now
                          (let [[remove add] (data/diff cs updated)]
                            (doseq [i (:content add)]
                              (rest/http-post (content-add-url cs i)
                                              {:body {(ent-to-api-req-field i) (rest/get-id i)}}))
                            (doseq [i (:content remove)]
                              (rest/http-delete (content-delete-url cs i)))))})

  tasks/Uniqueable tasks/entity-uniqueable-impl
  
  nav/Destination {:go-to (fn [{:keys [name deletion? env] :as cs}]
                            (nav/go-to ::named-page {:org (kt/org env)
                                                     :env (:prior env)
                                                     :next-env env
                                                     :changeset cs
                                                     :deletion? deletion?}))})

(defn promote-or-delete
  "Promotes the given changeset to its target environment and could also Delete
   content from an environment. An optional timeout-ms key will specify how long to
   wait for the promotion or deletion to complete successfully."
<<<<<<< HEAD
  [{:keys [name deletion? env] :as changeset} & [timeout-ms]]
  (nav/go-to changeset)
  (locking #'conf/promotion-deletion-lock
    (browser click ::review-for-promotion)
    ;;for the submission
    (sel/loop-with-timeout (* 10 60 1000) []
      (when-not (try+ (browser click ::promote-to-next-environment)
                      (check-for-success)
                      (catch (common/errtype ::notification/deletion-already-in-progress) _
                        (nav/go-to changeset))
                      (catch (common/errtype ::notification/promotion-already-in-progress) _
                        (nav/go-to changeset)))
        (Thread/sleep 30000)
        (recur)))
    ;;for confirmation
    (sel/loop-with-timeout (or timeout-ms (* 20 60 1000)) [current-status ""]
      (case current-status
        "Applied" current-status
        "Apply Failed" (throw+ {:type :promotion-failed
                                :changeset name
                                :from-env (:name env)
                                :to-env (-> env :next :name)})
        (do (Thread/sleep 2000)
            (recur (browser getText (status name))))))
    ;;wait for async success notif
    (check-for-success {:timeout-ms (* 20 60 1000) :match-pred (notification/request-type? :changeset-promoted)})))

(defn promote-delete-content
  "Creates the given changeset, adds content to it and promotes it. "
  [cs]
  (let [content (:content cs)
        cs (kt/newChangeset (dissoc cs :content ))] ; since creating doesn't include content
    (ui/create cs)
    (ui/update cs assoc :content content)
    (promote-or-delete cs)))

(defn sync-and-promote
  "Syncs all the repos and then promotes all their parent products
  *from* the env given."
  [repos env]
  (let [all-prods (distinct (map :product repos))]
    (assert/is (every? sync/success?
                       (vals (sync/perform-sync repos {:timeout 600000}))))
    (-> {:name "cs"
         :content all-prods
         :env env}
        katello/newChangeset
        uniqueify
        promote-delete-content)))

(defn- extract-content [] 
  (let [elems (for [index (iterate inc 1)]
                (content-item-n (str index)))
        retrieve (fn [elem]
                   (try (browser getText elem)
                        (catch Exception e nil)))]
    (->> (map retrieve elems) (take-while identity) set)))

(defn environment-content
  "Returns the content that is available to promote, in the given environment."
  [env]
  (nav/go-to ::named-environment-page {:env env
                                       :org (kt/org env)
                                       :next-env nil})
  (let [categories {katello/newProduct ::products-category,
                    katello/newTemplate ::templates-category}]
    (apply concat (for [[f category] categories]
              (do
                (browser click category)
                (browser sleep 2000)
                (let [result (for [item (extract-content)]
                               (f {:name item}))]
                  (browser click ::promotion-eligible-home)
                  result))))))

(defn ^{:TODO "finish me"} change-set-content [env]
  (nav/go-to ::named-environment-page {:env env}))

(defn environment-has-content?
  "If all the content is present in the given environment, returns true."
  [env content]
  (nav/go-to ::named-environment-page {:env env :next-env ""})
  (let [visible? #(try (do (browser isVisible (add-content-item %))
                           true)
                       (catch Exception e false))]
    (every? true? (doall (for [item content]
                           (do (go-to item)
                               (visible? (:name item))))))))

(defn add-link-exists?
  "When the product is not promoted to next env and if there is no add-link 
   visible for repos/packages, it returns true."
  [env content]
  (nav/go-to ::named-environment-page {:env env :next-env nil})
  (sel/->browser (click ::new)
                 (setText ::name-text (uniqueify "changeset1"))
                 (click ::save))
  (every? false? 
          (flatten
            (for [category (keys content)]
              (let [data (content category)
                    prod-item (:product-name (first data))]
                (if (some #{category} [:repos :packages :errata])
                  (do
                    (sel/->browser (click ::products-category)
                                   (click (select-product prod-item))
                                   (refresh)
                                   (click (->> category name (format "katello.changesets/select-%s") keyword)))
                  (if (= category :errata) (browser click ::select-errata-all)))
                  (sel/->browser (click ::errata-category)
                                 (click ::select-errata-all)))
                (let [visible (doall
                                (for [item (map :name data)]
                                  (browser isVisible (add-content-item item))))]
                  (sel/->browser (click ::remove-changeset)
                                 (click ::ui-box-confirm)
                                 (click ::promotion-eligible-home)
                                 (refresh))
                  visible))))))

(defn api-promote-changeset
  "Promotes a changeset, polls the API until the promotion completes,
   and returns the changeset. If the timeout is hit before the
   promotion completes, throws an exception."
  [changeset]
  (locking #'conf/promotion-deletion-lock
    (rest/http-post (rest/url-maker [["api/changesets/%s/promote" [identity]]] changeset))
    (loop-with-timeout (* 20 60 1000) [cs {}]
      (let [state (:state cs)]
        (case state
          "promoted" cs
          "failed" (throw+ {:type :failed-promotion :response cs})    
          (do (Thread/sleep 5000)
              (recur (rest/read changeset))))))))

(defn api-promote
  "Does a promotion of the given content (creates a changeset, adds
   the content, and promotes it. Content should match the JSON format
   that the API expects. currently like {:product_id '1234567890'}"
  [env content]
  (with-unique [cs (katello/newChangeset {:name "api-changeset"
                                          :env env})]
    (rest/create cs)
    (rest/update cs update-in [:content] (fnil concat (list)) content)  
    (api-promote-changeset cs)))
