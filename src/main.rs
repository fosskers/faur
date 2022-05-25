use clap::Parser;
use from_variants::FromVariants;
use log::{info, LevelFilter};
use serde::{Deserialize, Deserializer, Serialize};
use simplelog::{ColorChoice, Config, TermLogger, TerminalMode};
use std::borrow::Cow;
use std::collections::{HashMap, HashSet};
use std::fs::File;
use std::hash::Hash;
use std::io::BufReader;
use std::ops::Not;
use std::path::PathBuf;
use warp::Filter;

const DB_FILE: &str = "db.yaml";

/// Description words to ignore.
const IGNORES: &[&str] = &["for", "and", "the", "with", "from", "that", "your"];

trait Apply {
    fn apply<F, U>(self, f: F) -> U
    where
        F: FnOnce(Self) -> U,
        Self: Sized;
}

impl<T> Apply for T {
    fn apply<F, U>(self, f: F) -> U
    where
        F: FnOnce(Self) -> U,
        Self: Sized,
    {
        f(self)
    }
}

#[derive(Debug, FromVariants)]
enum Error {
    Io(std::io::Error),
    Yaml(serde_yaml::Error),
    Log(log::SetLoggerError),
}

#[derive(Parser)]
#[clap(author, version, about)]
#[clap(propagate_version = true, disable_help_subcommand = true)]
struct Args {
    /// Port to listen on.
    #[clap(long, default_value_t = 80, display_order = 1)]
    port: u16,

    /// Path to a TLS certificate.
    #[clap(long, display_order = 1)]
    cert: Option<PathBuf>,

    /// Path to the TLS certificate's private key.
    #[clap(long, display_order = 1)]
    key: Option<PathBuf>,
}

#[derive(Deserialize)]
struct Query {
    #[serde(deserialize_with = "commas")]
    names: Vec<String>,
    by: Option<By>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "lowercase")]
enum By {
    Prov,
    Desc,
}

/// A number of these fields will be missing from the usual AUR RPC responses if
/// it had no data from them (e.g. "CheckDepends"), but we always return all
/// fields for increased safety.
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "PascalCase", deny_unknown_fields)]
struct Package {
    #[serde(default, rename(deserialize = "cd"))]
    check_depends: Vec<String>,
    #[serde(default, rename(deserialize = "cf"))]
    conflicts: Vec<String>,
    #[serde(default, rename(deserialize = "dp"))]
    depends: Vec<String>,
    #[serde(rename(deserialize = "ds"))]
    description: Option<String>,
    #[serde(rename(deserialize = "fs"))]
    first_submitted: u64,
    #[serde(default, rename(deserialize = "gs"))]
    groups: Vec<String>,
    #[serde(rename(deserialize = "id", serialize = "ID"))]
    id: u64,
    #[serde(default, rename(deserialize = "ks"))]
    keywords: Vec<String>,
    #[serde(rename(deserialize = "lm"))]
    last_modified: u64,
    #[serde(default, rename(deserialize = "lc"))]
    license: Vec<String>,
    #[serde(rename(deserialize = "mt"))]
    maintainer: Option<String>,
    #[serde(default, rename(deserialize = "md"))]
    make_depends: Vec<String>,
    #[serde(rename(deserialize = "na"))]
    name: String,
    #[serde(rename(deserialize = "nv"))]
    num_votes: u64,
    #[serde(default, rename(deserialize = "os"))]
    opt_depends: Vec<String>,
    #[serde(rename(deserialize = "od"))]
    out_of_date: Option<u64>,
    #[serde(rename(deserialize = "pb"))]
    package_base: String,
    #[serde(rename(serialize = "PackageBaseID", deserialize = "pi"))]
    package_base_id: u64,
    #[serde(rename(deserialize = "pl"))]
    popularity: f64,
    #[serde(default, rename(deserialize = "pv"))]
    provides: Vec<String>,
    #[serde(default, rename(deserialize = "rp"))]
    replaces: Vec<String>,
    #[serde(rename(serialize = "URL", deserialize = "ul"))]
    url: Option<String>,
    #[serde(rename(serialize = "URLPath", deserialize = "up"))]
    url_path: String,
    #[serde(rename(deserialize = "vr"))]
    version: String,
}

impl PartialEq for Package {
    fn eq(&self, other: &Self) -> bool {
        self.name == other.name
    }
}

impl Eq for Package {}

impl Hash for Package {
    fn hash<H: std::hash::Hasher>(&self, state: &mut H) {
        self.name.hash(state);
    }
}

/// Various fast lookup schemes for the underlying [`Package`] data.
struct Index<'a> {
    by_name: HashMap<&'a str, &'a Package>,
    by_prov: HashMap<&'a str, Vec<&'a Package>>,
    by_word: HashMap<String, HashSet<&'a Package>>,
}

