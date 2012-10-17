(ns katello.ui-tasks
  (:require [clojure.data.json  :as json]
            [ui.navigate      :as nav]
            [com.redhat.qe.auto.selenium.selenium
             :refer [browser ->browser fill-form fill-item]]
            (katello [locators      :as locators]
                     [tasks         :refer :all] 
                     [notifications :refer :all] 
                     [conf          :refer [config]] 
                     [api-tasks     :refer [when-katello when-headpin]]) 
            [slingshot.slingshot :refer [throw+ try+]]
            [tools.verify        :refer [verify-that]]
            [inflections.core    :refer [pluralize]] 
            (clojure [string     :refer [capitalize replace-first]] 
                     [set        :refer [union]]
                     [string     :as string]))
  (:import [com.thoughtworks.selenium SeleniumException]
           [java.text SimpleDateFormat]))

(declare search)

;;UI tasks

(defn errtype
  "Creates a predicate that matches a caught UI error of the given
   type (see known-errors). Use this predicate in a slingshot 'catch'
   statement. If any of the error types match (in case of multiple
   validation errors), the predicate will return true. Uses isa? for
   comparison, so hierarchies will be checked.
   example (try+ (dothat) (catch (errtype ::validation-error) _ nil))"
  [t]
  (with-meta
    (fn [e] (some #(isa? % t) (:types e)))
    {:type :serializable.fn/serializable-fn
     :serializable.fn/source `(errtype ~t)}))

(def ^{:doc "Navigates to a named location in the UI. The first
  argument should be a keyword for the place in the page tree to
  navigate to. The 2nd optional argument is a mapping of keywords to
  strings, if any arguments are needed to navigate there.
  Example: (navigate :named-organization-page {:org-name 'My org'})
  See also katello.locators/page-tree for all the places that can be
  navigated to."
       :arglists '([location-kw & [argmap]])}
  navigate (nav/nav-fn #'locators/page-tree))

(defn fill-ajax-form
  "Fills in a web form and clicks the submit button. Only waits for
   ajax calls to complete. Items should be a map, where the keys are
   locators for form elements, and values are the values to fill in.
   Submit should be a locator for the form submit button."
  [items submit]
  (fill-form items submit (constantly nil)))

(defn activate-in-place
  "For an in-place edit input, switch it from read-only to editing
   mode. Takes the locator of the input in editing mode as an
   argument."
  [loc]
  (browser click (locators/inactive-edit-field loc)))

(defn in-place-edit
  "Fill out a form that uses in-place editing. Takes a map of locators
   to values. Each item will be activated, filled in and saved, and
   checks for success notification. Returns all the success
   notifications (or nil if notthing was changed)."
  [items] 
  (doall (for [[loc val] items]
           (if-not (nil? val)
             (do (activate-in-place loc)
                 (fill-item loc val)
                 (browser click :save-inplace-edit)
                 (check-for-success))))))

(defn extract-list [f]
  "Extract a list of items from the UI, accepts locator function as
   argument for example, extract-left-pane-list
   locators/left-pane-field-list"
  (let [elems (for [index (iterate inc 1)]
                (f (str index)))]
    (doall (take-while identity (for [elem elems]
                                  (try (browser getText elem)
                                       (catch SeleniumException e nil)))))))

(defn content-search-entity-type-from-attribute [attr-val]
  (let [words (string/split attr-val  #"_")
        known-types #{"product" "repo" "errata" "package"}
        mypluralize (fn [s] (if (= s "errata") ; because dev mixed plural and singular
                             "errata"
                             (pluralize s)))]
    (->> words
       (filter known-types)
       last
       mypluralize
       keyword)))

(defn extract-left-pane-list []
  (extract-list locators/left-pane-field-list))

(defn clear-search []
  (->browser (click :search-menu)
             (click :search-clear-the-search)))

(defn search
  "Search for criteria in entity-type, scope not yet implemented.
  if with-favorite is specified, criteria is ignored and the existing
  search favorite is chosen from the search menu. If add-as-favorite
  is true, use criteria and save it as a favorite, and also execute
  the search."
  [entity-type & [{:keys [criteria scope with-favorite add-as-favorite]}]]
  (navigate (entity-type {:users :users-page 
                          :organizations :manage-organizations-page
                          :roles :roles-page
                          :subscriptions :redhat-subscriptions-page
                          :gpg-keys :gpg-keys-page
                          :sync-plans :sync-plans-page
                          :systems  :systems-all-page
                          :system-groups :system-groups-page
                          :activation-keys :activation-keys-page
                          :changeset-promotion-history :changeset-promotion-history-page}))
  (if with-favorite
    (->browser (click :search-menu)
               (click (locators/search-favorite with-favorite)))
    (do (browser type :search-bar criteria)
        (when add-as-favorite
          (->browser (click :search-menu)
                     (click :search-save-as-favorite)))
        (browser click :search-submit)))
  (check-for-error {:timeout-ms 2000}))


(defn validate-content-search-results [results]
  (let [cols (:columns results)
        vis-col-count (->> cols (filter #(:shown %)) count) 
        rows (vals (:rows results))]
    (doseq [row rows]
      (assert (>= (count cols) (-> (:cells row) keys count))
              "Number of cells in row is greater than the number of columns")
      (doseq [child-id (:child_ids (:cells row))]
        (assert (= 1 (count (filter #(= (:id %) child-id) rows)))
                (str "Child ID not found: " child-id)))))
    results)

(defn attr-loc [locator attribute]
  (str (.getLocator locator) "@" attribute))

(defn get-repo-compare-package-names [] 
  (doall (for [locator (locators/get-all-of-locator locators/content-search-package-name)]
    (browser getText locator))))

(defn get-repo-compare-packages [] 
  (doall (for [locator (locators/get-all-of-locator locators/content-search-result-item-n)]
    (browser getText locator))))

(defn get-repo-compare-repositories [] 
  (doall (for [locator (locators/get-all-of-locator locators/content-search-repo-header-name)]
    (browser getText locator))))

(defn get-search-result-repositories [] 
  (doall (for [locator (locators/get-all-of-locator locators/content-search-repo-column-name)]
    (browser getText locator))))
 

(defn package-in-repository? [package repository]
  (let [row-id (browser getAttribute (attr-loc 
                                       (locators/search-result-row-id package)
                                       "data-id"))
       col-id (browser getAttribute (attr-loc 
                                       (locators/search-result-col-id repository)
                                       "data-id"))]
      (not (= "--" 
              (browser getText (locators/search-result-cell row-id col-id))))))

(defn autocomplete-adder-for-content-search [auto-comp-box add-button cont-item]
  (browser setText auto-comp-box cont-item)
  ;; typeKeys is necessary to trigger drop-down list
  (browser typeKeys auto-comp-box " ")
  (let [elem (locators/auto-complete-item cont-item)] 
    (->browser (waitForElement elem "2000")
               (mouseOver elem)
               (click elem)))
    (browser click add-button))

(defn load-all-results []
  (while (browser isElementPresent :content-search-load-more)
    (browser click :content-search-load-more)))

(defn add-to-repository-browser [repository]
  (autocomplete-adder-for-content-search :repo-auto-complete :add-repo repository))

(defn remove-one-repository-from-browser [repository]
  (browser click (locators/content-search-repo-remove repository)))

(defn remove-repositories [repositories]
  (do
    (doseq [removing repositories]
      (remove-one-repository-from-browser removing))
    (browser click :browse-button)))

(defn compare-repositories-in-search-result [repositories]
  (let [repo-id-map (apply hash-map 
                      (reduce 
                        (fn [result name] 
                          (conj result  name
                            (browser getAttribute (attr-loc 
                                                    (locators/search-result-repo-id name)
                                                    "data-id")))) 
                         []
                         repositories))]
    (doseq [repository repositories]
      (browser check (locators/content-search-compare-checkbox (repo-id-map repository))))
    (browser click :repo-compare-button)))

(defn compare-repositories [repositories]
  (navigate :content-search-page)
  (browser select :content-search-type "Repositories")
  (browser check :repo-auto-complete-radio)
  (doseq [repository repositories]
    (add-to-repository-browser repository))
  (browser click :browse-button)
  (compare-repositories-in-search-result repositories)
  (get-repo-compare-repositories))

(defn get-repo-packages [repo] 
  (compare-repositories [repo])
  (get-repo-compare-packages))

(defn search-for-content
  "Performs a search for the specified content type (:prod-type, :repo-type,
   :pkg-type, :errata-type) using any product, repository, package or errata
   filters specified. Note that while prods and repos should be vectors, errata
   and pkgs are expected to be strings. A vector of environments corresponding
   to table columns may also be specified.  Returns the search results as raw
   data from the browser javascript. 
   Example: search-for-content :errata-type {:prods ['myprod']
                                             :repos ['myrepo']
                                             :errata 'myerrata'}"
  [content-type & [{:keys [envs prods repos pkg errata]}]]
  (assert (some #{content-type} [:prod-type :repo-type :pkg-type :errata-type])
          "Unknown content search type.")
  (case content-type 
    :prod-type   (assert (and (empty? repos) (empty? pkg) (empty? errata)))
    :repo-type   (assert (and (empty? pkg) (empty? errata)))
    :pkg-type    (assert (empty? errata))
    :errata-type (assert (empty? pkg)))

  ;; Navigate to content search page and select content type
  (let [ctype-map {:prod-type   "Products"
                   :repo-type   "Repositories"
                   :pkg-type    "Packages"
                   :errata-type "Errata"}
        ctype-str (ctype-map content-type)]
    (navigate :content-search-page)
    (browser select :content-search-type ctype-str))
  
  ;; Select environments (columns)
  (doseq [env envs]
    (let [col-locator (locators/content-search-column env)]
    (browser mouseOver :column-selector) 
    (browser mouseOver col-locator) 
    (browser click     col-locator)
    (browser mouseOut :column-selector)))

  ;; Add content filters using auto-complete
  (doseq [[auto-comp-box add-button cont-items] 
          [[:prod-auto-complete :add-prod prods] 
           [:repo-auto-complete :add-repo repos]]]
    (doseq [cont-item cont-items]
      (autocomplete-adder-for-content-search auto-comp-box add-button cont-item)))

  ;; Add package
  (when-not (empty? pkg) (browser setText :pkg-search pkg))

  ;; Add errata
  (when-not (empty? errata) (browser setText :errata-search errata))
  
  (browser click :browse-button)

  (load-all-results)

  
  ;;extract and return content
  (->> "JSON.stringify(window.comparison_grid.export_data());"
       (browser getEval)
       (json/read-json)
       validate-content-search-results))

(defn create-activation-key
  "Creates an activation key with the given properties. Description
  and system-template are optional."
  [{:keys [name description environment system-template] :as m}]
  (navigate :new-activation-key-page)
  (browser click (locators/environment-link environment))
  (fill-ajax-form {:activation-key-name-text name
                   :activation-key-description-text description
                   :activation-key-template-select system-template}
                  :save-activation-key)
  (check-for-success))

(defn delete-activation-key
  "Deletes the given activation key."
  [name]
  (navigate :named-activation-key-page {:activation-key-name name})
  (browser click :remove-activation-key)
  (browser click :confirmation-yes)
  (check-for-success))

(defn create-template
  "Creates a system template with the given name and optional
  description."
  [{:keys [name description]}]
  (navigate :new-system-template-page)
  (fill-ajax-form {:template-name-text name
                   :template-description-text description}
                  :save-new-template)
  (check-for-success))

(defn add-to-template
  "Adds content to a given template.  Example:
   (add-to-template 'mytemplate' [{:product 'prod3'
                                   :packages ['rpm1' 'rpm2']}
                                  {:product 'prod6'
                                   :repositories ['x86_64']}]"
  [template content]
  (navigate :named-system-template-page {:template-name template})
  (let [add-item (fn [item] (locators/toggle locators/template-toggler item true))]
    (doseq [group content]
      (let [category-keyword (-> group (dissoc :product) keys first)
            category-name (-> category-keyword
                             name
                             (.replace "-" " ")
                             capitalize-all)]
        (->browser
         (getEval "window.onbeforeunload = function(){};") ;circumvent popup
         (sleep 2000)
         (click (locators/template-product (:product group)))
         (sleep 2000)
         (click (locators/template-eligible-category category-name)))
        (doall (map add-item (group category-keyword)))
        (browser click :template-eligible-home)))
    (browser click :save-template)
    (check-for-success)))

(defn enable-redhat-repositories
  "Enable the given list of repos in the current org."
  [repos]
  (navigate :redhat-repositories-page)
  (doseq [repo repos]
    (browser check (locators/repo-enable-checkbox repo))))

(defn create-gpg-key [name & [{:keys [filename contents]}]]
  (assert (not (and filename contents))
          "Must specify one one of :filename or :contents.")
  (assert (string? name))
  (navigate :new-gpg-key-page)
  (if filename
    (fill-ajax-form {:gpg-key-name-text name
                     :gpg-key-file-upload-text filename}
                    :gpg-key-upload-button)
    (fill-ajax-form {:gpg-key-name-text name
                     :gpg-key-content-text contents}
                    :gpg-keys-save))
  (check-for-success))
 

(defn remove-gpg-key 
  "Deletes existing GPG keys"
  [gpg-key-name]
  (navigate :named-gpgkey-page {:gpg-key-name gpg-key-name})
  (browser click :remove-gpg-key )
  (browser click :confirmation-yes)
  (check-for-success))

(defn create-package-filter [name & [{:keys [description]}]]
  "Creates new Package Filter"
  (assert (string? name))
  (navigate :new-package-filter-page)
    (fill-ajax-form {:new-package-filter-name  name
                     :new-package-filter-description description}
                     :save-new-package-filter)
  (check-for-success))

(defn remove-package-filter 
  "Deletes existing Package Filter"
  [package-filter-name]
  (navigate :named-package-filter-page {:package-filter-name package-filter-name})
  (browser click :remove-package-filter-key )
  (browser click :confirmation-yes)
  (check-for-success))

