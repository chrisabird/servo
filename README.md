# Servo

A local web application for organising folders of 3D model files. Point it at a directory of STL/3MF/OBJ folders, generate preview images, name and tag your collections, and search your library from a browser.

## Features

- Scans a root directory — each immediate subfolder becomes a collection
- Generates PNG preview thumbnails via [f3d](https://f3d.app) (stored alongside your files in `.servo-images/`)
- Rename collections, add free-text tags with autocomplete
- Browse a card grid with thumbnail previews, search by name or tag
- Collection detail page with full model grid and inline editing
- Live scan progress with per-model status updates

## Requirements

- Java 21 (Temurin recommended)
- Clojure CLI (`clj`)
- Node.js 22 (for Tailwind CSS)
- [f3d](https://f3d.app) — headless 3D renderer, must be on `$PATH`
- [mise](https://mise.jdx.dev) — manages tool versions and tasks

Install tools with mise:

```sh
mise install
```

## Setup

```sh
# Install Node dependencies (Tailwind + DaisyUI)
npm install

# Build CSS
mise run css:build
```

## Configuration

Configuration is loaded from `resources/config.edn` via environment variables:

| Variable | Default | Description |
|---|---|---|
| `STL_ROOT` | `stl` | Root directory to scan for collections |
| `DB_DIR` | `data/db` | Directory for application metadata (EDN store) |
| `PORT` | `8080` | HTTP port |
| `SERVO_ENV` | `dev` | Environment profile (`dev`, `prod`) |

Point `STL_ROOT` at your models directory before starting:

```sh
export STL_ROOT=/path/to/your/stl/library
```

## Running

```sh
# Start the server
mise run run
```

Open [http://localhost:8080](http://localhost:8080). Navigate to **Scan** (↺ in the nav bar) to trigger your first scan.

## Docker

```yaml
services:
  servo:
    image: ghcr.io/chrisabird/servo:latest
    ports:
      - "8080:8080"
    volumes:
      - ./data:/app/data/db      # persistent metadata (EDN store)
      - /path/to/your/models:/app/stl  # your STL/3MF/OBJ library
    environment:
      STL_ROOT: /app/stl
      DB_DIR: /app/data/db
```

Replace `/path/to/your/models` with the absolute path to your models directory on the host. The `./data` directory will be created automatically for the application metadata.

## Development

```sh
# Start an nREPL with reloaded.repl
mise run dev

# In the REPL
(go)     ; start the system
(reset)  ; reload changed namespaces and restart
(stop)   ; stop the system

# Watch CSS for changes (run in a second terminal)
mise run css:watch
```

## Testing

```sh
mise run test:all
```

## Project layout

```
src/servo/
  config.clj        # Aero config loading
  db.clj            # EDN file store (read-store / write-store!)
  scanner.clj       # Discovers collections from root directory
  preview.clj       # Generates PNG previews via f3d
  scan.clj          # Background scan job with atom-based progress
  system.clj        # Stuart Sierra Component system wiring
  main.clj          # Entry point (-main)
  web/
    handler.clj     # Ring/Reitit routes and Hiccup templates
    server.clj      # Jetty component (virtual threads)

resources/
  config.edn        # Aero configuration
  public/css/       # Tailwind input + compiled output

test/
  fixtures/         # STL files used in tests
  servo/            # clojure.test + kaocha suites
```

## Tech stack

Clojure 1.12 · Ring + Reitit · Stuart Sierra Component · EDN file store · Hiccup 2 · htmx 2 · Alpine.js 3 · Tailwind CSS 4 + DaisyUI 5 · f3d · telemere · aero · kaocha
