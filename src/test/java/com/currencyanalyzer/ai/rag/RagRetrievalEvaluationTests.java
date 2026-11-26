package com.currencyanalyzer.ai.rag;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden retrieval evaluation set (30 bilingual EN/ZH queries) measuring Recall@5 and MRR
 * so future retrieval and embedding model upgrades can be benchmarked objectively in CI.
 */
class RagRetrievalEvaluationTests {

    private static final double MIN_RECALL_AT_5 = 0.90;
    private static final double MIN_MRR = 0.80;

    @Test
    void meetsRecallAt5AndMrrTargetsOnBilingualGoldenEvaluationSet() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
            "jdbc:h2:mem:rag_eval_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;DB_CLOSE_DELAY=-1",
            "sa",
            "",
            true
        );
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        try {
            RagDocumentRepository repository = new RagDocumentRepository(jdbcTemplate);
            TextDocumentParser parser = new TextDocumentParser(155, 28, 500);

            seedDocument(repository, parser, "kelly-criterion.md", "凯利公式", """
                # 凯利公式
                凯利公式（Kelly Criterion）用于在已知胜率与赔率的情况下计算最优仓位比例 f* = (bp - q) / b。
                实践中通常采用半凯利（Half Kelly）或四分之一凯利来降低净值波动与估计误差风险。
                过度杠杆会显著增加长期复利回撤，甚至导致破产风险。
                """);

            seedDocument(repository, parser, "position-sizing.md", "仓位管理", """
                # 仓位管理
                单笔交易风险上限应控制在账户总资金的 1% 以内。
                结合 ATR（平均真实波幅）动态设置止损距离，并在相关性高的货币对（如 EUR/USD 与 GBP/USD）之间限制总敞口。
                Position sizing prevents a sequence of normal losing trades from causing irreversible drawdown.
                """);

            seedDocument(repository, parser, "howard-marks.md", "霍华德·马克斯", """
                # 霍华德·马克斯
                ## 基本信息
                Howard Marks 是 Oaktree Capital（橡树资本）联合创始人，自 1990 年起撰写投资备忘录。
                ## 第二层次思维
                第二层次思维（Second-level thinking）要求超越市场共识，判断资产价格是否已经充分反映乐观或悲观预期。
                ## 周期与钟摆理论
                市场情绪像钟摆一样在极度贪婪与极度恐惧之间摆动，很少停留在中点。控制下行风险比追逐牛市收益更重要。
                """);

            seedDocument(repository, parser, "stanley-druckenmiller.md", "斯坦利·德鲁肯米勒", """
                # 斯坦利·德鲁肯米勒
                ## 宏观流动性框架
                Stanley Druckenmiller 认为央行流动性变化与盈利预期拐点是驱动外汇与股票趋势的核心力量，而非静态估值。
                ## 集中持仓与止损纪律
                当高确定性宏观机会出现时敢于重仓（pig betting），但如果市场走势证伪逻辑，必须迅速止损离场，绝不摊平亏损。
                """);

            seedDocument(repository, parser, "george-soros.md", "乔治·索罗斯", """
                # 乔治·索罗斯
                ## 反身性理论
                George Soros 提出反身性理论（Reflexivity），指出市场参与者的认知偏差会影响基本面，而基本面变化又反过来强化预期，形成正反馈泡沫或崩盘。
                ## 1992 年英镑危机
                1992 年黑色星期三，量子基金做空英镑兑德国马克，利用欧洲汇率机制（ERM）的政策不可持续性获得巨额盈利。
                """);

            seedDocument(repository, parser, "user-guide.md", "Currency Analyzer User Guide", """
                # Currency Analyzer User Guide
                ## Live Rates and Historical Charts
                The dashboard tracks live FX pairs such as USD/CNY, EUR/USD, and USD/JPY with 60-second caching and up to one year of daily history.
                ## Threshold Alerts
                Users can configure threshold alerts to trigger notifications when a currency pair crosses a target buy or sell level.
                ## Forecast Model
                The forecast tab displays statistical time-series projections with confidence intervals. Forecasts are educational models and not investment advice.
                ## Knowledge Base Upload
                Administrators can upload Markdown (.md), plain text (.txt), or CSV (.csv) files to Google Cloud Storage and sync them into pgvector.
                """);

            seedDocument(repository, parser, "fx-carry-trade.md", "FX Carry Trade", """
                # FX Carry Trade
                A currency carry trade borrows in a low-interest-rate funding currency such as JPY or CHF and invests in a higher-yielding currency such as USD or MXN.
                The primary risk is sudden risk-off deleveraging and carry unwind during volatility spikes, which can wipe out months of interest rate differential.
                """);

            seedDocument(repository, parser, "changelog.md", "更新日志", """
                # 更新日志
                2026-05-16 新增 howard-marks、kelly-criterion 与 user-guide 归档条目。
                """);

            RagChunkRetriever retriever = new RagChunkRetriever(repository, (org.springframework.ai.embedding.EmbeddingModel) null, 0.45);

            List<GoldenCase> cases = List.of(
                new GoldenCase("凯利公式如何计算最优仓位", "kelly-criterion.md"),
                new GoldenCase("Kelly Criterion optimal fraction formula", "kelly-criterion.md"),
                new GoldenCase("什么是半凯利 Half Kelly", "kelly-criterion.md"),
                new GoldenCase("过度杠杆的破产风险", "kelly-criterion.md"),
                new GoldenCase("单笔交易风险上限百分之一", "position-sizing.md"),
                new GoldenCase("ATR 动态止损距离怎么设置", "position-sizing.md"),
                new GoldenCase("position sizing irreversible drawdown", "position-sizing.md"),
                new GoldenCase("高相关性货币对总敞口限制", "position-sizing.md"),
                new GoldenCase("Howard Marks", "howard-marks.md"),
                new GoldenCase("霍华德·马克斯 第二层次思维", "howard-marks.md"),
                new GoldenCase("Oaktree Capital investment memos", "howard-marks.md"),
                new GoldenCase("市场情绪钟摆理论 贪婪与恐惧", "howard-marks.md"),
                new GoldenCase("second-level thinking market consensus", "howard-marks.md"),
                new GoldenCase("Stanley Druckenmiller macro liquidity", "stanley-druckenmiller.md"),
                new GoldenCase("德鲁肯米勒 央行流动性与盈利拐点", "stanley-druckenmiller.md"),
                new GoldenCase("pig betting 集中持仓与止损纪律", "stanley-druckenmiller.md"),
                new GoldenCase("George Soros Reflexivity theory", "george-soros.md"),
                new GoldenCase("索罗斯 反身性理论 正反馈泡沫", "george-soros.md"),
                new GoldenCase("1992 黑色星期三 做空英镑 ERM", "george-soros.md"),
                new GoldenCase("量子基金 英镑兑德国马克", "george-soros.md"),
                new GoldenCase("how to configure threshold alerts", "user-guide.md"),
                new GoldenCase("live FX pairs 60-second caching", "user-guide.md"),
                new GoldenCase("statistical time-series forecast confidence intervals", "user-guide.md"),
                new GoldenCase("upload Markdown txt CSV Google Cloud Storage pgvector", "user-guide.md"),
                new GoldenCase("Currency Analyzer User Guide", "user-guide.md"),
                new GoldenCase("FX carry trade funding currency JPY CHF", "fx-carry-trade.md"),
                new GoldenCase("carry unwind risk-off deleveraging volatility spikes", "fx-carry-trade.md"),
                new GoldenCase("interest rate differential borrowing low yielding currency", "fx-carry-trade.md"),
                new GoldenCase("EUR/USD GBP/USD 相关性 敞口", "position-sizing.md"),
                new GoldenCase("橡树资本 投资备忘录 周期控制风险", "howard-marks.md")
            );

            int recallHits = 0;
            double reciprocalRankSum = 0.0;

            for (GoldenCase golden : cases) {
                List<RagChunk.RagSearchHit> hits = retriever.search(golden.query(), 5);
                int firstRank = 0;
                for (int index = 0; index < hits.size(); index++) {
                    if (golden.expectedFilename().equals(hits.get(index).filename())) {
                        firstRank = index + 1;
                        break;
                    }
                }
                if (firstRank > 0) {
                    recallHits++;
                    reciprocalRankSum += 1.0 / firstRank;
                }
            }

            double recallAt5 = (double) recallHits / cases.size();
            double mrr = reciprocalRankSum / cases.size();

            assertTrue(
                recallAt5 >= MIN_RECALL_AT_5,
                () -> String.format("Recall@5 %.3f fell below threshold %.2f", recallAt5, MIN_RECALL_AT_5)
            );
            assertTrue(
                mrr >= MIN_MRR,
                () -> String.format("MRR %.3f fell below threshold %.2f", mrr, MIN_MRR)
            );
        } finally {
            dataSource.destroy();
        }
    }

    private static void seedDocument(
        RagDocumentRepository repository,
        TextDocumentParser parser,
        String filename,
        String title,
        String content
    ) {
        UUID docId = UUID.randomUUID();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String sha = RagDocumentService.sha256Hex(bytes);
        Instant now = Instant.parse("2026-11-23T00:00:00Z");
        DocumentParser.ParsedDocument parsed = parser.parse(filename, bytes, title);
        repository.insert(new RagDocument(
            docId,
            RagDocument.SOURCE_UPLOAD,
            filename,
            parsed.title(),
            "text/markdown",
            bytes.length,
            sha,
            "local",
            "eval",
            "knowledge/" + filename,
            "1",
            RagDocument.STATUS_PENDING,
            0,
            null,
            "eval",
            now,
            now
        ));
        List<RagDocumentRepository.ChunkRecord> records = parsed.chunks().stream()
            .map(chunk -> new RagDocumentRepository.ChunkRecord(chunk.index(), chunk.heading(), chunk.content(), null))
            .toList();
        repository.replaceChunks(docId, sha, parsed.title(), records, now);
    }

    private record GoldenCase(String query, String expectedFilename) {
    }
}
