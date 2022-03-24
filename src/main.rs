mod error;

use error::Error;
use serde::{Deserialize, Deserializer, Serialize};
use std::fs::File;
use std::io::BufReader;
use warp::Filter;

#[derive(Deserialize)]
struct Query {
    #[serde(deserialize_with = "commas")]
    name: Vec<String>,
    by: Option<By>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "lowercase")]
enum By {
    Provides,
    Dep,
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "PascalCase")]
struct Package {
    name: String,
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
    let reader = BufReader::new(File::open("db.json")?);
    let db = serde_json::from_reader(reader)?;
    Ok(db)
}

#[tokio::main]
async fn main() -> Result<(), Error> {
    let db = db_init()?;

    let search = warp::get()
        .and(warp::path("packages"))
        .and(warp::query::<Query>())
        .map(|q: Query| {
            let msg = format!("You searched for: {:?} (by {:?})", q.name, q.by);
            warp::reply::json(&msg)
        });

    warp::serve(search).run(([127, 0, 0, 1], 3030)).await;

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
