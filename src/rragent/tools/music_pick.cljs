(ns rragent.tools.music-pick
  "The `music_pick` tool: a link to Storrito's music pick page, where
   the person picks the song of a story's music sticker and the part
   that plays (`rragent.music-picks`). The picked song reaches the
   bot's chat as a message with the `<insta-music>` element for the
   story. The person picks in a browser, so the tool is the bot's own,
   not a run's or a subagent's (`rragent.tools.work/bot-call!`)."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [rragent.music-picks :as music-picks]
            [rragent.tools.work :as work]))

(defn- text
  [s]
  #js {:output #js [#js {:type "text" :text s}]})

(defn answer
  "What the tool tells the bot about the link `url`."
  [url]
  (str "The music pick page: " url "\n"
       "Give the person this link as a card to open in a new tab, e.g.\n"
       "```hiccup\n[:a {:href \"" url "\"} \"Pick the song\"]\n```\n"
       "There they search the song like in Storrito's editor and drag the part that plays. "
       "When they confirm, the song reaches you here as a message with the <insta-music> element to put into the story; "
       "wait for it, do not guess a song. The link works once, for 24 hours."))

(defn tool
  "The tool for the bot whose REPL is `codemode`: its Storrito
   connection names the organization's Storrito."
  [codemode]
  (defineTool
   #js {:name "music_pick"
        :description (str "When the person wants music on an Instagram story (a music sticker, a song from Instagram's library), "
                          "let them pick it: this answers a link to Storrito's music pick page, where they search the song and choose the part that plays, "
                          "like in Storrito's editor. Their choice comes back to you as a message with the <insta-music search-term track-id start-ms> element "
                          "for the story's HTML. Do not pick or guess a song yourself when the person cares which one, and never use this for music you make "
                          "(that is the `music` tool). `sectionMs` is how long the story is: 15000 for an image story, a video story's length up to 60000.")
        :parameters (.Object Type #js {:sectionMs (.Optional Type (.Number Type #js {:description "How long the part that plays is, in milliseconds: the story's length, 15000 by default"}))})
        :execute (^:async fn [args api call-context]
                   (await (work/bot-call! api call-context "Music picks"))
                   (let [host (music-picks/storrito-host codemode)]
                     (when-not host
                       (throw (js/Error. "No Storrito connection: the music pick page is Storrito's. Ask the person to connect Storrito.")))
                     (let [section (music-picks/section-ms (.-sectionMs args))
                           {:keys [callback]} (await (music-picks/create! api section call-context))]
                       (when-not callback
                         (throw (js/Error. "This machine has no public address (RRAGENT_URL), so Storrito cannot send the song back.")))
                       (text (answer (music-picks/page-url host callback section))))))}))
