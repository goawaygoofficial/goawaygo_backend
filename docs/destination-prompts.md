# Discover (4.1): destination pages, photos and ideas

How the backend builds a destination page, the home banner and "Ideas for your next trip", and why each choice was
made. The prompts themselves are in `src/main/resources/prompts/` (edit them there; they are read at start-up).

## Endpoints

| Endpoint                                            | Who                  | What                                                                                        |
| --------------------------------------------------- | -------------------- | ------------------------------------------------------------------------------------------- |
| `GET /api/discover/places?q=`                       | public               | Up to 6 real places while the user types (Geoapify)                                         |
| `GET /api/discover/destination?placeId=` (or `?q=`) | public, JWT optional | The whole page: place, 9 cards, spot photos. Signed in: added to the user's recent searches |
| `GET /api/discover/recent`                          | signed in            | The user's last 5 searched places, each with a live photo (home banner)                     |
| `POST /api/discover/photos`                         | public               | The same for a signed-out user, whose last 5 searches stay on their device                  |
| `GET /api/discover/ideas?count=6`                   | public               | Random ideas for the next trip; the app asks again every minute                             |
| `GET/POST/DELETE /api/me/saved-places`              | signed in            | My Trips (up to 500 saved places)                                                           |

The destination page is returned only when everything is ready, so the website and app show their travel animation
until then and then the whole page at once (usually 10-20 s on the free AI tier; Cloud Run's timeout is 120 s).

## 1. The 9 info cards (AI)

**One request per page open** asks for all 9 cards; the answer is JSON with **one separate field per card**
(Gemini's `responseSchema`), so each card is checked and shown on its own. Nothing is stored: every page open asks
again.

| Card id       | Title                | Subtitle                                                               |
| ------------- | -------------------- | ---------------------------------------------------------------------- |
| `quickFacts`  | Quick Facts          | Country, languages, currency, time zone and what it's known for        |
| `visa`        | Visa & Entry Rules   | Visa systems, passport validity and entry requirements                 |
| `seasons`     | Best Time to Visit   | Peak, off-peak and shoulder months, weather and big events             |
| `attractions` | Top Attractions      | Famous landmarks and experiences worth your time                       |
| `hotels`      | Best Areas to Stay   | Neighbourhoods to book in, and who each one suits                      |
| `cuisine`     | Food & Dining        | Dishes to try and local dining customs                                 |
| `transport`   | Getting Around       | From the airport and the main ways to travel locally                   |
| `insurance`   | Travel Insurance     | Whether it's required, medical care and local risks                    |
| `currency`    | Money & Connectivity | Currency, cards or cash, tipping, SIM/eSIM, plugs and emergency number |

Prompts: `destination-cards-system.txt` (accuracy rules, the same every time) and `destination-cards-request.txt`
(the destination block, the only part that changes, plus each card's instructions).

### Models (tested 2026-10-26, Gemini API free tier, key from a no-billing project so it can never be charged)

| Order | Model                                    | Free limits           | Test result (3 destinations)          |
| ----- | ---------------------------------------- | --------------------- | ------------------------------------- |
| 1st   | `gemini-3-flash-preview` (thinking: low) | 5/min, 20/day         | 3/3 correct, 10-14 s, richest answers |
| 2nd   | `gemini-3.1-flash-lite`                  | 15/min, about 500/day | 3/3 correct, 7-13 s                   |
| 3rd   | `gemini-3.5-flash-lite`                  | 15/min, 500/day       | correct but slow, 20-53 s             |

A model that is at its limit, overloaded or failing is skipped and the next one asked, all within 100 s
(`goawaygo.discover.ai-deadline`). If none can answer, the page still opens with its photos and says the cards are
busy. Each model is its own provider (`gemini`, `geminilite`, `geminilite35`) with a daily limit just below Google's,
all using the one Gemini key. Not used: Gemini 3.6/3.8 Flash (overloaded, timeouts on free tier), Gemma 4
(unreliable JSON), 2.5 models (retired). At launch: paid Gemini by changing settings only.

Gemini 3 models think before answering; thinking counts against the output limit, so the request allows 16,000
output tokens and asks for low thinking, otherwise the answer is cut off.

### Why repeated searches don't drift into made-up answers

- Every request is **independent**: no chat history and no previous answers, so one search can't influence the next.
- **Temperature 0.1** and a **fixed seed**: the most likely (most factual) wording, the same each time.
- **Structured output**: the model must answer with exactly the 9 card fields.
- **"Leave it out if unsure"** rules in the prompt, and backend checks on every answer (below).

### What the backend checks (each card separately, `DestinationCards.check`)

- `unknownDestination: true` → the page says the place isn't known (no cards, no photos).
- At most 7 items; labels at most 6 words, details at most 35 (slack over the prompt's 4 and 25); markdown removed.
- **Links**: only `https://` addresses in the `link` field, each opened by the backend and shown only if it answers
  within 3 s; web addresses inside the text are removed.
- **Changing details** (prices, currency amounts, rates, times, years, percentages) are tagged `mayChange` even if
  the AI forgot. The apps show "may vary, check before you go" on those items.
- A card with nothing valid shows "Not available right now"; the other cards still show.

## 2. Spot photos (banner of the destination page)

Fetched live on every request and never stored. Up to 8 spots:

1. The AI's **Top Attractions** names (up to 6) → each spot's own **Wikipedia** article photo, accepted only if the
   article has the same name (`NameMatch`: "Musée d'Orsay station" is not "Musée d'Orsay") and lies near the place
   (60 km for a city, 400 km for a region, 2,500 km for a country). Unsplash if Wikipedia has none.
2. At the same time as the AI request, **Wikidata** lists well-known spots near the place (tourist attractions,
   monuments, temples, beaches, ...; not for countries). Up to 4 not already named by the AI → **Unsplash**, accepted
   only if the photo's own description mentions the spot; Wikipedia if Unsplash has none.
3. No spot found (a village): a photo of the place itself.

Every photo carries its credit as both sources require: Wikimedia Commons photographer and licence
("Photo: Jane Doe, CC BY-SA 4.0, Wikimedia Commons", linked to the file page), or "Photo by X on Unsplash" (linked to
the photographer with Unsplash's referral parameters). Unsplash is a demo app (50 requests an hour) until it is
approved for production.

## 3. Home banner (recent searches)

Only the user's own last 5 searched places, newest first. Signed in: kept in `search_history` (place and its top
spot, deleted with the account). Signed out: kept on the device and sent to `POST /photos`. Photos are looked up live
each time. No searches yet: the apps show "No recent searches".

## 4. Ideas for your next trip

Once an hour the AI (`gemini-3.1-flash-lite`, then `gemini-3.5-flash-lite`; prompts `ideas-system.txt`,
`ideas-request.txt`) suggests 30 varied real places from every continent: beaches, mountains, heritage, cities,
popular places and hidden gems, avoiding the previous hour's names. Each place is kept only if Wikipedia confirms it
(same name, a location and a photo). The list lives in memory only; each request gets a random 6, and the apps
refresh them every minute and offer **Save to My Trips**. If the AI can't answer, the previous list keeps being used
(right after a restart there are no ideas until the AI answers).

## Privacy

The AI prompt contains only the place (name, type, region, country, coordinates), never anything about the user.
Free-tier Gemini requests may be used by Google to improve its products, acceptable for place names. The Privacy
Policy lists: search history kept for signed-in users (last 5); place names sent to Google Gemini; place search by
Geoapify; photos from Wikimedia and Unsplash (the user's browser loads the images from them).

## Rate limits per IP

Search 60 a minute, destination pages 10 per 10 minutes (each costs an AI request), photos 20 a minute, ideas 30 a
minute (`goawaygo.discover.limits.*`).
