(ns katello.content-search
  )

(load "content_search_row")
(load "content_search_column")

;; Content Search Result Object

(def ^{:private true} rows nil)
(def ^{:private true} cols nil)


(defprotocol ResultProtocol
  (getRowCount           [this])
  (getColumnCount        [this])
  (getVisibleColumnCount [this])
  (addRow                [this row])
  (addColumn             [this col])
  (getRow                [this index])
  (getColumn             [this index])
  )

(deftype Result [mode]
  ResultProtocol

  (getRowCount [this] 
    (count (get rows this)))

  (getColumnCount [this] 
    (count (get cols this)))

  (getVisibleColumnCount [this]
    (count (filter true? (map .visible (get cols this)))))

  (addRow [this row]
    (assert (= (type row) katello.content_search.Row)
            "Attempt to add row with incorrect data type")
    (assert (= (.getCellCount row) (.getColumnCount this))
            "Attempt to add row with inconsistent number or cells")
    (let [myRows (get rows this)]
      (def rows (merge rows (conj rows row)))))

  (addColumn [this col]
    (assert (= (.getRowCount this) 0)
            "Cannot add columns after rows have been added to results")
    (assert (= (type col) katello.content_search.Column)
            "Attempt to add column with incorrect data type")
    (def cols (conj cols col)))
  )
