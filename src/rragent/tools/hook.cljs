(ns rragent.tools.hook
  "The `hook` tool: the agent makes, lists and deletes its hooks
   (`rragent.hooks`), the addresses other systems call to start a turn."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [rragent.docs :as docs]
            [rragent.hooks :as hooks]))

(defn- text
  [s]
  #js {:output #js [#js {:type "text" :text s}]})

(def tool
  (defineTool
   #js {:name "hook"
        :description (str "Your hooks: addresses another system can call (an HTTP POST) to send you a message. "
                          "`create` with a name and a purpose answers the address to give that system; a call's body reaches you as a message. "
                          "`delete` with the name revokes it; `list` shows them. A created name that exists gets a new secret. "
                          "Events of a service the organization connected (a new file in a Google Drive folder, a mail in Gmail) reach you through a Storrito integration trigger that posts to one of your hooks: "
                          "create the hook, then in the REPL (storrito/add-integration-trigger {:agentUuid (storrito/agent-uuid) :toolkit \"googledrive\" :trigger \"GOOGLEDRIVE_GOOGLE_DRIVE_CHANGES\" :config {} :targetUrl \"<the hook's address>\"}); "
                          "(storrito/list-integration-trigger-types {:toolkit \"googledrive\"}) lists the events of a service and the config each takes, "
                          "(storrito/list-integration-triggers {:agentUuid (storrito/agent-uuid)}) what is deployed and how the last event went. "
                          "Composio's own trigger endpoints are closed to you on purpose; the Storrito procedures are the way.")
        :parameters (.Object Type #js {:action (.Union Type #js [(.Literal Type "create") (.Literal Type "delete") (.Literal Type "list")])
                                       :name (.Optional Type (.String Type #js {:description "The hook's name: lowercase letters, digits, dashes"}))
                                       :purpose (.Optional Type (.String Type #js {:description "What calls it and why, for yourself"}))})
        :execute (^:async fn [args api call-context]
                   (let [name (.-name args)]
                     (case (.-action args)
                       "create" (do (when (empty? name)
                                      (throw (js/Error. "create needs the name")))
                                    (text (str "Hook " name " at " (await (hooks/create! api name (or (.-purpose args) "") call-context)))))
                       "delete" (do (when (empty? name)
                                      (throw (js/Error. "delete needs the name")))
                                    (text (if (await (hooks/delete! api name call-context)) (str "Hook " name " deleted") (str "No hook " name))))
                       "list" (let [listed (hooks/listed (await (docs/read api hooks/Hooks call-context)))]
                                (text (if (empty? listed) "No hooks" listed))))))}))
