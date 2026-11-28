package com.vajra.coreservice.position;

import com.vajra.coreservice.connector.Quote;
import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.instrument.InstrumentRepository;
import com.vajra.coreservice.marketdata.QuoteCache;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/** Positions with unrealized PnL from the quote cache, and PnL totals. Paper positions use testnet instruments. */
@Service
public class PositionService {

    private final PositionRepository positions;
    private final InstrumentRepository instruments;
    private final QuoteCache quotes;

    public PositionService(PositionRepository positions, InstrumentRepository instruments, QuoteCache quotes) {
        this.positions = positions;
        this.instruments = instruments;
        this.quotes = quotes;
    }

    public List<PositionView> views() {
        return positions.findAll().stream().map(this::view).toList();
    }

    public PnlSummary pnl() {
        List<PositionView> views = views();
        BigDecimal realized = sum(views.stream().map(PositionView::realizedPnl));
        BigDecimal fees = sum(views.stream().map(PositionView::feesPaid));
        boolean markMissing = views.stream().anyMatch(v -> v.size() != 0 && v.unrealizedPnl() == null);
        if (markMissing) {
            return new PnlSummary(realized, fees, null, null);
        }
        BigDecimal unrealized = sum(views.stream().map(PositionView::unrealizedPnl));
        return new PnlSummary(realized, fees, unrealized, realized.subtract(fees).add(unrealized));
    }

    private PositionView view(Position p) {
        Optional<BigDecimal> mark = markPrice(p);
        BigDecimal unrealized = p.size() == 0
                ? BigDecimal.ZERO
                : mark.flatMap(m -> contractValue(p).map(cv -> PositionMath.unrealizedPnl(p, m, cv))).orElse(null);
        return new PositionView(p.venue(), p.symbol(), p.size(), p.avgEntryPrice(), p.realizedPnl(), p.feesPaid(),
                mark.orElse(null), unrealized);
    }

    private Optional<BigDecimal> markPrice(Position p) {
        return quotes.fresh(p.symbol(), QuoteCache.MAX_AGE).map(Quote::markPrice);
    }

    private Optional<BigDecimal> contractValue(Position p) {
        return instruments.find(Venue.DELTA_TESTNET, p.symbol()).map(Instrument::contractValue);
    }

    private static BigDecimal sum(Stream<BigDecimal> values) {
        return values.reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
