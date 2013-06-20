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
 {expand-product  "//div[@id='rpms']//td[contains(@style,'cursor') and contains(.,'%s')]/span"
  select-repo-set "//div[@id='rpms']//span[@class='expander_area' and contains(.,'%s')]/../../td/input[@type='checkbox']"
  expand-repo-set  "//div[@id='rpms']//span[@class='expander_area' and contains(.,'%s')]/span"
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

#_(def repo-map
  {:rpms    ::rpms-page
   :srpms   ::source-rpms-page
   :debug   ::debug-rpms-page
   :beta    ::beta-rpms-page})

(def red-hat-provider (kt/newProvider {:name "Red Hat" :org katello.conf/*session-org*}))

(def cloudforms (kt/newProduct {:name "Red Hat CloudForms" :provider red-hat-provider}))
(def system-engine (kt/newRHRepoSet {:name "Red Hat CloudForms System Engine", :product cloudforms}))

(def rhel (kt/newProduct {:name "Red Hat Enterprise Linux Server" :provider red-hat-provider}))
(def rhel-cftools (kt/newRHRepoSet {:name "Red Hat CloudForms Tools for RHEL 6" :product rhel}))
                                    
(def rh-repos (concat (for [reponame ["Red Hat CloudForms System Engine RPMs x86_64 6.4"
                                      "Red Hat CloudForms System Engine RPMs x86_64 6Server"]]
                          (kt/newRHRepo {:name reponame, :reposet system-engine}))
                        (for [reponame ["Red Hat CloudForms Tools for RHEL 6 RPMs i386 6.4"
                                        "Red Hat CloudForms Tools for RHEL 6 RPMs i386 6Server"
                                        "Red Hat CloudForms Tools for RHEL 6 RPMs x86_64 6.4"
                                        "Red Hat CloudForms Tools for RHEL 6 RPMs x86_64 6Server"]]
                          (kt/newRHRepo {:name reponame, :reposet rhel-cftools}))))

(defn enable-redhat-repos
  "Enable the given list of rh-repos in the current org."
  [rh-repos]
  (doseq [repo rh-repos]
    (nav/go-to ::rpms-page (kt/org repo))
    (let [prd (kt/product repo)
          reposet (kt/reposet repo)
          checked? (common/disabled? (select-repo-set (:name reposet)))]
      (browser click (expand-product (:name prd)))
      (if-not checked?
        (browser check (select-repo-set (:name reposet)))
        (browser click (expand-repo-set (:name reposet))))
      (browser check (select-repo (:name repo))))))
