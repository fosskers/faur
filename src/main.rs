use serde::{Deserialize, Deserializer};
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

fn commas<'de, D>(deserializer: D) -> Result<Vec<String>, D::Error>
where
    D: Deserializer<'de>,
{
    let s = String::deserialize(deserializer)?;
    let v = s.split(',').map(|s| s.to_string()).collect();
    Ok(v)
}

#[tokio::main]
async fn main() {
    let search = warp::get()
        .and(warp::path("packages"))
        .and(warp::query::<Query>())
        .map(|q: Query| {
            let msg = format!("You searched for: {:?} (by {:?})", q.name, q.by);
            warp::reply::json(&msg)
        });

    warp::serve(search).run(([127, 0, 0, 1], 3030)).await;
}
