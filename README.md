## discogs.clj

A tiny Discogs video scraping Clojure script written for [babashka](https://babashka.org).

```
discogs.clj -- Scrape the Discogs API for release videos
  -h, --help              Show help
  -g, --genre GENRE       Genre
  -s, --style STYLE       Style
  -y, --year YEAR         Year
  -p, --page PAGE      1  Starting page
  -i, --index INDEX    1  Starting index
  -d, --database PATH     Database path

```

### Dependencies

- **babashka**: See the [babashka installation instructions](https://github.com/babashka/babashka#installation) for getting started.
- **mpv** (optional): Install [mpv](https://mpv.io) for audio playback.

### Running

```sh
$ chmod +x discogs.clj
$ ./discogs.clj
```

### Instructions

```
[?] Press n for next release, p for previous, q to quit.
    To navigate videos, press j to go down, k to go up
    Press Enter to open video link in the browser
    Press Space to play/pause audio
```

### Roadmap

Use [Datalevin](https://github.com/datalevin/datalevin) to store progress and
save releases.
