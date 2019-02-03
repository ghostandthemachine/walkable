(ns walkable.sql-query-builder-test
  (:require [walkable.sql-query-builder :as sut]
            [walkable.sql-query-builder.floor-plan :as floor-plan]
            [clojure.test :as t :refer [deftest is]]
            [com.wsscode.pathom.core :as p]))

(deftest process-children-test
  (is (= (sut/process-children
           {:ast                     (p/query->ast [:pet/age
                                                    :pet/will-be-ignored
                                                    :pet/owner
                                                    :abc/unknown
                                                    {:> [:pet/age]}])
            ::p/placeholder-prefixes #{">"}
            ::sut/floor-plan         {::floor-plan/column-keywords
                                      #{:pet/yob}
                                      ::floor-plan/required-columns
                                      {:pet/age #{:pet/yob}}
                                      ::floor-plan/source-columns
                                      {:pet/owner :person/number}}})
        {:join-children    #{{:type         :prop,
                              :dispatch-key :pet/owner,
                              :key          :pet/owner}},
         :columns-to-query #{:pet/yob :person/number}})))

(deftest combine-params-test
  (is (= (sut/combine-params {:params [1]} {:params [2 3]} {:params [4 5 6]})
        [1 2 3 4 5 6]))
  (is (= (sut/combine-params {:params [1]} {:params [2 3]} nil)
        [1 2 3]))
  (is (= (sut/combine-params {:params [1]} {:params []} {:params [4 5 6]})
        [1 4 5 6]))
  (is (= (sut/combine-params {:params [1]} nil {:params [4 5 6]})
        [1 4 5 6]))
  (is (= (sut/combine-params {:params [1]} nil nil)
        [1]))
  (is (= (sut/combine-params nil nil nil)
        [])))
