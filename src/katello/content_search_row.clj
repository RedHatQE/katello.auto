(ns katello.content-search
  )

(load "content_search_cell")

;; Content Search Row Object

(def ^{:private true} children nil)
(def ^{:private true} cells nil)

(defprotocol RowProtocol
  (addChild      [this row])
  (addCell       [this cell])
  (getCellCount  [this])
  (getChildCount [this])
  (getChild      [this index])
  (getCell       [this index])
  )

(deftype Row [id name data-type value comparable?]
  RowProtocol

  (getCellCount [this]
    (count (get cells this)))

  (getChildCount [this]
    (count (get children this)))

  (getChild [this index] ((get children this) index))

  (getCell [this index] ((get cells this) index))

  (addChild [this child]
    (assert (= (type child) (type this))
            "Attempt to add child with incorrect data type")
    (assert (not= child this)
            "Cannot recursively add current object as child")
    (let [myChildren (get children this)]
      (def children (merge children {this (conj myChildren child)}))))

  (addCell [this cell]
    (assert (= (type cell) katello.content_search.Cell))
    (let [myCells (get cells this)]
      (def cells (merge cells (conj myCells cell)))))
  )

