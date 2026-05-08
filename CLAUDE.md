# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```sh
mise run run          # start the server (http://localhost:8080)
mise run dev          # start nREPL with reloaded.repl
mise run test:all     # run full kaocha test suite
mise run css:build    # compile Tailwind CSS once
mise run css:watch    # watch and recompile CSS

# Run a single test namespace
clj -M:test --focus servo.scanner-test

# Run a single deftest by var
clj -M:test --focus servo.scanner-test/fresh-scan-produces-collections
```

`f3d` must be on `$PATH` and `STL_ROOT` must point at a directory of model folders before starting.

## Architecture

The app is a server-rendered Clojure web app. All HTML is generated with Hiccup 2 on the server; interactivity uses htmx (partial swaps) and Alpine.js (tag chip editor, lightbox).

### Component system

`servo.system/build-system` wires three Stuart Sierra Components:
- `:db` (`servo.db/Store`) — thin wrapper around an EDN file directory; all persistence goes through `read-store` / `write-store!`
- `:handler` (`servo.web.handler/Handler`) — Ring handler built from Reitit routes; receives `config` (plain map) and `db` (injected by Component)
- `:server` (`servo.web.server/WebServer`) — Jetty with `:virtual-threads? true`; receives `:handler`

In the dev REPL (`dev/user.clj`), `(go)` / `(reset)` / `(stop)` are wired via `reloaded.repl`.

### Data flow: scanning

`scan-root!` (scanner) → `generate-previews!` (preview) → writes `collections.edn` to the EDN store.

`servo.scan/trigger!` runs this pipeline in a `future`. It passes an `on-progress` callback that `swap!`s into a global atom (`servo.scan/state`). The handler polls `GET /scan/status` every second and reads that atom — this is the only cross-thread communication mechanism.

Collection records shape:
```edn
{:id "uuid-string"
 :folder-path "/abs/path"
 :name "folder-name"
 :tags ["tag1"]
 :models [{:path "/abs" :filename "x.stl" :preview-path "/abs/.servo-images/x.stl.png"}]
 :scanned-at #inst "..."}
```
All collections are stored as a single `{id → collection}` map in `collections.edn`.

Preview images are written as `<collection-folder>/.servo-images/<model-filename>.png` by `f3d` CLI. The preview route `GET /previews/:collection-id/:filename` looks up the collection's `:folder-path` and serves from `.servo-images/`. Path traversal is blocked by canonicalising the resolved path and checking the prefix.

### Handler structure

`build-handler` in `handler.clj` applies middleware in this order (outermost last):
```
wrap-content-type → wrap-resource "public" → wrap-params → reitit ring-handler
```

Static assets (`/css/output.css`) are served from `resources/public/` via `wrap-resource`.

The browse page (`GET /`) supports htmx partial swaps: when `hx-request: true` is present it returns only the `#collection-grid` div; otherwise the full page. Tag filtering is server-side substring match on name + tags.

Collection cards use a `<div>` with an invisible `<a>` overlay (`absolute inset-0 z-0`) for the card click target. Tag pill `<a>` elements sit above it with `relative z-10`. This avoids invalid nested `<a>` elements which cause browsers to break card layout.

The `PATCH /collections/:id` endpoint reads form params where `tag` may be a single string or a vector (ring's `wrap-params` behaviour when a field appears multiple times).

### CSS

Tailwind v4 — config is CSS-only (`resources/public/css/input.css`). No `tailwind.config.js`. Build via `npm run css:build` / `css:watch`. The compiled `output.css` is gitignored and must be built before first run.
