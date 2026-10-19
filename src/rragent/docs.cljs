(ns rragent.docs
  "Reading a pi-durable document from inside a tool: a tool's api has
   no read of its own, so the read is a commit that changes nothing and
   answers a plain copy of the draft (the draft itself is the
   transaction's, not for keeping)."
  )

(defn ^:async read
  "The document `token` of the tool's conversation, as a JSON copy; an
   absent one is its initial value."
  [api token call-context]
  (await (.commit api
                  (^:async fn [tx]
                    (js/JSON.parse (js/JSON.stringify (await (.doc tx token (.-conversationId api))))))
                  call-context)))
