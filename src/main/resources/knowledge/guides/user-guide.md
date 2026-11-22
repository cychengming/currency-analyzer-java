# Currency Analyzer User Guide

Currency Market Analyzer tracks live exchange rates, historical price series, rule-based alerts, strategy backtests, risk-based trade sizing, deep-learning forecasts, and a knowledge-grounded AI assistant.

## Overview Dashboard

The Overview tab shows live quotes for tracked currency and commodity pairs (such as `EUR/USD`, `GBP/USD`, `GOLD/USD`), recent triggered alerts, and an interactive historical price chart. Select any pair card to update the chart, and switch the timeframe unit between days, weeks, and months.

## Alerts & Monitoring

- **Manage Alerts**: Configure per-pair alert rules including trend percentage thresholds, price highs/lows, volatility triggers, and moving-average crossover signals.
- **Start / Stop Monitoring**: Use the top header controls to enable or pause background monitoring checks.
- **Alert History**: Review every recorded alert with trigger rate, previous rate, percent change, and timestamp.
- **Settings**: Set global defaults for trend threshold, detection period, check interval, email delivery, and sound alerts.

## Backtest

Use the Backtest tab to test entry and exit rules on daily historical bars. Choose a currency pair, lookback window (2–5 years is a solid starting point), initial capital, stop-loss percentage, take-profit percentage, and maximum holding days. Inspect the summary metrics and click individual trades to view the trade detail chart.

## Trade & Risk Sizing

The Trade tab computes `ATR(14)` and 252-day daily volatility (`sigma`) to help size positions based on account equity, risk per trade percentage, and ATR stop multiples. You can also enforce a daily risk limit and log trade theses, stop levels, and post-trade reflections in the Trade Diary.

## Forecast & Deep Learning

- **Deep Learning**: Trigger historical data ingestion and GRU or LSTM model training with configurable lookback and forecast horizons.
- **Forecast**: Review the latest stored model forecast path for an asset. Stored forecasts are model projections for educational analysis, not live quotes or guaranteed future prices.

## Knowledge & Document Upload (RAG)

Open the **Knowledge** tab to manage documents indexed for the AI assistant:

- **Upload Documents**: Upload `.md` (Markdown), `.txt` (plain text), or `.csv` files (up to 5 MB by default). Uploaded files are stored in Google Cloud Storage and automatically parsed, chunked, embedded, and indexed in PostgreSQL (`pgvector`).
- **Sync from GCS**: Import `.md`, `.txt`, and `.csv` files that were uploaded directly into your configured GCS bucket prefix. Unchanged objects are skipped automatically.
- **Document Lifecycle**: Track status (`PENDING`, `INDEXING`, `READY`, `FAILED`), download originals, trigger re-indexing, or delete uploaded documents (built-in guides are read-only).
- **Test Retrieval**: Enter a question in the retrieval test box to preview which document chunks and similarity scores match before asking the assistant.

## Ask Currency Analyzer (AI Assistant)

Click **Ask Currency Analyzer** in the header to open the streaming chat drawer. The assistant can read live rates, historical series, pair comparisons, alert history, alert preferences, settings (with secrets omitted), latest forecasts, and indexed knowledge chunks (built-in guides and uploaded documents). It provides educational explanations only and does not offer personalized investment advice or execute trades.
