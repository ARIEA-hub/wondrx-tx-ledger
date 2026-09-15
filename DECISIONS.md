# Decision Log

## 1. How did you handle the concurrency race condition?

There are actually two different concurrency problems in this brief, and I used two
different mechanisms on purpose because a single lock can't solve both cleanly.

**Duplicate `transactionId` (idempotency).** I didn't reach for an application-level
lock (e.g. a `synchronized` block or a distributed lock) at all. Instead,
`ProcessedTransaction.transactionId` is the primary key of its own table, and the very
first thing `process()` does is try to insert a row for that id
(`IdempotencyService.claim()`, in its own `REQUIRES_NEW` transaction, flushed
immediately with `saveAndFlush`). If two or three requests for the same
`transactionId` arrive at once, the database's own primary-key constraint guarantees
only one of those inserts can succeed - the others get a constraint violation, which I
translate into a `409 Conflict`. This is stronger than an app-level lock: it's correct
even across multiple app instances, because the guarantee lives in the database, not
in this process's memory.

**Negative balance under concurrent debits (the race condition test).** Here every
request has a *different* `transactionId`, so the unique-constraint trick above
doesn't apply - there's nothing to deduplicate. Instead, `WalletService.debit()` takes
a pessimistic row lock on the wallet (`SELECT ... FOR UPDATE`, via
`@Lock(LockModeType.PESSIMISTIC_WRITE)` on `WalletRepository.findByIdForUpdate`) for
the whole read-check-write sequence. Whichever request gets the lock first reads the
balance, checks it, writes the new balance, and only then releases the lock - so a
second concurrent request against the same wallet genuinely waits, reads the *updated*
balance, and correctly sees insufficient funds once the wallet is drained. That's what
makes the 10-concurrent-requests-against-a-500-balance test come out to exactly 5
successes / 5 rejections / a final balance of exactly 0, instead of a racy, sometimes-negative
result.

Both of those only work because the transaction boundary is real - which is also the
main thing that went wrong along the way (see below).

## 2. Where did your AI assistant give you an incorrect or sub-optimal suggestion?

Working through this with Claude, three real bugs showed up in the first draft. I'm
listing all three because they're the same underlying lesson twice, plus one classic
JPA trap, and I think that's a more honest answer than picking one:

**Bug 1 - Spring self-invocation silently drops `@Transactional` (idempotency claim).**
The first draft had `claimTransactionId()` and `markStatus()` as `@Transactional(REQUIRES_NEW)`
*private/protected methods on `TransactionService` itself*, called as
`this.claimTransactionId(...)` from `process()`. That compiles fine and looks correct,
but Spring's `@Transactional` is implemented as a proxy around the bean - a call from
*inside* the same class never goes through that proxy, so `REQUIRES_NEW` (and the
whole transaction) is silently ignored. The bug wouldn't show up in a quick manual
check; it would show up as flaky idempotency under real concurrent load. Fix: moved
the idempotency-claim logic into its own bean, `IdempotencyService`, so
`TransactionService` calls it through the real Spring proxy.

**Bug 2 - the exact same trap, again, for the wallet lock.** Immediately after fixing
Bug 1, the draft still had `debit()` as an `@Transactional` method on
`TransactionService`, called as `this.debit(...)` from `process()` - the identical
mistake in a second place. This one is worse than Bug 1: without a real transaction
wrapping the whole method, Spring Data would open-and-close its own short transaction
*just for the `findByIdForUpdate` query*, so the pessimistic lock would be released
the instant that query returned - before the balance check and the write. Two
concurrent debits could then both read the same starting balance and both succeed,
which is precisely the negative-balance bug this lock exists to prevent. Fix: same
pattern - extracted the debit logic into its own bean, `WalletService`.

**Bug 3 - `save()` silently choosing `merge()` over `persist()` for a manually-assigned ID.**
`ProcessedTransaction` uses `transactionId` as a manually-assigned `@Id` (no
`@GeneratedValue`, no `@Version`). Spring Data JPA's default `save()` decides whether
to call `entityManager.persist()` (a real INSERT) or `entityManager.merge()`
(SELECT-then-upsert) based on whether the `@Id` looks "new" - and by default that just
means "is the id null?" Since `transactionId` is always set before saving, it never
looks null, so `save()` was routing through `merge()` even for a genuinely new row.
That's not just inefficient - it's actively wrong here: `merge()` on a `transactionId`
that already exists just updates that row instead of throwing a constraint violation,
which would have silently defeated the whole idempotency check for the one case it's
supposed to catch (two requests with the same id "succeeding" by overwriting each
other instead of the second one being rejected). Fix: `ProcessedTransaction` now
implements Spring Data's `Persistable<UUID>` with an explicit transient `isNew` flag
that starts `true` and flips to `false` on `@PostPersist`/`@PostLoad`, so `save()`
correctly calls `persist()` only for genuinely new rows.

**Honest limitation on verification.** I wrote this in a sandboxed environment whose
network policy blocks Maven Central (`repo.maven.apache.org` - confirmed via a 403
from the egress proxy, both from the build sandbox and from a shell on my own
machine), so I was not able to run `mvn test` myself to get an actual green build
before submitting. Everything above is the result of a careful manual line-by-line
review rather than a real test run. I'd treat that as it deserves - please run
`mvn clean test` in IntelliJ as the very first step; if anything doesn't compile or a
test doesn't pass, that's on me to fix, not something to guess around.
