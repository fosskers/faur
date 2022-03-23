use serde::Deserialize;
use warp::Filter;

#[derive(Deserialize)]
struct Query {
    name: String,
    // TODO by=provides, etc.
}

#[tokio::main]
async fn main() {
    let search = warp::get()
        .and(warp::path("packages"))
        .and(warp::query::<Query>())
        .map(|q: Query| warp::reply::json(&format!("You searched for: {}", q.name)));

    warp::serve(search).run(([127, 0, 0, 1], 3030)).await;
}
