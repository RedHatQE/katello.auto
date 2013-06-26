(ns katello.rh-repositories
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [katello :as kt]
            (katello [ui :as ui]                     
                     [navigation :as nav]                    
                     [notifications :as notification]
                     [ui-common :as common]
                     [manifest :as manifest])))

;; Locators

(sel/template-fns
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
  [::redhat-page
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
   :beta    ::beta-rpms-page})

  (rh-repos {:rh-allrepos    '(["Red Hat CloudForms System Engine RPMs x86_64 6.4"
                                "Red Hat CloudForms System Engine RPMs x86_64 6Server"] 
                               ["Red Hat CloudForms Tools for RHEL 6 RPMs i386 6.4"
                                "Red Hat CloudForms Tools for RHEL 6 RPMs i386 6Server"
                                "Red Hat CloudForms Tools for RHEL 6 RPMs x86_64 6.4"
                                "Red Hat CloudForms Tools for RHEL 6 RPMs x86_64 6Server"]) 
             :rh-allreposets '("Red Hat CloudForms System Engine" 
                               "Red Hat CloudForms Tools for RHEL 6") 
             :rh-allprds     '("Red Hat CloudForms" 
                               "Red Hat Enterprise Linux Server") 
             :org            katello.conf/*session-org* 
             :repo-type      "rpms" 
             :deselect?      false})
;; One could select, deselect, any RedHat repo-type "rpms", "srpms", "debug", "beta"

(defn rh-repos
  [{:keys [rh-allrepos rh-allreposets rh-allprds org repo-type deselect?]}]
  (let [red-hat-provider     (katello/newProvider {:name "Red Hat" :org org})
        red-hat-items        (map list rh-allprds rh-allreposets rh-allrepos)       
        red-hat-repositories (concat (for [[rh-prd rh-reposet rh-repos]  red-hat-items]
                                       (let [prd        (katello/newProduct {:name rh-prd :provider red-hat-provider})
                                             reposet    (katello/newRedHatRepoSet {:name rh-reposet, :product prd})
                                             repos      (for [reponame rh-repos]
                                                          (katello/newRedHatRepo {:name reponame, :reposet reposet, 
                                                                                  :type repo-type, :deselect? deselect?}))]
                                         repos)))]
    (flatten red-hat-repositories)))

  (rh-repos {:rh-allrepos    '(["Red Hat CloudForms System Engine RPMs x86_64 6.4"
                                "Red Hat CloudForms System Engine RPMs x86_64 6Server"] 
                               ["Red Hat CloudForms Tools for RHEL 6 RPMs i386 6.4"
                                "Red Hat CloudForms Tools for RHEL 6 RPMs i386 6Server"
                                "Red Hat CloudForms Tools for RHEL 6 RPMs x86_64 6.4"
                                "Red Hat CloudForms Tools for RHEL 6 RPMs x86_64 6Server"]) 
             :rh-allreposets '("Red Hat CloudForms System Engine" 
                               "Red Hat CloudForms Tools for RHEL 6") 
             :rh-allprds     '("Red Hat CloudForms" 
                               "Red Hat Enterprise Linux Server") 
             :org            katello.conf/*session-org* 
             :repo-type      "rpms" 
             :deselect?      false})

(defn enable-disable-redhat-repos
  "Enable the given list of rh-repos in the current org."
  [rh-repos]
  (doseq [repo rh-repos]
    (nav/go-to (repo-map (keyword (repo :type))) (kt/org repo))
    (let [prd      (kt/product repo)
          reposet  (kt/reposet repo)
          checked? (common/disabled? (select-repo-set (:type repo) (:name reposet)))]
      (browser click (expand-product (:type repo) (:name prd)))
      (if-not checked?
        (browser check (select-repo-set (:type repo)(:name reposet)))
        (browser click (expand-repo-set (:type repo)(:name reposet))))
      (if (repo :deselect?)
        (browser uncheck (select-repo (:name repo)))
        (browser check (select-repo (:name repo)))))))
