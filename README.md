# tx-ledger

Idempotent payment/wallet event processor - WonDRx Java Backend Intern assignment.

## Stack

Java 17, Spring Boot 3.3, Spring Data JPA, H2 (in-memory, zero config).

## Running

```bash
mvn spring-boot:run
```

The app starts on a random port (see `server.port=0` in `application.properties`) and
exposes:

```
POST /api/v1/transactions/process
Content-Type: application/json

{
  "transactionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "userId": "3fa85f64-5717-4562-b3fc-2c963f66afa7",
  "amount": 250.00,
  "type": "DEBIT"
}
```

Responses:

- `200 OK` - debited successfully, body includes the new balance.
- `409 Conflict` - this `transactionId` was already processed (or is being processed
  right now by a concurrent request).
- `422 Unprocessable Entity` - insufficient funds.
- `404 Not Found` - no wallet exists for that `userId`.

There's no seed/signup endpoint in scope for this assignment - the test suite seeds a
wallet directly via `WalletRepository` before exercising the endpoint, which is also
the fastest way to try it yourself from a scratch file / IntelliJ scratch buffer.

## Running the tests

Everything runs against an in-memory H2 database, so no external setup is needed -
just run the test class in IntelliJ (or `mvn test`):

- `TransactionProcessingTests.processesSingleValidDebitTransactionSuccessfully`
- `TransactionProcessingTests.sendsThreeIdenticalTransactionIdsSimultaneously_balanceDeductedOnce`
- `TransactionProcessingTests.sendsTenConcurrentDebitsAgainstFiveHundredBalance_exactlyFiveSucceedFiveFail`

Each test prints its intent and result to the console (see `System.out.println` calls
in the test body) in addition to the JUnit `@DisplayName`.

**Note on verification:** this was built in a network-sandboxed environment that
blocks Maven Central, so I could not run `mvn test` myself before pushing - see
`DECISIONS.md` for the full explanation. Please run the suite as the first step.

## Design notes

See `DECISIONS.md` for how the two concurrency problems (idempotent duplicate
detection vs. the negative-balance race condition) are handled, and for the real bugs
that showed up - and got fixed - while building this with AI assistance.
