mod error;

use clap::Parser;
use error::Error;
use serde::{Deserialize, Deserializer, Serialize};
use std::borrow::Cow;
use std::collections::HashMap;
use std::fs::File;
use std::io::BufReader;
use std::path::PathBuf;
use warp::Filter;

const DB_FILE: &str = "db.yaml";

#[derive(Parser)]
#[clap(author, version, about)]
#[clap(propagate_version = true, disable_help_subcommand = true)]
pub(crate) struct Args {
    /// Port to listen on.
    #[clap(long, default_value_t = 80, display_order = 1)]
    pub(crate) port: u16,

    /// Path to a certificate for HTTS.
    #[clap(long, display_order = 1)]
    pub(crate) cert: Option<PathBuf>,

    /// Path to the certificate's private key.
    #[clap(long, display_order = 1)]
    pub(crate) key: Option<PathBuf>,
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

/// Various fast lookup schemes for the underlying [`Package`] data.
struct Index<'a> {
    by_name: HashMap<&'a str, &'a Package>,
    by_prov: HashMap<&'a str, Vec<&'a Package>>,
}

impl<'a> Index<'a> {
    /// Construct a new `Index`.
    fn new(db: &'a [Package]) -> Index<'a> {
        let by_name = db.iter().map(|p| (p.name.as_str(), p)).collect();
        let mut by_prov: HashMap<&'a str, Vec<&'a Package>> = HashMap::new();

        for pkg in db.iter() {
            if pkg.provides.is_empty() {
                // Packages always provide themselves, if there is no other explicit listing.
                let set = by_prov.entry(pkg.name.as_str()).or_default();
                set.push(pkg);
            } else {
                // Otherwise, believe what the package self-declares about its "provides".
                for prov in pkg.provides.iter() {
                    let set = by_prov.entry(prov.as_str()).or_default();
                    set.push(pkg);
                }
            }
        }

        Index { by_name, by_prov }
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
    let s = String::deserialize(deserializer)?;
    let v = s.split(',').map(|s| s.to_string()).collect();
    Ok(v)
}

fn db_init() -> Result<Vec<Package>, Error> {
    let reader = BufReader::new(File::open(DB_FILE)?);
    let db = serde_yaml::from_reader(reader)?;
    Ok(db)
}

#[tokio::main]
async fn main() -> Result<(), Error> {
    let args = Args::parse();

    println!("Initializing package database.");

    // The `Box` tricks ensure that the `Index` can actually be passed to the
    // request handlers with a static lifetime, which is a requirement of Warp.
    let db: &'static Vec<Package> = Box::leak(Box::new(db_init()?));
    let ix = Index::new(db);

    let search = warp::get()
        .and(warp::path("packages"))
        .and(warp::query::<Query>())
        .map(move |q: Query| {
            let ps: Cow<'_, [&Package]> = match q.by {
                Some(By::Prov) => match q.names.as_slice() {
                    [p, ..] => match ix.by_prov.get(p.as_str()) {
                        Some(ps) => Cow::Borrowed(ps),
                        None => Cow::Owned(vec![]),
                    },
                    [] => Cow::Owned(vec![]),
                },
                Some(By::Desc) => ix
                    .by_name
                    .values()
                    .filter(|p| {
                        q.names.iter().all(|name| {
                            p.name.contains(name)
                                || p.description
                                    .as_deref()
                                    .map(|d| d.contains(name))
                                    .unwrap_or(false)
                        })
                    })
                    .copied()
                    .collect(),
                None => q
                    .names
                    .iter()
                    .filter_map(|p| ix.by_name.get(p.as_str()))
                    .copied() // Only to deference a `&&`. Doesn't copy Package data.
                    .collect(),
            };

            warp::reply::json(&ps)
        });

    println!("Init complete: {} packages available.", db.len());
    println!("Listening on Port {}", args.port);

    match (args.cert, args.key) {
        (Some(c), Some(k)) => {
            warp::serve(search)
                .tls()
                .cert_path(c)
                .key_path(k)
                .run(([0, 0, 0, 0], args.port))
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
