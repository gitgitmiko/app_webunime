# WEBUNIME scrape proxy

Vercel serverless endpoint that triggers `scraper_webunime` → workflow **Sync WEBUNIME Catalog**.

## Deploy

```bash
cd proxy/scrape-trigger
vercel --prod
vercel env add GITHUB_TOKEN production   # PAT with actions:write on scraper_webunime
vercel env add APP_KEY production        # shared key; must match app BuildConfig
```

## App

- `POST https://<deployment>/api/scrape`
- Header `X-Webunime-Key: <APP_KEY>`