impl<'a> Index<'a> {
    /// Construct a new `Index`.
    fn new(db: &'a [Package]) -> Index<'a> {
        let by_name = db.iter().map(|p| (p.name.as_str(), p)).collect();
        let mut by_prov: HashMap<&'a str, Vec<&'a Package>> = HashMap::new();
        let mut by_word: HashMap<String, HashSet<&'a Package>> = HashMap::new();

        for pckg in db.iter() {
            if pckg.provides.is_empty() {
                // Packages always provide themselves, if there is no other explicit listing.
                let set = by_prov.entry(pckg.name.as_str()).or_default();
                set.push(pckg);
            } else {
                // Otherwise, believe what the package self-declares about its "provides".
                for prov in pckg.provides.iter() {
                    let set = by_prov.entry(prov.as_str()).or_default();
                    set.push(pckg);
                }
            }

            // Associate a `Package` with each word in its description and name.
            // This allows O(logn) description searches.
            pckg.description
                .as_deref()
                .unwrap_or("")
                .trim()
                .split_ascii_whitespace()
                .map(|s| s.trim_start_matches(['(', '"', '*']))
                .map(|s| s.trim_end_matches(['.', ',', '!', '?', ':', ')', '"', ';', '*']))
                .map(|s| s.trim_end_matches("'s"))
                .chain(pckg.name.split(['-', '_'])) // Sneak the name in there too.
                .filter(|s| s.len() > 2)
                .map(|s| s.to_ascii_lowercase()) // Allocates a heap String!
                .into_iter()
                .filter(|word| IGNORES.contains(&word.as_str()).not())
                .for_each(|word| {
                    let entry = by_word.entry(word).or_default();
                    entry.insert(&pckg);
                });
        }

        Index {
            by_name,
            by_prov,
            by_word,
        }
    }
}

fn commas<'de, D>(deserializer: D) -> Result<Vec<String>, D::Error>
where
    D: Deserializer<'de>,
{
    // FIXME Wed Mar 23 17:02:35 2022
    //
    // Potentially avoid all the allocating by dropping down to `hyper` and
    // using data borrowed from the deserializer entirely.
    let s: &'de str = Deserialize::deserialize(deserializer)?;
    let v = s.split(',').map(|s| s.to_string()).collect();
    Ok(v)
}

fn intersections<'a, 'b: 'a, I, T>(iter: I) -> HashSet<&'b T>
where
    I: Iterator<Item = &'a HashSet<&'b T>>,
    T: Eq + Hash,
{
    let mut sets: Vec<_> = iter.collect();
    sets.sort_by(|a, b| b.len().cmp(&a.len()));

    match sets.pop() {
        None => HashSet::new(),
        Some(smallest) => {
            let mut res = smallest.clone();
            sets.iter().for_each(|s| res.retain(|t| s.contains(t)));
            res
        }
    }
}

fn db_init() -> Result<Vec<Package>, Error> {
    let reader = BufReader::new(File::open(DB_FILE)?);
    let db = serde_yaml::from_reader(reader)?;
    Ok(db)
}

#[tokio::main]
async fn main() -> Result<(), Error> {
    let args = Args::parse();

    TermLogger::init(
        LevelFilter::Info,
        Config::default(),
        TerminalMode::Mixed,
        ColorChoice::Auto,
    )?;

    info!("Initializing package database.");

    // The `Box` tricks ensure that the `Index` can actually be passed to the
    // request handlers with a static lifetime, which is a requirement of Warp.
    let db: &'static Vec<Package> = Box::leak(Box::new(db_init()?));
    info!("Database read. Forming Index...");
    let ix = Index::new(db);
    info!("Index formed.");
    info!("Init complete: {} packages available.", db.len());
    info!("{} unique description words.", ix.by_word.len());

    // let mut fooq: Vec<_> = ix
    //     .by_word
    //     .iter()
    //     .map(|(word, set)| (word.as_str(), set.len()))
    //     .collect();
    // fooq.sort_by_key(|(_, freq)| *freq);
    // fooq.reverse();
    // fooq.into_iter()
    //     .take(40)
    //     .for_each(|(word, freq)| println!("{}: {}", word, freq));

    let search = warp::get()
        .and(warp::path("packages"))
        .and(warp::query::<Query>())
        .map(move |q: Query| {
            let ps: Cow<'_, [&Package]> = match q.by {
                Some(By::Prov) => q
                    .names
                    .first()
                    .and_then(|p| ix.by_prov.get(p.as_str()))
                    .map(Cow::Borrowed)
                    .unwrap_or_default(),
                Some(By::Desc) => {
                    let by_name: HashSet<&Package> = q
                        .names
                        .iter()
                        .filter_map(|word| ix.by_name.get(word.as_str()))
                        .copied()
                        .collect();

                    q.names
                        .into_iter()
                        .filter_map(|word| ix.by_word.get(&word))
                        .chain(std::iter::once(&by_name))
                        .apply(intersections)
                        .into_iter()
                        .collect()
                }
                None => q
                    .names
                    .iter()
                    .filter_map(|p| ix.by_name.get(p.as_str()))
                    .copied() // Only to deference a `&&`. Doesn't copy Package data.
                    .collect(),
            };

            warp::reply::json(&ps)
        });

    match (args.cert, args.key) {
        (Some(c), Some(k)) => {
            warp::serve(search)
                .tls()
                .cert_path(c)
                .key_path(k)
                .run(([0, 0, 0, 0], 443))
                .await
        }
        _ => warp::serve(search).run(([0, 0, 0, 0], args.port)).await,
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parseable_database() {
        let db = db_init();
        assert!(db.is_ok());
    }
}
