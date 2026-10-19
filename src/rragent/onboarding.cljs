(ns rragent.onboarding
  "How a chat with nothing in it starts: the skill the agent is made of
   introduces itself from the `metadata` of its SKILL.md, the keys
   agentitos keeps for harnesses: `title`, the task's name; `ask`, the
   first message, what the person should bring; `placeholder`, the
   composer's. Each text can have a `-<locale>` variant, `-de` for
   German. A definition without an `ask` keeps the plain empty chat.")

(defn- text
  "The text of the key in the locale, its `-<locale>` variant when there
   is one, else the plain one; nil when both are empty."
  [metadata k locale]
  (some not-empty [(when (and locale (not= :en locale))
                     (get metadata (str k "-" (name locale))))
                   (get metadata k)]))

(defn onboarding
  "`{:title :ask :placeholder}` of the metadata in the locale; nil
   without an `ask`."
  [metadata locale]
  (when-let [ask (text metadata "ask" locale)]
    {:title (text metadata "title" locale)
     :ask ask
     :placeholder (text metadata "placeholder" locale)}))

(comment
  (onboarding {"title" "Kinetic shorts" "title-de" "Kinetic Shorts"
               "ask" "Paste the link of a YouTube video." "ask-de" "Füge den Link eines YouTube-Videos ein."
               "placeholder" "Paste a YouTube link"}
              :de)
  )
