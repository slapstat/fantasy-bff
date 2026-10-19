# CLAUDE.md — fantasy-bff

Backend-for-Frontend (BFF) for the fantasy hockey draft tool. It is the only
service the Angular frontend (`fantasy-web`) talks to. It handles auth (JWT),
serves player data, and orchestrates calls to downstream services
(`fantasy-db-service` for users/persistence, `fantasy-yahoo-service` for Yahoo leagues,
`fantasy-espn-service` for ESPN leagues, `fantasy-projection-service` for the projection
model). The **player pool** comes from whichever platform `players.source` names — see
`PlayerPoolSource`.

## Design rules

- **No business logic here.** A rule that belongs to a domain lives in the service that owns
  that domain. The BFF receives, fans out, aggregates, reshapes, returns.
- **Frontend-driven API design.** Endpoints and response shapes are designed around what the
  Angular app needs, not around what the downstream services happen to return.
- **Stateless** — no session state; identity travels in the JWT.
- **Virtual threads, never reactive.** `spring.threads.virtual.enabled=true` plus synchronous
  `RestClient` calls. Never write `Mono`, `Flux` or `.subscribe()`, and never reach for
  `WebClient` or the deprecated `RestTemplate`. Blocking a virtual thread is the point:
  it keeps the whole stack free to block (JDBC, any library) without starving a carrier pool.
- **Fail gracefully.** A downstream being unavailable should not fail the whole response when
  a partial one is meaningful — `/players` serves the Yahoo line without the ESPN-only stats
  when espn-service is unreachable.
- **Layering is one-way**: controller → service → client. No skipping.
- Java `record`s for DTOs (requests in `dto/request/`, responses in `dto/response/`);
  `Optional` rather than a returned `null`; prefer built-in exceptions over custom ones.
- Never hardcode a URL or a secret — `@Value` / `@ConfigurationProperties` backed by env.
- Every new service method gets a test: happy path plus at least one failure or edge case.

## Tech stack

Versions live in `build.gradle` and nowhere else, so they are not repeated here.

- Java 25, Spring Boot 4, Gradle (wrapper: `./gradlew`)
- Spring Security + JWT (jjwt, HS256)
- Spring WebMVC (virtual threads enabled), `RestClient` for downstream calls
- springdoc OpenAPI / Swagger UI
- openapi-generator (generates model POJOs from `specs/` at compile time)
- Tests: JUnit 5, Spring Boot Test, MockMvc, WireMock (standalone), H2 not used here

## Common commands

```bash
./gradlew build          # compile + test (CI runs: ./gradlew build --no-daemon)
./gradlew test           # tests only

# Run locally (start the downstreams first — see those repos). Startup fails without
# JWT_SECRET and all four internal keys, each matching that service's own INTERNAL_API_KEY:
SPRING_PROFILES_ACTIVE=dev JWT_SECRET=<32chars> DB_INTERNAL_API_KEY=… YAHOO_INTERNAL_API_KEY=… \
  ESPN_INTERNAL_API_KEY=… PROJECTION_INTERNAL_API_KEY=… ./gradlew bootRun

./gradlew generateOpenApiClients   # regenerate every downstream client from specs/
./gradlew openApiGenerate          # just db-service
./gradlew generateYahooClient      # just yahoo-service
./gradlew generateEspnClient       # just espn-service
./gradlew generateProjectionClient # just projection-service
```

Swagger UI (when running): `http://localhost:8080/swagger-ui.html`

