## discogs.clj

A tiny Discogs video scraping script written in Clojure for [babashka](https://babashka.org).

```
discogs.clj -- Scrape the Discogs API for release videos
  -h, --help              Show help
  -g, --genre GENRE       Genre (required)
  -s, --style STYLE       Style
  -y, --year YEAR         Year
  -p, --page PAGE      1  Starting page
  -i, --index INDEX    0  Starting index
  -r, --resume            Resume last session
  -l, --list              List and pick sessions
  -d, --database PATH     Database path
  -x, --no-database       Skip database connection

```

### Dependencies

- **babashka**: See the [babashka installation instructions](https://github.com/babashka/babashka#installation) for getting started.
- **mpv** (optional): Install [mpv](https://mpv.io) for audio playback.

### Running

```sh
$ chmod +x discogs.clj
$ ./discogs.clj
```
