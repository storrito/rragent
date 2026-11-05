(ns rragent.storrito-test
  "The pure parts of the Storrito functions: the connection's root,
   where its catalog is, the docstrings from the catalog."
  (:require [clojure.test :refer [deftest is testing]]
            [rragent.fake-gateway :as fake-gateway]
            [rragent.storrito :as storrito]))

(defn- custom-api
  [id base & [notes]]
  {:provider "custom_api" :id id :notes (or notes []) :provider_info {:auth_method "bearer" :base_api_url base}})

(deftest api-root-test
  (is (= {:host "https://acme.storrito.com" :prefix "/api/v1"} (storrito/api-root (custom-api "c" "https://acme.storrito.com/"))))
  (is (= {:host "https://acme.storrito.com" :prefix ""} (storrito/api-root (custom-api "c" "https://acme.storrito.com/api/v1"))) "a base that is the API itself")
  (is (nil? (storrito/api-root {:provider "custom_api" :id "c" :provider_info {}})))
  (is (= "/api/v1/generate-uuid" (storrito/procedure-path (custom-api "c" "https://acme.storrito.com") "generate-uuid")))
  (is (= "/generate-uuid" (storrito/procedure-path (custom-api "c" "https://acme.storrito.com/api/v1") "generate-uuid"))))

(deftest catalog-link-test
  (testing "the catalog at the host, or the link of a note"
    (is (= "https://acme.storrito.com/documentation/api/v1/catalog.json" (storrito/catalog-link (custom-api "c" "https://acme.storrito.com"))))
    (is (= "https://acme.storrito.com/documentation/api/v1/catalog.json" (storrito/catalog-link (custom-api "c" "https://acme.storrito.com/api/v1"))))
    (is (= "https://other.example/the/catalog.json"
           (storrito/catalog-link (custom-api "c" "https://api.example" [{:title "Catalog" :body "Read https://other.example/the/catalog.json first."}]))))
    (is (nil? (storrito/catalog-link {:provider "custom_api" :id "c" :provider_info {}}))))
  (testing "which connections may be Storrito"
    (is (true? (storrito/candidate? (custom-api "c" "https://acme.storrito.com"))))
    (is (false? (storrito/candidate? (custom-api "c" "https://backend.composio.dev"))) "Composio is not")
    (is (false? (storrito/candidate? {:provider "github" :id "g"})))
    (is (true? (storrito/storrito-host? (custom-api "c" "https://acme.storrito.com"))))
    (is (false? (storrito/storrito-host? (custom-api "c" "https://api.example"))))))

(deftest description-test
  (let [[uuid status] (:procedures fake-gateway/catalog)]
    (is (= "Generates a UUID." (storrito/description (:doc uuid))))
    (is (= "The status of a story post.\n\nSecond paragraph." (storrito/description (:doc status))))
    (is (= "" (storrito/description nil)))
    (is (= "Generates a UUID.\n\nTakes no parameters." (storrito/docstring uuid)))
    (is (= (str "The status of a story post.\n\nSecond paragraph.\n\nParameters, a map:\n"
                "  storyPostUuid (string), required: The UUID of the story post\n"
                "  verbose (boolean)\n"
                "  limit (integer)\n"
                "  destination")
           (storrito/docstring status)))
    (is (= [{:name "generate-uuid" :doc "Generates a UUID."}
            {:name "status-instagram-story" :doc "The status of a story post."}
            {:name "flaky-once" :doc "Answers 503 the first time."}
            {:name "create-note" :doc "Makes a note; a command."}]
           (storrito/summary fake-gateway/catalog)))))

(deftest command-test
  (testing "the verb tells a query from a command while the catalog does not"
    (is (false? (storrito/command? "list-social-accounts")))
    (is (false? (storrito/command? "status-draft")))
    (is (false? (storrito/command? "get-thing")))
    (is (false? (storrito/command? "search-media")))
    (is (false? (storrito/command? "find-file")))
    (is (false? (storrito/command? "generate-uuid")))
    (is (true? (storrito/command? "schedule-social-post")))
    (is (true? (storrito/command? "create-draft")))
    (is (true? (storrito/command? "cancel-social-post")))
    (is (true? (storrito/command? "upload-temp-blob")))
    (is (true? (storrito/command? "delete-draft"))))
  (is (= {"generate-uuid" {:kind :query} "status-instagram-story" {:kind :query} "flaky-once" {:kind :command} "create-note" {:kind :command}}
         (storrito/kinds fake-gateway/catalog)))
  (is (= {} (storrito/kinds nil)) "no catalog, no procedures"))
