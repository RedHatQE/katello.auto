(in-ns 'katello.ui-tasks)

;;
;; Environments
;;

(defn create-environment
  "Creates an environment with the given name, and a map containing
   the organization name to create the environment in, the prior
   environment, and an optional description."
  [name {:keys [org-name description prior-env]}]
  (navigate :new-environment-page {:org-name org-name})
  (fill-ajax-form {:env-name-text name
                   :env-description-text description
                   :prior-environment prior-env}
                  :create-environment)
  (check-for-success))

(defn delete-environment
  "Deletes an environment from the given organization."
  [env-name {:keys [org-name]}]
  (navigate :named-environment-page {:org-name org-name
                                     :env-name env-name})
  (if (browser isElementPresent :remove-environment)
    (browser click :remove-environment)
    (throw+ {:type :env-cant-be-deleted :env-name env-name}))
  (browser click :confirmation-yes)
  (check-for-success))

(defn edit-environment
  "Edits an environment with the given name. Also takes a map
   containing the name of the environment's organization, and optional
   fields: a new description."
  [env-name {:keys [org-name description]}]
  (navigate :named-environment-page {:org-name org-name
                                     :env-name env-name})
  (in-place-edit {:env-description-text description}))

(defn create-environment-path
  "Creates a path of environments in the given org. All the names in
  the environment list must not already exist in the given org. Example:
  (create-environment-path 'ACME_Corporation' ['Dev' 'QA' 'Production'])"
  [org-name environments]
  (let [env-chain  (partition 2 1 (concat [library] environments))]
    (doseq [[prior curr] env-chain]
      (create-environment curr {:prior-env prior
                                :org-name org-name}))))

(defn- extract-content []
  (let [elems (for [index (iterate inc 1)]
                (locators/promotion-content-item-n (str index)))
        retrieve (fn [elem]
                   (try (browser getText elem)
                        (catch Exception e nil)))]
    (->> (map retrieve elems) (take-while identity) set)))

(defn environment-content
  "Returns the content that is available to promote, in the given environment."
  [env-name]
  (navigate :named-environment-changesets-page {:env-name env-name
                                                :next-env-name nil})
  (let [categories [:products :templates]]
    (zipmap categories
            (doall (for [category categories]
                     (do
                       (browser click (-> category name (str "-category") keyword))
                       (browser sleep 2000) 
                       (let [result (extract-content)]
                         (browser click :promotion-eligible-home)
                         result)))))))

(defn ^{:TODO "finish me"} change-set-content [env]
  (navigate :named-environment-changesets-page {:env-name env}))

(defn environment-has-content?
  "If all the content is present in the given environment, returns true."
  [env content]
  (navigate :named-environment-changesets-page {:env-name env :next-env-name ""})
  (every? true?
          (flatten
           (for [category (keys content)]
             (do (browser click (-> category name (str "-category") keyword))
                 (for [item (content category)]
                   (try (do (browser isVisible
                                     (locators/promotion-add-content-item item))
                            true)
                        (catch Exception e false))))))))



