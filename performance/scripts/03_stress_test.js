import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { provisionCustomerWithAccount } from './helpers/auth.js';

const BASE_URL = __ENV.GATEWAY_URL || 'http://localhost:8080';

const errorRate = new Rate('banking_error_rate');
const transferDuration = new Trend('banking_transfer_duration');
const totalTransactions = new Counter('banking_total_transactions');

export const options = {
    stages: [
        { duration: '30s', target: 20 },  // Ramp to 20 VUs
        { duration: '30s', target: 50 },  // Ramp to 50 VUs
        { duration: '40s', target: 100 }, // Ramp to 100 VUs (stress peak)
        { duration: '30s', target: 100 }, // Sustain 100 VUs
        { duration: '20s', target: 0 },   // Ramp down
    ],
    thresholds: {
        // Stress testing threshold: track degradation and limit failure rate
        'http_req_duration': ['p(95)<5000'],
        'http_req_failed': ['rate<0.15'],
    },
};

export function setup() {
    console.log(`[Stress Test Setup] Provisioning large account pool (30 accounts) on ${BASE_URL}...`);
    const accounts = [];
    const POOL_SIZE = 30;

    for (let i = 1; i <= POOL_SIZE; i++) {
        try {
            const user = provisionCustomerWithAccount(BASE_URL, i, 500000);
            accounts.push(user);
        } catch (e) {
            console.error(`Error provisioning user ${i}: ${e.message}`);
        }
    }

    console.log(`[Stress Test Setup] Successfully provisioned ${accounts.length} accounts.`);
    return { accounts };
}

export default function (data) {
    const accounts = data.accounts;
    if (!accounts || accounts.length < 2) {
        return;
    }

    const srcIndex = (__VU + __ITER) % accounts.length;
    const sourceUser = accounts[srcIndex];

    let targetIndex = (__VU + __ITER + 1) % accounts.length;
    if (targetIndex === srcIndex) {
        targetIndex = (srcIndex + 2) % accounts.length;
    }
    const targetUser = accounts[targetIndex];

    const rand = Math.random();

    if (rand < 0.40) {
        // 40% TRANSFER
        const idempotencyKey = `stress-txf-${__VU}-${__ITER}-${Date.now()}`;
        const startTime = Date.now();

        const res = http.post(
            `${BASE_URL}/transactions`,
            JSON.stringify({
                sourceAccountNumber: sourceUser.accountNumber,
                targetAccountNumber: targetUser.accountNumber,
                amount: 5,
                type: 'TRANSFER',
                description: 'Stress test transfer'
            }),
            {
                headers: {
                    ...sourceUser.authHeader,
                    'Idempotency-Key': idempotencyKey
                }
            }
        );

        transferDuration.add(Date.now() - startTime);
        totalTransactions.add(1);

        const isSuccess = res.status === 201;
        errorRate.add(!isSuccess);

    } else if (rand < 0.70) {
        // 30% DEPOSIT
        const idempotencyKey = `stress-dep-${__VU}-${__ITER}-${Date.now()}`;
        const res = http.post(
            `${BASE_URL}/transactions`,
            JSON.stringify({
                sourceAccountNumber: sourceUser.accountNumber,
                amount: 20,
                type: 'DEPOSIT',
                description: 'Stress test deposit'
            }),
            {
                headers: {
                    ...sourceUser.authHeader,
                    'Idempotency-Key': idempotencyKey
                }
            }
        );

        totalTransactions.add(1);
        errorRate.add(res.status !== 201);

    } else {
        // 30% READ QUERIES (Mini-statement & Account details)
        const res = http.get(
            `${BASE_URL}/transactions/mini-statement/${sourceUser.accountNumber}`,
            { headers: sourceUser.authHeader }
        );

        errorRate.add(res.status !== 200);
    }

    sleep(0.02 + Math.random() * 0.05);
}
