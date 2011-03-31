(ns kalpana.tests.promotions
  (:require [kalpana.tasks :as tasks]
            [kalpana.api-tasks :as api])
  (:import [org.testng.annotations Test BeforeClass])
  (:use [kalpana.conf :only [config]]
        [test-clj.testng :only [gen-class-testng data-driven]]
        [com.redhat.qe.verify :only [verify]]))

(def provider-name (atom nil))
(def root-next-env (atom nil))
(def locker "locker")
(def root "root")
(def myorg "admin")

(defn get-root-next-env
  "Gets the environment whose 'prior' is the root environment.  If
there is none, one will be created and its name returned."
  [org]
  (let [rootid (api/get-id-by-name :environment root org)
        matches (filter #(= (:prior %) rootid)
                        (api/all-entities :environment org))]
    (cond
     (second matches) (throw (IllegalStateException.
                              "Multiple envs have root as their prior! See bz 692592."))
     (first matches) (-> matches first :name)
     :else (let [new-env-name (tasks/timestamp "promote-test-env")]
             (api/create-environment org new-env-name "for testing content promotion" :prior-env root)
             new-env-name))))

(defn ^{BeforeClass {:groups ["promotions"]}} setup [_]
  (reset! provider-name (tasks/timestamp "promotion-cp"))
  (api/create-provider @provider-name "test provider for promotions"
                       "http://blah.com" "Red Hat"
                       (@config :admin-user) (@config :admin-password))
  (reset! root-next-env (get-root-next-env myorg)))

(defn verify_promote_content [org envs content]
  (doseq [product-name (content :products)]
    (api/create-product org product-name @provider-name))
  (doseq [[from-env target-env] (partition 2 1 envs)]
    (tasks/promote-content from-env content)
    (verify (tasks/environment-has-content? target-env content))))

(data-driven verify_promote_content {org.testng.annotations.Test
                                     {:groups ["promotions"] :description
                                      "Takes content and promotes it thru more environments.
                                       Verifies that it shows up in the new env."}}
             [[myorg [locker root] {:products (set (tasks/timestamp "MyProduct" 3))}]
              [myorg [locker root @root-next-env] {:products (set (tasks/timestamp "ProductMulti" 3))}]])

(gen-class-testng)




(comment "not needed - dates are not required for api"
         (def date-fmt (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSSZZZZ"))

         (defn formatted-date
           ([d] (.format date-fmt d))
           ([] (formatted-date (java.util.Date.)))))