**Updating a downstream client:** never edit a pinned spec by hand — copy the producer's
committed spec over it verbatim and regenerate (see
[OpenAPI-first downstream clients](#openapi-first-downstream-clients)). Generated classes
are not committed; every build regenerates them.

## Architecture

- `controller/` — REST endpoints under `/api/v1` (`AuthController`, `PlayerController`,
  `ProjectionController` — the user's saved projections; takes the user id from the JWT
  and forwards to db-service, never trusting a client-supplied user id. A create may set
  `kind: draft`, which is how a draft **against a preset** is started: the preset is defined
  here and not by the caller, so it must come with `source=default` or `source=model` — the
  **player rows** are the server's. The **name** is the caller's, here as anywhere: a draft can
  be renamed the moment it exists, so overruling the caller only ever held for the seconds in
  between, and what a draft was played against is recorded in `preset` (and
  `sourceProjectionId`) rather than in its name. Drafts are kept out of "my projections" by the
  web filtering on `kind`). Reading one runs it through `ProjectionPoolReconciler` first — see
  below
  - `POST /api/v1/projections/{id}/drafts` — starting a draft **against one of the user's own
    boards**. db-service copies that board's rows into a draft of its own, so the ~0.5 MB never
    travels and the board stays editable, and deletable, while the draft is under way. A board
    may be drafted against **any number of times**: the draft is a row, not a field on the
    board, and a name another draft holds is numbered (`My league (2)`) rather than refused.
    Nothing is reconciled here — a draft is a snapshot by design, and adding players to it
    mid-draft would move the numbers under whoever is picking.
  - `PUT /api/v1/projections/{id}/name` — renaming a board or a draft without sending the board
    with it. A name the **user** typed is refused where it is taken (409); one the **app**
    derived (`derived: true`, a draft taking the name of the league it was just synced with) is
    numbered instead, and is skipped altogether where the user has already named the row.
  - `POST /api/v1/projections/imports` on `ProjectionController` — **following** a shared board
    by its share token. Anyone signed in who holds a link may follow it; the follow is a live
    mirror, rewritten name and all every time the author publishes again, and the follower's own
    draft is the only thing on it they may change. It reports `origin` (share token + the
    author's name) so the app can say whose numbers it holds. Following is idempotent — a user
    has at most one follow per token, and following again returns that one with **200** instead
    of 201 — and the follow disappears with the share. The link being the caller's **own** board
    is refused with 400 here. The mirrored rows are squared with the pool **in memory on every
    read** and never written back (db-service takes only `data.draft` on a follow).
  - `POST /api/v1/projections/copies` on `ProjectionController` — **copying** a shared board into
    a projection of the caller's own: the board as published now, named `Copy of <name>`
    (numbered where that name is taken), with no draft, no `origin` and no link back. Nothing
    the author publishes afterwards reaches it. **Only the copy is made** — no follow is created
    and a follow the user already holds is left alone (fantasy-db-service#143) — and a board of
    one's **own** may be copied, so the 400 for "this link is your own board" lives on
    `/imports` only. The rows arrive against the author's pool, so the BFF squares them and
    saves them straight away, reporting nothing: a board copied seconds ago has no "since".
  - `ProjectionShareController` / `SharedProjectionController` — publishing a projection under a
    public link, which is a one-way action: there is no endpoint to refresh or withdraw a
    published snapshot. The owner's side lives under `/api/v1/projections/{id}/share` (authenticated);
    the visitor's side is `GET /api/v1/shared/{token}`, permitted for everyone — a share link has
    to open for someone who has never signed in. `SharedProjectionController.preview` additionally
    serves a small HTML document with per-share Open Graph tags: chat and social crawlers run no
    JavaScript, so without it every shared link would unfurl as the site-wide preview. nginx routes
    crawler user agents for `/s/*` there; it is `@Hidden` from the spec since it serves HTML to bots
    rather than JSON to the web client.
    `GET /api/v1/shared/{token}/avatar` is permitted for everyone too: the picture of whoever
    published the board, for the byline on that page. It is fetched from db-service by the share
    token, so neither the browser nor this service holds a user id for a public page, and the
    address the page is given (`authorAvatar`) carries the stamp on the picture so a replacement
    is a new address rather than a cached old one. It is **not** behind `players.avatars.enabled`
    below: that switch is about the platform's photographs of players, which we have no licence
    to show; an account's own picture is its own.
    The tags point at `ShareCardRenderer`'s per-share card (`/{token}/og-image.png`), drawn with
    Java2D from the same snapshot the page shows — which is why the runtime image installs
    `fontconfig` and a font: without them Java2D renders every glyph as a box instead of
    failing, so it would only surface when someone looked at a preview.
  - `AccountController` — the signed-in account itself: `GET /api/v1/account`,
    `PUT /api/v1/account/username`, and the profile picture under `/api/v1/account/avatar`
    (`GET` serves the bytes, or an empty 204 for an account without one; `PUT` takes a
    multipart `file`; `DELETE`), and `POST /api/v1/account/sessions/revoke`, "sign out
    everywhere": it has db-service bump the account's `tokenVersion`, so every refresh token issued
    before stops working at its next `/refresh` (access tokens run out their 15 minutes). It is the
    only way an account without a password can end a stolen session. `AccountResponse` is deliberately thinner than what
    db-service returns to its trusted caller — the password hash and social subject ids stop
    here. Sharing a projection requires a username, so the share endpoints relay db-service's
    409 when an account has not picked one. The picture goes through `AvatarService`, the one
    place user-supplied bytes enter to be served back out as an image: it reads the file's own
    magic bytes (PNG, JPEG or WebP; never an SVG, which can carry scripts) and caps it at 512 KB,
    then hands db-service the bytes with the type it found, not the one the upload claimed. The
    web scales the picture down before uploading, so a real upload is a few tens of KB;
    `spring.servlet.multipart` bounds the request itself at 1 MB.
  - `AdminController` also carries the premium admin view (`ROLE_ADMIN` like the rest of
    `/api/v1/admin`): `GET /premium/customers` lists everyone with premium right now, paying or
    granted, and `POST /premium/grants` gives an account premium for a number of months without a
    payment (`DELETE /premium/grants/{userId}` takes it back). A grant lives in its own table in
    db-service rather than in `subscriptions`, so giving one never touches billing and a provider
    event can never overwrite it. `EntitlementService` asks db-service one question
    (`GET /api/v1/users/{id}/premium`) that already accounts for both, and `EntitlementsResponse`
    reports `source` so the account page can say "free until <date>" and hide a billing portal
    there is nothing behind.
  - `BillingController` — `/api/v1/billing`: checkout, the customer portal, the current
    entitlement, and the provider's webhook. Billing **state** lives in db-service; the BFF
    stays stateless and only relays. Everything goes through the `payments/`
    `PaymentProvider` interface, so which provider is live is a `payments.provider` config
    change and nothing else — `MockPaymentProvider` drives the whole lifecycle locally,
    `StripePaymentProvider` is the real one. Two things to know before changing it: the **webhook is
    `permitAll`** — the provider calls it unauthenticated, so its only defence is the
    signature `parseAndVerify` checks over the *raw* body, and the body stays `byte[]` the
    whole way down because re-serializing it would change the bytes the signature covers;
    and a verified event with **no subscription snapshot is acknowledged and dropped**, since
    a provider sends more event types than we model and retries anything we answer with an
    error.
  - `payments/StripePaymentProvider` — Stripe Billing through Stripe-hosted Checkout, with
    **Managed Payments** (Stripe/Link as merchant of record) on unless `STRIPE_MANAGED_PAYMENTS`
    turns it off. The checkout URL is Stripe's own, so the web only redirects. The user rides on
    the subscription's `metadata.user_id` (set through `subscription_data`), and the session's
    `metadata.price_id` is what `isCheckoutOpen` compares. It calls the API over `RestClient`
    with a pinned `Stripe-Version` and reads webhooks as plain JSON rather than through Stripe's
    Java library, whose event deserializer goes silent when the endpoint's API version differs
    from its own; the one field that moved between versions (`current_period_end`, now on the
    subscription item) is read from both places. `StripeSignatureVerifier` accepts any matching
    `v1` in `Stripe-Signature`, since both secrets sign while one is being rolled.
  - `FeedbackController` — `POST /api/v1/feedback`: a signed-in user's bug report or feature
    request, filed by `FeedbackService` as an issue in the **private** repository
    `feedback.github.repository` (`HttpGitHubIssueClient`), with the account's email so the
    answer can go out by mail, and `ResendFeedbackNotificationEmailSender` mails the title and
    link to `feedback.notify-email` (GitHub never notifies the token's owner of its own issues). The user's text sits in a code block sized past any backticks it
    holds, so it can neither ping nor cross-reference anything; `page` is a bare path, since a
    query string can carry a reset token. Always served; `FEEDBACK_GITHUB_TOKEN` is required. Never point it at a public
    repository.
  - `ProjectionModelController` — `/api/v1/projection-model`: the projection service's output
    made usable here. `/seed` returns model lines keyed by *this* platform's player id, ready
    to save as a new projection — deliberately without scoring settings, which belong to the
    user's league. `/splits/{skaters,goalies}` return what players **actually produced** over
    a stretch of games — measured, not projected. Ranges are team game numbers so the same
    range means the same stretch for everyone, and `lastGames` is each team's own last N, so it
    means the same mid-season. A split with **no season is left to projection-service**, which
    reads the newest season with a game played: last season until the new one is underway.
    `/splits/seasons` lists each season's own length (82, or 84 from 2026-27) and how far it has
    got, which is where the web takes both from; no season length lives in this repo.
    **Whether the AI projection is served at all is one answer, `AiProjectionAvailability`**:
    `ai-projection.enabled` (`AI_PROJECTION_ENABLED`, on by default). The prefix itself is open
    to any signed-in user (its `PROJECTION_MODEL_ENABLED` switch was removed 2026-10-09).
    `/seed` 404s without it, `source=model` in
    `ProjectionService.create` is refused without it, and public `GET /api/v1/features`
    reports it as `aiProjection`, which is what the web reads to offer or drop the AI preset.
    The web has no switch of its own, so the two cannot disagree. `ai-projection.enabled` does
    **not** cover the splits: those are measured numbers behind Who's hot and stay up.
    **The model's lines are premium**, and there are two ways to them that share no code, so
    both are gated: `/seed`, which hands them to the new-projection page, and `source=model`
    in `ProjectionService.create`, which fills a projection or a preset's draft with them
    server-side without the client ever seeing a row. Either refuses with **403
    `PREMIUM_REQUIRED`**. The switch is checked first — an environment without the feature
    answers about the switch, since "subscribe" would point at a page that cannot make it
    appear — and `source=default` is never gated, so the free starting point costs no
    subscription lookup. This one is deliberately **not** hidden by the web: the AI projection
    stays visible to a free account, marked and sold, so the server is what makes it a refusal
    rather than a missing button, and anyone reading the network tab meets the same answer.
    The splits carry a switch of their own: **which stretch** they measure is premium. A free
    account gets the last 5 games (`lastGames<=5`, or a `fromGame`/`toGame` window of five or
    fewer inside the last five of its own season: 78-82 in 2025-26, 80-84 from 2026-27, so the
    length is asked of projection-service, and only for a window short enough to be free); anything else is
    **403 `PREMIUM_REQUIRED`**, including an open range, which means the whole season. Two
    orderings matter and are tested: the range is judged *before* the subscription is read, so
    a free account's own requests cost db-service nothing. Entitlement comes from
    `EntitlementService`, which `BillingController` shares — `entitlements()` is what to
    *report*, `hasPremiumAccess()` is what to *allow*. Payments are always on (the
    `PAYMENTS_ENABLED` switch was removed 2026-10-09).
  - `YahooController` / `EspnController` — the signed-in user's league integrations, both
    forwarding the JWT subject to the service that owns the data and both ending at the same
    place: `…/leagues/{id}/projection-settings`, the league's scoring mapped into *our*
    projection settings, so the two platforms converge before the web ever sees them. They
    differ where the platforms differ — Yahoo has `POST /connect`, `POST /connect/complete` and a
    `/connection` status because it has OAuth (the callback only parks the tokens; `/connect/complete`
    claims them with the one-time code from the web's URL fragment, and yahoo-service attaches them
    only for the user who started the flow, so the user id must come from the JWT, never the body —
    the admin twin `/admin/yahoo/connect/complete` claims for the service account); ESPN has none, so `EspnController` manages the user's stored
    `espn_s2` + `SWID` cookies instead. Note `GET /espn/credentials/values` hands the caller
    back **their own** cookies (everything else exposes only a `hasCredentials` flag).
    `GET /yahoo/leagues/{key}/draft` is what the draft room polls while it follows a league's
    live draft: teams keyed by Yahoo's team key in first-round order, and only the unbroken run
    of picks made from pick 1, since the board numbers a pick by its place in the list.
    `orderKnown` is yahoo-service's, passed on unchanged: false while the teams are in Yahoo's own
    list order rather than the draft's, as they are before a live draft runs. It is
    served wherever **`LeagueDraftSyncAvailability`** says so: a pool on Yahoo ids, because the
    picks name players by Yahoo's. Without it the endpoint 404s. Its `DRAFT_LEAGUE_SYNC_ENABLED`
    switch and the `leagueDraftSync` field on `GET /api/v1/features` were removed 2026-10-09, so
    the web offers following unconditionally. Not premium.
    An ESPN league's draft is **not** followed: ESPN's league API lists a draft's picks only once
    the draft is over (seen on a real draft 2026-09-29), so the ESPN draft endpoint and its
    `ESPN_DRAFT_LEAGUE_SYNC_ENABLED` switch were removed (2026-09-30). What survives of it is
    Team Power Rankings' read of an ESPN league (`EspnLeagueRosterService`, from espn-service's
    `/rosters`): team ids `espn.l.{leagueId}.t.{teamId}`, and ESPN ids put into the pool's
    numbering by `EspnPoolIdCrosswalk` — identity over an ESPN pool; over a Yahoo pool the
    **whole** ESPN pool matched against the whole served pool by `PlayerIdResolver` (a league's
    subset would blind its namesake guards), held for `league-draft-sync.espn-crosswalk-ttl-ms`
    (30 min). A rostered player with no counterpart is kept as the **negative** of his ESPN id,
    which no pool id is, so he counts as unprojected.
  - `StreamerPlannerController` — also `GET /free-agents?platform=&leagueId=&start=&end=`: the
    players a league has available, each with the **model's line over that stretch**
    (projection-service's `/projections/range`). The two sides are joined on **identity**, through
    `PlayerIdResolver` against the NHL-side names, not through the pool's id space — a Yahoo
    league's wire carries Yahoo ids, which an ESPN-numbered pool could not resolve at all, so the
    answer is right whichever way `PLAYERS_SOURCE` is set. Stats go out in the projection's own
    vocabulary and **nothing here scores them**: the client weighs them by the league's scoring
    settings, as everywhere else. A free agent the model does not project is counted in
    `unprojected` rather than shown with zeroes.
  - `StreamerPlannerController` — `/api/v1/streamer-planner`: `GET /weeks` (the newest published
    season's Monday-Sunday weeks, numbered from opening night, and the week today falls in) and
    `GET /teams?start=&end=` (every NHL team's games over up to 31 days, with a skater and a goalie
    score and rank). Both are projection-service's `/schedule/*`, mapped into the BFF's own records;
    the rating itself lives there. Signed in, not premium; always served (the
    `STREAMER_PLANNER_ENABLED` / `STREAMER_PLANNER_MY_TEAM_ENABLED` switches were removed
    2026-10-09). Team codes are the NHL's (`TBL`), not ESPN's (`TB`).
    `GET /my-team?platform=&leagueId=` (`StreamerPlannerMyTeamService`) is the user's own team in
    the league, read live from the platform's rosters (never the pool): each player's club, real
    positions, today's slot and `out` (IR/NA slots, O/OUT/IR/suspension; day-to-day plays). The web
    places them per night to find the open slots.
  - `PlayerScoutController` — **Player Scout**, which replaced the FA scout on 2026-10-14 (its
    route and flag are gone; Role Changes was folded in too, then given back its own page on
    2026-10-19, see `RoleChangesController`). `GET /api/v1/player-scout/players?platform=&leagueId=
    [&since=]`: every player the league has available **or on a team** (`LeagueOwnTeam.readAll`,
    each row with `owner` {teamName, mine} or `availability`), joined on identity, with the served
    rest of the season `now` and the model's line as it stood on `since` as `then`: the newest day
    projection-service kept on or before it (`/rest-of-season?as_of=`), else the preseason line,
    and `since` {kind SNAPSHOT|PRESEASON|NONE, resolved, earliestSnapshot} says which. **The then
    line is re-spread over the games his club has left today** (`ScoutRescale`: the share of the
    schedule it expected him to play, of today's schedule, at its own per-game rates; rates such as
    `toiPerGame`, `shPct`, `svPct`, `gaa`, `winPct` carry as they are), so the two can be read side
    by side. Beside them `usage`, his measured ice since the same day
    (`/role-changes/skaters?since=`): one date asks both questions. Nothing is scored; the web
    ranks. `GET /my-team` is the own-team read (`ScoutLineService.restOfSeasonTeam`) plus `schedule`, every club's remaining
    nights, for the web to seat the roster over the rest of the season. Signed in, not premium
    (Alexander's call, 2026-10-14). Behind `player-scout.enabled` (`PLAYER_SCOUT_ENABLED`, off by
    default) AND the AI projection (`PlayerScoutAvailability`): otherwise 404, and
    `GET /api/v1/features` reports `playerScout` false.
  - `RoleChangesController` — `GET /api/v1/role-changes?recentGames=[&season=]`: every skater's
    ice and power-play share over his club's last N games beside his baseline, and where the lineup
    page lists him now and before (`RoleChangeService`, projection-service's
    `/role-changes/skaters?recent_games=`, joined through `PlayerSplitContextProvider`). Measured,
    not projected; unsorted. Signed in, not premium. Behind `role-changes.enabled`
    (`ROLE_CHANGES_ENABLED`, off by default; `RoleChangesAvailability`): otherwise 404, and
    `GET /api/v1/features` reports `roleChanges` false.
  - `StartBenchController` — `GET /api/v1/start-bench/my-team?platform=&leagueId=&date=`: the
    user's team as the platform has it **set for that day** (Yahoo's `roster;date=`, ESPN's daily
    scoring period, through each service's rosters `date`), each player with the model's line for
    that day alone (`StreamerPlannerMyTeamService.onDate`, a one-day stretch). The date is required:
    the web sends its own hockey night rather than the server picking one. The web sets the best
    lineup from it. `GET /rest-of-season?platform=&leagueId=` is FA Scout's own-team read
    (`ScoutLineService.restOfSeasonTeam`) under Start/Bench's switch, not the scout's: the web ranks
    the roster by it to name the drop when a healthy player comes off injured reserve.
    Signed in, not premium. Behind `start-bench.enabled` (`START_BENCH_ENABLED`, off
    by default) AND the AI projection (`StartBenchAvailability`): otherwise 404, and
    `GET /api/v1/features` reports `startBench` false.
  - `DraftAnalysisController` — `GET /api/v1/draft-analysis/yahoo/{leagueKey}`: a Yahoo league's
    draft with **every pick graded against the model** (`DraftAnalysisService`, `scoring.DraftGrader`).
    Players are valued by the league's own scoring over the whole pool, as Team Power Rankings values
    them (`ProjectionScoring`), against the frozen preseason line (`model_version=preseason`), or the
    current season line where none is stored (`preseason: false`). The rank is by **value over
    replacement** (`scoring.ReplacementLevel`): the league's starting slots times its teams are
    filled best value first (own position, then W, F, Util), and a position's replacement is the
    best player eligible there left unstarted, so a league starting 4 D and 2 G ranks them up; a
    multi-position player is measured where his replacement is weakest (`positionRank` "D1"). A
    pick's grade is `(pick + teams) / (rank + teams)` in five bands (STEAL ... BIG_REACH; UNRANKED
    without a line); its value over the slot is its value over replacement less that of the
    model's player at that number, summed per team with an A-F letter. Teams' sums for everyone, each pick's rank/grade/best available with premium
    (the Team Power Rankings rule). Behind `draft-analysis.enabled` (`DRAFT_ANALYSIS_ENABLED`, off by
    default) AND the AI projection AND a Yahoo-id pool (`DraftAnalysisAvailability`): otherwise
    404, and `GET /api/v1/features` reports `draftAnalysis` false.
  - `VersionController` — `GET /api/v1/versions`: each service's deployed version and whether
    it answered, probed in parallel on virtual threads. A service that cannot be reached comes
    back `reachable: false` rather than failing the response — the endpoint exists to show
    that, so it must survive it. It also reports `playerSource`, the platform the pool is
    **actually** being served from, read off the wired `PlayerPoolSource` rather than the
    configured value: the switch is an environment variable, and one that did not take looks
    exactly like one that was never set. projection-service is absent on purpose — it is
    FastAPI, serves no `/actuator/info` and stamps no deployed version, so probing it would
    report it down forever. It is public (the promotion workflows poll production's copy
    unauthenticated), so `VersionService` keeps one answer for 10 seconds and shares it with
    everyone who asks meanwhile, and the route is rate-limited like the other public reads. Hiding
    the versions was not the point: every production release is a published GitHub release of a
    public repo already.
- `service/` — business logic (`AuthService`, `PlayerService`)
  - `PlayerPoolSource` — where the player pool comes from, chosen by `players.source`:
    `YahooPlayerPoolSource` (yahoo-service, with the four ESPN-only stats matched in by name)
    or `EspnPlayerPoolSource` (espn-service, where every stat is already on the player's own
    row). Both hand back the same shape, so nothing downstream can tell which answered — but
    **not the same player ids**. A stored projection is keyed by the ids that were in use when
    it was saved, so flipping the flag goes together with migrating those rows. `PlayerService`,
    `ProjectionSeedService` and `PlayerSplitContextProvider` all read the pool through this
    seam, so the switch reaches every one of them at once. The source also answers
    `playerIdSpace()`, which is what a new projection is stamped with on create — asked of the
    pool rather than read off config, because the pool is what produced the numbers, and a
    projection stamped with the wrong space is only found out when a remap translates ids that
    were never in it.
  - `PlayerIdRemapService` — the one-off that goes with flipping that flag: it matches the
    Yahoo pool to the ESPN one with `PlayerIdResolver` and hands the crosswalk to db-service,
    which rewrites the ids in every saved projection, draft pick and share. This is the only
    service that can see both pools — the Yahoo one is still readable from yahoo-service's
    cache even though Yahoo no longer serves players. Admin-only, dry run by default, and it
    refuses to apply if either pool came back short, or if too few of the players who **actually
    played** matched — a row left behind comes back marked as migrated and a rerun cannot reach
    it. The gate is deliberately not overall coverage: the Yahoo pool is frozen from last season
    and hundreds of its players never got into a game, so they are correctly absent from ESPN's
    active list (measured on staging: 83.7% of the pool matched, and every one of the 259 that
    did not had played nothing).
  - Headshots come from `/api/v1/players/{id}/headshot` for **both** pools, framed on the face
    by `HeadshotThumbnailer` and held by `HeadshotCache`. The ESPN pool's used to be ESPN's own
    CDN URLs handed straight to the browser, because proxying put a page's worth of image
    requests — some sixteen hundred — through one host and the edge refused a share of them;
    what that cost was the framing, since nothing we wrote ever saw those pictures. The refusals
    were Traefik's `api-ratelimit` (15/s) on a route that has no business behind it, and that
    route now has its own laxer limiter (see the monorepo `INFRASTRUCTURE.md`). The address
    carries the recipe the avatar was drawn by (`?v=96-1.55-0.04`) so that reframing or
    resizing it is a different thing to fetch rather than something a week-old browser cache
    hides. Each pool drops the URL for a player it has no picture for — roughly one in seven on
    ESPN — so an address that arrives is one that resolves, and the web falls back to initials
    for the rest.
  - **All of that is switched off by default** (`players.avatars.enabled`,
    `PLAYER_AVATARS_ENABLED`, `PlayerAvatarsProperties`): the pictures are the platform's
    photographs and we hold no licence to show them. Off, `PlayerService` sends every player
    without a headshot and answers the endpoint with nothing before touching the cache or the
    pool, and `SharedProjectionController` strips the address a share snapshot stored. The web
    has no switch of its own; a missing headshot already draws initials.
  - `mapping/PlayerFieldMapping` — the reshaping both sources share (positions, `avgToi` →
    seconds, shooting pct fraction → percent, rounding, goalie win %). The two must agree
    exactly: a projection is keyed by the stat names these produce.
  - `AdminController`'s `POST /api/v1/admin/espn/players/sync` starts a refresh of the ESPN
    player pool — which is *the* player pool — and answers **202** as soon as it is under way;
    `GET /api/v1/admin/espn/players/sync/latest` is how it is watched, until `running` is false
    and `syncedAt` has moved. The work is minutes long, and holding a request open for it across
    every hop is what made the first, synchronous version fragile. Without the lever the pool
    only moves on espn-service's nightly run, and a deployment that changes what the sync stores
    has to wait for morning to show its effect.
  - `AdminController`'s `GET /api/v1/admin/yahoo/probe` proxies yahoo-service's live Yahoo probe
    (see its `YahooProbeService`): one call for a chosen game key and season, reporting the status
    Yahoo answered with and its own error wording. A failing sync only says that *something* was
    refused; this is how you find out what. Admin-only, since the answer names the service account
    and quotes upstream errors.
  - `service/mapping/PlayerIdResolver` — matches NHL players to the platform's players, because
    **nobody publishes a crosswalk** between NHL ids and Yahoo/ESPN ids. Everything the
    projection model feeds (seeding, splits) crosses this bridge, so read its Javadoc before
    touching it: the rules were measured against staging data, not guessed. Two that look wrong
    until you see the numbers — **team is not a matching key** (requiring it drops coverage from
    97.4% to 77.9%, since the platform's rows are a sync snapshot while the NHL's team is live),
    and leftovers **stay unmatched on purpose** (a fantasy platform simply doesn't carry fringe
    players). `ProjectionSeedService` and `PlayerSplitService` sit on top of it — the seed side
    also refuses to fill an absent stat with a zero, which would read as a terrible goalie
    rather than one the model projects no starts for.
  - **Which model version the seed reads is not ours to decide.**
    `PROJECTION_MODEL_VERSION` is empty by default, and empty means "whichever version
    projection-service most recently ran for the season". It used to name a version, and that
    made every model bump a manual step here: nothing enforced it, nothing alarmed when it was
    missed, and the BFF simply went on serving the old version's rows, which are still in the
    projection database because a run only clears stale rows within its own version. Naming a
    version still pins it, which is the rollback path. The version in `SeededProjectionResponse`
    is read off the rows that came back rather than echoed from config, since unpinned we do not
    know it until we see it.
  - `PlayerPoolRows` — the player read model as projection rows, either keeping each player's
    stats or zeroed. The one place rows are built, so a player added to a projection a season
    after it was written carries exactly the stat keys of the ones created alongside it.
  - `RookieService` — which players are rookies for the season being projected, by the NHL's
    rule (projection-service decides; see its `rookies.py`). It reads the same cached NHL-side
    context the game-range splits are built on, so the answer costs nothing extra. The response
    carries `known` because "nobody is a rookie" and "we cannot say" are different answers that
    would otherwise arrive as the same empty list — **`known: false` is the answer whenever
    projection-service cannot be reached or fails**, and a client that ignored it would mark an
    entire league as veterans.
  - `ProjectionPoolReconciler` — keeps a saved projection's rows in step with the pool, which
    moves under it all season (a new roster in the autumn, trades and call-ups after). It **only
    ever adds**: a row whose player has left the pool stays where it is and is simply not shown
    (the client already skips any row it cannot draw). Deleting it would be irreversible, and the
    pool is not reliable enough to bet a user's work on — a truncated fetch, or a player Yahoo
    momentarily stops listing, would cost numbers they cannot get back; kept, those rows return
    by themselves when the pool does. Gained players are added, seeded from the projection's
    `playerBasis` — last season's stat line, zeros for one started from scratch, or the model's
    line for one started from the AI projection (last season's where the model has none, which
    is every player without an NHL season; also when the model is off or unreadable, and
    premium is not asked). The basis is stamped at create from `source`; a projection saved
    before it existed is read off its own rows (almost all zeros → started from scratch), which
    can never yield `model`. A `model` basis the client sends itself (a copy, a save) is kept
    only with premium or when the stored projection already holds it, else recorded as
    `last_season` — otherwise it would unlock the model's lines for every row left out. Guarded by `playerPoolSyncedAt` against the
    latest successful sync run, so the full pool read happens at most once per projection per
    sync rather than on every open; the result is written back, and the players a read added are
    appended to the settings' `unacknowledgedNewPlayerIds`, where they stay until the client sends
    the list back empty. That list, not a one-off response field, is what the app's "Player list
    updated" notice reads, so it survives a reload and another device. A new projection is
    squared **before it is written** (on create, and on import straight after db-service copies
    it), with nothing reported: a starting point that does not cover the whole pool, like the
    model's lines or a shared board, would otherwise have its first read announce the players it
    lacked as having joined since it was created. A pool that cannot
    be read — or comes back empty — leaves the projection alone rather than dropping every row
    it cannot account for.
- `client/` — downstream clients. Each is an **interface** plus an **http**
  implementation that uses OpenAPI-generated models (no mock implementations —
  tests replace clients with `@MockitoBean`).
  - `PlayerServiceClient` — `HttpPlayerServiceClient` reads the cached player read model from
    `fantasy-yahoo-service`, asking for the season named by `services.yahoo-fantasy.stats-season`.
    That is what the app **shows** — the reference column a projection is built against — and is
    a deliberately separate choice from what yahoo-service is **collecting** (`SYNC_YAHOO_SEASON`
    there): for most of the year we gather the season being played while the editor still reads
    the one that finished. It owns the reshaping into the frontend's `SkaterResponse` /
    `GoalieResponse` (positions from Yahoo eligibility, `avgToi` → seconds, shooting pct
    fraction → percent, ppa/sha derived from points − goals, special teams summed, null stats
    → zeroed blocks for rookies).
  - `EspnServiceClient` — ESPN leagues, the whole ESPN-sourced player pool
    (`skaters`/`goalies`), and the cached ESPN stat lines for the four stats Yahoo does not
    report at all (hat tricks, shifts, goalie overtime losses, time on ice).
    `YahooPlayerPoolSource` merges those four onto the Yahoo line; see `EspnStatLineIndex` for
    how the two sides are matched, and why a line that two players answer to goes to neither.
  - `YahooServiceClient` — a user's Yahoo OAuth connection and league settings.
  - `DatabaseServiceClient` — `HttpDatabaseServiceClient` talks to `fantasy-db-service`.
- `config/` — `SecurityConfig`, `RestClientConfig` (downstream `RestClient` beans),
  `*Properties` (typed config), `OpenApiConfig`
- `security/RateLimitFilter` — per-client-IP limits, configured entirely under
  `security.rate-limit.endpoints`. A key is written in brackets (`"[/api/v1/auth/login]"`), since
  the binder strips the slashes from a bare map key and the rule then matches nothing. A key is an exact path or an Ant pattern, and a pattern
  counts every path it covers into **one bucket per client** — which is the point for the
  public share reads, where a caller working through tokens would never fill a per-path
  bucket. `method` defaults to POST, so the auth rules read as before.
  Note what the client is for the crawler-facing share paths: nginx proxies them through the
  public origin, so the last forwarded hop is the web server and every crawler shares one bucket.
  Those limits are a ceiling on total load rather than a per-caller limit, and are set
  accordingly; the per-caller limit for those two paths lives in fantasy-web's `nginx.conf`,
  which is the last hop that still sees the real client.
  `emails-per-address` is the one limit not keyed on the caller: `EmailSendThrottle` caps the
  verification and password-reset emails one address is sent, whoever asks, and answers a
  request over the cap exactly like one that sent mail.
- `security/RefreshTokenCookie` — every response that issues a token pair (login, register, google,
  google/code, facebook, refresh) also sets the refresh token as the HttpOnly cookie `slapstat_refresh`
  (Path `/api/v1/auth`, SameSite=Lax, host-only; Secure unless `security.refresh-cookie.secure` is
  false, which only the dev profile sets). Safari wipes localStorage after seven days without a
  visit; a server-set cookie outlives that. `/refresh` reads the cookie before the body, answers a
  request with neither with a quiet 401, and clears a cookie it refuses. `POST /auth/logout` and
  sign-out-everywhere clear it; logout revokes nothing. The refresh token is never in a response
  body (`AuthService` returns `IssuedTokens`, the controller splits it into cookie and body); the
  request body still takes one, for sessions a browser stored in localStorage before the cookie.
- `security/` — `JwtAuthenticationFilter`, `JwtTokenValidator`, and
  `GoogleTokenVerifier`/`NimbusGoogleTokenVerifier` (validates Google ID tokens against
  Google's JWKS: signature, issuer, audience = `security.google.client-id`, verified
  email). `POST /api/v1/auth/google` exchanges a Google ID token for our own JWT pair;
  db-service resolves the account (find by subject / link by email / create
  password-less). The Client ID is **public** (shipped to the browser by design) and
  comes from `${GOOGLE_CLIENT_ID}`; when unset the endpoint rejects all requests.
- `dto/` — request/response records
- `model/downstream/` — models for downstream responses (e.g. `User`)
- `exception/GlobalExceptionHandler` — maps exceptions → `ErrorDto`

## Profiles & config (`src/main/resources/application*.yaml`)

- **base** (`application.yaml`): downstream service URLs (env-overridable),
  JWT settings, `server.port=${PORT:8080}`, permitted URLs,
  `services.projection.season` (the season the model projects), **the CORS
  allowlist** (`${CORS_ALLOWED_ORIGINS:${WEB_ORIGIN:}}`) and **the API-docs gate**
  (`${SWAGGER_ENABLED:false}`).
- **`dev`**: enables + permits Swagger, allows CORS from `http://localhost:4200`, and is the
  only place `RESEND_API_KEY` may be blank — it sets `email.log-links`, so reset and
  verification links go to the log instead. Those links carry live tokens: never set it on a
  deployed environment.
- **`staging`**: a QA convenience only — it permits the API-doc URLs (staging pairs it with
  `SWAGGER_ENABLED=true`). No downstream timeout overrides — the services are co-located on
  the Docker network, so the base timeouts apply.

**No profile is load-bearing.** Everything a deployed instance depends on lives in the base
config and is driven by env vars, so prod is correct with no `SPRING_PROFILES_ACTIVE` at all.
This is deliberate: CORS origins and the Swagger gate used to live *only* in the `staging`
profile, which meant prod had to run a profile named "staging" (and inherited its
`http://localhost:4200` CORS origin as a side effect). `NoProfileDeployedSecurityTest` fails if
either setting moves back into a profile.

Adding config that a deployed environment needs? Put it in `application.yaml` behind an env
var — never in a profile.

`JWT_SECRET` must be ≥32 chars (HS256) and is supplied per environment as a Coolify env var.

**Required secrets fail fast.** `JWT_SECRET`, the four `*_INTERNAL_API_KEY`s
(`InternalApiKeyProperties`) and `RESEND_API_KEY` (`EmailProperties`) have no default, so a
missing or blank value stops startup. The Stripe, mock-payment, Google and Facebook secrets
keep empty defaults because their features fail closed without them; the list is at the top of
`application.yaml`. Once up, `DownstreamKeyVerifier` asks each downstream whether it accepts
our key; a 401 moves readiness to `REFUSING_TRAFFIC`, which `/actuator/health` includes, so the
container health check fails and the deploy rolls back.

## Conventions

- **No code comments unless asked.** Don't write code comments or documentation unless
  specifically asked to — prefer self-explanatory names. (Same AI guideline as the
  `fantasy-web` repo.)
- Downstream clients are an interface + an http implementation. Tests never hit
  real downstream services: integration tests replace the client interfaces with
  `@MockitoBean`; http clients are tested in isolation with WireMock, started from
  `WireMockConfigs.http11()` (h2c off: over it WireMock drops POST bodies and a verify
  races the request journal).
- Keep new endpoints under `/api/v1`. Default is authenticated. To make something
  public, pick the mechanism by what it is:
  - **Infra/plumbing** (health, auth flow, swagger): add to `security.permitted-urls`.
    Note the list is redefined **wholesale per profile** (base + `-dev` + `-staging` —
    Spring does not merge list properties), so keep all three in sync.
  - **Domain endpoints** (e.g. the public player reads): add an explicit,
    **method-scoped** matcher in `SecurityConfig` (`.requestMatchers(HttpMethod.GET,
    "…").permitAll()`) so the whole API's authorization posture stays reviewable in
    one place and any new method on the path falls back to `denyAll`.

### Error handling

The monorepo-wide rule (never silence an error; `ERROR` for 5xx, quiet for 4xx) lives in
the root `CLAUDE.md`. What is specific here: `GlobalExceptionHandler` maps

| Exception | Status | Case |
|---|---|---|
| `SecurityException` | 401 | JWT invalid/expired, bad credentials |
| `NoSuchElementException` | 404 | Resource does not exist |
| `IllegalArgumentException` | 400 | Invalid input, business rule violation |
| `IllegalStateException` | 502 | Downstream error or timeout |
| `MethodArgumentNotValidException` | 400 | Bean Validation failure |
| Spring MVC's own `ErrorResponse` 4xx | its own | Wrong method (405), body type (415) or `Accept` (406), missing part — not logged |
| `Exception` | 500 | Catch-all — logs the stack trace |

Whatever ends outside a controller (an exception in a filter, a `sendError` from Spring
Security's refusals) reaches `ErrorPageController`, which answers with the status the error
already has and the same `ErrorDto`. `SecurityConfig` permits the `ERROR` dispatch for it:
that dispatch carries no token, and judging it again turned each such error into a 401.

Set connect + read timeouts on every `RestClient`, use `onStatus()` so a non-2xx becomes an
exception, and let the `RestClientException` handler capture the upstream status.

### OpenAPI-first downstream clients

All BFF → downstream service communication must use **OpenAPI-generated** typed
clients, not hand-written `RestClient` calls.

Workflow for a new downstream service:
1. Ensure the downstream service has complete `@Operation`, `@ApiResponse`, and
   `@Schema` annotations on its controllers and DTOs.
2. Add a committed spec YAML to `specs/` and a new `openApiGenerate`-style task in
   `build.gradle` (see `generateYahooClient` for the pattern).
3. Define an `interface` in `client/` and implement it with the generated model
   classes; integration tests mock the interface with `@MockitoBean`.
4. Register a `RestClient` bean for it in `RestClientConfig` (base URL, timeouts, API key
   from `services.<name>.*`), and give `SPEC_READ_TOKEN` read access to the new repo — the
   drift check 404s rather than reporting drift if you forget.

Every downstream client is fully generated, each from its pinned spec in `specs/`:
`com.fantasy.bff.generated.db.model`, `.yahoo.model`, `.espn.model` and `.projection.model`.

The `specs/fantasy-*-openapi.*` files are **verbatim pinned copies** of each service's
committed spec: `specs/openapi.yaml` in the three Spring services, `specs/openapi.json` in
projection-service. To update after a downstream API change: copy the producer's file from its
`master` over the pinned copy, re-run the generate task, and fix any resulting compile errors.
Re-pin in a PR of its own, never inside unrelated work (see below).

### Own spec snapshot (`specs/bff-openapi.yaml`)

The BFF is itself a producer — `fantasy-web` generates its client from this spec.
`OpenApiSpecSnapshotTest` boots the app and asserts
`specs/bff-openapi.yaml` matches the live `/v3/api-docs.yaml`, so **any
controller/DTO change that isn't reflected in the spec fails the build**.

After an intentional API change, regenerate and commit:
```
./gradlew test -DupdateSpec=true   # rewrites specs/bff-openapi.yaml
git add specs/bff-openapi.yaml
```
`OpenApiConfig` pins the server URL to `/` so the spec is deterministic across runs.

## CI / workflow

- `.github/workflows/pr-checks.yml`: runs `./gradlew build --no-daemon` on PRs to `master`.
- A **spec drift check** runs first (`.github/scripts/check-pinned-spec.sh`): it fetches each
  downstream service's spec from its `master` and compares the pinned copy. It **fails only
  when this branch broke the pin** (edited it by hand, or re-pinned and the producer has merged
  since); a pin that was already behind on the base branch is a warning, since the PR did not
  cause it. The weekday `spec-freshness.yml` run fails on any stale pin. A 401 there is an
  expired token, not drift. This needs a repo secret
  `SPEC_READ_TOKEN` — a fine-grained PAT with read access to the contents of every service
  whose spec is pinned here (db, yahoo, espn, projection). Adding a downstream means adding
  it to that token too, or the check 404s rather than reporting drift.

## Monorepo conventions

The full set lives in the monorepo root `CLAUDE.md`: input validation at every boundary,
logging & error handling, secrets only from env, one worktree per agent, and the merge
procedure. In short — the web talks only to the BFF; inter-service calls carry a shared
`X-Internal-Api-Key` header. Branch → push → PR → checks pass → **squash merge** to `master`
(the PR title becomes the commit message; make it a proper `feat:`/`fix:` message and merge
with an explicit `--subject`). No attribution trailers. Secrets only from env, never
committed. Never merge a PR titled "wip"/"draft".

## Deployment

- Dockerized (multi-stage `Dockerfile`), deployed via **Coolify** (Hetzner) as a web service
  on prod (`api.slapstat.com`) and staging (`api.staging.slapstat.com`). **Production runs
  with no profile**; staging sets `SPRING_PROFILES_ACTIVE=staging` only to permit the API-doc
  URLs (see [Profiles & config](#profiles--config-srcmainresourcesapplicationyaml)).
- `DEPLOYMENT.md` lists every variable, with secret / required / default. Each downstream
  needs a `*_SERVICE_URL` + `*_INTERNAL_API_KEY` pair (`DATABASE_`/`DB_`, `YAHOO_`, `ESPN_`,
  `PROJECTION_`); the URLs use the services' Docker network aliases (`http://db-service:8086`,
  `http://yahoo-service:8088`, `http://espn-service:8090`, `http://projection-service:8092`),
  and each key is the value that service holds as its own `INTERNAL_API_KEY`.
- Health check: `/actuator/health`, which includes readiness (see the fail-fast note above).
