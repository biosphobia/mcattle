package dev.mcattle.core.llm;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Runs language-model calls off the server thread and enforces the request-rate and daily token budgets.
 * When a budget is exhausted the futures complete empty and callers fall back to the built-in behavior.
 */
public final class LlmService implements AutoCloseable {
    private final LlmProvider provider;
    private final int maxRequestsPerMinute;
    private final long maxTokensPerDay;
    private final Consumer<String> log;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "mcattle-llm");
        t.setDaemon(true);
        return t;
    });

    private final Deque<Long> recentRequests = new ArrayDeque<>();
    private LocalDate day = LocalDate.now();
    private long tokensToday;
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong totalInput = new AtomicLong();
    private final AtomicLong totalCached = new AtomicLong();
    private final AtomicLong totalOutput = new AtomicLong();

    public LlmService(LlmProvider provider, int maxRequestsPerMinute, long maxTokensPerDay, Consumer<String> log) {
        this.provider = provider;
        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.maxTokensPerDay = maxTokensPerDay;
        this.log = log;
    }

    public boolean available() {
        return provider != null;
    }

    /** Why a request would be rejected right now, or null if it would go through. */
    public synchronized String blockedReason() {
        if (provider == null) return "no language model configured";
        rollDay();
        long now = System.currentTimeMillis();
        while (!recentRequests.isEmpty() && now - recentRequests.peekFirst() > 60_000) recentRequests.pollFirst();
        if (recentRequests.size() >= maxRequestsPerMinute) return "request rate limit reached";
        if (maxTokensPerDay > 0 && tokensToday >= maxTokensPerDay) return "daily token budget used up";
        return null;
    }

    private synchronized boolean reserve() {
        if (blockedReason() != null) return false;
        recentRequests.addLast(System.currentTimeMillis());
        return true;
    }

    private void rollDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(day)) {
            day = today;
            tokensToday = 0;
        }
    }

    private synchronized void account(LlmResult<?> result) {
        rollDay();
        tokensToday += result.totalTokens();
        totalRequests.incrementAndGet();
        totalInput.addAndGet(result.inputTokens());
        totalCached.addAndGet(result.cachedTokens());
        totalOutput.addAndGet(result.outputTokens());
    }

    public CompletableFuture<Optional<Orders>> plan(String situation) {
        if (!reserve()) return CompletableFuture.completedFuture(Optional.empty());
        return CompletableFuture.supplyAsync(() -> {
            try {
                LlmResult<Orders> result = provider.plan(situation);
                account(result);
                if (result.value() == null) log.accept("Planner returned nothing: " + result.note());
                return Optional.ofNullable(result.value());
            } catch (Exception e) {
                log.accept("Planner call failed: " + e);
                return Optional.<Orders>empty();
            }
        }, executor);
    }

    public CompletableFuture<Optional<Design>> design(String context) {
        return submit(() -> provider.design(context), "Design");
    }

    public CompletableFuture<Optional<StyleLines>> restyle(String context) {
        return submit(() -> provider.restyle(context), "Restyle");
    }

    public CompletableFuture<Optional<ActivityTurn>> direct(String context) {
        return submit(() -> provider.direct(context), "Activity");
    }

    private interface Call<T> {
        LlmResult<T> run() throws Exception;
    }

    private <T> CompletableFuture<Optional<T>> submit(Call<T> call, String what) {
        if (!reserve()) return CompletableFuture.completedFuture(Optional.empty());
        return CompletableFuture.supplyAsync(() -> {
            try {
                LlmResult<T> result = call.run();
                account(result);
                if (result.value() == null) log.accept(what + " call returned nothing: " + result.note());
                return Optional.ofNullable(result.value());
            } catch (Exception e) {
                log.accept(what + " call failed: " + e);
                return Optional.<T>empty();
            }
        }, executor);
    }

    public CompletableFuture<Optional<Banter>> banter(String situation) {
        if (!reserve()) return CompletableFuture.completedFuture(Optional.empty());
        return CompletableFuture.supplyAsync(() -> {
            try {
                LlmResult<Banter> result = provider.banter(situation);
                account(result);
                return Optional.ofNullable(result.value());
            } catch (Exception e) {
                log.accept("Banter call failed: " + e);
                return Optional.<Banter>empty();
            }
        }, executor);
    }

    public synchronized String usageReport() {
        rollDay();
        return String.format("%s | requests: %d | input: %d | cached input: %d | output: %d | today: %d / %s tokens",
                provider == null ? "LLM disabled" : provider.describe(), totalRequests.get(), totalInput.get(),
                totalCached.get(), totalOutput.get(), tokensToday, maxTokensPerDay > 0 ? Long.toString(maxTokensPerDay) : "unlimited");
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
