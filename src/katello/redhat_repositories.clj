(ns katello.redhat-repositories
  (:require [katello :as kt]
            [webdriver :as wd]
            [clj-webdriver.taxi :as browser]
            (katello [ui :as ui]                   
                     [navigation :as nav]
                     [notifications :as notification]
                     [ui-common :as common])))

;; Locators

(wd/template-fns
 {expand-product  "//div[@id='%s']//td[contains(@style,'cursor') and contains(.,'%s')]/span"
  select-repo-set "//div[@id='%s']//span[@class='expander_area' and contains(.,'%s')]/../../td/input[@type='checkbox']"
  expand-repo-set  "//div[@id='%s']//span[@class='expander_area' and contains(.,'%s')]/span"
  select-repo     "//td[contains(.,'%s')]/../td/label/input[@type='checkbox']"})

(ui/defelements :katello.deployment/any []
  {::rpms-tab       "//a[@href='#rpms']"    
   ::srpms-tab      "//a[@href='#srpms']"
   ::debugs-tab     "//a[@href='#debug']"
   ::beta-tab       "//a[@href='#beta']"
   ::isos-tab       "//a[@href='#isos']"
   ::others-tab     "//a[@href='#other']"})

;; Nav

(nav/defpages :katello.deployment/any katello.menu
  [::page
   [::rpms-page (nav/browser-fn (click ::rpms-tab))]
   [::source-rpms-page (nav/browser-fn (click ::srpms-tab))]
   [::debug-rpms-page (nav/browser-fn (click ::debugs-tab))] 
   [::beta-rpms-page (nav/browser-fn (click ::beta-tab))]
   [::product-isos-page (nav/browser-fn (click ::isos-tab))] 
   [::others-page (nav/browser-fn (click ::others-tab))]])

(def repo-map
  {:rpms    ::rpms-page
   :srpms   ::source-rpms-page
   :debug   ::debug-rpms-page
   :beta    ::beta-rpms-page
   :other   ::others-page})

(def enable-redhat-repos 
            {:allrepos    '(["Red Hat CloudForms System Engine RPMs x86_64 6.4"
                             "Red Hat CloudForms System Engine RPMs x86_64 6Server"] 
                            ["Red Hat CloudForms Tools for RHEL 6 RPMs i386 6.4"
                             "Red Hat CloudForms Tools for RHEL 6 RPMs i386 6Server"
                             "Red Hat CloudForms Tools for RHEL 6 RPMs x86_64 6.4"
                             "Red Hat CloudForms Tools for RHEL 6 RPMs x86_64 6Server"]) 
             :allreposets '("Red Hat CloudForms System Engine" 
                            "Red Hat CloudForms Tools for RHEL 6") 
             :allprds     '("Red Hat CloudForms" 
                            "Red Hat Enterprise Linux Server")
             :repo-type     "rpms" 
             :deselect?      false})
;; One could select, deselect, any RedHat repo-type "rpms", "srpms", "debug", "beta"

(defn describe-repos-to-enable-disable
  [{:keys [allrepos allreposets allprds repo-type deselect?]}]
  (let [repo-items        (map list allprds allreposets allrepos)       
        repositories      (concat (for [[prd reposet repos]  repo-items]
                                       (let [prd-r        (katello/newProduct {:name prd :provider kt/red-hat-provider})
                                             reposet-r    (katello/newRedHatRepoSet {:name reposet, :product prd-r})
                                             repos-r      (for [reponame repos]
                                                          (katello/newRedHatRepo {:name reponame, :reposet reposet-r, 
                                                                                  :type repo-type, :deselect? deselect?}))]
                                         repos-r)))]
    (flatten repositories)))

(defn enable-disable-repos
  "Enables or Disables a given list of rh-repos in the current org."
  [repos]
  (nav/go-to (-> repos first :type keyword repo-map) (first repos))
  (doseq [repo repos]
    (let [prd      (kt/product repo)
          reposet  (kt/reposet repo)
          checked? (common/disabled? (select-repo-set (:type repo) (:name reposet)))]
      (wd/click (expand-product (:type repo) (:name prd)))
      (if-not checked?
        (wd/click (select-repo-set (:type repo)(:name reposet)))
        (wd/click (expand-repo-set (:type repo)(:name reposet))))
      (if (repo :deselect?)
        (browser/deselect (select-repo (:name repo)))
        (wd/click (select-repo (:name repo)))))))  
