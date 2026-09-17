import http from 'k6/http';
import { check, sleep } from 'k6';
import { provisionCustomerWithAccount, loginCustomer } from './helpers/auth.js';

const BASE_URL = __ENV.GATEWAY_URL || 'http://localhost:8080';

export const options = {
    vus: 1,
    duration: '30s',
    thresholds: {
        http_req_duration: ['p(95)<2000'],
        http_req_failed: ['rate<0.01'],
    },
};

export function setup() {
    console.log(`Setting up baseline test on ${BASE_URL}...`);
    // Pre-provision 2 customers with funded accounts for transfers
    const sender = provisionCustomerWithAccount(BASE_URL, 1, 50000);
    const receiver = provisionCustomerWithAccount(BASE_URL, 2, 10000);
    console.log(`Baseline setup complete: sender=${sender.accountNumber}, receiver=${receiver.accountNumber}`);
    return { sender, receiver };
}

export default function (data) {
    const sender = data.sender;
    const receiver = data.receiver;

    // 1. DEPOSIT (Idempotent)
    const depositKey = `base-dep-${__VU}-${__ITER}-${Date.now()}`;
    const depositRes = http.post(
        `${BASE_URL}/transactions`,
        JSON.stringify({
            sourceAccountNumber: sender.accountNumber,
            amount: 100,
            type: 'DEPOSIT',
            description: 'Baseline deposit test'
        }),
        {
            headers: {
                ...sender.authHeader,
                'Idempotency-Key': depositKey
            }
        }
    );
    check(depositRes, {
        'deposit status is 201': (r) => r.status === 201,
        'deposit transaction is SUCCESS': (r) => {
            try { return JSON.parse(r.body).status === 'SUCCESS'; } catch(e) { return false; }
        }
    });

    // 2. WITHDRAW
    const withdrawKey = `base-wth-${__VU}-${__ITER}-${Date.now()}`;
    const withdrawRes = http.post(
        `${BASE_URL}/transactions`,
        JSON.stringify({
            sourceAccountNumber: sender.accountNumber,
            amount: 50,
            type: 'WITHDRAW',
            description: 'Baseline withdraw test'
        }),
        {
            headers: {
                ...sender.authHeader,
                'Idempotency-Key': withdrawKey
            }
        }
    );
    check(withdrawRes, {
        'withdraw status is 201': (r) => r.status === 201,
        'withdraw transaction is SUCCESS': (r) => {
            try { return JSON.parse(r.body).status === 'SUCCESS'; } catch(e) { return false; }
        }
    });

    // 3. TRANSFER (Saga debit + credit)
    const transferKey = `base-txf-${__VU}-${__ITER}-${Date.now()}`;
    const transferRes = http.post(
        `${BASE_URL}/transactions`,
        JSON.stringify({
            sourceAccountNumber: sender.accountNumber,
            targetAccountNumber: receiver.accountNumber,
            amount: 25,
            type: 'TRANSFER',
            description: 'Baseline transfer test'
        }),
        {
            headers: {
                ...sender.authHeader,
                'Idempotency-Key': transferKey
            }
        }
    );
    check(transferRes, {
        'transfer status is 201': (r) => r.status === 201,
        'transfer transaction is SUCCESS': (r) => {
            try { return JSON.parse(r.body).status === 'SUCCESS'; } catch(e) { return false; }
        }
    });

    // 4. GET Mini Statement
    const miniRes = http.get(
        `${BASE_URL}/transactions/mini-statement/${sender.accountNumber}`,
        { headers: sender.authHeader }
    );
    check(miniRes, {
        'mini-statement status is 200': (r) => r.status === 200,
        'mini-statement returns list': (r) => {
            try { return Array.isArray(JSON.parse(r.body)); } catch(e) { return false; }
        }
    });

    // 5. GET Account Balance / Details
    const accRes = http.get(
        `${BASE_URL}/accounts/${sender.accountNumber}`,
        { headers: sender.authHeader }
    );
    check(accRes, {
        'account details status is 200': (r) => r.status === 200,
    });

    sleep(0.1);
}
