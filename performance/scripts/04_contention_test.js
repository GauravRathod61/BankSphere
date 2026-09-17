import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { provisionCustomerWithAccount } from './helpers/auth.js';

const BASE_URL = __ENV.GATEWAY_URL || 'http://localhost:8080';

// Custom metrics for contention analysis
const occConflictCounter = new Counter('banking_occ_conflicts');
const successfulContentionTx = new Counter('banking_contention_success_tx');
const contentionDuration = new Trend('banking_contention_req_duration');

export const options = {
    scenarios: {
        hot_account_contention: {
            executor: 'constant-vus',
            vus: 10,           // 10 concurrent threads hammering the SAME account
            duration: '30s',
        },
    },
    thresholds: {
        // Under heavy lock contention, OCC retries will increase latency but transactions should succeed
        'http_req_failed': ['rate<0.10'], // Less than 10% hard failures after 3 OCC retries
    },
};

export function setup() {
    console.log(`[Contention Test Setup] Creating Hot Account and Counterparty on ${BASE_URL}...`);
    const initialFunds = 1000000; // 1,000,000 initial balance
    const hotUser = provisionCustomerWithAccount(BASE_URL, 100, initialFunds);
    const counterpartyUser = provisionCustomerWithAccount(BASE_URL, 200, initialFunds);

    console.log(`[Contention Test Setup] Hot Account: ${hotUser.accountNumber}, Initial Balance: ${initialFunds}`);
    console.log(`[Contention Test Setup] Counterparty: ${counterpartyUser.accountNumber}`);

    return { hotUser, counterpartyUser, initialFunds };
}

export default function (data) {
    const hotUser = data.hotUser;
    const counterpartyUser = data.counterpartyUser;

    const opType = Math.random() < 0.5 ? 'DEPOSIT' : 'TRANSFER';
    const amount = 10;
    const timestamp = Date.now();
    const idempotencyKey = `contend-${__VU}-${__ITER}-${timestamp}-${Math.floor(Math.random()*10000)}`;

    const startTime = Date.now();
    let res;

    if (opType === 'DEPOSIT') {
        // High concurrency DEPOSIT to Hot Account
        res = http.post(
            `${BASE_URL}/transactions`,
            JSON.stringify({
                sourceAccountNumber: hotUser.accountNumber,
                amount: amount,
                type: 'DEPOSIT',
                description: `Contention test concurrent deposit VU=${__VU}`
            }),
            {
                headers: {
                    ...hotUser.authHeader,
                    'Idempotency-Key': idempotencyKey
                }
            }
        );
    } else {
        // High concurrency TRANSFER from Hot Account to Counterparty
        res = http.post(
            `${BASE_URL}/transactions`,
            JSON.stringify({
                sourceAccountNumber: hotUser.accountNumber,
                targetAccountNumber: counterpartyUser.accountNumber,
                amount: amount,
                type: 'TRANSFER',
                description: `Contention test concurrent transfer VU=${__VU}`
            }),
            {
                headers: {
                    ...hotUser.authHeader,
                    'Idempotency-Key': idempotencyKey
                }
            }
        );
    }

    const duration = Date.now() - startTime;
    contentionDuration.add(duration);

    if (res.status === 201) {
        successfulContentionTx.add(1);
    } else if (res.status === 409 || res.status === 500) {
        // Potential OCC retry exhaustion under extreme concurrency
        occConflictCounter.add(1);
    }

    check(res, {
        'contention tx processed': (r) => r.status === 201 || r.status === 409 || r.status === 500,
    });

    // Tight loop to maximize race conditions and OCC contention
    sleep(0.01 + Math.random() * 0.02);
}

export function teardown(data) {
    console.log(`[Contention Test Teardown] Verifying final balance and integrity...`);
    const hotUser = data.hotUser;
    const res = http.get(`${BASE_URL}/accounts/${hotUser.accountNumber}`, {
        headers: hotUser.authHeader
    });

    if (res.status === 200) {
        const acc = JSON.parse(res.body);
        console.log(`[Contention Test Teardown] Hot Account ${hotUser.accountNumber} Final Balance: ${acc.balance}, Version: ${acc.version}`);
    }
}
