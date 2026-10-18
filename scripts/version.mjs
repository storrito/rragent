// Writes src/rragent/version.cljs from package.json, so that the bundle
// says the version of the release it was built from: `npm run build`
// runs it first (prebuild). A git checkout without a release has 0.0.0.
import { readFileSync, writeFileSync } from "node:fs";
const { version } = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8"));
writeFileSync(new URL("../src/rragent/version.cljs", import.meta.url),
  `(ns rragent.version\n  "The version of rragent, as package.json says it; written by\n   scripts/version.mjs before a build.")\n\n(def version\n  "${version}")\n`);
console.log(`rragent ${version}`);
