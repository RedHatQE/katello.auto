(ns katello.content-search-results)

;; Content Search Cell Object

(deftype Cell [hover])

(defn create-cells [cells-map]
  (for [cell-key (keys cells-map)]
    (-> (cell-key cells-map) :hover Cell.))
  )


;; Content Search Column Object

(deftype Column [id span visible? content custom?])

(defn create-columns [cols]
  (for [col cols]
    (let [col-display (:display col)]
      (Column. (:id col) 
               (:span col) 
               (:shown col) 
               (:content col-display) 
               (:custom col-display))))
  )


;; Content Search Row Object

(defprotocol RowProtocol
  (getChildren [this result])
  (getCells    [this])
  )

(deftype Row [id name data-type value comparable? children cells]
  RowProtocol

  (getChildren [this result]
    (for [child children]
      (.getRowById result child)))
  
  (getCells [this] cells)
  )

(defn create-rows [rows-map]
  (for [row-key (keys rows-map)]
    (let [row-map  (row-key rows-map)] 
      (Row. (:id row-map) 
            (:name row-map) 
            (:data_type row-map) 
            (:value row-map)
            (:comparable row-map)
            (:child_ids row-map)
            (create-cells (:cells row-map)))) )
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
    (count (filter #(.visible? %) cols)))

  (getRowById [this id]
    (->> rows (filter #(= (.id %) id)) first))
  )

(defn create-result [result-map]
  (Result. (create-rows (:rows result-map))
           (create-columns (:columns result-map))
           (:mode result-map))
  )
