(ns rragent.test-templates
  "Fake templates for the tests: a folder of templates under a temp
   dir, which RRAGENT_TEMPLATES names while a test runs. A template is
   `{:name :frontmatter :body :project {file text} :render text :serve
   text}`, the scripts executable."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]))

(defn temp-dir
  []
  (fs/mkdtempSync (path/join (os/tmpdir) "rragent-templates-")))

(defn executable!
  [dir name text]
  (let [file (path/join dir name)]
    (fs/writeFileSync file text)
    (fs/chmodSync file "700")
    file))

(defn template!
  "The template written into `dir`, its folder answered."
  [dir {:keys [name frontmatter body project render serve]}]
  (let [folder (path/join dir name)]
    (fs/mkdirSync folder #js {:recursive true})
    (when frontmatter
      (fs/writeFileSync (path/join folder "TEMPLATE.md")
                        (str "---\n" (str/join "\n" (for [[k v] frontmatter] (str k ": " v))) "\n---\n\n" (or body ""))))
    (doseq [[file text] project]
      (let [target (path/join folder "project" file)]
        (fs/mkdirSync (path/dirname target) #js {:recursive true})
        (fs/writeFileSync target text)))
    (when render
      (executable! folder "render" render))
    (when serve
      (executable! folder "serve" serve))
    folder))

(def render-script
  "`render <project-dir> <out>`: two progress lines, the file written,
   the arguments noted beside the script; a `fail` file beside it makes
   it fail with a word on stderr, a `slow` file makes it sleep after
   the first line."
  (str "#!/bin/sh\n"
       "here=$(dirname \"$0\")\n"
       "echo \"$@\" > \"$here/args\"\n"
       "echo \"CHROME=$CHROME FFMPEG=$FFMPEG\" > \"$here/env\"\n"
       "echo 'progress 1/4'\n"
       "[ -f \"$here/slow\" ] && { echo video > \"$2\"; sleep 30; }\n"
       "[ -f \"$here/fail\" ] && { echo 'Error: the design has no frame' >&2; exit 3; }\n"
       "echo 'progress 90%'\n"
       "echo video > \"$2\"\n"
       "echo 'Rendered'\n"
       "exit 0\n"))

(def serve-script
  "`serve <project-dir>`: a page on PORT that says its folder, and an
   eval endpoint that answers the code in capitals, or 400 for the
   word `boom`."
  (str "#!/usr/bin/env node\n"
       "const http = require('node:http');\n"
       "const dir = process.argv[2];\n"
       "http.createServer((req, res) => {\n"
       "  let body = '';\n"
       "  req.on('data', (c) => { body += c; });\n"
       "  req.on('end', () => {\n"
       "    if (req.method === 'POST' && req.url === '/eval') {\n"
       "      const status = body.trim() === 'boom' ? 400 : 200;\n"
       "      res.writeHead(status, {'Content-Type': 'text/plain'});\n"
       "      res.end(status === 200 ? body.toUpperCase() : 'boom failed');\n"
       "      return;\n"
       "    }\n"
       "    res.writeHead(200, {'Content-Type': 'text/plain'});\n"
       "    res.end(`page of ${dir} ${req.method} ${req.url}`);\n"
       "  });\n"
       "}).listen(Number(process.env.PORT), '127.0.0.1');\n"))

(def fake
  "A template that renders with `render-script` and serves with
   `serve-script`."
  {:name "fake"
   :frontmatter {"name" "fake"
                 "description" "A fake template for the tests."
                 "renders" "mp4"
                 "needs" "chrome, ffmpeg"
                 "entry" "serve"}
   :body "# Fake\n\nWrite hello.txt."
   :project {"hello.txt" "hello\n"}
   :render render-script
   :serve serve-script})

(def static
  "A template the pane serves as files: its entry is a page."
  {:name "static"
   :frontmatter {"name" "static"
                 "description" "A page served as it is."
                 "renders" "png"}
   :body "A page."
   :project {"index.html" "<h1>static</h1>"
             "style.css" "h1 {}"}})

(defn install!
  "The templates written into a fresh folder, RRAGENT_TEMPLATES set to
   it: `{:dir :restore!}`, `restore!` puts the environment back and
   removes the folder."
  [templates]
  (let [dir (temp-dir)
        env (.-env js/process)
        before (aget env "RRAGENT_TEMPLATES")]
    (doseq [template templates]
      (template! dir template))
    (aset env "RRAGENT_TEMPLATES" dir)
    {:dir dir
     :restore! (fn []
                 (if before (aset env "RRAGENT_TEMPLATES" before) (js-delete env "RRAGENT_TEMPLATES"))
                 (fs/rmSync dir #js {:recursive true :force true}))}))
