(ns katello.activation-keys
  (:require (katello [navigation :as nav]
                     [notifications :as notification]
                     [ui-common :as ui])
            [com.redhat.qe.auto.selenium.selenium :as sel]
            [com.redhat.qe.auto.selenium.selenium :refer [browser]]))

;; Nav

(nav/graft-page-tree
 :subscriptions-tab
 [:activation-keys-page [] (browser clickAndWait :activation-keys)
  [:named-activation-key-page [activation-key-name]
   (nav/choose-left-pane activation-key-name)]
  [:new-activation-key-page [] (browser click :new-activation-key)]]                    )

;; Tasks

(defn create
  "Creates an activation key with the given properties. Description
  and system-template are optional."
  [{:keys [name description environment system-template] :as m}]
  (nav/go-to :new-activation-key-page)
  (browser click (ui/environment-link environment))
  (sel/fill-ajax-form {:activation-key-name-text name
                       :activation-key-description-text description
                       :activation-key-template-select system-template}
                      :save-activation-key)
  (notification/check-for-success))

(defn delete
  "Deletes the given activation key."
  [name]
  (nav/go-to :named-activation-key-page {:activation-key-name name})
  (browser click :remove-activation-key)
  (browser click :confirmation-yes)
  (notification/check-for-success))

(defn add-subscriptions
  "Add subscriptions to activation key."
  [name subscriptions]
  (nav/go-to :named-activation-key-page {:activation-key-name name})
  (browser click :available-subscriptions)
  (doseq [subscription subscriptions]
    (browser click (ui/subscription-checkbox subscription)))
  (browser click :add-subscriptions-to-activation-key)
  (notification/check-for-success))