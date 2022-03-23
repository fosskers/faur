use serde::Deserialize;
use warp::Filter;

#[derive(Deserialize)]
struct Query {
    name: String,
    by: Option<By>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "lowercase")]
enum By {
    Provides,
    Dep,
}

#[tokio::main]
async fn main() {
    let search = warp::get()
        .and(warp::path("packages"))
        .and(warp::query::<Query>())
        .map(|q: Query| {
            let msg = format!("You searched for: {} (by {:?})", q.name, q.by);
            warp::reply::json(&msg)
        });

    warp::serve(search).run(([127, 0, 0, 1], 3030)).await;
}
