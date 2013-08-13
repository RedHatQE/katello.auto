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
 {add-content-item       "//a[@data-display_name='%s' and starts-with(@id,'add_remove_') and contains(.,'Add')]"
  remove-content-item    "//a[@data-display_name='%s' and starts-with(@id,'add_remove_') and contains(.,'Undo')]"
  content-category       "//div[@id='%s']"
  content-item-n         "//div[@id='list']//li[%s]//div[contains(@class,'simple_link')]/descendant::text()[(position()=0 or parent::span) and string-length(normalize-space(.))>0]"
  select-product         "//span[contains(.,'%s')]"
  select-env             "//a[normalize-space(.)='%s' and contains(@class,'path_link')]"
  select-published-names "xpath=(//div[contains(@class,'simple_link')])[%s]"
  status                 "//span[.='%s']/..//span[@class='changeset_status']"
  list-item              "//div[starts-with(@id,'changeset_') and normalize-space(.)='%s']"})

;; Nav

(ui/defelements :katello.deployment/any []
  {::promotion-eligible-home     "//div[@id='content_tree']//span[contains(@class,'home_img_inactive')]"
   ::review-for-promotion        "review_changeset"
   ::promote-to-next-environment "//div[@id='promote_changeset' and not(contains(@class,'disabled'))]"
   ::new                         "new"
   ::name-text                   "changeset_name"
   ::save                        "save_changeset_button"
   ::content                     "//div[contains(@class,'slider_two') and contains(@class,'has_content')]"
   ::type                        "changeset[action_type]"
   ::promotion                   "//div[@data-cs_type='promotion']"
   ::deletion                    "//div[@data-cs_type='deletion']"
   ::remove-changeset            "//span[contains(.,'Remove')]"})  
   
(nav/defpages :katello.deployment/any katello.menu
  [::page
   [::named-environment-page (fn [cs]
                               (let [env (kt/env cs)]
                                 (nav/select-environment-widget (:prior env) {:next-env env :wait true})))
    [::named-page (fn [cs] (when (:deletion? cs)
                             (browser click ::deletion)
                             (browser click (list-item (:name cs)))))]]])

;; Tasks

(defn- create
  "Creates a changeset for promotion from env to next-env 
  or for deletion from env-name."
  [{:keys [name env deletion?]}]
  (nav/go-to ::named-environment-page env)
  (if deletion?
    (do
      (browser click (select-env (:name env)))
      (browser sleep 3000)
      (if (browser isElementPresent ::promotion)
        (browser click ::deletion))))
  (sel/->browser (click ::new)
                 (setText ::name-text name)
                 (click ::save))
  (check-for-success))


(defn- update [{:keys [env name deletion?] :as changeset} new-changeset]
  (let [[to-remove to-add _] (data/diff changeset new-changeset)
        go-home (fn []
                  (browser sleep 5000)
                  (browser click ::promotion-eligible-home))]
    (nav/go-to changeset env)
    (doseq [item (:content to-add)]
      (browser click (-> item :published-name add-content-item)))
    (doseq [item (:content to-remove)]
      (browser click (-> item :published-name remove-content-item)))))

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
  
  nav/Destination {:go-to (partial nav/go-to ::named-page)})

(defn promote-or-delete
  "Promotes the given changeset to its target environment and could also Delete
   content from an environment. An optional timeout-ms key will specify how long to
   wait for the promotion or deletion to complete successfully."
  [{:keys [name deletion? env] :as changeset} & [timeout-ms]]
  (nav/go-to changeset env)
  (locking #'conf/promotion-deletion-lock 
     (browser sleep 2000)
    (browser click ::review-for-promotion)
    (browser sleep 5000)
    (browser refresh)
    ;;for the submission
    (sel/loop-with-timeout (* 10 60 1000) []
      (when-not (try+ (browser click ::promote-to-next-environment)
                      (check-for-success)
                      (catch (common/errtype ::notification/promotion-already-in-progress) _
                        (nav/go-to changeset)))
        (Thread/sleep 30000)
        (recur)))
    ;;for confirmation
    (sel/loop-with-timeout (or timeout-ms (* 20 60 1000)) [current-status ""]
      (case current-status
        "Applied" current-status
        "Apply Failed" (throw+ {:type ::promotion-failed
                                :changeset name
                                :from-env (:name env)
                                :to-env (-> env :next :name)})
        (do (Thread/sleep 2000)
            (recur (browser getText (status name))))))
    ;;wait for async success notif
    (check-for-success {:timeout-ms (* 20 60 1000)})))

(defn promote-delete-content
  "Creates the given changeset, adds content to it and promotes it. "
  [cs]
  (when-not (-> cs kt/env kt/library?)
    (let [content (:content cs)
          cs (kt/newChangeset (dissoc cs :content ))] ; since creating doesn't include content
      (ui/create cs)
      (ui/update cs assoc :content content)
      (promote-or-delete cs))))

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

(defn environment-has-content?
  "If the published-name is present in the given environment, returns true."
  [{:keys [name deletion? env content] :as changeset}]
  (nav/go-to ::named-environment-page env)
  (browser click (select-env (:name env)))
  (every? true? (doall (for [cv content]
                         (some #(= (cv :published-name) %) (common/extract-list select-published-names))))))   

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
