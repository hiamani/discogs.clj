## discogs.clj

A tiny Discogs video scraping Clojure script written for [babashka](https://babashka.org).

```
discogs.clj -- Scrape the Discogs API for release videos
  -h, --help            Show help
  -g, --genre GENRE     Genre
  -s, --style STYLE     Style
  -y, --year YEAR       Year
  -p, --page PAGE    1  Starting page
  -i, --index INDEX  1  Starting index
```

### Dependencies

**babashka**: See the [babashka installation instructions](https://github.com/babashka/babashka#installation) for getting started.

### Running

```sh
$ chmod +x discogs.clj
$ ./discogs.clj
```

### Roadmap

Use [Datalevin](https://github.com/datalevin/datalevin) to store progress and
save releases.
