import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { provisionCustomerWithAccount } from './helpers/auth.js';

const BASE_URL = __ENV.GATEWAY_URL || 'http://localhost:8080';

// Custom metrics
const successfulTxCounter = new Counter('banking_successful_tx');
const failedTxCounter = new Counter('banking_failed_tx');
const transferDuration = new Trend('banking_transfer_duration');
const depositDuration = new Trend('banking_deposit_duration');

export const options = {
    stages: [
        { duration: '20s', target: 10 },  // Ramp up to 10 VUs
        { duration: '40s', target: 10 },  // Sustain 10 VUs
        { duration: '20s', target: 20 },  // Ramp up to 20 VUs
        { duration: '40s', target: 20 },  // Sustain 20 VUs peak
        { duration: '15s', target: 0 },   // Ramp down
    ],
    thresholds: {
        http_req_duration: ['p(95)<3000'],
        http_req_failed: ['rate<0.05'],
        'banking_failed_tx': ['count<50'],
    },
};

export function setup() {
    console.log(`[Load Test Setup] Provisioning distributed account pool (20 accounts) on ${BASE_URL}...`);
    const accounts = [];
    const POOL_SIZE = 20;

    for (let i = 1; i <= POOL_SIZE; i++) {
        try {
            const user = provisionCustomerWithAccount(BASE_URL, i, 100000);
            accounts.push(user);
        } catch (e) {
            console.error(`Error provisioning user ${i}: ${e.message}`);
        }
    }

    console.log(`[Load Test Setup] Successfully provisioned ${accounts.length} accounts.`);
    return { accounts };
}

export default function (data) {
    const accounts = data.accounts;
    if (!accounts || accounts.length < 2) {
        console.error('Insufficient accounts in pool!');
        return;
    }

    // Select source account distributed across pool based on VU & random index
    const srcIndex = (__VU + Math.floor(Math.random() * accounts.length)) % accounts.length;
    const sourceUser = accounts[srcIndex];

    // Select distinct target account
    let targetIndex = Math.floor(Math.random() * accounts.length);
    if (targetIndex === srcIndex) {
        targetIndex = (srcIndex + 1) % accounts.length;
    }
    const targetUser = accounts[targetIndex];

    const rand = Math.random();

    if (rand < 0.35) {
        // --- 35% TRANSFER (Cross-service Saga: Account Debit + Account Credit) ---
        const idempotencyKey = `load-txf-${__VU}-${__ITER}-${Date.now()}`;
        const startTime = Date.now();

        const res = http.post(
            `${BASE_URL}/transactions`,
            JSON.stringify({
                sourceAccountNumber: sourceUser.accountNumber,
                targetAccountNumber: targetUser.accountNumber,
                amount: 10,
                type: 'TRANSFER',
                description: 'Load test distributed transfer'
            }),
            {
                headers: {
                    ...sourceUser.authHeader,
                    'Idempotency-Key': idempotencyKey
                }
            }
        );

        transferDuration.add(Date.now() - startTime);

        const success = check(res, {
            'transfer HTTP 201': (r) => r.status === 201,
            'transfer status SUCCESS': (r) => {
                try { return JSON.parse(r.body).status === 'SUCCESS'; } catch (e) { return false; }
            }
        });

        if (success) {
            successfulTxCounter.add(1);
        } else {
            failedTxCounter.add(1);
        }

    } else if (rand < 0.65) {
        // --- 30% DEPOSIT (Single-account OCC & Idempotency check) ---
        const idempotencyKey = `load-dep-${__VU}-${__ITER}-${Date.now()}`;
        const startTime = Date.now();

        const res = http.post(
            `${BASE_URL}/transactions`,
            JSON.stringify({
                sourceAccountNumber: sourceUser.accountNumber,
                amount: 50,
                type: 'DEPOSIT',
                description: 'Load test deposit'
            }),
            {
                headers: {
                    ...sourceUser.authHeader,
                    'Idempotency-Key': idempotencyKey
                }
            }
        );

        depositDuration.add(Date.now() - startTime);

        const success = check(res, {
            'deposit HTTP 201': (r) => r.status === 201,
            'deposit status SUCCESS': (r) => {
                try { return JSON.parse(r.body).status === 'SUCCESS'; } catch (e) { return false; }
            }
        });

        if (success) {
            successfulTxCounter.add(1);
        } else {
            failedTxCounter.add(1);
        }

    } else if (rand < 0.85) {
        // --- 20% Mini Statement Query ---
        const res = http.get(
            `${BASE_URL}/transactions/mini-statement/${sourceUser.accountNumber}`,
            { headers: sourceUser.authHeader }
        );

        check(res, {
            'mini-statement HTTP 200': (r) => r.status === 200,
            'mini-statement valid array': (r) => {
                try { return Array.isArray(JSON.parse(r.body)); } catch (e) { return false; }
            }
        });

    } else {
        // --- 15% Transaction History (Paginated) ---
        const res = http.get(
            `${BASE_URL}/transactions/account/${sourceUser.accountNumber}?page=0&size=10`,
            { headers: sourceUser.authHeader }
        );

        check(res, {
            'history HTTP 200': (r) => r.status === 200,
            'history has content': (r) => {
                try { return JSON.parse(r.body).content !== undefined; } catch (e) { return false; }
            }
        });
    }

    sleep(0.05 + Math.random() * 0.1);
}
