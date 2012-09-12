(ns katello.content-search-results)

;; Content Search Cell Object

(deftype Cell [hover])

(defn create-cells [cells-map]
  (mapcat 
    (fn [cell-key] [(-> (cell-key cells-map) :hover Cell.)])
    (keys cells-map))
  )


;; Content Search Column Object

(deftype Column [id span visible? content custom?])

(defn create-columns [cols]
  (mapcat
    (fn [col]
      (let [col-display (:to_display col)] 
        [(Column. (:id col)
                  (:span col)
                  (:shown col)
                  (:content col-display)
                  (:custom col-display))]))
    cols)
  )


;; Content Search Row Object

(defprotocol RowProtocol
  (getChildren [this])
  (getCells    [this])
  )

(deftype Row [id name data-type value comparable? children cells]
  RowProtocol

  (getChildren [this] children)
  
  (getCells [this] cells)
  )

(defn create-rows [rows-map]
  (mapcat 
    (fn [row-key]
      (let [row-map  (row-key rows-map)] 
        [(Row. (:id row-map) 
               (:name row-map) 
               (:data_type row-map) 
               (:value row-map)
               (:comparable row-map)
               (:child_ids row-map)
               (create-cells (:cells row-map)))]))
    (keys rows-map)) 
  )

;; Content Search Result Object

(defprotocol ResultProtocol
  (getRows               [this])
  (getColumns            [this])
  (getVisibleColumnCount [this])
  (getRowById            [this id])
  )

(deftype Result [rows cols mode]
  ResultProtocol

  (getRows [this] rows)

  (getColumns [this] cols)

  (getVisibleColumnCount [this]
    (->> (mapcat (fn [col] [(.visible? col)]) cols) (filter true?) count))

  (getRowById [this id]
    (filter (fn [row] (not (nil? row)))
            (map (fn [row] (when (= (.id row) id) row)) rows)))
  )

(defn create-result [result-map]
  (Result. (create-rows (:rows result-map))
           (create-columns (:columns result-map))
           (:mode result-map))
  )
